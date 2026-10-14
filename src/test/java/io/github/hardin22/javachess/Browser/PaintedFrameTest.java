package io.github.hardin22.javachess.Browser;

import org.cef.browser.CefPaintEvent;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The copy of the off-screen page kept from Chromium's paints (BGRA frames and their changed rectangles). */
class PaintedFrameTest {

    /** A BGRA frame of one colour (0xRRGGBB). */
    private static ByteBuffer frame(int w, int h, int rgb) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            b.put((byte) rgb).put((byte) (rgb >> 8)).put((byte) (rgb >> 16)).put((byte) 0xFF);
        }
        b.flip();
        return b;
    }

    private static CefPaintEvent paint(ByteBuffer frame, int w, int h, boolean popup, Rectangle... dirty) {
        return new CefPaintEvent(null, popup, dirty, frame, w, h);
    }

    @Test
    void theFirstFrameIsCopiedWholeAndThenOnlyWhatChanged() {
        PaintedFrame f = new PaintedFrame();
        assertFalse(f.hasFrame());
        f.accept(paint(frame(6, 4, 0x112233), 6, 4, false, new Rectangle(0, 0, 1, 1)));
        assertTrue(f.hasFrame());
        BufferedImage first = f.snapshot();
        assertEquals(6, first.getWidth());
        assertEquals(0x112233, first.getRGB(5, 3) & 0xFFFFFF, "a new size is copied whole, whatever changed");

        f.accept(paint(frame(6, 4, 0xABCDEF), 6, 4, false, new Rectangle(2, 1, 3, 2)));
        BufferedImage next = f.snapshot();
        assertEquals(0xABCDEF, next.getRGB(2, 1) & 0xFFFFFF);
        assertEquals(0xABCDEF, next.getRGB(4, 2) & 0xFFFFFF);
        assertEquals(0x112233, next.getRGB(1, 1) & 0xFFFFFF, "outside the changed rectangle");
        assertEquals(0x112233, next.getRGB(5, 3) & 0xFFFFFF);
        assertEquals(0x112233, first.getRGB(2, 1) & 0xFFFFFF, "a snapshot does not change afterwards");
    }

    @Test
    void popupsAreNotPartOfThePage() {
        PaintedFrame f = new PaintedFrame();
        f.accept(paint(frame(4, 4, 0x000000), 4, 4, false, new Rectangle(0, 0, 4, 4)));
        f.accept(paint(frame(2, 2, 0xFFFFFF), 2, 2, true, new Rectangle(0, 0, 2, 2)));
        assertEquals(4, f.snapshot().getWidth());
        assertEquals(0, f.snapshot().getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void aChangedRectangleOutsideTheFrameIsCut() {
        PaintedFrame f = new PaintedFrame();
        f.accept(paint(frame(4, 4, 0x000000), 4, 4, false, new Rectangle(0, 0, 4, 4)));
        f.accept(paint(frame(4, 4, 0x00FF00), 4, 4, false, new Rectangle(2, 2, 10, 10)));
        assertEquals(0x00FF00, f.snapshot().getRGB(3, 3) & 0xFFFFFF);
        assertEquals(0, f.snapshot().getRGB(1, 1) & 0xFFFFFF);
    }
}
