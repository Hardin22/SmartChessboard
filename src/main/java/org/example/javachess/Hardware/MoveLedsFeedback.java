package org.example.javachess.Hardware;

import org.example.javachess.Engine.CandidateFeedback;
import org.example.javachess.Engine.MoveFeedback;
import org.example.javachess.Engine.MoveFeedbackListener;

import java.util.List;

/**
 * Shows the engine's move feedback on the board LEDs (registered with {@code MoveCoach} by {@link Hardware}).
 * Called on the engine event thread; {@link MoveLeds} never blocks, so nothing here does either.
 */
final class MoveLedsFeedback implements MoveFeedbackListener {

    private final MoveLeds leds;

    MoveLedsFeedback(MoveLeds leds) {
        this.leds = leds;
    }

    @Override
    public void onCandidates(CandidateFeedback feedback) {
        feedback.destinations().forEach((to, quality) ->
                leds.showCandidate(feedback.fromSquare(), to, quality.toClassification()));
    }

    @Override
    public void onLegalTargets(String fromSquare, List<String> targets) {
        leds.showLegalTargets(fromSquare, targets);
    }

    @Override
    public void onMoveClassified(MoveFeedback feedback) {
        leds.showVerdict(feedback.uci(), feedback.quality().toClassification(), feedback.bestMoveUci());
    }

    @Override
    public void onClear() {
        leds.clearCandidates();
    }
}
