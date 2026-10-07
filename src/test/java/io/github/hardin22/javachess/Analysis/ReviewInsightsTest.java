package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Analysis.ReviewFixtures.Spec;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewInsightsTest {

    static final List<String> MOVES = List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "f3g5", "d7d5");

    @Test
    void moveInsightOfABadMove() {
        List<Spec> specs = new ArrayList<>(List.of(Spec.book(), Spec.book(), Spec.book(), Spec.book(), Spec.good(),
                Spec.bad(MoveClassification.MISTAKE, "f8c5", 0.5, 0.3, 40)));
        GameReview r = ReviewFixtures.review(null, MOVES, specs);
        ReviewInsights.MoveInsight m = ReviewInsights.move(r, 5);
        assertEquals("3… Cf6", m.moveText());
        assertEquals("3… Ac5", m.bestText());
        assertEquals("3… Ac5", m.bestLineText());
        assertTrue(m.showBest());
        assertFalse(m.white());
        assertEquals(0.2, m.winLoss(), 1e-9);
        assertEquals("+1.00", m.evalAfter());
        assertEquals("−0.50", m.evalBefore());

        ReviewInsights.MoveInsight book = ReviewInsights.move(r, 0);
        assertTrue(book.book());
        assertFalse(book.showBest());
        assertEquals("", book.bestText(), "best = played: nothing to show");
        assertNull(ReviewInsights.move(r, 99));
        assertNull(ReviewInsights.move(null, 0));
    }

    @Test
    void keyMomentsInOrderWithSwing() {
        List<Spec> specs = List.of(Spec.good(), Spec.good(), Spec.good(),
                Spec.bad(MoveClassification.BLUNDER, "g8f6", 0.55, 0.1, 10),
                new Spec(MoveClassification.GREAT, null, 0.9, 0.9, 100),
                Spec.bad(MoveClassification.INACCURACY, "f8c5", 0.2, 0.15, 70),
                Spec.bad(MoveClassification.MISS, "f3e5", 0.8, 0.5, 30));
        GameReview r = ReviewFixtures.review(null, MOVES, specs);
        List<ReviewInsights.KeyMoment> all = ReviewInsights.keyMoments(r, null);
        assertEquals(3, all.size(), "inaccuracies are not key moments");
        assertEquals(3, all.get(0).ply());
        assertEquals(ReviewInsights.MomentKind.ERROR, all.get(0).kind());
        assertEquals("−45%", all.get(0).swingText());
        assertEquals("2… Cf6", all.get(0).bestText());
        assertEquals(ReviewInsights.MomentKind.GREAT_MOVE, all.get(1).kind());
        assertEquals("", all.get(1).swingText());
        assertEquals(MoveClassification.MISS, all.get(2).label());
        assertEquals(2, ReviewInsights.keyMoments(r, true).size());
        assertEquals(1, ReviewInsights.keyMoments(r, false).size());
        assertTrue(ReviewInsights.keyMoments(null, null).isEmpty());
    }

    @Test
    void phasesOfAGameReachingTheEndgame() {
        // A short game in a simplified position: from a FEN with few pieces it is an endgame from the first move
        String endgame = "4k3/pp3ppp/8/8/8/8/PP3PPP/R3K2R w KQ - 0 30";
        GameReview r = ReviewFixtures.review(endgame, List.of("a1d1", "e8e7", "d1d4"), List.of(
                Spec.good(), Spec.bad(MoveClassification.MISTAKE, "a7a6", 0.4, 0.2, 50), Spec.good()));
        ReviewInsights.PhaseSummary s = ReviewInsights.phases(r);
        assertEquals(0, s.endgameStart());
        assertEquals(ReviewInsights.Phase.ENDGAME, s.phaseOf(1));
        assertEquals(1, s.white().size());
        assertEquals(ReviewInsights.Phase.ENDGAME, s.white().get(0).phase());
        assertEquals(100.0, s.white().get(0).accuracy());
        assertEquals(ReviewInsights.Grade.EXCELLENT, s.white().get(0).grade());
        assertEquals(1, s.black().get(0).mistakes());
        assertEquals("50,0", s.black().get(0).accuracyText());
        assertEquals(ReviewInsights.Grade.POOR, s.black().get(0).grade());
    }

    @Test
    void openingOnlyGameAndBookMovesExcluded() {
        GameReview r = ReviewFixtures.review(null, MOVES.subList(0, 4), List.of(Spec.book(), Spec.book(),
                Spec.good(), Spec.bad(MoveClassification.INACCURACY, "g8f6", 0.5, 0.45, 80)));
        ReviewInsights.PhaseSummary s = ReviewInsights.phases(r);
        assertEquals(-1, s.middlegameStart());
        assertEquals(-1, s.endgameStart());
        assertEquals(1, s.white().size());
        assertEquals(1, s.white().get(0).moves(), "the book move is not counted");
        assertEquals(1, s.black().get(0).inaccuracies());
        assertEquals(ReviewInsights.Grade.GOOD, s.black().get(0).grade());
    }

    @Test
    void phaseRules() {
        Board start = new Board();
        assertEquals(14, ReviewInsights.majorsAndMinors(start));
        assertFalse(ReviewInsights.isMiddlegame(start, 0));
        assertTrue(ReviewInsights.isMiddlegame(start, 40), "past move 20");
        Board castledAndDeveloped = new Board();
        castledAndDeveloped.loadFromFen("r4rk1/pppq1ppp/2npbn2/2b1p3/2B1P3/2NPBN2/PPPQ1PPP/R4RK1 w - - 0 9");
        assertTrue(ReviewInsights.isMiddlegame(castledAndDeveloped, 12), "back ranks thinned out");
        Board ending = new Board();
        ending.loadFromFen("4k3/pp3ppp/2n5/8/8/2N5/PP3PPP/R3K2R w KQ - 0 30");
        assertTrue(ReviewInsights.isEndgame(ending));
    }

    @Test
    void evalTexts() {
        assertEquals("M2", ReviewInsights.evalText(Eval.whiteMates(2)));
        assertEquals("−M3", ReviewInsights.evalText(Eval.blackMates(3)));
        assertEquals("1-0", ReviewInsights.evalText(Eval.whiteMates(0)));
        assertEquals("", ReviewInsights.evalText(null));
    }
}
