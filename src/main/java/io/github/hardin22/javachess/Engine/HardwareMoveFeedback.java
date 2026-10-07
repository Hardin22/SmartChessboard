package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Hardware.Hardware;

import java.util.List;
import java.util.Locale;

/**
 * Default {@link MoveFeedbackListener}: renders the engine feedback with the hardware layer's
 * {@code MoveLeds} (non-blocking, thread-safe; nothing happens when no board is connected).
 * Installed by {@link MoveCoach#get()} as fallback; a custom listener set with
 * {@link MoveCoach#setFeedbackListener} replaces it.
 */
final class HardwareMoveFeedback implements MoveFeedbackListener {

    @Override
    public void onCandidates(CandidateFeedback fb) {
        var leds = Hardware.moveLeds();
        String from = fb.fromSquare().toLowerCase(Locale.ROOT);
        fb.destinations().forEach((to, q) -> leds.showCandidate(from, to.toLowerCase(Locale.ROOT), q.toClassification()));
    }

    @Override
    public void onLegalTargets(String fromSquare, List<String> targets) {
        Hardware.moveLeds().showLegalTargets(fromSquare.toLowerCase(Locale.ROOT),
                targets.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    public void onMoveClassified(MoveFeedback fb) {
        Hardware.moveLeds().showVerdict(fb.uci(), fb.quality().toClassification(), fb.bestMoveUci());
    }

    @Override
    public void onClear() {
        Hardware.moveLeds().clearCandidates();
    }
}
