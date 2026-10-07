package io.github.hardin22.javachess.Engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProcessPlanTest {

    @Test
    void tiersFollowTheRam() {
        ProcessPlan pi1 = ProcessPlan.forRam(906, 4);      // MemTotal of a Pi 4 1 GB
        assertTrue(pi1.botSharesAnalysis(), "1 GB: one Stockfish process only");
        assertTrue(pi1.idleCloseMs() <= 2 * 60_000);

        ProcessPlan pi2 = ProcessPlan.forRam(1_850, 4);
        assertFalse(pi2.botSharesAnalysis());
        assertEquals("2 GB", pi2.tier());

        ProcessPlan pi4 = ProcessPlan.forRam(3_790, 4);
        assertEquals("4 GB", pi4.tier());

        ProcessPlan pi8 = ProcessPlan.forRam(7_900, 4);
        assertEquals("8 GB+", pi8.tier());
        assertTrue(pi8.analysisThreads() >= 1);
        assertNotNull(pi8.describe());
    }

    @Test
    void liteNeverExceedsTheSmallHash() {
        ProcessPlan p = ProcessPlan.forRam(906, 4);
        assertTrue(EngineManager.Budget.lite(p).hashMb() <= 16);
        assertEquals(1, EngineManager.Budget.lite(p).threads());
    }

    @Test
    void stockfishLiteIsSizedForThePi5() {
        ProcessPlan pi5 = new ProcessPlan(7_900, "8 GB+", false, 128, 32, 2, 600_000, 4);
        EngineManager.Budget lite = EngineManager.Budget.lite(pi5);
        assertEquals(2, lite.threads(), "Pi 5: two analysis threads in a game");
        assertEquals(64, lite.hashMb());
        assertEquals(new EngineManager.ReviewPlan(3, 1, 64), lite.review(), "review: 3 processes, a core for the UI");

        ProcessPlan pi4 = new ProcessPlan(906, "1 GB", true, 16, 16, 2, 120_000, 4);
        assertEquals(1, EngineManager.Budget.lite(pi4).threads());
        assertEquals(1, EngineManager.Budget.lite(pi4).review().workers());

        ProcessPlan mac = new ProcessPlan(24_000, "8 GB+", false, 128, 32, 4, 600_000, 10);
        assertEquals(6, EngineManager.Budget.full(mac).review().workers());
    }

    @Test
    void detectsSomeRam() {
        assertTrue(ProcessPlan.totalRamMb() > 256);
    }
}
