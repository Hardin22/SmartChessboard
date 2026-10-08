package io.github.hardin22.javachess.Browser;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

/**
 * The page shown by the integrated browser, as the rest of the code sees it. The JCEF implementation
 * ({@link CdpPageDriver}) talks to Chromium through the DevTools protocol, so it works the same with windowed and
 * off-screen rendering, needs no screen-capture or accessibility permission and never moves the real mouse.
 * Tests use a fake page.
 *
 * <p>All coordinates are CSS pixels relative to the viewport. Methods never block; futures complete on a browser
 * thread and fail (exceptionally) on timeouts or when no page is loaded.</p>
 */
public interface PageDriver {

    /** URL of the page currently shown ("" when none). */
    String url();

    /** Evaluates a JavaScript expression in the page and returns its value as JSON ("null" for undefined). */
    CompletableFuture<String> evaluate(String expression);

    /** Picture of a region of the page, at the device's pixel density. */
    CompletableFuture<BufferedImage> screenshot(BoardSnapshot.Rect clip);

    /** A real (trusted) left click at the given point of the viewport. */
    CompletableFuture<Void> click(double x, double y);

    /** Types text into the focused field like a keyboard (trusted input events). */
    CompletableFuture<Void> typeText(String text);
}
