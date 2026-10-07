package io.github.hardin22.javachess.Browser;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardPictureTest {

    @Test
    void visibility() {
        BoardSnapshot.Rect r = new BoardSnapshot.Rect(-8, 106, 720, 720);
        assertFalse(r.inside(705, 1265));
        assertTrue(r.mostlyInside(705, 1265), "8 + 15 px of 720 hidden: readable");
        assertFalse(new BoardSnapshot.Rect(0, -350, 720, 720).mostlyInside(705, 1265));
        assertEquals(new BoardSnapshot.Rect(0, 106, 705, 720), r.visiblePart(705, 1265));
    }

    @Test
    void thePictureKeepsTheBoardsSizeAndSquares() throws Exception {
        FakeSite site = new FakeSite();
        site.rect = new BoardSnapshot.Rect(-8, 100, 720, 720);
        site.viewportWidth = 705;
        site.pictures = clip -> {
            // device pixel ratio 2; a vertical stripe at board x = 100 (css) to check the alignment
            BufferedImage img = new BufferedImage((int) (clip.w() * 2), (int) (clip.h() * 2), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    double boardX = clip.x() + x / 2.0 - site.rect.x();
                    img.setRGB(x, y, Math.abs(boardX - 100) < 1 ? 0xFF0000 : 0x00FF00);
                }
            }
            return img;
        };
        BoardSnapshot s = BoardProbe.parse(site.json());
        BufferedImage img = BoardPicture.take(site, s).get(5, TimeUnit.SECONDS);
        assertEquals(1440, img.getWidth());
        assertEquals(1440, img.getHeight());
        assertEquals(0xFF0000, img.getRGB(200, 700) & 0xFFFFFF, "the stripe stays at board x = 100");
        assertEquals(0x00FF00, img.getRGB(2, 700) & 0xFFFFFF, "the hidden strip repeats the edge");

        site.rect = new BoardSnapshot.Rect(0, -400, 720, 720);
        BoardSnapshot away = BoardProbe.parse(site.json());
        assertThrows(Exception.class, () -> BoardPicture.take(site, away).get(5, TimeUnit.SECONDS));
    }
}
