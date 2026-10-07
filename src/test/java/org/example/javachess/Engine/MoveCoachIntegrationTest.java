package org.example.javachess.Engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LED move classification on known positions with the real Stockfish (skipped when it is not installed).
 * Uses the production budget numbers ({@link EngineManager.Budget#full()} min/confirm depth).
 */
class MoveCoachIntegrationTest {

    static UciClient engine;
    static PositionAnalyzer analyzer;
    static MoveCoach coach;
    static final List<MoveFeedback> verdicts = new CopyOnWriteArrayList<>();
    static final List<CandidateFeedback> candidates = new CopyOnWriteArrayList<>();
    static EngineManager.Budget budget;

    @BeforeAll
    static void setUp() {
        StockfishTestSupport.requireStockfish();
        EngineManager.Budget full = EngineManager.Budget.full();
        budget = new EngineManager.Budget(2, 32, 30, full.coachMinDepth(), full.coachConfirmDepth(), full.coachCapMs(),
                full.candidateNodes(), full.candidateCapMs(), 1, 16, 1_000, 0, 0);
        engine = StockfishTestSupport.client("coach-test", budget.threads(), budget.hashMb());
        analyzer = new PositionAnalyzer(() -> engine, () -> budget);
        coach = new MoveCoach(analyzer, () -> budget);
        coach.setFeedbackListener(new MoveFeedbackListener() {
            @Override
            public void onMoveClassified(MoveFeedback fb) {
                verdicts.add(fb);
            }

            @Override
            public void onCandidates(CandidateFeedback fb) {
                candidates.add(fb);
            }
        });
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @BeforeEach
    void clear() {
        verdicts.clear();
        candidates.clear();
    }

    /** Lets the analysis of fenBefore run like a player thinking, then plays the move; returns the last verdict. */
    static MoveFeedback play(String fenBefore, String uci, long thinkMs) throws Exception {
        analyzer.analyze(fenBefore, 18, 1, null);
        Thread.sleep(thinkMs);
        coach.onMovePlayed(fenBefore, uci);
        return awaitFinal(uci);
    }

    static MoveFeedback awaitFinal(String uci) throws Exception {
        long deadline = System.currentTimeMillis() + MoveCoach.HARD_DEADLINE_MS + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (coach.isIdle() && verdicts.stream().anyMatch(v -> v.uci().equals(uci))) {
                break;
            }
            Thread.sleep(20);
        }
        Thread.sleep(50); // let the event thread deliver
        List<MoveFeedback> mine = new ArrayList<>(verdicts.stream().filter(v -> v.uci().equals(uci)).toList());
        assertFalse(mine.isEmpty(), "no verdict for " + uci);
        return mine.get(mine.size() - 1);
    }

    static final String[] CASES = {
            // fen; move; expected; description
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1; e2e4; OK; opening main line",
            "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3; g8f6; BLUNDER; allows Qxf7 mate",
            "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3; g7g6; OK; defends f7",
            "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4; h5f7; BEST; mate in one",
            "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4; h5h3; BLUNDER; misses mate in one",
            "r1bqkbnr/pppp1ppp/2n5/4p2Q/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 2 3; h5e5; BLUNDER; hangs the queen",
            "r1bqkbnr/pppp1ppp/2n5/4p2Q/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 2 3; f1c4; OK; develops with threat",
            "k7/8/2K5/8/8/8/8/1Q6 w - - 0 1; b1b7; BEST; queen mate",
            "k7/8/2K5/8/8/8/8/1Q6 w - - 0 1; b1b6; BLUNDER; stalemate throws the win",
            "8/8/4k3/8/8/4K3/8/8 w - - 0 1; e3d3; OK; dead draw, any king move",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5; e1g1; OK; castles",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5; c4f7; ERROR; bishop sac that loses material",
    };

    @TestFactory
    List<DynamicTest> classifiesKnownPositions() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String c : CASES) {
            String[] f = c.split(";");
            tests.add(DynamicTest.dynamicTest(f[3].trim() + ": " + f[1].trim() + " -> " + f[2].trim(),
                    () -> classify(f[0], f[1], f[2], f[3])));
        }
        return tests;
    }

    void classify(String fen, String move, String expected, String description) throws Exception {
        MoveFeedback fb = play(fen.trim(), move.trim(), 800);
        MoveQuality q = fb.quality();
        switch (expected.trim()) {
            case "OK" -> assertFalse(q.isError(), description + ": got " + q);
            case "ERROR" -> assertTrue(q.isError(), description + ": got " + q);
            default -> assertEquals(MoveQuality.valueOf(expected.trim()), q, description);
        }
        assertTrue(fb.depth() >= budget.coachMinDepth() || q == MoveQuality.BEST || fb.depth() == 0,
                "verdict below the minimum depth: " + fb.depth());
        if (q.isError()) {
            assertNotNull(fb.bestMoveUci(), "an error must come with the right move to show");
        }
    }

    @Test
    void liftedPieceGetsOneVerdictPerDestinationAndInstantMoveVerdict() throws Exception {
        String fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 2 3";
        analyzer.analyze(fen, 18, 1, null);
        Thread.sleep(500);
        CandidateFeedback fb = coach.onPieceLifted(fen, "H5").get(5, TimeUnit.SECONDS);
        assertNotNull(fb);
        assertEquals(MoveQuality.BLUNDER, fb.destinations().get("E5"), fb.toString());
        assertEquals(MoveQuality.BLUNDER, fb.destinations().get("F7"), fb.toString());
        assertEquals(13, fb.destinations().size(), "every queen move scored: " + fb);
        assertTrue(fb.latencyMs() < budget.candidateCapMs(), "candidate latency " + fb.latencyMs());
        System.out.printf("lift hints: %d moves, depth %d, %d ms%n", fb.destinations().size(), fb.depth(), fb.latencyMs());
        Thread.sleep(50);
        assertEquals(1, candidates.size(), "candidate event emitted once");

        // the move of the lifted piece gets an immediate (preliminary) verdict from the same search
        coach.onMovePlayed(fen, "h5e5");
        Thread.sleep(30);
        MoveFeedback first = verdicts.stream().filter(v -> v.uci().equals("h5e5")).findFirst().orElse(null);
        assertNotNull(first, "instant verdict expected");
        assertEquals(MoveQuality.BLUNDER, first.quality());
        assertTrue(first.latencyMs() < 50, "latency " + first.latencyMs());
        assertEquals(MoveQuality.BLUNDER, awaitFinal("h5e5").quality());
    }

    @Test
    void verdictIsGuaranteedWhenTheGameMovesOnImmediately() throws Exception {
        // PvC with an instant bot (Maia): the analysis jumps to the position after the bot reply within ms.
        String fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 2 3";
        analyzer.analyze(fen, 18, 1, null);
        Thread.sleep(300);
        coach.onMovePlayed(fen, "h5e5");
        Thread.sleep(5);
        analyzer.analyze("r1bqkbnr/pppp1ppp/8/4n3/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 0 4", 18, 1, null);
        MoveFeedback fb = awaitFinal("h5e5");
        assertEquals(MoveQuality.BLUNDER, fb.quality());
    }

    @Test
    void searchmovesSearchHonoursTheNodeLimit() throws Exception {
        String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        SearchResult r = engine.search(fen, List.of(), SearchLimits.nodes(20_000).withMultiPv(2)
                .withSearchMoves(List.of("g1f3", "g1h3")), null).result().get(10, TimeUnit.SECONDS);
        assertTrue(r.nodes() < 40_000, "nodes " + r.nodes());
        assertEquals(2, r.lines().size());
        assertTrue(r.lines().stream().allMatch(l -> l.move().startsWith("g1")));
    }

    @Test
    void staleLiftIsDropped() throws Exception {
        String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        analyzer.analyze(fen, 18, 1, null);
        CompletableFuture<CandidateFeedback> f = coach.onPieceLifted(fen, "G1");
        coach.onPieceReleased();
        assertNull(f.get(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertTrue(candidates.isEmpty());
    }
}
