package io.github.hardin22.javachess.Engine;

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
 * Uses the production verdict depths ({@link EngineManager.Budget#full()} min/confirm depth) with ONE thread and no
 * time caps, so the searches are node/depth-limited and the results do not depend on the speed of the machine
 * (slow CI runners): no sleeps, every wait is on a condition with a generous deadline.
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
        budget = new EngineManager.Budget(1, 32, 30, full.coachMinDepth(), full.coachConfirmDepth(), 60_000,
                full.candidateNodes(), 60_000, 1, 16, 1_000, 0, 0);
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

    /** Waits for a condition (polling) with a deadline generous enough for slow CI machines. */
    static void await(String what, java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!cond.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    /** Waits until every event already queued on the engine event thread has been delivered. */
    static void flushEvents() throws Exception {
        EngineEvents.EXECUTOR.submit(() -> { }).get(60, TimeUnit.SECONDS);
    }

    /** The player "thinks" until the live analysis of {@code fen} reached {@code depth}. */
    static void think(String fen, int depth) throws InterruptedException {
        analyzer.analyze(fen, 18, 1, null);
        await("analysis of " + fen + " to depth " + depth, () -> {
            AnalysisUpdate u = analyzer.lastUpdate();
            return u != null && u.fen().equals(fen) && (u.depth() >= depth || u.finished());
        });
    }

    /** Plays the move after the analysis reached the verdict depth; returns the last verdict. */
    static MoveFeedback play(String fenBefore, String uci) throws Exception {
        think(fenBefore, budget.coachMinDepth());
        coach.onMovePlayed(fenBefore, uci);
        return awaitFinal(uci);
    }

    static MoveFeedback awaitFinal(String uci) throws Exception {
        await("verdict for " + uci, () -> coach.isIdle() && verdicts.stream().anyMatch(v -> v.uci().equals(uci)));
        flushEvents();
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
            "k7/8/2K5/8/8/8/8/1Q6 w - - 0 1; b1b6; ERROR; stalemate throws the win",
            "8/8/4k3/8/8/4K3/8/8 w - - 0 1; e3d3; OK; dead draw, any king move",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5; e1g1; OK; castles",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5; c4f7; ERROR; bishop sac that loses material",
            // the user's game 1.e4 d5 2.exd5 Bd7 3.d4 a5 4.c4 f6 5.Qh5+ g6 6.Be2 gxh5?? 7.Bxh5#
            "rn1qkbnr/1ppbp2p/5pp1/p2P3Q/2PP4/8/PP2BPPP/RNB1K1NR b KQkq - 1 6; g6h5; BLUNDER; user game: allows Bxh5#",
            "rn1qkbnr/1ppbp2p/5p2/p2P3p/2PP4/8/PP2BPPP/RNB1K1NR w KQkq - 0 7; e2h5; BEST; user game: Bxh5#",
            "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2; d8h4; BEST; Black mates (fool's mate)",
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
        MoveFeedback fb = play(fen.trim(), move.trim());
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
        think(fen, budget.coachMinDepth());
        CandidateFeedback fb = coach.onPieceLifted(fen, "H5").get(60, TimeUnit.SECONDS);
        assertNotNull(fb);
        assertEquals(MoveQuality.BLUNDER, fb.destinations().get("E5"), fb.toString());
        assertEquals(MoveQuality.BLUNDER, fb.destinations().get("F7"), fb.toString());
        assertEquals(13, fb.destinations().size(), "every queen move scored: " + fb);
        System.out.printf("lift hints: %d moves, depth %d, %d ms%n", fb.destinations().size(), fb.depth(), fb.latencyMs());
        flushEvents();
        assertEquals(1, candidates.size(), "candidate event emitted once");

        // The move of the lifted piece gets an immediate (preliminary) verdict from the same search, when that
        // search was deep enough (node-limited: the depth depends on the Stockfish version, not on the machine).
        coach.onMovePlayed(fen, "h5e5");
        flushEvents();
        MoveFeedback first = verdicts.stream().filter(v -> v.uci().equals("h5e5")).findFirst().orElse(null);
        if (fb.depth() >= budget.coachMinDepth() - 2) {
            assertNotNull(first, "instant verdict expected (candidate depth " + fb.depth() + ")");
            assertTrue(first.preliminary());
            assertEquals(MoveQuality.BLUNDER, first.quality());
        }
        assertEquals(MoveQuality.BLUNDER, awaitFinal("h5e5").quality());
    }

    @Test
    void instantMoveWithoutPriorAnalysisIsStillJudged() throws Exception {
        // e.g. first move of a game: no analysis of the position before yet
        String fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 2 3";
        analyzer.analyze(fen, 18, 1, null);
        coach.onMovePlayed(fen, "h5e5");
        MoveFeedback fb = awaitFinal("h5e5");
        assertEquals(MoveQuality.BLUNDER, fb.quality());
        assertNotNull(fb.bestMoveUci());
    }

    @Test
    void verdictIsGuaranteedWhenTheGameMovesOnImmediately() throws Exception {
        // PvC with an instant bot (Maia): the analysis jumps to the position after the bot reply within ms.
        String fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 2 3";
        think(fen, budget.coachMinDepth());
        coach.onMovePlayed(fen, "h5e5");
        analyzer.analyze("r1bqkbnr/pppp1ppp/8/4n3/4P3/8/PPPP1PPP/RNB1KBNR w KQkq - 0 4", 18, 1, null);
        MoveFeedback fb = awaitFinal("h5e5");
        assertEquals(MoveQuality.BLUNDER, fb.quality());
    }

    @Test
    void searchmovesSearchHonoursTheNodeLimit() throws Exception {
        String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        SearchResult r = engine.search(fen, List.of(), SearchLimits.nodes(20_000).withMultiPv(2)
                .withSearchMoves(List.of("g1f3", "g1h3")), null).result().get(60, TimeUnit.SECONDS);
        assertTrue(r.nodes() < 40_000, "nodes " + r.nodes());
        assertEquals(2, r.lines().size());
        assertTrue(r.lines().stream().allMatch(l -> l.move().startsWith("g1")));
    }

    @Test
    void staleLiftIsDropped() throws Exception {
        String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        // a huge node budget: the hint search cannot finish before the piece is put down
        EngineManager.Budget slow = new EngineManager.Budget(1, 32, 30, budget.coachMinDepth(),
                budget.coachConfirmDepth(), 60_000, 500_000_000L, 60_000, 1, 16, 1_000, 0, 0);
        MoveCoach local = new MoveCoach(analyzer, () -> slow);
        List<CandidateFeedback> seen = new CopyOnWriteArrayList<>();
        local.setFeedbackListener(new MoveFeedbackListener() {
            @Override
            public void onCandidates(CandidateFeedback fb) {
                seen.add(fb);
            }
        });
        analyzer.analyze(fen, 18, 1, null);
        CompletableFuture<CandidateFeedback> f = local.onPieceLifted(fen, "G1");
        local.onPieceReleased();
        assertNull(f.get(60, TimeUnit.SECONDS));
        flushEvents();
        assertTrue(seen.isEmpty());
    }
}
