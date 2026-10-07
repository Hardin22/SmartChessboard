package org.example.javachess.Engine;

/**
 * Pure move classification maths, shared by the LED feedback and the game review.
 *
 * <p>Model: win probability {@code WP = 1 / (1 + 10^(-cp/400))} for the side that moves, mates mapped to
 * +/-10000 cp. The loss of a move is {@code WP(best) - WP(played)}. Thresholds are the chess.com style
 * expected-points ones already used by the review ({@code GameAnalyzer}): inaccuracy &ge; 0.05,
 * mistake &ge; 0.10, blunder &ge; 0.20. A "blunder" in an already lost position (WP(best) &lt; 0.10) is
 * reported as an inaccuracy, like the review does.</p>
 */
public final class MoveClassifier {

    public static final double BEST_MAX_LOSS = 0.005;
    public static final double INACCURACY_LOSS = 0.05;
    public static final double MISTAKE_LOSS = 0.10;
    public static final double BLUNDER_LOSS = 0.20;
    public static final double LOST_POSITION_WP = 0.10;

    private MoveClassifier() {
    }

    /** Result of a classification. {@code winLoss} in 0..1, {@code cpLoss} &ge; 0 (mates as 10000). */
    public record Classification(MoveQuality quality, double winLoss, int cpLoss) {
    }

    /** Win probability (0..1) of the side to move for a centipawn score. */
    public static double winProbability(double cp) {
        if (cp >= Score.MATE_CP) {
            return 1.0;
        }
        if (cp <= -Score.MATE_CP) {
            return 0.0;
        }
        return 1.0 / (1.0 + Math.pow(10, -cp / 400.0));
    }

    /**
     * Classifies a move.
     *
     * @param bestBefore   score of the best line in the position before the move, mover's point of view
     * @param playedAfter  score after the played move, mover's point of view (i.e. the negated score of the
     *                     resulting position)
     * @param playedIsBest true when the played move is the engine's top move
     */
    public static Classification classify(Score bestBefore, Score playedAfter, boolean playedIsBest) {
        double bestWp = bestBefore.winProbability();
        double playedWp = playedAfter.winProbability();
        // A deeper look at the played move can make it look better than the (shallower) best line:
        // never report a negative loss.
        double loss = Math.max(0, bestWp - playedWp);
        int cpLoss = Math.max(0, bestBefore.centipawns() - playedAfter.centipawns());
        if (playedIsBest) {
            return new Classification(MoveQuality.BEST, loss, cpLoss);
        }
        return new Classification(qualityForLoss(loss, bestWp), loss, cpLoss);
    }

    /** Maps a win probability loss (0..1) to a quality, given the WP of the best move. */
    public static MoveQuality qualityForLoss(double loss, double bestWp) {
        MoveQuality q;
        if (loss < BEST_MAX_LOSS) {
            q = MoveQuality.BEST;
        } else if (loss < INACCURACY_LOSS) {
            q = MoveQuality.GOOD;
        } else if (loss < MISTAKE_LOSS) {
            q = MoveQuality.INACCURACY;
        } else if (loss < BLUNDER_LOSS) {
            q = MoveQuality.MISTAKE;
        } else {
            q = MoveQuality.BLUNDER;
        }
        if (q == MoveQuality.BLUNDER && bestWp < LOST_POSITION_WP) {
            q = MoveQuality.INACCURACY;
        }
        return q;
    }
}
