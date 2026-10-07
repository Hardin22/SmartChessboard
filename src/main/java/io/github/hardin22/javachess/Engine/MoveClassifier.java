package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.FastVerdict;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.WinModel;

/**
 * Move classification for the LED coach: the "fast" verdict of the game review core
 * ({@link ReviewClassifier#fast}), fed with UCI scores from the mover's point of view.
 *
 * <p>Same win chance curve (the review's, for an unknown rating), thresholds and mate rules as the review (no
 * MultiPV, no history, so never Brilliant/Great/Miss/Book): a move that mates is BEST, a move that allows a forced
 * mate the best move avoided is an error, a slower forced mate is still a good move. The review's history rules
 * (lost-position Mistake, Miss) are left out: at LED depths they turned correct moves into errors (LED study).
 * {@link #winProbability} is the evaluation bar's Lichess curve, not the classification's.</p>
 */
public final class MoveClassifier {

    private MoveClassifier() {
    }

    /** Result of a classification. {@code winLoss} in 0..1, {@code cpLoss} &ge; 0 (mates as 10000). */
    public record Classification(MoveQuality quality, double winLoss, int cpLoss) {
    }

    /** Win probability (0..1) of the side to move for a centipawn score (mates as +/-{@link Score#MATE_CP}). */
    public static double winProbability(double cp) {
        if (cp >= Score.MATE_CP) {
            return 1.0;
        }
        if (cp <= -Score.MATE_CP) {
            return 0.0;
        }
        return WinModel.fromCp(cp);
    }

    /**
     * Classifies a move.
     *
     * @param bestBefore   score of the best line in the position before the move, mover's point of view
     * @param playedAfter  score after the played move, mover's point of view (i.e. the negated score of the
     *                     resulting position; {@link Score#mateDelivered()} when the move mates)
     * @param playedIsBest true when the played move is the engine's top move
     */
    public static Classification classify(Score bestBefore, Score playedAfter, boolean playedIsBest) {
        FastVerdict v = ReviewClassifier.fast(moverAsWhite(bestBefore), moverAsWhite(playedAfter), true, playedIsBest);
        int cpLoss = Math.max(0, bestBefore.centipawns() - playedAfter.centipawns());
        return new Classification(v.quality(), v.winLoss(), cpLoss);
    }

    /** A mover-POV score as an {@link Eval} where the mover plays White. */
    static Eval moverAsWhite(Score s) {
        if (s.isCheckmate()) {
            return s.isWinningMate() ? Eval.whiteMates(0) : Eval.blackMates(0);
        }
        return Eval.fromUci(s, true);
    }
}
