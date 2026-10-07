package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import org.example.javachess.Controllers.ArduinoController;
import org.example.javachess.Engine.CandidateFeedback;
import org.example.javachess.Engine.MoveCoach;
import org.example.javachess.Engine.MoveFeedbackListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LED hints when a piece is lifted (entry point used by {@link BoardStateManager}; signatures unchanged).
 *
 * <p>The engine work is done by {@link MoveCoach}: one {@code searchmoves} MultiPV search for all the moves
 * of the lifted piece (instead of one search per move), reusing the hash of the live analysis. The LED
 * rendering belongs to the hardware layer, which registers a {@link MoveFeedbackListener} with
 * {@link MoveCoach#setFeedbackListener}; until it does, the legacy rendering below (direct Arduino batch) is used.
 * The constructor is cheap: no engine process is started here.</p>
 */
public class MoveEvaluatorService {

    private static final int[] SOURCE_COLOR = { 255, 255, 255 };

    public MoveEvaluatorService() {
        MoveCoach.get().setFallbackListener(new LegacyLedRenderer());
    }

    /**
     * Piece lifted from {@code fromSquare}: colours every legal destination by move quality.
     * {@code currentEval} and {@code bestMove} are no longer needed (the live analysis provides them) and are
     * kept only for source compatibility.
     */
    public void startEvaluation(Board board, Square fromSquare, double currentEval, String bestMove) {
        MoveCoach.get().onPieceLifted(board.getFen(), fromSquare.name());
    }

    /** Stops any pending hint search (piece put down). */
    public void stopEvaluation() {
        MoveCoach.get().onPieceReleased();
    }

    /** Evaluation disabled (online game, suggestions off): legal destinations only, in {@code color}. */
    public void highlightLegalMoves(Board board, Square fromSquare, int[] color) {
        LegacyLedRenderer.legalColor = color;
        MoveCoach.get().showLegalTargets(board.getFen(), fromSquare.name());
    }

    /** Pre-hardware-layer rendering: one LED batch per event, no sleeps. */
    static final class LegacyLedRenderer implements MoveFeedbackListener {
        static volatile int[] legalColor = { 0, 100, 255 };

        @Override
        public void onCandidates(CandidateFeedback fb) {
            Map<String, int[]> batch = new HashMap<>();
            batch.put(fb.fromSquare(), SOURCE_COLOR);
            fb.destinations().forEach((to, q) -> batch.put(to, q.defaultRgb()));
            send(batch);
        }

        @Override
        public void onLegalTargets(String fromSquare, List<String> targets) {
            Map<String, int[]> batch = new HashMap<>();
            batch.put(fromSquare, SOURCE_COLOR);
            for (String t : targets) {
                batch.put(t, legalColor);
            }
            send(batch);
        }

        private static void send(Map<String, int[]> batch) {
            ArduinoController arduino = ArduinoController.getInstance();
            arduino.clearLeds();
            arduino.sendLedBatch(batch);
        }
    }
}
