package org.example.javachess.Hardware;

import org.example.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedRendererTest {

    private SimulatedBoard sim;
    private LedRenderer leds;
    private MoveLeds moveLeds;

    @BeforeEach
    void setUp() {
        sim = new SimulatedBoard();
        leds = new LedRenderer(sim, LedMapping.DEFAULT);
        moveLeds = new MoveLeds(leds);
    }

    @AfterEach
    void tearDown() {
        leds.shutdown();
    }

    private static int led(String square) {
        return LedMapping.DEFAULT.ledIndex(Squares.parse(square));
    }

    @Test
    void eventToSerialFrameLatency() throws InterruptedException {
        // warm up the render thread and the JIT a little
        for (int i = 0; i < 20; i++) {
            moveLeds.showCandidate("g1", i % 2 == 0 ? "f3" : "h3", MoveClassification.GOOD);
            Thread.sleep(20);
        }
        moveLeds.clearCandidates();
        Thread.sleep(40);

        long worst = 0;
        long total = 0;
        int runs = 30;
        for (int i = 0; i < runs; i++) {
            MoveClassification quality = i % 2 == 0 ? MoveClassification.BLUNDER : MoveClassification.INACCURACY;
            int expected = LedColors.forQuality(quality);
            Thread.sleep(25); // idle board: no frame due
            sim.clearRecordedFrames();
            long t0 = System.nanoTime();
            moveLeds.showCandidate("e2", "e4", quality);
            SimulatedBoard.SentFrame frame = sim.awaitFrame(f -> f[led("e4")] == expected, 1000);
            assertNotNull(frame, "frame never sent");
            long latency = frame.nanoTime() - t0;
            worst = Math.max(worst, latency);
            total += latency;
        }
        System.out.printf("LED latency event->frame: avg %.2f ms, worst %.2f ms%n", total / runs / 1e6, worst / 1e6);
        assertTrue(worst < 30_000_000L, "worst latency " + worst / 1e6 + " ms");
    }

    @Test
    void rapidUpdatesAreCoalescedIntoFewFrames() throws InterruptedException {
        Thread.sleep(30);
        sim.clearRecordedFrames();
        List<String> targets = List.of("a3", "b3", "c3", "d3", "e3", "f3", "g3", "h3", "a4", "b4", "c4", "d4", "e4", "f4", "g4", "h4");
        for (int round = 0; round < 10; round++) {
            for (String target : targets) {
                moveLeds.showCandidate("b1", target, MoveClassification.GOOD);
            }
        }
        Thread.sleep(80);
        int frames = sim.frameCount();
        assertTrue(frames <= 3, "160 updates in a burst should give at most a few frames, got " + frames);
        int[] last = sim.lastFrame();
        for (String target : targets) {
            assertEquals(LedColors.GOOD, last[led(target)], target);
        }
        assertEquals(LedColors.SOURCE, last[led("b1")]);
    }

    @Test
    void identicalFramesAreNotResent() throws InterruptedException {
        leds.set(LedRenderer.Layer.BASE, 10, 0x00FF00);
        Thread.sleep(40);
        int count = sim.frameCount();
        leds.set(LedRenderer.Layer.BASE, 10, 0x00FF00);
        leds.replace(LedRenderer.Layer.HINT, Map.of());
        Thread.sleep(40);
        assertEquals(count, sim.frameCount());
    }

    @Test
    void higherLayersCoverLowerOnes() {
        leds.set(LedRenderer.Layer.BASE, 5, 0x0000FF);
        leds.set(LedRenderer.Layer.ALERT, 5, 0xFF0000);
        assertEquals(0xFF0000, leds.composeNow()[5]);
        leds.clear(LedRenderer.Layer.ALERT);
        assertEquals(0x0000FF, leds.composeNow()[5]);
    }

    @Test
    void flashBlinksAndThenDisappears() throws InterruptedException {
        sim.clearRecordedFrames();
        leds.flash(LedRenderer.Layer.ALERT, new int[]{Squares.parse("d4")}, 0xFF0000, 3, 60);
        Thread.sleep(300);
        long onFrames = sim.frames().stream().filter(f -> f.wireFrame()[led("d4")] == 0xFF0000).count();
        long offFrames = sim.frames().stream().filter(f -> f.wireFrame()[led("d4")] == 0).count();
        assertEquals(3, onFrames, "three blinks");
        assertTrue(offFrames >= 3);
        assertEquals(0, sim.lastFrame()[led("d4")]);
    }

    @Test
    void verdictShowsQualityAndBestMoveThenExpires() throws InterruptedException {
        moveLeds.showVerdict("e2e4", MoveClassification.MISTAKE, "d2d4");
        Thread.sleep(40);
        int[] frame = sim.lastFrame();
        assertEquals(LedColors.MISTAKE, frame[led("e4")]);
        assertEquals(LedColors.dim(LedColors.MISTAKE, 35), frame[led("e2")]);
        assertEquals(LedColors.dim(LedColors.BEST, 35), frame[led("d2")]);
        assertTrue(sim.frames().stream().anyMatch(f -> f.wireFrame()[led("d4")] == LedColors.BEST), "best move blinks");
        Thread.sleep(MoveLeds.VERDICT_MS + 100);
        assertTrue(Arrays.stream(sim.lastFrame()).allMatch(c -> c == 0), "verdict expired");
    }

    @Test
    void brightnessScalesColors() throws InterruptedException {
        leds.setBrightnessPercent(50);
        leds.set(LedRenderer.Layer.BASE, 0, 0xFF8040);
        Thread.sleep(40);
        assertEquals(0x7F4020, sim.lastFrame()[0]);
    }

    @Test
    void victoryWaveEndsWithLedsOff() throws InterruptedException {
        leds.playVictoryWave();
        Thread.sleep(300);
        assertTrue(Arrays.stream(sim.lastFrame()).anyMatch(c -> c != 0));
        Thread.sleep(3 * 17 * 70);
        assertTrue(Arrays.stream(sim.lastFrame()).allMatch(c -> c == 0));
    }
}
