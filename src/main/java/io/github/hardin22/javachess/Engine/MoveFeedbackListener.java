package io.github.hardin22.javachess.Engine;

import java.util.List;

/**
 * Receives move feedback for the board LEDs. Implemented by the hardware layer (rendering is not the
 * engine's job). All callbacks run on the single engine event thread, in order: never block in them.
 * Squares are chesslib names in upper case ("E2").
 */
public interface MoveFeedbackListener {

    /** A piece was lifted: quality of each legal destination of that piece. */
    default void onCandidates(CandidateFeedback feedback) {
    }

    /** Evaluation is disabled: just the legal destinations of the lifted piece. */
    default void onLegalTargets(String fromSquare, List<String> targets) {
    }

    /** A human move was classified (preliminary and/or final, see {@link MoveFeedback#preliminary()}). */
    default void onMoveClassified(MoveFeedback feedback) {
    }

    /** Hints are no longer valid (piece put down, game over): clear candidate LEDs. */
    default void onClear() {
    }
}
