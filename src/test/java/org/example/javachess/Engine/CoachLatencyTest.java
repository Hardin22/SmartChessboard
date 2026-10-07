package org.example.javachess.Engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Latency of the LED verdict on a simulated Raspberry Pi.
 *
 * <p>Simulation independent of the machine running the test: the single-thread speed of THIS machine is measured
 * first, then every latency is scaled by {@code thisNps / piNps}. Pi numbers: SF19 aarch64 does 1.08 M nps on one
 * Apple-silicon core in the linux/arm64 container; a Cortex-A76 (Pi 5) core is ~3.5x slower (310 k) and a
 * Cortex-A72 (Pi 4) core ~9x slower (120 k). Override with {@code -Dpi5.nps}, {@code -Dpi4.nps} once measured on a
 * real board ({@code engines/stockfish/stockfish bench}). A slow CI runner measures longer latencies but also a lower nps, so the
 * estimate and the assertions stay stable. The analysis engine has ONE thread here (two on the real Pi), so the
 * estimate is pessimistic. The player "thinks" until the live analysis of the position reached the verdict depth,
 * and does not lift the piece first (no instant verdict from the hint search): this measures the post-move path.</p>
 */
class CoachLatencyTest {

    static final long PI5_NPS = Long.getLong("pi5.nps", 310_000);
    static final long PI4_NPS = Long.getLong("pi4.nps", 120_000);
    static final String MIDDLEGAME = "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R w KQ - 0 8";

    /** Middlegame / opening positions with a natural move each. */
    static final String[][] CASES = {
            { "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4", "d2d3" },
            { "rnbqkb1r/pp2pppp/3p1n2/8/3NP3/8/PPP2PPP/RNBQKB1R w KQkq - 1 5", "b1c3" },
            { "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R w KQ - 0 8", "a1c1" },
            { "r2q1rk1/pp1bbppp/2nppn2/8/3NP3/2N1B3/PPPQBPPP/R4RK1 w - - 4 10", "f2f4" },
            { "2rq1rk1/pp1bppbp/3p1np1/4n3/3NP3/1BN1BP2/PPPQ2PP/2KR3R w - - 5 12", "h2h4" },
            { "r1b2rk1/pp1nqppp/2pbpn2/3p4/2PP4/2NBPN2/PPQ2PPP/R1B2RK1 w - - 2 9", "e3e4" },
            { "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", "c3d5" },
            { "rnbq1rk1/ppp1ppbp/3p1np1/8/2PPP3/2N2N2/PP2BPPP/R1BQK2R b KQ - 3 6", "e7e5" },
            { "r1bqk2r/pp2bppp/2nppn2/8/3NP3/2N1B3/PPP1BPPP/R2QK2R b KQkq - 3 8", "e8g8" },
            { "8/5pk1/6p1/3R4/5P2/r5P1/5K2/8 b - - 1 45", "a3a2" },
            { "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1", "d1d8" },
            { "r3k2r/ppp2ppp/2n1bn2/2bqp3/8/2NP1NP1/PPP1PPBP/R1BQ1RK1 w kq - 2 8", "c3d5" },
    };

    @Test
    void verdictLatencyOnSimulatedPi() throws Exception {
        StockfishTestSupport.requireStockfish();
        EngineManager.Budget full = EngineManager.Budget.full();
        EngineManager.Budget b = new EngineManager.Budget(1, 16, 30, full.coachMinDepth(), full.coachConfirmDepth(),
                full.coachCapMs(), full.candidateNodes(), full.candidateCapMs(), 1, 16, 1_000, 0, 0);
        List<MoveFeedback> verdicts = new CopyOnWriteArrayList<>();
        List<Long> latency = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        long nps;
        try (UciClient engine = StockfishTestSupport.client("latency", 1, 16)) {
            engine.start().get(60, TimeUnit.SECONDS);
            SearchResult speed = engine.search(MIDDLEGAME, SearchLimits.movetime(1_000)).result().get(60, TimeUnit.SECONDS);
            nps = Math.max(1, speed.nodes() * 1000 / Math.max(1, speed.elapsedMs()));
            engine.newGame().get(60, TimeUnit.SECONDS);

            PositionAnalyzer analyzer = new PositionAnalyzer(() -> engine, () -> b);
            MoveCoach coach = new MoveCoach(analyzer, () -> b);
            coach.setFeedbackListener(new MoveFeedbackListener() {
                @Override
                public void onMoveClassified(MoveFeedback fb) {
                    verdicts.add(fb);
                }
            });
            for (String[] c : CASES) {
                verdicts.clear();
                analyzer.analyze(c[0], 18, 1, null);
                await(() -> {
                    AnalysisUpdate u = analyzer.lastUpdate();
                    return u != null && u.fen().equals(c[0]) && (u.depth() >= b.coachMinDepth() || u.finished());
                });
                coach.onMovePlayed(c[0], c[1]);
                await(() -> !verdicts.isEmpty());
                MoveFeedback first = verdicts.get(0);
                latency.add(first.latencyMs());
                depths.add(first.depth());
                // depth 0 = the move ended the game (mate/stalemate): exact verdict without search
                assertTrue(first.depth() >= b.coachMinDepth() || first.depth() == 0, "depth " + first.depth());
                await(coach::isIdle);
            }
            analyzer.stop();
        }
        double toPi5 = (double) nps / PI5_NPS;
        double toPi4 = (double) nps / PI4_NPS;
        long p50 = pct(latency, 50);
        long p95 = pct(latency, 95);
        String report = String.format(Locale.ROOT,
                "verdict latency, this machine (1 thread, %d nps): p50=%d ms p95=%d ms | Pi 5 est. (%d nps/core): p50=%d ms "
                        + "p95=%d ms | Pi 4 est. (%d nps/core): p50=%d ms p95=%d ms | min depth %d (required %d)",
                nps, p50, p95, PI5_NPS, Math.round(p50 * toPi5), Math.round(p95 * toPi5), PI4_NPS,
                Math.round(p50 * toPi4), Math.round(p95 * toPi4),
                depths.stream().mapToInt(Integer::intValue).filter(d -> d > 0).min().orElse(0), b.coachMinDepth());
        System.out.println(report);
        // Targets (EngineManager.Budget): verdict after the piece is put down within 0.5 s typical / 1.5 s worst
        // on a Pi 5, 1 s / 3 s on a Pi 4 (pessimistic: one analysis thread here).
        assertTrue(p50 * toPi5 < 500, report);
        assertTrue(p95 * toPi5 < 1_500, report);
        assertTrue(p95 * toPi4 < 3_000, report);
    }

    static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!cond.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out");
            Thread.sleep(2);
        }
    }

    static long pct(List<Long> v, int p) {
        long[] a = v.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        int idx = (int) Math.ceil(p / 100.0 * a.length) - 1;
        return a[Math.max(0, Math.min(a.length - 1, idx))];
    }
}
