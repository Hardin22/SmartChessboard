package org.example.javachess.Vision;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Checker-pattern grid search, without the neural network. */
class GridFinderTest {

    @BeforeAll
    static void openCv() {
        boolean ok;
        try {
            nu.pattern.OpenCV.loadLocally();
            ok = true;
        } catch (Throwable e) {
            ok = false;
        }
        assumeTrue(ok, "OpenCV not available");
    }

    private static Mat gray(BufferedImage img) {
        Mat bgr = PieceClassifier.toMat(img);
        Mat g = new Mat();
        Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY);
        bgr.release();
        return g;
    }

    private static BufferedImage screenWithBoard(int x, int y, int size, SyntheticBoards.Theme theme) {
        BufferedImage screen = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        g.setColor(new java.awt.Color(30, 30, 30));
        g.fillRect(0, 0, 640, 640);
        g.drawImage(SyntheticBoards.render("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R", "Legno", theme,
                size, false), x, y, null);
        g.dispose();
        return screen;
    }

    @Test
    void refinesALooseEstimateToThePixel() {
        for (SyntheticBoards.Theme theme : SyntheticBoards.Theme.values()) {
            Mat gray = gray(screenWithBoard(57, 81, 480, theme));
            GridFinder.Grid g = new GridFinder(gray).refine(new Rectangle(70, 70, 500, 500), 0.08);
            assertTrue(g.score() >= GridFinder.MIN_SCORE, theme + " score " + g.score());
            assertEquals(57, g.x(), 1.5, theme.toString());
            assertEquals(81, g.y(), 1.5, theme.toString());
            assertEquals(60, g.cell(), 0.5, theme.toString());
            gray.release();
        }
    }

    @Test
    void noBoardMeansLowScore() {
        BufferedImage blank = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = blank.createGraphics();
        g2.setColor(new java.awt.Color(90, 90, 90));
        g2.fillRect(0, 0, 640, 640);
        g2.setColor(java.awt.Color.WHITE);
        for (int i = 0; i < 20; i++) {
            g2.drawString("some text on the screen " + i, 20, 30 + i * 30);
        }
        g2.dispose();
        Mat gray = gray(blank);
        GridFinder.Grid g = new GridFinder(gray).search(0, 200, 0, 200, 40, 60);
        assertTrue(g.score() < GridFinder.MIN_SCORE, "score " + g.score());
        gray.release();
    }
}
