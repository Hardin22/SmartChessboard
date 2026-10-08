package io.github.hardin22.javachess.Vision;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tracking logic with a scripted detector: pictures are plain images whose colour stands for a position, so the
 * frame sequences of real situations (animations, drags, popups, a misread frame) can be written down exactly.
 */
class VisionTrackerTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
    private static final String E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR";
    private static final String E4E5 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR";

    /** Reads a picture by its colour; counts the reads. */
    static final class ScriptedDetector implements VisionTracker.Detector {
        final java.util.Map<Integer, String> positions = new java.util.HashMap<>();
        Rectangle board;
        int reads;
        boolean failNext;

        @Override
        public Rectangle findBoard(BufferedImage frame) {
            return board;
        }

        @Override
        public BoardReading read(BufferedImage picture, boolean flipped) throws Exception {
            reads++;
            if (failNext) {
                failNext = false;
                throw new Exception("model failure");
            }
            String placement = positions.get(picture.getRGB(picture.getWidth() / 2, picture.getHeight() / 2) & 0xFFFFFF);
            if (placement == null) {
                return new BoardReading(empty(), false, 1);
            }
            return BoardReading.certain(placement);
        }

        private static float[][][] empty() {
            float[][][] p = new float[8][8][BoardReading.SYMBOLS.length()];
            for (float[][] f : p) {
                for (float[] r : f) {
                    r[BoardReading.SYMBOLS.length() - 1] = 1f;
                }
            }
            return p;
        }
    }

    static BufferedImage picture(int rgb) {
        BufferedImage img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, 96, 96);
        g.dispose();
        return img;
    }

    private final ScriptedDetector detector = new ScriptedDetector();
    private final VisionTracker tracker = new VisionTracker(detector);

    {
        detector.positions.put(0x101010, START);
        detector.positions.put(0x808080, E4);
        detector.positions.put(0xF0F0F0, E4E5);
        tracker.setBoardHint(new Rectangle(0, 0, 96, 96));
    }

    private List<VisionTracker.Status> feed(int... colours) {
        List<VisionTracker.Status> out = new ArrayList<>();
        for (int c : colours) {
            out.add(tracker.accept(picture(c)).status());
        }
        return out;
    }

    @Test
    void aPositionIsReportedOnceWhenStable() {
        assertEquals(List.of(VisionTracker.Status.READING, VisionTracker.Status.NEW_POSITION,
                VisionTracker.Status.STABLE, VisionTracker.Status.STABLE), feed(0x101010, 0x101010, 0x101010, 0x101010));
        assertEquals(START, tracker.lastReported().placement());
        assertEquals(1, detector.reads, "an unchanged picture is not read again");
    }

    @Test
    void aMoveIsReportedOnceAfterTheAnimation() {
        feed(0x101010, 0x101010);
        // the piece slides (changing pictures), then the board is still
        List<VisionTracker.Status> s = feed(0x404040, 0x606060, 0x808080, 0x808080, 0x808080, 0x808080);
        assertEquals(List.of(VisionTracker.Status.MOVING, VisionTracker.Status.MOVING, VisionTracker.Status.MOVING,
                VisionTracker.Status.READING, VisionTracker.Status.NEW_POSITION, VisionTracker.Status.STABLE), s);
        assertEquals(E4, tracker.lastReported().placement());
        assertEquals(2, detector.reads, "one read per position");
    }

    @Test
    void aSingleMisreadFrameNeverBecomesAPosition() {
        feed(0x101010, 0x101010);
        // one frame read as something else (e.g. a highlight flash), then back
        detector.positions.put(0x202020, E4);
        List<VisionTracker.Status> s = feed(0x202020, 0x101010, 0x101010);
        assertTrue(s.stream().noneMatch(st -> st == VisionTracker.Status.NEW_POSITION), s.toString());
        assertEquals(START, tracker.lastReported().placement());
    }

    @Test
    void aPopupOverTheBoardIsNoBoardNotAPosition() {
        feed(0x101010, 0x101010);
        List<VisionTracker.Status> s = feed(0x00FF00, 0x00FF00, 0x00FF00);
        assertEquals(VisionTracker.Status.NO_BOARD, s.get(2));
        assertEquals(START, tracker.lastReported().placement(), "the position does not change while hidden");
        s = feed(0x101010, 0x101010);
        assertTrue(s.stream().noneMatch(st -> st == VisionTracker.Status.NEW_POSITION),
                "the same position after the popup is not a new one");
    }

    @Test
    void forgetReportedReportsTheCurrentPositionAgain() {
        feed(0x101010, 0x101010);
        tracker.forgetReported();
        assertEquals(VisionTracker.Status.NEW_POSITION, tracker.accept(picture(0x101010)).status());
    }

    @Test
    void anOrientationChangeReadsTheBoardAgain() {
        feed(0x101010, 0x101010);
        int reads = detector.reads;
        tracker.setFlipped(true);
        feed(0x101010);
        assertEquals(reads + 1, detector.reads);
    }

    @Test
    void detectorErrorsAreReportedAndRecovered() {
        feed(0x101010);
        assertEquals(VisionTracker.Status.MOVING, tracker.accept(picture(0x808080)).status());
        detector.failNext = true;
        assertEquals(VisionTracker.Status.ERROR, tracker.accept(picture(0x808080)).status());
        assertEquals(List.of(VisionTracker.Status.READING, VisionTracker.Status.NEW_POSITION), feed(0x808080, 0x808080));
    }

    @Test
    void withoutHintTheBoardMustBeFoundOnThreeFrames() {
        VisionTracker t = new VisionTracker(detector);
        detector.board = new Rectangle(10, 10, 60, 60);
        BufferedImage frame = picture(0x101010);
        assertEquals(VisionTracker.Status.SEARCHING, t.accept(frame).status());
        assertEquals(VisionTracker.Status.SEARCHING, t.accept(frame).status());
        assertEquals(VisionTracker.Status.SEARCHING, t.accept(frame).status());
        assertEquals(new Rectangle(10, 10, 60, 60), t.board());
        assertEquals(VisionTracker.Status.READING, t.accept(frame).status());
        assertEquals(VisionTracker.Status.NEW_POSITION, t.accept(frame).status());
    }

    @Test
    void aBoardThatJumpsAroundIsNotLocked() {
        VisionTracker t = new VisionTracker(detector);
        BufferedImage frame = picture(0x101010);
        for (int i = 0; i < 6; i++) {
            detector.board = new Rectangle(i * 40, 0, 50, 50);
            t.accept(frame);
        }
        assertNull(t.board());
    }

    @Test
    void theBoardIsSearchedAgainWhenLost() {
        VisionTracker t = new VisionTracker(detector);
        detector.board = new Rectangle(0, 0, 96, 96);
        for (int i = 0; i < 5; i++) {
            t.accept(picture(0x101010));
        }
        VisionTracker.Status last = t.accept(picture(0x00FF00)).status(); // first different frame: moving
        assertEquals(VisionTracker.Status.MOVING, last);
        for (int i = 0; i < VisionTracker.LOST_FRAMES; i++) {
            last = t.accept(picture(0x00FF00)).status();
        }
        assertEquals(VisionTracker.Status.LOST, last);
        assertNull(t.board());
    }

    @Test
    void pictureComparison() {
        assertTrue(!VisionTracker.hasImageChanged(picture(0x101010), picture(0x101010)));
        assertTrue(!VisionTracker.hasImageChanged(picture(0x101010), picture(0x121212)), "compression noise");
        assertTrue(VisionTracker.hasImageChanged(picture(0x101010), picture(0x808080)));
        assertTrue(VisionTracker.hasImageChanged(picture(0x101010), new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        assertTrue(VisionTracker.similar(new Rectangle(0, 0, 100, 100), new Rectangle(2, 2, 100, 100)));
        assertTrue(!VisionTracker.similar(new Rectangle(0, 0, 100, 100), new Rectangle(30, 0, 100, 100)));
    }
}
