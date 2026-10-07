package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.concurrent.Task;

import org.example.javachess.Services.EngineService;
import java.util.List;

public abstract class AbstractGame {
    protected Board board;
    protected ChessBoardUI chessBoardUI;
    // Removed evaluationLabel
    // Removed move labels
    protected EvalBar evalBar;
    protected boolean gameRunning;
    protected UCIEngine stockfish;
    protected StringBuilder pgn;
    protected boolean saveGame = true;
    protected String initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    protected Task<Void> moveCalculationTask;
    protected UCIEngine.AnalysisUpdateCallback analysisCallback;
    protected java.util.function.Consumer<String> statusCallback;

    public AbstractGame(ChessBoardUI chessBoardUI, EvalBar evalBar) {
        this.board = new Board();
        this.chessBoardUI = chessBoardUI;
        // Removed evaluationLabel assignment
        this.evalBar = evalBar;
        // Removed move labels assignment
        this.stockfish = EngineService.getInstance().getEngine();
        this.pgn = new StringBuilder();
    }

    public void setStatusCallback(java.util.function.Consumer<String> callback) {
        this.statusCallback = callback;
    }

    protected void updateStatus(String message) {
        if (statusCallback != null) {
            Platform.runLater(() -> statusCallback.accept(message));
        }
    }

    public void setAnalysisCallback(UCIEngine.AnalysisUpdateCallback callback) {
        this.analysisCallback = callback;
    }

    public abstract void startGame();

    public abstract void handleMoveInput(String moveInput);

    public abstract void endGame(String endMessage, boolean saveGame);

    protected int analysisDepth = org.example.javachess.Utils.ConfigManager.getIntProperty("game.depth", 18);
    protected int analysisMultiPV = 1;
    protected boolean analysisEnabled = org.example.javachess.Utils.ConfigManager.getBooleanProperty("game.evaluation",
            true);

    protected void evaluatePositionAndMoves() {
        if (!gameRunning || !analysisEnabled) {
            if (stockfish != null)
                stockfish.stopCalculating();
            return;
        }

        if (moveCalculationTask != null && moveCalculationTask.isRunning()) {
            moveCalculationTask.cancel();
        }

        moveCalculationTask = new Task<Void>() {
            @Override
            protected Void call() {
                stockfish.startAnalysis(board.getFen(), analysisDepth, analysisMultiPV, analysisCallback, chessBoardUI,
                        evalBar, showArrows);
                return null;
            }
        };

        new Thread(moveCalculationTask).start();
    }

    public void setAnalysisParams(int depth, int multiPV) {
        this.analysisDepth = depth;
        this.analysisMultiPV = multiPV;
        evaluatePositionAndMoves(); // Restart with new params
    }

    public void setAnalysisEnabled(boolean enabled) {
        this.analysisEnabled = enabled;
        if (enabled) {
            evaluatePositionAndMoves();
        } else {
            if (stockfish != null)
                stockfish.stopCalculating();
            chessBoardUI.clearArrows();
        }
    }

    protected boolean showArrows = org.example.javachess.Utils.ConfigManager.getBooleanProperty("game.suggestions",
            true);

    public void setShowArrows(boolean show) {
        this.showArrows = show;
        if (!show) {
            chessBoardUI.clearArrows();
        }
        // Restart analysis to update arrows? Or just let next update handle it.
        // Stockfish.startAnalysis needs to know about this flag if we want to stop
        // sending arrows.
        // But simpler: just clear them if false, and in Stockfish callback don't draw
        // if false.
    }

    public void clearArrows() {
        chessBoardUI.clearArrows();
    }

    protected void updatePgn(Move move) {
        if (board.getSideToMove() == Side.BLACK) { // White just moved
            pgn.append(board.getMoveCounter()).append(". ").append(move.toString()).append(" ");
        } else {
            pgn.append(move.toString()).append(" ");
        }
    }

    protected String getDrawReason() {
        if (board.isStaleMate())
            return "Stallo";
        if (board.isRepetition())
            return "Triplice ripetizione";
        if (board.isInsufficientMaterial())
            return "Materiale insufficiente";
        if (board.getHalfMoveCounter() >= 100)
            return "Regola delle 50 mosse";
        return "Patta";
    }

    protected Move parseMoveInput(String moveInput) {
        if (moveInput.length() == 4) {
            Square from = Square.valueOf(moveInput.substring(0, 2).toUpperCase());
            Square to = Square.valueOf(moveInput.substring(2, 4).toUpperCase());
            return new Move(from, to);
        } else if (moveInput.length() == 3) {
            char pieceChar = moveInput.charAt(0);
            Square to = Square.valueOf(moveInput.substring(1, 3).toUpperCase());
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            for (Move move : legalMoves) {
                if (move.getTo().equals(to) && board.getPiece(move.getFrom()).equals(parsePiece(pieceChar))) {
                    return move;
                }
            }
        } else if (moveInput.length() == 2) {
            Square to = Square.valueOf(moveInput.toUpperCase());
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            for (Move move : legalMoves) {
                if (move.getTo().equals(to) && board.getPiece(move.getFrom()).equals(parsePiece('P'))) {
                    return move;
                }
            }
        }
        return null;
    }

    protected Piece parsePiece(char pieceChar) {
        switch (Character.toUpperCase(pieceChar)) {
            case 'R':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
            case 'N':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
            case 'B':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
            case 'Q':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
            case 'K':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_KING : Piece.BLACK_KING;
            case 'P':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_PAWN : Piece.BLACK_PAWN;
            default:
                throw new IllegalArgumentException("Pezzo non valido: " + pieceChar);
        }
    }

    protected void saveGameToJson(String result, String openingName, String type, String timeControl) {
        pgn.append(" ").append(result);
        System.out.println("Partita salvata in formato PGN: " + pgn.toString());

        org.example.javachess.Services.GameArchiveService.saveGame(
                type,
                openingName,
                pgn.toString(),
                initialFen,
                board.getFen(),
                result,
                timeControl);
    }

    public Board getBoard() {
        return board;
    }

    public UCIEngine getStockfish() {
        return stockfish;
    }

    // --- LED VISUALIZATION METHODS ---
    protected void notifyOpponentMove(String from, String to) {
        // Check for Check/Mate
        if (board.isMated()) {
            notifyMate();
        } else if (board.isKingAttacked()) {
            notifyCheck();
        }
    }

    protected void notifyCheck() {
        org.example.javachess.Controllers.ArduinoController arduino = org.example.javachess.Controllers.ArduinoController
                .getInstance();
        Square kingSq = board.getKingSquare(board.getSideToMove());
        arduino.sendLedCommand(kingSq.name(), 255, 69, 0); // OrangeRed
    }

    protected void notifyMate() {
        if (!org.example.javachess.Utils.ConfigManager.getBooleanProperty("ui.mate.animation", true)) {
            return;
        }
        org.example.javachess.Controllers.ArduinoController arduino = org.example.javachess.Controllers.ArduinoController
                .getInstance();
        arduino.playVictoryAnimation();

        // Trigger UI Animation
        com.github.bhlangonijr.chesslib.Side winner = board.getSideToMove().flip();
        String winnerText = (winner == com.github.bhlangonijr.chesslib.Side.WHITE ? "IL BIANCO" : "IL NERO") + " VINCE";
        Platform.runLater(() -> chessBoardUI.showVictoryAnimation("SCACCO MATTO", winnerText));
    }

    protected void clearBoardLeds() {
        org.example.javachess.Controllers.ArduinoController.getInstance().clearLeds();
    }
}
