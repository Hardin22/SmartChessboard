package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Engine.ProcessPlan;

/**
 * Engine budget of a game review.
 *
 * @param nodes           nodes per position (main pass, MultiPV 1)
 * @param secondLineNodes nodes of the MultiPV 2 re-search of the positions that need it (Great/Brilliant/Miss)
 * @param processes       Stockfish processes searching in parallel (1 thread each)
 * @param hashMb          Hash of each process
 */
public record ReviewSettings(long nodes, long secondLineNodes, int processes, int hashMb) {

    public ReviewSettings {
        nodes = Math.max(1_000, nodes);
        secondLineNodes = Math.max(1_000, secondLineNodes);
        processes = Math.max(1, processes);
        hashMb = Math.max(1, hashMb);
    }

    /**
     * Stockfish Lite (default, Raspberry Pi 5): 300k nodes per position (depth ~16-19), one process per core
     * (up to 4) while nobody plays. SPEC §9; calibrated by the validation harness.
     */
    public static ReviewSettings lite(ProcessPlan plan, int cores) {
        return new ReviewSettings(300_000, 450_000, Math.max(1, Math.min(4, cores)), 64);
    }

    /** Full Stockfish: 1M nodes per position, like Lichess user analysis. */
    public static ReviewSettings full(ProcessPlan plan, int cores) {
        return new ReviewSettings(1_000_000, 1_500_000, Math.max(1, Math.min(8, cores)), 64);
    }

    public ReviewSettings withNodes(long n) {
        return new ReviewSettings(n, n * 3 / 2, processes, hashMb);
    }

    public ReviewSettings withProcesses(int p) {
        return new ReviewSettings(nodes, secondLineNodes, p, hashMb);
    }
}
