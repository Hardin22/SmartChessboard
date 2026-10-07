package io.github.hardin22.javachess.Engine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import io.github.hardin22.javachess.Services.GameAnalyzer;
import io.github.hardin22.javachess.review.MateProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the mate bugs reported by the user.
 *
 * <ul>
 *   <li>Eval bar: right after White gives mate the bar turned fully black (the loser's colour).</li>
 *   <li>Review: in 1.e4 d5 2.exd5 Bd7 3.d4 a5 4.c4 f6 5.Qh5+ g6 6.Be2 gxh5?? 7.Bxh5# the move 6...gxh5 (allows mate in
 *       one from about -1.5) was labelled "best".</li>
 *   <li>In general: a move that allows a forced mate is never a good move, a move that gives mate is never an error,
 *       both in the review and in the fast LED verdict.</li>
 * </ul>
 *
 * Tests annotated with {@code review.pending} document a bug whose fix has not landed yet: run them with
 * {@code -Dreview.pending=true} (they fail until the fix), and drop the annotation together with the fix.
 */
class MateRegressionTest {

    /** The user's game; ply 12 (index 11) is 6...gxh5??, ply 13 (index 12) is 7.Bxh5#. */
    static final List<String> GXH5_GAME = List.of("e2e4", "d7d5", "e4d5", "c8d7", "d2d4", "a7a5", "c2c4", "f7f6",
            "d1h5", "g7g6", "f1e2", "g6h5", "e2h5");
    static final int GXH5 = 11;
    static final int BXH5_MATE = 12;
    /** Fool's mate: 1.f3 e5 2.g4?? Qh4#. */
    static final List<String> FOOLS_MATE = List.of("f2f3", "e7e5", "g2g4", "d8h4");

    static final Set<MoveClassification> ERRORS = EnumSet.of(MoveClassification.INACCURACY, MoveClassification.MISTAKE,
            MoveClassification.MISS, MoveClassification.BLUNDER);
    static final Set<MoveClassification> GOOD = EnumSet.of(MoveClassification.BRILLIANT, MoveClassification.GREAT,
            MoveClassification.BEST, MoveClassification.EXCELLENT, MoveClassification.GOOD, MoveClassification.BOOK_MOVE);

    static List<String> fens(List<String> uci) {
        Board b = new Board();
        List<String> out = new ArrayList<>();
        out.add(b.getFen());
        for (String m : uci) {
            Move move = new Move(m, b.getSideToMove());
            assertTrue(b.legalMoves().contains(move), "illegal " + m);
            b.doMove(move);
            out.add(b.getFen());
        }
        return out;
    }

    static String fenAfter(List<String> uci) {
        List<String> f = fens(uci);
        return f.get(f.size() - 1);
    }

    // ------------------------------------------------------------------ eval bar (no engine needed)

    @Test
    void positionsUsedHereAreReallyMate() {
        Board white = new Board();
        white.loadFromFen(fenAfter(GXH5_GAME));
        assertTrue(white.isMated(), "7.Bxh5# is mate");
        Board black = new Board();
        black.loadFromFen(fenAfter(FOOLS_MATE));
        assertTrue(black.isMated(), "2...Qh4# is mate");
    }

    @Test
    void evalBarIsBlackWhenBlackGaveMate() {
        AnalysisUpdate u = terminal(fenAfter(FOOLS_MATE));
        assertTrue(u.whitePawns() < -900, "bar must be all black, was " + u.whitePawns());
    }

    @Test
    @EnabledIfSystemProperty(named = "review.pending", matches = "true",
            disabledReason = "pending fix (realtime): mate 0 loses its side on negate(), bar shows the loser's colour")
    void evalBarIsWhiteWhenWhiteGaveMate() {
        AnalysisUpdate u = terminal(fenAfter(GXH5_GAME));
        assertTrue(u.whitePawns() > 900, "bar must be all white after 7.Bxh5#, was " + u.whitePawns());
        assertFalse(u.evalText(0).startsWith("-"), "White won: text must not be negative, was " + u.evalText(0));
    }

    @Test
    void evalBarFollowsMateInOneForBothSides() {
        // after 6...gxh5 White (to move) mates in one; after 2.g4 Black (to move) mates in one
        String whiteMates = fens(GXH5_GAME).get(BXH5_MATE);
        String blackMates = fens(FOOLS_MATE).get(3);
        assertTrue(line(whiteMates, Score.mate(1)).whitePawns() > 900);
        assertTrue(line(blackMates, Score.mate(1)).whitePawns() < -900);
        // the same positions seen by a side that gets mated in one
        assertTrue(line(whiteMates.replace(" w ", " b "), Score.mate(-1)).whitePawns() > 900);
        assertTrue(line(blackMates.replace(" b ", " w "), Score.mate(-1)).whitePawns() < -900);
    }

