package io.github.hardin22.javachess.Browser;

import org.cef.browser.CefPaintEvent;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.function.Consumer;

/**
 * Off-screen rendering: a copy of what the page shows, kept from the pictures Chromium sends to draw it
 * ({@code CefBrowser.addOnPaintListener}: the whole frame, BGRA, and the rectangles that changed), so that the
 * board can be pictured without asking Chromium to draw the page again. Page.captureScreenshot does that, and
 * off-screen it sent an empty frame meanwhile: the page went black for an instant at every few moves
 * (8 October 2026).
 */
final class PaintedFrame implements Consumer<CefPaintEvent> {

    private int width;
    private int height;
    private int[] pixels = new int[0];

    @Override
    public void accept(CefPaintEvent e) {
        if (e.getPopup()) {
            return; // drop-down lists and the like: drawn over the page, not part of it
        }
        IntBuffer frame = e.getRenderedFrame().duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int w = e.getWidth();
        int h = e.getHeight();
        synchronized (this) {
            Rectangle[] dirty = e.getDirtyRects();
            if (w != width || h != height || pixels.length != w * h) {
                width = w;
                height = h;
                pixels = new int[w * h];
                dirty = new Rectangle[] {new Rectangle(0, 0, w, h)};
            }
            for (Rectangle r : dirty) {
                Rectangle c = r.intersection(new Rectangle(0, 0, w, h));
                for (int y = c.y; y < c.y + c.height; y++) {
                    frame.position(y * w + c.x);
                    frame.get(pixels, y * w + c.x, c.width); // B, G, R, A bytes = an ARGB int (little endian)
                }
            }
        }
    }

    /** True once Chromium has drawn the page. */
    synchronized boolean hasFrame() {
        return width > 0 && height > 0;
    }

    /** What the page shows now (device pixels). */
    synchronized BufferedImage snapshot() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }
}
