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
    void detectsSomeRam() {
        assertTrue(ProcessPlan.totalRamMb() > 256);
    }
}
