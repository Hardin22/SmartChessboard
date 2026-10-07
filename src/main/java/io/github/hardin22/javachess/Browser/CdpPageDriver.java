package io.github.hardin22.javachess.Browser;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
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
 * {@link PageDriver} for a JCEF browser, through the Chrome DevTools protocol ({@link CefDevToolsClient}):
 * {@code Runtime.evaluate} to read the page, {@code Page.captureScreenshot} to picture the board (the page's own
 * pixels, whatever covers the window and without screen-recording permissions), {@code Input.dispatchMouseEvent}
 * for trusted clicks that never move the real mouse pointer.
 */
public final class CdpPageDriver implements PageDriver {

    private static final Logger log = LoggerFactory.getLogger(CdpPageDriver.class);
    private static final long TIMEOUT_MS = 5000;

    private final CefBrowser browser;
    private CefDevToolsClient client;

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
        JSONObject params = new JSONObject().put("format", "png").put("captureBeyondViewport", false);
        if (clip != null) {
            params.put("clip", new JSONObject().put("x", clip.x()).put("y", clip.y()).put("width", clip.w())
                    .put("height", clip.h()).put("scale", 1));
        }
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
        CefDevToolsClient c;
        try {
            c = client();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<String> answer;
        try {
            answer = c.executeDevToolsMethod(method, params.toString());
        } catch (RuntimeException e) {
            reset(c);
            return CompletableFuture.failedFuture(e);
        }
        return answer.orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((r, error) -> {
            if (error instanceof TimeoutException) {
                log.warn("DevTools {} timed out, reconnecting", method);
                reset(c); // the next call opens a new DevTools session
            }
        });
    }

    private synchronized CefDevToolsClient client() {
        if (client == null || client.isClosed()) {
            client = browser.getDevToolsClient();
            if (client == null) {
                throw new IllegalStateException("DevTools not available for this browser");
            }
        }
        return client;
    }

    private synchronized void reset(CefDevToolsClient stale) {
        if (client == stale) {
            client = null;
            try {
                stale.close();
            } catch (RuntimeException e) {
                log.debug("DevTools close failed: {}", e.toString());
            }
        }
    }

    /** Closes the DevTools session (the browser itself is not closed). */
    public synchronized void close() {
        if (client != null) {
            reset(client);
        }
    }
}