    @Test
    @EnabledIfSystemProperty(named = "review.pending", matches = "true",
            disabledReason = "pending fix (realtime): terminal mate published as mate 0 without a side")
    void liveAnalysisOfAMatedPositionShowsTheWinnerForBothColours() throws Exception {
        try (UciClient engine = new UciClient(UciClientTest.fake("normal"))) {
            PositionAnalyzer analyzer = new PositionAnalyzer(() -> engine, () -> StockfishTestSupport.budget(3, 6));
            try {
                List<AnalysisUpdate> u = new CopyOnWriteArrayList<>();
                analyzer.analyze(fenAfter(GXH5_GAME), 10, 1, u::add);
                PositionAnalyzerTest.await(() -> !u.isEmpty());
                assertTrue(u.get(0).whitePawns() > 900, "White mated Black: bar all white, was " + u.get(0).whitePawns());
                u.clear();
                analyzer.analyze(fenAfter(FOOLS_MATE), 10, 1, u::add);
                PositionAnalyzerTest.await(() -> !u.isEmpty());
                assertTrue(u.get(0).whitePawns() < -900, "Black mated White: bar all black, was " + u.get(0).whitePawns());
            } finally {
                analyzer.stop();
            }
        }
    }

    private static AnalysisUpdate terminal(String fen) {
        return new AnalysisUpdate(fen, 0, List.of(), true, Score.mate(0), 0, 0);
    }

    private static AnalysisUpdate line(String fen, Score sideToMove) {
        InfoLine l = InfoLine.parse("info depth 10 multipv 1 score " + (sideToMove.mate() ? "mate " : "cp ")
                + sideToMove.value() + " nodes 1000 pv a1a1").orElseThrow();
        return new AnalysisUpdate(fen, 10, List.of(l), true, null, 1000, 1);
    }

    // ------------------------------------------------------------------ shared classification maths

    @Test
    void allowingMateFromABetterPositionIsABlunder() {
        // before 6...gxh5 Black's best is about -1.5; after it White mates in one (mate -1 for Black)
        MoveClassifier.Classification c = MoveClassifier.classify(Score.cp(-150), Score.mate(-1), false);
        assertEquals(MoveQuality.BLUNDER, c.quality());
        // even from equal or winning positions
        assertEquals(MoveQuality.BLUNDER, MoveClassifier.classify(Score.cp(0), Score.mate(-3), false).quality());
        assertEquals(MoveQuality.BLUNDER, MoveClassifier.classify(Score.mate(2), Score.mate(-1), false).quality());
    }

    @Test
    void givingMateIsNeverAnError() {
        assertFalse(MoveClassifier.classify(Score.mate(1), Score.mate(1), true).quality().isError());
        // a deeper look at a mating move may see mate the best line missed: still not an error
        assertFalse(MoveClassifier.classify(Score.cp(800), Score.mate(1), false).quality().isError());
        // a slower mate is fine as long as it is still forced
        assertFalse(MoveClassifier.classify(Score.mate(1), Score.mate(3), false).quality().isError());
    }

    @Test
    @EnabledIfSystemProperty(named = "review.pending", matches = "true",
            disabledReason = "pending fix (analysis): lost-position rule downgrades a mate blunder to inaccuracy/good")
    void allowingMateIsNeverAGoodMove() {
        // Clearly lost (-6) but not yet mated: allowing mate in one must still be an error, never "good"/"best".
        assertTrue(MoveClassifier.classify(Score.cp(-600), Score.mate(-1), false).quality().isError());
        assertTrue(MoveClassifier.classify(Score.cp(-1500), Score.mate(-1), false).quality().isError());
    }

    // ------------------------------------------------------------------ full review (real Stockfish)

    @Test
    @EnabledIfSystemProperty(named = "review.pending", matches = "true",
            disabledReason = "pending fix (analysis): 'recovery' rule labels 6...gxh5?? as BEST")
    void reviewLabelsGxh5AsBlunderAndBxh5MateAsGood() {
        StockfishTestSupport.requireStockfish();
        GameAnalyzer analyzer = new GameAnalyzer();
        List<MoveAnalysis> a = analyzer.analyzeGame(String.join(" ", GXH5_GAME), 14, null);
        assertEquals(GXH5_GAME.size(), a.size());
        assertEquals(MoveClassification.BLUNDER, a.get(GXH5).getClassification(), "6...gxh5?? allows mate in one");
        assertFalse(ERRORS.contains(a.get(BXH5_MATE).getClassification()),
                "7.Bxh5# gives mate: " + a.get(BXH5_MATE).getClassification());
        assertTrue(a.get(BXH5_MATE).getScore() > 50_000, "White mated: White POV score " + a.get(BXH5_MATE).getScore());
        assertMateInvariants(a, GXH5_GAME);
    }

