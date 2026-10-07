package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Services.VisionService;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.VisionTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardWatcherTest {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final List<BoardSnapshot> snapshots = new CopyOnWriteArrayList<>();
    private final List<BoardWatcher.PositionUpdate> updates = new CopyOnWriteArrayList<>();
    private final List<BoardWatcher.Problem> problems = new CopyOnWriteArrayList<>();
    private final Map<Integer, String> colours = new ConcurrentHashMap<>();
    private FakeSite site;
    private int visionReads;

    private final BoardWatcher.Listener listener = new BoardWatcher.Listener() {
        @Override
        public void onSnapshot(BoardSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        @Override
        public void onPosition(BoardWatcher.PositionUpdate update) {
            updates.add(update);
        }

        @Override
        public void onProblem(BoardWatcher.Problem problem) {
            problems.add(problem);
        }
    };

    /** "Vision" that recognises the site's pictures: each position is painted in its own colour. */
    private final VisionTracker.Detector detector = new VisionTracker.Detector() {
        @Override
        public Rectangle findBoard(BufferedImage frame) {
            return new Rectangle(0, 0, frame.getWidth(), frame.getHeight());
        }

        @Override
        public BoardReading read(BufferedImage board, boolean flipped) {
            visionReads++;
            String placement = colours.get(board.getRGB(4, 4) & 0xFFFFFF);
            return placement == null ? BoardReading.certain("8/8/8/8/8/8/8/8") : BoardReading.certain(placement);
        }
    };

    @BeforeEach
    void setUp() {
        site = new FakeSite();
        site.pictures = clip -> {
            String placement = site.placement();
            int rgb = placement.hashCode() & 0xFFFFFF;
            colours.put(rgb, placement);
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(rgb));
            g.fillRect(0, 0, 64, 64);
            g.dispose();
            return img;
        };
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private void poll(BoardWatcher w, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            w.pollOnce().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void pageModeReportsEachPositionOnceAfterTwoPolls() throws Exception {
        BoardWatcher w = new BoardWatcher(site, null, executor, listener, BoardWatcher.ReadMode.PAGE);
        poll(w, 1);
        assertTrue(updates.isEmpty(), "one poll is not stable yet");
        poll(w, 3);
        assertEquals(1, updates.size());
        assertEquals(site.placement(), updates.get(0).page().placement());
        assertEquals(4, snapshots.size(), "every poll reports the page state");

        site.opponentPlays("e2e4");
        site.animating = true; // the piece is still sliding
        poll(w, 3);
        assertEquals(1, updates.size(), "nothing while animating");
        site.animating = false;
        poll(w, 3);
        assertEquals(2, updates.size());
        assertEquals(site.placement(), updates.get(1).primary().placement());
    }

    @Test
    void visionModeReadsPicturesAndCarriesThePageReadingAlong() throws Exception {
        VisionService vision = new VisionService(detector);
        BoardWatcher w = new BoardWatcher(site, vision, executor, listener, BoardWatcher.ReadMode.VISION);
        poll(w, 4);
        BoardWatcher.PositionUpdate last = updates.get(updates.size() - 1);
        assertNotNull(last.vision());
        assertNotNull(last.page());
        assertEquals(site.placement(), last.primary().placement());
        assertEquals(last.vision(), last.primary(), "vision first");
        int reads = visionReads;
        poll(w, 5);
        assertEquals(reads, visionReads, "an unchanged board is not read again");

        site.opponentPlays("d2d4");
        poll(w, 4);
        last = updates.get(updates.size() - 1);
        assertEquals(site.placement(), last.vision().placement());
        assertEquals(site.placement(), last.page().placement());
    }

    @Test
    void visionOnlyIgnoresThePage() throws Exception {
        VisionService vision = new VisionService(detector);
        BoardWatcher w = new BoardWatcher(site, vision, executor, listener, BoardWatcher.ReadMode.VISION_ONLY);
        poll(w, 4);
        BoardWatcher.PositionUpdate last = updates.get(updates.size() - 1);
        assertNull(last.page());
        assertNull(last.check());
        assertEquals(site.placement(), last.primary().placement());
    }

    @Test
    void unknownMarkupFallsBackToVision() throws Exception {
        site.placementReadable = false; // the site changed its markup
        VisionService vision = new VisionService(detector);
        BoardWatcher w = new BoardWatcher(site, vision, executor, listener, BoardWatcher.ReadMode.PAGE);
        poll(w, 4);
        BoardWatcher.PositionUpdate last = updates.get(updates.size() - 1);
        assertNull(last.page());
        assertEquals(site.placement(), last.primary().placement(), "page mode without markup: vision");
    }

    @Test
    void aBoardOutOfViewIsScrolledBackForVision() throws Exception {
        site.rect = new BoardSnapshot.Rect(0, -300, 720, 720);
        BoardWatcher w = new BoardWatcher(site, new VisionService(detector), executor, listener,
                BoardWatcher.ReadMode.VISION_ONLY);
        poll(w, 1);
        assertEquals(1, site.scrolls, "scrolled back into view");
        assertTrue(problems.isEmpty(), "not reported when scrolling works");
        poll(w, 3);
        assertEquals(1, w.autoScrolls());
        assertTrue(updates.size() >= 1, "read once visible");

        FakeSite fixed = new FakeSite();
        fixed.rect = new BoardSnapshot.Rect(0, -300, 720, 720);
        fixed.scrollable = false;
        BoardWatcher stuck = new BoardWatcher(fixed, new VisionService(detector), executor, listener,
                BoardWatcher.ReadMode.VISION_ONLY);
        problems.clear();
        poll(stuck, 2);
        assertEquals(List.of(BoardWatcher.Problem.BOARD_OUT_OF_VIEW), problems, "told when it stays out of view");
    }

    @Test
    void anUnreachablePageIsAProblemNotACrash() throws Exception {
        BoardWatcher w = new BoardWatcher(site, null, executor, listener, BoardWatcher.ReadMode.PAGE);
        site.unreachable = true;
        poll(w, 3);
        assertEquals(List.of(BoardWatcher.Problem.PAGE_UNREADABLE), problems, "reported once");
        assertTrue(snapshots.isEmpty());
        site.unreachable = false;
        poll(w, 3);
        assertEquals(1, updates.size());
    }

    @Test
    void forgetReportedSendsTheCurrentPositionAgain() throws Exception {
        BoardWatcher w = new BoardWatcher(site, null, executor, listener, BoardWatcher.ReadMode.PAGE);
        poll(w, 3);
        assertEquals(1, updates.size());
        w.forgetReported();
        poll(w, 1);
        assertEquals(2, updates.size());
    }

    @Test
    void theLoopRunsAndStops() throws Exception {
        BoardWatcher w = new BoardWatcher(site, null, executor, listener, BoardWatcher.ReadMode.PAGE);
        w.setIntervalMs(20);
        w.start();
        long deadline = System.currentTimeMillis() + 3000;
        while (updates.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        w.stop();
        assertEquals(1, updates.size());
        Thread.sleep(100);
        int probes = site.probes;
        Thread.sleep(150);
        assertEquals(probes, site.probes, "no polls after stop");
    }
}
