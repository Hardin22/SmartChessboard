package io.github.hardin22.javachess.Engine.review;

/** Progressive results of a review (called from review threads, not the FX thread). */
public interface ReviewListener {

    ReviewListener NONE = new ReviewListener() {
    };

    /** Fraction done (0..1). */
    default void onProgress(double fraction) {
    }

    /**
     * Evaluation of position {@code index} became available (moves are labelled at the end, once the whole game
     * is known: labels such as Miss and Book depend on the previous moves).
     */
    default void onPosition(int index, PositionEval eval) {
    }
}
