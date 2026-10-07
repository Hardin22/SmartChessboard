package io.github.hardin22.javachess.Engine.review;

/** Progressive results of a review (called from review threads, not the FX thread). */
public interface ReviewListener {

    ReviewListener NONE = new ReviewListener() {
    };

    /** Fraction done (0..1). */
    default void onProgress(double fraction) {
    }

    /** Evaluation of position {@code index} became available. */
    default void onPosition(int index, PositionEval eval) {
    }

    /**
     * Provisional review of the first moves of the game, whose positions are all evaluated (several times during the
     * review, growing). Great and Brilliant come only with the final result; Book can still extend.
     */
    default void onPartial(GameReview partial) {
    }
}
