package io.github.hardin22.javachess.Engine;

import org.junit.jupiter.api.Test;

import static io.github.hardin22.javachess.Engine.MoveQuality.*;
import static org.junit.jupiter.api.Assertions.*;

class MoveClassifierTest {

    static MoveQuality q(Score best, Score played) {
        return MoveClassifier.classify(best, played, false).quality();
    }

    @Test
    void winProbabilityModel() {
        assertEquals(0.5, MoveClassifier.winProbability(0), 1e-9);
        assertEquals(0.591, MoveClassifier.winProbability(100), 1e-3); // Lichess logistic, shared with the review
        assertEquals(0.813, MoveClassifier.winProbability(400), 1e-3);
        assertEquals(1.0, MoveClassifier.winProbability(10_000));
        assertEquals(0.0, MoveClassifier.winProbability(-10_000));
    }

    @Test
    void thresholdsInEqualPositions() {
        assertEquals(GOOD, q(Score.cp(20), Score.cp(19)));       // excellent: shown as good on the LEDs
        assertEquals(BEST, q(Score.cp(20), Score.cp(20)));
        assertEquals(GOOD, q(Score.cp(20), Score.cp(0)));        // -1.8%
        assertEquals(INACCURACY, q(Score.cp(20), Score.cp(-40))); // -5.5%
        assertEquals(MISTAKE, q(Score.cp(20), Score.cp(-120)));   // -12.7%
        assertEquals(BLUNDER, q(Score.cp(20), Score.cp(-300)));   // hangs a piece
    }

    @Test
    void engineBestMoveIsAlwaysBest() {
        assertEquals(BEST, MoveClassifier.classify(Score.cp(50), Score.cp(-100), true).quality());
    }

    @Test
    void mates() {
        assertEquals(BLUNDER, q(Score.cp(0), Score.mate(-1)));         // allows mate in 1
        assertEquals(BEST, q(Score.mate(3), Score.mate(2)));           // keeps the fastest mate
        assertEquals(GOOD, q(Score.mate(3), Score.mate(6)));           // slower mate still wins
        assertEquals(MISTAKE, q(Score.mate(2), Score.cp(450)));        // misses mate, still winning
        assertEquals(BLUNDER, q(Score.mate(2), Score.cp(0)));          // stalemates / throws the win
        assertEquals(BEST, q(Score.mate(-3), Score.mate(-3)));         // lost anyway, best defence
        assertEquals(GOOD, q(Score.mate(-3), Score.mate(-2)));         // lost anyway, mated sooner
    }

    @Test
    void allowingAForcedMateIsNeverGood() {
        // the user's game: 6...gxh5?? 7.Bxh5# with Black at about -1.5
        assertEquals(BLUNDER, q(Score.cp(-150), Score.mate(-1)));
        assertEquals(BLUNDER, q(Score.cp(-600), Score.mate(-4)));
        assertEquals(BLUNDER, q(Score.cp(-1500), Score.mate(-6)));
        assertTrue(q(Score.cp(300), Score.mate(-2)).isError());
    }

    @Test
    void matingMovesAreNeverErrors() {
        assertEquals(BEST, q(Score.mate(1), Score.mateDelivered()));
        assertEquals(BEST, q(Score.cp(300), Score.mateDelivered()));  // shallow best missed the mate
        assertEquals(BEST, q(Score.mate(-1).negate(), Score.mate(0).negate()));
        assertFalse(q(Score.mate(2), Score.mate(4)).isError());
    }

    @Test
    void lostPositionsLoseLittle() {
        assertEquals(MISTAKE, q(Score.cp(-400), Score.cp(-2000)));
        assertEquals(BLUNDER, q(Score.cp(-200), Score.cp(-900)));
        assertEquals(BEST, q(Score.cp(-1500), Score.cp(-2500)));    // both clamped at -10: nothing lost
    }

    @Test
    void neverNegativeLoss() {
        MoveClassifier.Classification c = MoveClassifier.classify(Score.cp(10), Score.cp(80), false);
        assertEquals(0.0, c.winLoss());
        assertEquals(BEST, c.quality());
    }
}