    @Test
    void reviewNeverCallsTheFoolsMateBlunderGood() {
        StockfishTestSupport.requireStockfish();
        List<MoveAnalysis> a = new GameAnalyzer().analyzeGame(String.join(" ", FOOLS_MATE), 14, null);
        assertEquals(MoveClassification.BLUNDER, a.get(2).getClassification(), "2.g4?? allows mate in one");
        assertFalse(ERRORS.contains(a.get(3).getClassification()), "2...Qh4# gives mate");
        assertMateInvariants(a, FOOLS_MATE);
    }

    /** Engine-independent checks: mate given is never an error, mate in one allowed is never a good move. */
    static void assertMateInvariants(List<MoveAnalysis> a, List<String> uci) {
        List<String> fens = fens(uci);
        for (int i = 0; i < a.size(); i++) {
            MoveClassification c = a.get(i).getClassification();
            String san = (i / 2 + 1) + (i % 2 == 0 ? ". " : "... ") + uci.get(i);
            if (MateProbe.isMate(fens.get(i + 1))) {
                assertFalse(ERRORS.contains(c), san + " gives mate but is labelled " + c);
            } else if (MateProbe.mateInOneFor(fens.get(i + 1)) != null && MateProbe.mateInOneFor(fens.get(i)) == null) {
                assertFalse(GOOD.contains(c), san + " allows mate in one but is labelled " + c);
            }
        }
    }

    // ------------------------------------------------------------------ LED verdict (real Stockfish)

    @Test
    void ledVerdictCallsGxh5ABlunderAndTheMateNotAnError() throws Exception {
        List<String> fens = fens(GXH5_GAME);
        assertEquals(MoveQuality.BLUNDER, ledVerdict(fens.get(GXH5), GXH5_GAME.get(GXH5)));
        assertFalse(ledVerdict(fens.get(BXH5_MATE), GXH5_GAME.get(BXH5_MATE)).isError());
        assertEquals(MoveQuality.BLUNDER, ledVerdict(fens(FOOLS_MATE).get(2), FOOLS_MATE.get(2)));
        assertFalse(ledVerdict(fens(FOOLS_MATE).get(3), FOOLS_MATE.get(3)).isError());
    }

    /** Last LED verdict of the move, with the default full-profile depths on one thread. */
    static MoveQuality ledVerdict(String fen, String uci) throws Exception {
        StockfishTestSupport.requireStockfish();
        EngineManager.Budget full = EngineManager.Budget.full();
        EngineManager.Budget b = new EngineManager.Budget(1, 16, 30, full.coachMinDepth(), full.coachConfirmDepth(),
                full.coachCapMs(), full.candidateNodes(), full.candidateCapMs(), 1, 16, 1_000, 0, 0);
        List<MoveFeedback> verdicts = new CopyOnWriteArrayList<>();
        try (UciClient engine = StockfishTestSupport.client("mate-regression", 1, 16)) {
            engine.start().get(60, TimeUnit.SECONDS);
            PositionAnalyzer analyzer = new PositionAnalyzer(() -> engine, () -> b);
            MoveCoach coach = new MoveCoach(analyzer, () -> b);
            coach.setFeedbackListener(new MoveFeedbackListener() {
                @Override
                public void onMoveClassified(MoveFeedback fb) {
                    verdicts.add(fb);
                }
            });
            try {
                analyzer.analyze(fen, 18, 1, null);
                CoachLatencyTest.await(() -> {
                    AnalysisUpdate u = analyzer.lastUpdate();
                    return u != null && u.fen().equals(fen) && (u.depth() >= b.coachMinDepth() || u.finished());
                });
                coach.onMovePlayed(fen, uci);
                // a preliminary verdict is confirmed silently when the deeper search agrees
                CoachLatencyTest.await(() -> !verdicts.isEmpty());
                CoachLatencyTest.await(coach::isIdle);
            } finally {
                analyzer.stop();
            }
        }
        MoveFeedback last = verdicts.get(verdicts.size() - 1);
        return last.quality();
    }
}
