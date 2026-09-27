package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import org.example.javachess.Controllers.ArduinoController;
import org.example.javachess.Oggetti.UCIEngine;
import org.example.javachess.Oggetti.AnalysisResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MoveEvaluatorService {

    private UCIEngine stockfish;
    private AtomicBoolean isEvaluating = new AtomicBoolean(false);
    private Thread evaluationThread;

    public MoveEvaluatorService() {
        this.stockfish = EngineService.getInstance().getEngine();
    }

    public void startEvaluation(Board board, Square fromSquare, double currentEval, String bestMove) {
        // Stop any previous evaluation
        stopEvaluation();

        isEvaluating.set(true);
        evaluationThread = new Thread(() -> {
            try {
                System.out.println("[MoveEvaluator] Starting evaluation for piece at " + fromSquare);

                // 1. Generate Legal Moves
                List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);

                // 2. Filter for the lifted piece
                List<Move> pieceMoves = legalMoves.stream()
                        .filter(m -> m.getFrom() == fromSquare)
                        .toList();

                if (pieceMoves.isEmpty()) {
                    System.out.println("[MoveEvaluator] No legal moves for " + fromSquare);
                    return;
                }

                // 3. Evaluate each move one by one
                for (Move move : pieceMoves) {
                    if (!isEvaluating.get())
                        break;

                    // Apply move to a temp board
                    Board tempBoard = new Board();
                    tempBoard.loadFromFen(board.getFen());
                    tempBoard.doMove(move);

                    double newEval;
                    long duration = 0;
                    int[] color;

                    // CHECK IF THIS IS THE BEST MOVE (from main engine)
                    if (bestMove != null && move.toString().equalsIgnoreCase(bestMove)) {
                        System.out.println("[MoveEvaluator] Move " + move + " is the BEST MOVE. Highlighting CYAN.");
                        color = new int[] { 0, 255, 255 }; // Cyan/Light Blue
                        newEval = currentEval; // Placeholder, not used for color
                    } else if (tempBoard.isMated()) {
                        // This move is checkmate!
                        newEval = (board.getSideToMove() == Side.WHITE) ? 1000.0 : -1000.0;
                        System.out.println("[MoveEvaluator] Move " + move + " is CHECKMATE!");
                        color = getColorForMove(newEval - currentEval, currentEval, newEval); // Standard logic
                    } else if (tempBoard.isDraw()) {
                        newEval = 0.0;
                        color = getColorForMove(newEval - currentEval, currentEval, newEval);
                    } else {
                        // Evaluate with Stockfish
                        long startTime = System.currentTimeMillis();
                        int depth = org.example.javachess.Utils.ConfigManager.getIntProperty("move.eval.depth", 8);
                        AnalysisResult result = stockfish.getEvaluation(tempBoard.getFen(), depth);
                        duration = System.currentTimeMillis() - startTime;
                        newEval = result.score;

                        // Calculate Delta based on Side to Move
                        double delta;
                        double currentScoreSide;
                        double newScoreSide;

                        if (board.getSideToMove() == Side.WHITE) {
                            currentScoreSide = currentEval;
                            newScoreSide = newEval;
                            delta = newEval - currentEval;
                        } else {
                            currentScoreSide = -currentEval;
                            newScoreSide = -newEval;
                            delta = currentEval - newEval;
                        }

                        System.out.println(String.format(
                                "[MoveEvaluator] Move %s: Current=%.2f, New(Abs)=%.2f, Delta=%.2f (Time: %dms)",
                                move.toString(), currentEval, newEval, delta, duration));

                        // Determine Color using Smart Logic
                        color = getColorForMove(delta, currentScoreSide, newScoreSide);
                    }

                    // Send LED Command (Immediate)
                    ArduinoController.getInstance().sendLedCommand(move.getTo().name(), color[0], color[1], color[2]);

                    // DELAY for Reliability (Crucial!)
                    // Mimics the batch strategy of Setup Mode to prevent Serial Buffer Overflow
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        evaluationThread.start();
    }

    public void stopEvaluation() {
        isEvaluating.set(false);
        if (evaluationThread != null && evaluationThread.isAlive()) {
            evaluationThread.interrupt();
        }
    }

    public void highlightLegalMoves(Board board, Square fromSquare, int[] color) {
        // Stop any previous evaluation
        stopEvaluation();

        isEvaluating.set(true);
        evaluationThread = new Thread(() -> {
            try {
                System.out.println("[MoveEvaluator] Highlighting legal moves for " + fromSquare);

                List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
                List<Move> pieceMoves = legalMoves.stream()
                        .filter(m -> m.getFrom() == fromSquare)
                        .toList();

                if (pieceMoves.isEmpty())
                    return;

                // ROBUST BATCH STRATEGY (Mimicking Setup Mode)
                // 1. Clear LEDs to ensure clean state
                ArduinoController.getInstance().clearLeds();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                }

                // 2. Prepare Batch (Source + Moves)
                java.util.Map<String, int[]> batch = new java.util.HashMap<>();

                // Add Source Square (White) - Essential since we cleared LEDs
                batch.put(fromSquare.name(), new int[] { 255, 255, 255 });

                // Add Legal Moves
                for (Move move : pieceMoves) {
                    if (!isEvaluating.get())
                        break;
                    batch.put(move.getTo().name(), color);
                }

                // 3. Send Batch
                if (!batch.isEmpty() && isEvaluating.get()) {
                    System.out.println("[MoveEvaluator] Sending Batch of " + batch.size() + " items (Source + "
                            + pieceMoves.size() + " moves).");
                    ArduinoController.getInstance().sendLedBatch(batch);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        evaluationThread.start();
    }

    private int[] getColorForMove(double delta, double currentScoreSide, double newScoreSide) {
        // Smart Logic copied from GameAnalyzer
        // Green: Best/Excellent/Good
        // Yellow: Inaccuracy
        // Orange: Mistake
        // Red: Blunder

        // 1. Handle Winning Positions (Context-Aware) - Lenient
        if (currentScoreSide > 5.0) {
            if (newScoreSide > 3.5)
                return new int[] { 0, 255, 0 }; // Green (Still Winning)
            if (newScoreSide > 1.0)
                return new int[] { 255, 255, 0 }; // Yellow (Inaccuracy but winning)
            return new int[] { 255, 0, 0 }; // Red (Blunder, threw away win)
        }

        // 2. Handle Losing Positions (Context-Aware) - Lenient
        if (currentScoreSide < -5.0) {
            // Already lost, if we stay lost it's not a blunder, just "expected"
            // But if we miss a chance to un-lose...
            // For simplicity, if we are lost, small deltas don't matter much.
            if (delta >= -0.5)
                return new int[] { 0, 255, 0 }; // Green (Best we can do)
        }

        // 3. Standard CPL Thresholds (Delta is usually negative for bad moves)
        // CPL is Positive Loss. Delta is Negative Gain.
        // So CPL = -Delta.

        double cpl = -delta;
        if (cpl < 0)
            cpl = 0; // Improvement (should be green)

        if (cpl <= 0.05)
            return new int[] { 0, 255, 0 }; // Best -> Green
        if (cpl <= 0.20)
            return new int[] { 0, 255, 0 }; // Excellent -> Green
        if (cpl <= 0.50)
            return new int[] { 0, 255, 0 }; // Good -> Green
        if (cpl <= 0.90)
            return new int[] { 255, 255, 0 }; // Inaccuracy -> Yellow
        if (cpl <= 2.00)
            return new int[] { 255, 60, 0 }; // Mistake -> Orange
        return new int[] { 255, 0, 0 }; // Blunder -> Red
    }
}
