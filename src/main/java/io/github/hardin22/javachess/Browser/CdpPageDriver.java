package io.github.hardin22.javachess.Browser;

import org.cef.browser.CefBrowser;
import org.cef.browser.DevToolsAccess;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link PageDriver} for a JCEF browser, through the Chrome DevTools protocol ({@link DevToolsAccess}: JCEF's own
 * {@code CefDevToolsClient} now and then never delivers an answer, see {@link DevToolsReplies}):
 * {@code Runtime.evaluate} to read the page, {@code Page.captureScreenshot} to picture the board (the page's own
 * pixels, whatever covers the window and without screen-recording permissions), {@code Input.dispatchMouseEvent}
 * for trusted clicks that never move the real mouse pointer.
 */
public final class CdpPageDriver implements PageDriver {

    private static final Logger log = LoggerFactory.getLogger(CdpPageDriver.class);
    private static final long TIMEOUT_MS = 5000;

    private final CefBrowser browser;
    private DevToolsReplies replies;
    private AutoCloseable observer;

    public CdpPageDriver(CefBrowser browser) {
        this.browser = browser;
    }

    @Override
    public String url() {
        String url = browser.getURL();
        return url == null ? "" : url;
    }

    @Override
    public CompletableFuture<String> evaluate(String expression) {
        JSONObject params = new JSONObject().put("expression", expression).put("returnByValue", true)
                .put("awaitPromise", false);
        return call("Runtime.evaluate", params).thenApply(CdpPageDriver::evaluationValue);
    }

    /** The JSON value of a {@code Runtime.evaluate} answer; fails with the page's exception text, if any. */
    static String evaluationValue(String answer) {
        JSONObject o = new JSONObject(answer);
        JSONObject details = o.optJSONObject("exceptionDetails");
        if (details != null) {
            JSONObject exception = details.optJSONObject("exception");
            String text = exception != null ? exception.optString("description", details.optString("text"))
                    : details.optString("text");
            throw new IllegalStateException("Script failed in the page: " + text);
        }
        JSONObject result = o.optJSONObject("result");
        if (result == null || !result.has("value")) {
            return "null";
        }
        return JSONObject.valueToString(result.get("value"));
    }

    @Override
    public CompletableFuture<BufferedImage> screenshot(BoardSnapshot.Rect clip) {
        if (clip == null) {
            return capture(new JSONObject().put("format", "png").put("captureBeyondViewport", false));
        }
        // the clip of Page.captureScreenshot is in page coordinates: add how far the page is scrolled
        return call("Page.getLayoutMetrics", new JSONObject()).thenCompose(metrics -> {
            JSONObject vv = new JSONObject(metrics).optJSONObject("cssVisualViewport");
            double sx = vv == null ? 0 : vv.optDouble("pageX", 0);
            double sy = vv == null ? 0 : vv.optDouble("pageY", 0);
            return capture(new JSONObject().put("format", "png").put("captureBeyondViewport", false)
                    .put("clip", new JSONObject().put("x", clip.x() + sx).put("y", clip.y() + sy)
                            .put("width", clip.w()).put("height", clip.h()).put("scale", 1)));
        });
    }

    private CompletableFuture<BufferedImage> capture(JSONObject params) {
        return call("Page.captureScreenshot", params).thenApply(answer -> {
            byte[] png = Base64.getDecoder().decode(new JSONObject(answer).getString("data"));
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null) {
                    throw new IllegalStateException("Unreadable screenshot");
                }
                return image;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> click(double x, double y) {
        JSONObject move = mouse("mouseMoved", x, y).put("button", "none");
        JSONObject press = mouse("mousePressed", x, y).put("button", "left").put("buttons", 1).put("clickCount", 1);
        JSONObject release = mouse("mouseReleased", x, y).put("button", "left").put("buttons", 0).put("clickCount", 1);
        return call("Input.dispatchMouseEvent", move)
                .thenCompose(r -> call("Input.dispatchMouseEvent", press))
                .thenCompose(r -> call("Input.dispatchMouseEvent", release))
                .thenApply(r -> null);
    }

    @Override
    public CompletableFuture<Void> typeText(String text) {
        return call("Input.insertText", new JSONObject().put("text", text)).thenApply(r -> null);
    }

    private static JSONObject mouse(String type, double x, double y) {
        return new JSONObject().put("type", type).put("x", x).put("y", y);
    }

    private CompletableFuture<String> call(String method, JSONObject params) {
        DevToolsReplies r;
        try {
            r = session();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        log.debug("DevTools {}", method); // tests check that nothing reaches login and verification pages
        CompletableFuture<String> answer = new CompletableFuture<>();
        int[] sentId = {0};
        try {
            DevToolsAccess.send(browser, method, params.toString()).whenComplete((id, error) -> {
                if (error != null || id == null || id <= 0) {
                    answer.completeExceptionally(error != null ? error
                            : new IllegalStateException("DevTools did not accept " + method));
                } else {
                    sentId[0] = id;
                    r.await(id, answer);
                }
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return answer.orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((v, error) -> {
            if (error instanceof TimeoutException) {
                log.warn("DevTools {} not answered in {} ms", method, TIMEOUT_MS);
                r.forget(sentId[0]);
            }
        });
    }

    private synchronized DevToolsReplies session() {
        if (replies == null) {
            if (!DevToolsAccess.supported(browser)) {
                throw new IllegalStateException("DevTools not available for this browser");
            }
            DevToolsReplies r = new DevToolsReplies();
            observer = DevToolsAccess.observe(browser, r::received);
            replies = r;
        }
        return replies;
    }

    /** Closes the DevTools session (the browser itself is not closed). */
    public synchronized void close() {
        if (replies != null) {
            replies.close();
            replies = null;
        }
        if (observer != null) {
            try {
                observer.close();
            } catch (Exception e) {
                log.debug("DevTools observer close failed: {}", e.toString());
            }
            observer = null;
        }
    }
}
