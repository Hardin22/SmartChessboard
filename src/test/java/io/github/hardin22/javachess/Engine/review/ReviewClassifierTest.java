package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Engine.Score;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure classification maths: mate scores, points of view, labels (no engine). */
class ReviewClassifierTest {

    @Test
    void uciScoresConvertToWhitePointOfView() {
        assertEquals(Eval.cp(35), Eval.fromUci(Score.cp(35), true));
        assertEquals(Eval.cp(-35), Eval.fromUci(Score.cp(35), false));
        assertEquals(Eval.whiteMates(2), Eval.fromUci(Score.mate(2), true));
        assertEquals(Eval.blackMates(2), Eval.fromUci(Score.mate(2), false));
        assertEquals(Eval.blackMates(3), Eval.fromUci(Score.mate(-3), true));
        assertEquals(Eval.whiteMates(3), Eval.fromUci(Score.mate(-3), false));
        // "mate 0": the side to move is checkmated, the OTHER side won
        assertEquals(Eval.blackMates(0), Eval.fromUci(Score.mate(0), true));
        assertEquals(Eval.whiteMates(0), Eval.fromUci(Score.mate(0), false));
    }

    @Test
    void terminalPositions() {
        // fool's mate: White to move is checkmated
        Eval mated = Eval.terminal("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3").orElseThrow();
        assertEquals(Eval.blackMates(0), mated);
        assertEquals(0.0, mated.winChance(true));
        assertEquals(1.0, mated.winChance(false));
        assertEquals(-1000.0, mated.legacyPawns());
        assertEquals(Eval.DRAW, Eval.terminal("k7/8/1QK5/8/8/8/8/8 b - - 0 1").orElseThrow(), "stalemate");
        assertTrue(Eval.terminal(GameReplay.START_FEN).isEmpty());
    }

    @Test
    void winChancesAreSymmetric() {
        assertEquals(0.5, Eval.cp(0).winChance(true), 1e-9);
        Eval e = Eval.cp(150);
        assertEquals(1.0, e.winChance(true) + e.winChance(false), 1e-9);
        assertTrue(e.winChance(true) > 0.6);
        assertEquals(1.0, Eval.whiteMates(7).winChance(true));
        assertEquals(0.0, Eval.whiteMates(7).winChance(false));
    }

    @Test
    void fastMateRules() {
        // Black plays gxh5?? from about -1.5 (Black POV) into mate in 1 for White
        FastVerdict v = ReviewClassifier.fast(Eval.cp(150), Eval.whiteMates(1), false, false);
        assertEquals(MoveClassification.BLUNDER, v.label());
        // delivering mate is best, whatever the engine's line said
        assertEquals(MoveClassification.BEST,
                ReviewClassifier.fast(Eval.whiteMates(1), Eval.whiteMates(0), true, false).label());
        // allowing mate is never better than a mistake from a bad position, an inaccuracy from a lost one
        assertEquals(MoveClassification.MISTAKE,
                ReviewClassifier.fast(Eval.cp(-600), Eval.blackMates(2), true, false).label());
        assertEquals(MoveClassification.INACCURACY,
                ReviewClassifier.fast(Eval.cp(-1500), Eval.blackMates(2), true, false).label());
        // already mated: never a blunder
        assertNotEquals(MoveClassification.BLUNDER,
                ReviewClassifier.fast(Eval.blackMates(3), Eval.blackMates(1), true, false).label());
        // keeping a forced mate is fine
        assertEquals(MoveClassification.BEST,
                ReviewClassifier.fast(Eval.whiteMates(3), Eval.whiteMates(2), true, false).label());
        assertEquals(MoveClassification.EXCELLENT,
                ReviewClassifier.fast(Eval.whiteMates(3), Eval.whiteMates(3), true, false).label());
    }

    @Test
    void lossThresholds() {
        assertEquals(MoveClassification.BEST, ReviewClassifier.labelForLoss(0));
        assertEquals(MoveClassification.EXCELLENT, ReviewClassifier.labelForLoss(0.01));
        assertEquals(MoveClassification.GOOD, ReviewClassifier.labelForLoss(0.02));
        assertEquals(MoveClassification.GOOD, ReviewClassifier.labelForLoss(0.04));
        assertEquals(MoveClassification.INACCURACY, ReviewClassifier.labelForLoss(0.08));
        assertEquals(MoveClassification.MISTAKE, ReviewClassifier.labelForLoss(0.15));
        assertEquals(MoveClassification.BLUNDER, ReviewClassifier.labelForLoss(0.30));
    }

    @Test
    void reportedGameFromStoredEvaluations() {
        // 1.e4 d5 2.exd5 Bd7 3.d4 a5 4.c4 f6 5.Qh5+ g6 6.Be2 gxh5?? 7.Bxh5#
        GameReplay g = GameReplay.of(GameReplay.START_FEN,
                "1. e4 d5 2. exd5 Bd7 3. d4 a5 4. c4 f6 5. Qh5+ g6 6. Be2 gxh5 7. Bxh5#");
        assertEquals(13, g.uci().size());
        assertEquals("g6h5", g.uci().get(11));
        assertEquals("Bxh5#", g.sans().get(12));
        List<PositionEval> evals = new ArrayList<>();
        for (int i = 0; i < g.fens().size(); i++) {
            String fen = g.fens().get(i);
            if (i == 13) {
                evals.add(PositionEval.terminal(fen, Eval.terminal(fen).orElseThrow()));
            } else if (i == 12) { // after gxh5: White mates in 1
                evals.add(line(fen, "e2h5", Eval.whiteMates(1)));
            } else if (i == 11) { // after Be2: Black should not take, White about +1.5
                evals.add(line(fen, "g8h6", Eval.cp(150)));
            } else {
                evals.add(line(fen, i < 12 ? g.uci().get(i) : "a1a1", Eval.cp(100)));
            }
        }
        GameReview r = ReviewClassifier.classifyGame(new ReviewInput(g.initialFen(), g.uci(), evals, OpeningBook.NONE));
        assertEquals(MoveClassification.BLUNDER, r.moves().get(11).label());
        assertEquals(MoveClassification.BEST, r.moves().get(12).label());
        assertTrue(r.blackAccuracy() < r.whiteAccuracy());
    }

    @Test
    void openingBookLabelsTheOpening() {
        OpeningBook book = OpeningBook.standard();
        assertTrue(book.size() > 3000);
        GameReplay g = GameReplay.of(null, "e4 e5 Nf3 Nc6 Bc4");
        assertTrue(book.nameAfter(g.fens().get(5)).orElseThrow().contains("Italian"));
    }

    private static PositionEval line(String fen, String move, Eval e) {
        return new PositionEval(fen, e, List.of(new EngineLine(move, e, List.of(move), 12)), 12, 1000, false);
    }
}
