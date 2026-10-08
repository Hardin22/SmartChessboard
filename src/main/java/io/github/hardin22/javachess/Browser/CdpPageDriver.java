package io.github.hardin22.javachess.Browser;

import org.cef.browser.CefBrowser;
import org.cef.browser.DevToolsAccess;
import org.json.JSONArray;
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
    /** Off-screen only: what the page shows (see {@link PaintedFrame}); null in windowed mode. */
    private final PaintedFrame painted;
    private DevToolsReplies replies;
    private AutoCloseable observer;

    public CdpPageDriver(CefBrowser browser) {
        this.browser = browser;
        if (browser instanceof org.cef.handler.CefRenderHandler offscreen) {
            // off-screen rendering (Linux, Raspberry Pi): pictures of the board from what the page shows
            painted = new PaintedFrame();
            offscreen.addOnPaintListener(painted);
        } else {
            painted = null;
        }
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

    /**
     * Off-screen (Linux, Raspberry Pi) the picture is what the page shows, kept from Chromium's own paints
     * ({@link PaintedFrame}): Page.captureScreenshot makes Chromium draw the page again and, off-screen, an empty
     * frame was shown meanwhile (the page went black for an instant at every few moves).
     *
     * <p>In windowed mode (macOS) the visible page is captured whole and the region is cut out here. Never with the
     * {@code clip} parameter of
     * {@code Page.captureScreenshot}: for a clip Chromium emulates a viewport on the region and resizes the page's
     * real view to it until the picture is taken ({@code PageHandler::CaptureScreenshot} in
     * content/browser/devtools/protocol/page_handler.cc: {@code SetDeviceEmulationParams} and
     * {@code GetView()->SetSize(clip)}); on macOS, where the view is a native window, the page flashed white and was
     * laid out again at every picture of the board, i.e. at every move (8 October 2026). Without a clip nor
     * emulation it is a plain copy of what is on screen.</p>
     */
    @Override
    public CompletableFuture<BufferedImage> screenshot(BoardSnapshot.Rect clip) {
        PaintedFrame shown = painted;
        CompletableFuture<BufferedImage> whole = shown != null && shown.hasFrame()
                ? CompletableFuture.supplyAsync(shown::snapshot)
                : capture(CAPTURE);
        if (clip == null) {
            return whole;
        }
        return evaluate(VIEW_SCALE).thenCombineAsync(whole,
                (scale, image) -> crop(image, clip, new JSONArray(scale)));
    }

    /** Parameters of Page.captureScreenshot: the visible page as it is, never a {@code clip} (see above). */
    static final JSONObject CAPTURE = new JSONObject().put("format", "png").put("captureBeyondViewport", false)
            .put("optimizeForSpeed", true);

    /** Device pixels per CSS pixel and where the visible part of the viewport starts (pinch zoom). */
    private static final String VIEW_SCALE = "(() => { const v = window.visualViewport;"
            + " return [window.devicePixelRatio * (v ? v.scale : 1), v ? v.offsetLeft : 0, v ? v.offsetTop : 0]; })()";

    /** The region (viewport CSS pixels) of a picture of the visible page, at the device's pixel density. */
    static BufferedImage crop(BufferedImage image, BoardSnapshot.Rect clip, JSONArray view) {
        double scale = view.optDouble(0, 1);
        if (!(scale > 0)) {
            scale = 1;
        }
        int x0 = (int) Math.round((clip.x() - view.optDouble(1, 0)) * scale);
        int y0 = (int) Math.round((clip.y() - view.optDouble(2, 0)) * scale);
        int w = Math.max(1, (int) Math.round(clip.w() * scale));
        int h = Math.max(1, (int) Math.round(clip.h() * scale));
        x0 = Math.max(0, Math.min(x0, image.getWidth() - 1));
        y0 = Math.max(0, Math.min(y0, image.getHeight() - 1));
        w = Math.min(w, image.getWidth() - x0);
        h = Math.min(h, image.getHeight() - y0);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, w, h, x0, y0, x0 + w, y0 + h, null);
        g.dispose();
        return out;
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

    /** A DevTools method (package-private for the tests). */
    CompletableFuture<String> call(String method, JSONObject params) {
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
