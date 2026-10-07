package org.example.javachess.Hardware;

import org.example.javachess.Engine.CandidateFeedback;
import org.example.javachess.Engine.MoveCoach;
import org.example.javachess.Engine.MoveFeedback;
import org.example.javachess.Engine.MoveFeedbackListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Bridge between the engine's move coach ({@link MoveCoach}, which classifies moves) and the board LEDs
 * ({@link MoveLeds}, which draws them): candidate destinations when a piece is lifted, the verdict after a move.
 * Callbacks arrive on the engine event thread; {@link MoveLeds} only updates LED layers, so nothing blocks.
 */
public final class CoachLeds implements MoveFeedbackListener {

    private static final Logger log = LoggerFactory.getLogger(CoachLeds.class);
    private static volatile boolean installed;

    private final MoveLeds leds;

    CoachLeds(MoveLeds leds) {
        this.leds = leds;
    }

    /** Registers the bridge once (call after {@link Hardware#get()}). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        MoveCoach.get().setFeedbackListener(new CoachLeds(Hardware.moveLeds()));
        installed = true;
        log.debug("Move coach connected to the board LEDs");
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
