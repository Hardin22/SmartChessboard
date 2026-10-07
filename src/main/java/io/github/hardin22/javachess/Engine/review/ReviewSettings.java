package io.github.hardin22.javachess.Engine.review;

/**
 * Engine budget of a game review.
 *
 * @param nodes           nodes per position (main pass, MultiPV 1)
 * @param secondLineNodes nodes of the second best line (search of all moves but the best) where Great/Brilliant
 *                        need it
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
     * Stockfish Lite (default, Raspberry Pi 5): 300k nodes per position (depth ~16-19). The processes and hash used
     * by the app come from {@code EngineManager.Budget.review()} (3 x 64 MB on a Pi 5 8 GB).
     */
    public static ReviewSettings lite() {
        return new ReviewSettings(300_000, 100_000, 3, 64);
    }

    /** Full Stockfish: 1M nodes per position, like Lichess user analysis. */
    public static ReviewSettings full() {
        return new ReviewSettings(1_000_000, 300_000, 6, 128);
    }

    public ReviewSettings withNodes(long n) {
        return new ReviewSettings(n, Math.max(50_000, n / 3), processes, hashMb);
    }

    public ReviewSettings withProcesses(int p) {
        return new ReviewSettings(nodes, secondLineNodes, p, hashMb);
    }
}
