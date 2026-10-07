package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import org.example.javachess.Engine.MoveCoach;

/**
 * LED hints when a piece is lifted (entry point used by {@link BoardStateManager}; signatures unchanged).
 *
 * <p>The engine work is done by {@link MoveCoach}: one {@code searchmoves} MultiPV search for all the moves
 * of the lifted piece (instead of one search per move), reusing the hash of the live analysis. The result is
 * rendered on the board by the coach's feedback listener (by default the hardware layer's {@code MoveLeds}).
 * The constructor is cheap: no engine process is started here.</p>
 */
public class MoveEvaluatorService {

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

    /** Evaluation disabled (online game, suggestions off): legal destinations only ({@code color} is ignored). */
    public void highlightLegalMoves(Board board, Square fromSquare, int[] color) {
        MoveCoach.get().showLegalTargets(board.getFen(), fromSquare.name());
    }
}
