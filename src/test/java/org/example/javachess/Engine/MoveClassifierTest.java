package org.example.javachess.Engine;

import org.junit.jupiter.api.Test;

import static org.example.javachess.Engine.MoveQuality.*;
import static org.junit.jupiter.api.Assertions.*;

class MoveClassifierTest {

    static MoveQuality q(Score best, Score played) {
        return MoveClassifier.classify(best, played, false).quality();
    }

    @Test
    void winProbabilityModel() {
        assertEquals(0.5, MoveClassifier.winProbability(0), 1e-9);
        assertEquals(0.640, MoveClassifier.winProbability(100), 1e-3);
        assertEquals(0.909, MoveClassifier.winProbability(400), 1e-3);
        assertEquals(1.0, MoveClassifier.winProbability(10_000));
        assertEquals(0.0, MoveClassifier.winProbability(-10_000));
    }

    @Test
    void thresholdsInEqualPositions() {
        assertEquals(BEST, q(Score.cp(20), Score.cp(19)));       // equivalent move
        assertEquals(GOOD, q(Score.cp(20), Score.cp(0)));        // -2.9%
        assertEquals(INACCURACY, q(Score.cp(20), Score.cp(-40))); // -8.6%
        assertEquals(MISTAKE, q(Score.cp(20), Score.cp(-120)));   // -19.8%
        assertEquals(BLUNDER, q(Score.cp(20), Score.cp(-300)));   // hangs a piece
    }

    @Test
    void engineBestMoveIsAlwaysBest() {
        assertEquals(BEST, MoveClassifier.classify(Score.cp(50), Score.cp(-100), true).quality());
    }

    @Test
    void mates() {
        assertEquals(BLUNDER, q(Score.cp(0), Score.mate(-1)));         // allows mate in 1
        assertEquals(BEST, q(Score.mate(3), Score.mate(5)));           // slower mate still wins
        assertEquals(INACCURACY, q(Score.mate(2), Score.cp(450)));     // misses mate, still winning
        assertEquals(BLUNDER, q(Score.mate(2), Score.cp(0)));          // stalemates / throws the win
        assertEquals(BEST, q(Score.mate(-3), Score.mate(-2)));         // lost anyway
    }

    @Test
    void alreadyLostPositionsAreNotBlunders() {
        assertEquals(INACCURACY, q(Score.cp(-400), Score.cp(-2000))); // loss can never exceed WP(best)
        assertEquals(BLUNDER, q(Score.cp(-200), Score.cp(-900)));
    }

    @Test
    void neverNegativeLoss() {
        MoveClassifier.Classification c = MoveClassifier.classify(Score.cp(10), Score.cp(80), false);
        assertEquals(0.0, c.winLoss());
        assertEquals(BEST, c.quality());
    }
}
