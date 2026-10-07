package org.example.javachess.Engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Latency of the LED verdict on a simulated Raspberry Pi 5.
 *
 * <p>Simulation: the analysis engine runs with ONE thread here and the measured time is divided by the
 * per-core speed ratio of a Pi 5 vs an Apple M-series core ({@code -Dpi5.ratio}, default 0.33). On the Pi the
 * analysis engine has 2 threads, so this is a pessimistic estimate. The player "thinks" 300 ms only (a fast
 * move, warm hash of the parent position as in the real flow) and the move is not lifted first (no instant
 * verdict from the candidate search): this measures the post-move search path.</p>
 */
class CoachLatencyTest {

    static final double PI5 = Double.parseDouble(System.getProperty("pi5.ratio", "0.25"));

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
    void verdictLatencyOnSimulatedPi5() throws Exception {
        StockfishTestSupport.requireStockfish();
        EngineManager.Budget full = EngineManager.Budget.full();
        EngineManager.Budget b = new EngineManager.Budget(1, 16, 30, full.coachMinDepth(), full.coachConfirmDepth(),
                full.coachCapMs(), full.candidateNodes(), full.candidateCapMs(), 1, 16, 1_000, 0, 0);
        List<MoveFeedback> verdicts = new CopyOnWriteArrayList<>();
        List<Long> prelim = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        try (UciClient engine = StockfishTestSupport.client("latency", 1, 16)) {
            PositionAnalyzer analyzer = new PositionAnalyzer(() -> engine, () -> b);
            MoveCoach coach = new MoveCoach(analyzer, () -> b);
            coach.setFeedbackListener(new MoveFeedbackListener() {
                @Override
                public void onMoveClassified(MoveFeedback fb) {
                    verdicts.add(fb);
                }
            });
            engine.start().get();
            for (String[] c : CASES) {
                verdicts.clear();
                analyzer.analyze(c[0], 18, 1, null);
                Thread.sleep(300);
                coach.onMovePlayed(c[0], c[1]);
                long deadline = System.currentTimeMillis() + MoveCoach.HARD_DEADLINE_MS;
                while (verdicts.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5);
                }
                assertFalse(verdicts.isEmpty(), "no verdict for " + c[1]);
                MoveFeedback first = verdicts.get(0);
                prelim.add(first.latencyMs());
                depths.add(first.depth());
                // depth 0 = the move ended the game (mate/stalemate): exact verdict without search
                assertTrue(first.depth() >= b.coachMinDepth() || first.depth() == 0, "depth " + first.depth());
                while (!coach.isIdle() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
            }
            analyzer.stop();
        }
        long p50 = pct(prelim, 50);
        long p95 = pct(prelim, 95);
        String report = String.format(Locale.ROOT,
                "verdict latency, Mac 1 thread: p50=%d ms p95=%d ms | simulated Pi 5 (x%.2f): p50=%d ms p95=%d ms | depth min=%d (min required %d)",
                p50, p95, PI5, (long) (p50 / PI5), (long) (p95 / PI5),
                depths.stream().mapToInt(Integer::intValue).filter(d -> d > 0).min().orElse(0), b.coachMinDepth());
        System.out.println(report);
        // Realistic Pi 5 targets: a verdict within ~1 s typically and ~2 s worst case after the piece is put down.
        assertTrue(p50 / PI5 < 1_200, report);
        assertTrue(p95 / PI5 < 2_500, report);
    }

    static long pct(List<Long> v, int p) {
        long[] a = v.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        int idx = (int) Math.ceil(p / 100.0 * a.length) - 1;
        return a[Math.max(0, Math.min(a.length - 1, idx))];
    }
}
