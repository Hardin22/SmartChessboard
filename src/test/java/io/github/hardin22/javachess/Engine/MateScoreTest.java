package io.github.hardin22.javachess.Engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mate scores and their point of view. Regression for the eval bar turning fully black when White delivers
 * checkmate: {@code mate 0} (side to move is mated) used to lose its sign when negated.
 */
class MateScoreTest {

    /** White just mated Black (scholar's mate): Black to move, no legal moves. */
    static final String BLACK_MATED = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4";
    /** Black just mated White (fool's mate): White to move, no legal moves. */
    static final String WHITE_MATED = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";

    @Test
    void mateZeroIsALossForTheSideToMoveAndAWinForTheOther() {
        Score mated = Score.mate(0);
        assertTrue(mated.isLosingMate());
        assertFalse(mated.isWinningMate());
        assertTrue(mated.isCheckmate());
        Score won = mated.negate();
        assertTrue(won.isWinningMate());
        assertFalse(won.isLosingMate());
        assertTrue(won.isCheckmate());
        assertEquals(Score.mateDelivered(), won);
        assertNotEquals(mated, won);
        assertEquals(mated, won.negate());
        assertEquals(Score.MATE_CP, won.centipawns());
        assertEquals(-Score.MATE_CP, mated.centipawns());
        assertEquals(1.0, won.winProbability());
        assertEquals(0.0, mated.winProbability());
        assertEquals(1000.0, won.legacyPawns());
        assertEquals(-1000.0, mated.legacyPawns());
        assertEquals("#", won.format());
        assertEquals("-#", mated.format());
    }

    @Test
    void ordinaryScoresNegateAsBefore() {
        assertEquals(Score.mate(-3), Score.mate(3).negate());
        assertEquals(Score.cp(-40), Score.cp(40).negate());
        assertEquals(Score.cp(0), Score.cp(0).negate());
        assertFalse(new Score(true, 2, true).delivered(), "delivered only applies to mate 0");
        assertFalse(new Score(false, 0, true).delivered());
    }

    @Test
    void whiteDeliversMateBarIsFullyWhite() {
        AnalysisUpdate u = terminal(BLACK_MATED);
        assertEquals(1000.0, u.whitePawns(), "White POV: White has mated");
        assertTrue(u.whiteScore(0).isWinningMate());
        assertEquals("1-0", u.evalText(0));
    }

    @Test
    void blackDeliversMateBarIsFullyBlack() {
        AnalysisUpdate u = terminal(WHITE_MATED);
        assertEquals(-1000.0, u.whitePawns(), "White POV: White is mated");
        assertTrue(u.whiteScore(0).isLosingMate());
        assertEquals("0-1", u.evalText(0));
    }

    @Test
    void stalemateIsHalfAndHalf() {
        AnalysisUpdate u = new AnalysisUpdate("k7/8/1Q6/8/8/8/8/2K5 b - - 0 1", 0, List.of(), true, Score.cp(0), 0, 0);
        assertEquals(0.0, u.whitePawns());
        assertEquals("½-½", u.evalText(0));
    }

    @Test
    void mateInNKeepsItsSideWhicheverColourIsToMove() {
        InfoLine whiteMatesIn2 = new InfoLine(10, 10, 1, Score.mate(2), InfoLine.Bound.EXACT, 0, 0, 0, List.of("d1h5"));
        AnalysisUpdate w = new AnalysisUpdate("4k3/8/8/8/8/8/8/4K2Q w - - 0 1", 10, List.of(whiteMatesIn2), true,
                null, 0, 0);
        assertEquals(998.0, w.whitePawns());
        assertEquals("M2", w.evalText(0));
        // Black to move and Black gets mated in 2: UCI says "mate -2" for the side to move.
        InfoLine blackMatedIn2 = new InfoLine(10, 10, 1, Score.mate(-2), InfoLine.Bound.EXACT, 0, 0, 0, List.of("e8d8"));
        AnalysisUpdate b = new AnalysisUpdate("4k3/8/8/8/8/8/8/4K2Q b - - 0 1", 10, List.of(blackMatedIn2), true,
                null, 0, 0);
        assertEquals(998.0, b.whitePawns());
        assertEquals("M2", b.evalText(0));
        // Black to move and Black mates in 1.
        InfoLine blackMatesIn1 = new InfoLine(10, 10, 1, Score.mate(1), InfoLine.Bound.EXACT, 0, 0, 0, List.of("h4e1"));
        AnalysisUpdate c = new AnalysisUpdate("4k3/8/8/8/7q/8/8/4K3 b - - 0 1", 10, List.of(blackMatesIn1), true,
                null, 0, 0);
        assertEquals(-999.0, c.whitePawns());
        assertEquals("-M1", c.evalText(0));
    }

    private static AnalysisUpdate terminal(String fen) {
        // what PositionAnalyzer publishes for a position without legal moves while in check
        return new AnalysisUpdate(fen, 0, List.of(), true, Score.mate(0), 0, 0);
    }
}
