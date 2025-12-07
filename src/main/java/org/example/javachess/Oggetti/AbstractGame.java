package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import org.example.javachess.Services.StockfishService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public abstract class AbstractGame {
    protected Board board;
    protected ChessBoardUI chessBoardUI;
    protected Label evaluationLabel;
    protected Label move1Label;
    protected Label move2Label;
    protected Label move3Label;
    protected EvalBar evalBar;
    protected boolean gameRunning;
    protected Stockfish stockfish;
    protected StringBuilder pgn;
    protected int gameId;
    protected Path archivePath;
    protected boolean saveGame = true;
    protected Task<Void> moveCalculationTask;

    public AbstractGame(ChessBoardUI chessBoardUI, Label evaluationLabel, EvalBar evalBar, Label move1Label, Label move2Label, Label move3Label) {
        this.board = new Board();
        this.chessBoardUI = chessBoardUI;
        this.evaluationLabel = evaluationLabel;
        this.evalBar = evalBar;
        this.move1Label = move1Label;
        this.move2Label = move2Label;
        this.move3Label = move3Label;
        this.stockfish = StockfishService.getInstance();
        this.pgn = new StringBuilder();
        this.archivePath = copyArchiveJsonToWritableLocation();
        this.gameId = getNextGameId();
    }

    public abstract void startGame();

    public abstract void handleMoveInput(String moveInput);

    public abstract void endGame(String endMessage, boolean saveGame);

    protected Path copyArchiveJsonToWritableLocation() {
        Path targetPath = Paths.get("archive.json");
        if (!Files.exists(targetPath)) {
            try (InputStream resourceStream = getClass().getResourceAsStream("/archive.json")) {
                if (resourceStream == null) {
                    throw new IllegalArgumentException("archive.json not found in resources");
                }
                Files.copy(resourceStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return targetPath;
    }

    protected int getNextGameId() {
        int nextId = 1;
        try {
            if (Files.exists(archivePath)) {
                String content = new String(Files.readAllBytes(archivePath));
                JSONArray gamesArray = new JSONArray(content);
                for (int i = 0; i < gamesArray.length(); i++) {
                    JSONObject game = gamesArray.getJSONObject(i);
                    int id = game.getInt("id");
                    if (id >= nextId) {
                        nextId = id + 1;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return nextId;
    }

    protected int analysisDepth = 18;
    protected int analysisMultiPV = 1;
    protected boolean analysisEnabled = true;

    protected void evaluatePositionAndMoves() {
        if (!gameRunning || !analysisEnabled) {
             if (stockfish != null) stockfish.stopCalculating();
             return;
        }

        if (moveCalculationTask != null && moveCalculationTask.isRunning()) {
            moveCalculationTask.cancel();
        }

        moveCalculationTask = new Task<Void>() {
            @Override
            protected Void call() {
                stockfish.startAnalysis(board.getFen(), analysisDepth, analysisMultiPV, move1Label, move2Label, move3Label, chessBoardUI, evaluationLabel, evalBar, showArrows);
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
            if (stockfish != null) stockfish.stopCalculating();
            chessBoardUI.clearArrows();
        }
    }
    
    protected boolean showArrows = true;
    
    public void setShowArrows(boolean show) {
        this.showArrows = show;
        if (!show) {
            chessBoardUI.clearArrows();
        }
        // Restart analysis to update arrows? Or just let next update handle it.
        // Stockfish.startAnalysis needs to know about this flag if we want to stop sending arrows.
        // But simpler: just clear them if false, and in Stockfish callback don't draw if false.
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
        if (board.isStaleMate()) return "Stallo";
        if (board.isRepetition()) return "Triplice ripetizione";
        if (board.isInsufficientMaterial()) return "Materiale insufficiente";
        if (board.getHalfMoveCounter() >= 100) return "Regola delle 50 mosse";
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
            case 'R': return board.getSideToMove() == Side.WHITE ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
            case 'N': return board.getSideToMove() == Side.WHITE ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
            case 'B': return board.getSideToMove() == Side.WHITE ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
            case 'Q': return board.getSideToMove() == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
            case 'K': return board.getSideToMove() == Side.WHITE ? Piece.WHITE_KING : Piece.BLACK_KING;
            case 'P': return board.getSideToMove() == Side.WHITE ? Piece.WHITE_PAWN : Piece.BLACK_PAWN;
            default: throw new IllegalArgumentException("Pezzo non valido: " + pieceChar);
        }
    }

    protected void saveGameToJson(String result, String openingName, String type, String timeControl) {
        pgn.append(" ").append(result);
        System.out.println("Partita salvata in formato PGN: " + pgn.toString());

        try {
            JSONObject gameJson = new JSONObject();
            gameJson.put("id", gameId);
            gameJson.put("type", type);
            gameJson.put("opening", openingName);
            gameJson.put("pgn", pgn.toString());
            gameJson.put("fen", board.getFen());
            gameJson.put("result", result);
            gameJson.put("time", timeControl);

            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
            gameJson.put("datetime", now.format(formatter));

            JSONArray gamesArray;
            if (Files.exists(archivePath)) {
                String content = new String(Files.readAllBytes(archivePath));
                gamesArray = new JSONArray(content);
            } else {
                gamesArray = new JSONArray();
            }

            gamesArray.put(gameJson);
            Files.write(archivePath, gamesArray.toString(4).getBytes());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Board getBoard() {
        return board;
    }

    public Stockfish getStockfish() {
        return stockfish;
    }
}
