package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Engine.MoveQuality;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

/**
 * Result of {@link ReviewClassifier#fast}: the cheap verdict for the board LEDs (never Brilliant, Great, Miss or
 * Book: those need MultiPV and the game history).
 *
 * @param label     BEST, EXCELLENT, GOOD, INACCURACY, MISTAKE or BLUNDER (same thresholds as the review)
 * @param winBefore win chance (0..1) of the mover with the best move
 * @param winAfter  win chance (0..1) of the mover after the played move
 * @param winLoss   {@code max(0, winBefore - winAfter)}
 */
public record FastVerdict(MoveClassification label, double winBefore, double winAfter, double winLoss) {

    /** The coarse LED quality (EXCELLENT counts as GOOD). */
    public MoveQuality quality() {
        return ReviewClassifier.toQuality(label);
    }
}
