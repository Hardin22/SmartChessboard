package io.github.hardin22.javachess.Browser;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The board cut out of a picture of the whole visible page (see {@link CdpPageDriver#screenshot}). */
class CdpPageDriverCropTest {

    /** A page picture where each pixel encodes its own device coordinates. */
    private static BufferedImage page(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (x << 12) | y);
            }
        }
        return img;
    }

    @Test
    void chromiumIsNeverAskedForARegion() {
        // a clip makes Chromium resize the page's real view to it for the picture: a flash on macOS at every move
        org.junit.jupiter.api.Assertions.assertFalse(CdpPageDriver.CAPTURE.has("clip"));
        org.junit.jupiter.api.Assertions.assertFalse(CdpPageDriver.CAPTURE.optBoolean("captureBeyondViewport"));
    }

    @Test
    void theRegionIsCutAtTheDevicesDensity() {
        BufferedImage retina = page(1440, 1800); // a 720x900 CSS view at 2 device pixels per CSS pixel
        BufferedImage board = CdpPageDriver.crop(retina, new BoardSnapshot.Rect(10, 100, 400, 400),
                new JSONArray("[2, 0, 0]"));
        assertEquals(800, board.getWidth());
        assertEquals(800, board.getHeight());
        assertEquals((20 << 12) | 200, board.getRGB(0, 0) & 0xFFFFFF);
        assertEquals((819 << 12) | 999, board.getRGB(799, 799) & 0xFFFFFF);
    }

    @Test
    void oneDevicePixelPerCssPixelOnThePi() {
        BufferedImage pi = page(720, 1700);
        BufferedImage board = CdpPageDriver.crop(pi, new BoardSnapshot.Rect(0, 337.4, 720, 720),
                new JSONArray("[1, 0, 0]"));
        assertEquals(720, board.getWidth());
        assertEquals(720, board.getHeight());
        assertEquals(337, board.getRGB(0, 0) & 0xFFF);
    }

    @Test
    void aPinchZoomedViewIsTakenIntoAccount() {
        BufferedImage img = page(1000, 1000);
        // visual viewport scrolled by (50, 60) CSS px, 1 device px per CSS px
        BufferedImage board = CdpPageDriver.crop(img, new BoardSnapshot.Rect(150, 160, 100, 100),
                new JSONArray("[1, 50, 60]"));
        assertEquals((100 << 12) | 100, board.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void aRegionPastTheEdgeIsCutToThePicture() {
        BufferedImage img = page(720, 1000);
        BufferedImage board = CdpPageDriver.crop(img, new BoardSnapshot.Rect(10, 900, 720, 720),
                new JSONArray("[1, 0, 0]"));
        assertEquals(710, board.getWidth());
        assertEquals(100, board.getHeight());
    }
}
