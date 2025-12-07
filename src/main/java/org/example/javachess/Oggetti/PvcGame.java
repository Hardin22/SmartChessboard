package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import org.example.javachess.Utils.ConfigManager;

public class PvcGame extends AbstractGame {

    private Label openingPvc;
    private Stockfish playerStockfish;
    private boolean isPlayerWhite;
    private int skillLevel;

    public PvcGame(ChessBoardUI chessBoardUI, Label evaluationLabel, EvalBar evalBar, Label move1Label, Label move2Label, Label move3Label, Label openingPvc, boolean isPlayerWhite, int skillLevel) {
        super(chessBoardUI, evaluationLabel, evalBar, move1Label, move2Label, move3Label);
        this.openingPvc = openingPvc;
        this.isPlayerWhite = isPlayerWhite;
        this.skillLevel = skillLevel;
        
        String stockfishPath = ConfigManager.getProperty("stockfish.path", "/opt/homebrew/bin/stockfish");
        this.playerStockfish = new Stockfish(stockfishPath);
        this.playerStockfish.setSkillLevel(skillLevel);
    }

    @Override
    public void startGame() {
        gameRunning = true;
        move1Label.setText("");
        move2Label.setText("");
        move3Label.setText("");

        Platform.runLater(() -> chessBoardUI.setPosition(board.getFen(), null));
        evaluatePositionAndMoves();

        if (!isPlayerWhite) {
            handleComputerMove();
        }
    }

    public boolean isSaveGame() {
        return saveGame;
    }

    String OpeningName = "";

    @Override
    public void handleMoveInput(String moveInput) {
        if (!gameRunning) return;

        try {
            Move move = parseMoveInput(moveInput);

            if (move != null && MoveGenerator.generateLegalMoves(board).contains(move)) {
                board.doMove(move);
                updatePgn(move);

                Platform.runLater(() -> chessBoardUI.setPosition(board.getFen(), move));
                OpeningName = stockfish.getOpeningName(board.getFen());
                if (!OpeningName.equals("Unknown Opening") && !OpeningName.equals("Error in API Call")) {
                    openingPvc.setText(OpeningName);
                }

                if (board.isMated()) {
                    String winner = board.getSideToMove().flip() == Side.WHITE ? "Bianco" : "Nero";
                    endGameWithMessage("Scaccomatto! Vince il " + winner + ".");
                } else if (board.isDraw()) {
                    String drawReason = getDrawReason();
                    endGameWithMessage("Partita patta per " + drawReason + ".");
                } else {
                    handleComputerMove();
                }
            } else {
                System.out.println("Mossa illegale o non valida, riprova.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleComputerMove() {
        if (!gameRunning) return;

        Task<Void> computerMoveTask = new Task<Void>() {
            @Override
            protected Void call() {
                String bestMoveUci = playerStockfish.getBestMove(board.getFen());
                if (bestMoveUci != null) {
                    Move bestMove = parseMoveUci(bestMoveUci);

                    if (isPromotionMove(bestMove)) {
                        Side side = board.getSideToMove();
                        Piece promotionPiece = side == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
                        bestMove = new Move(bestMove.getFrom(), bestMove.getTo(), promotionPiece);
                    }

                    final Move finalBestMove = bestMove;
                    board.doMove(finalBestMove);
                    updatePgn(finalBestMove);
                    
                    Platform.runLater(() -> {
                        chessBoardUI.setPosition(board.getFen(), finalBestMove);
                        OpeningName = stockfish.getOpeningName(board.getFen());
                        if (!OpeningName.equals("Unknown Opening") && !OpeningName.equals("Error in API Call")) {
                            openingPvc.setText(OpeningName);
                        }
                        if (board.isMated()) {
                            String winner = board.getSideToMove().flip() == Side.WHITE ? "Bianco" : "Nero";
                            endGameWithMessage("Scaccomatto! Vince il " + winner + ".");
                        } else if (board.isDraw()) {
                            String drawReason = getDrawReason();
                            endGameWithMessage("Partita patta per " + drawReason + ".");
                        } else {
                            evaluatePositionAndMoves();
                        }
                    });
                }
                return null;
            }
        };

        new Thread(computerMoveTask).start();
    }

    private boolean isPromotionMove(Move move) {
        Piece piece = board.getPiece(move.getFrom());
        return piece.getPieceType() == PieceType.PAWN &&
                (move.getTo().getRank() == Rank.RANK_8 || move.getTo().getRank() == Rank.RANK_1);
    }

    private void endGameWithMessage(String message) {
        System.out.println(message);
        evaluationLabel.setText(message);
        move1Label.setText("");
        move2Label.setText("");
        move3Label.setText("");
        
        saveGameToJson(message, openingPvc.getText(), "Player vs Stockfish livello " + skillLevel, "∞");
        
        saveGame = false;
        endGame(false);
    }

    public void endGame(boolean saveGame) {
        gameRunning = false;

        if (pgn.length() < 10){
            saveGame = false;
            System.out.println("Partita non salvata, mossa minima non raggiunta.");
        }
        
        // Note: stockfish (analysis) is managed by AbstractGame/StockfishService, 
        // but playerStockfish is local to this game instance.
        if (playerStockfish != null) {
            playerStockfish.close();
            playerStockfish = null;
            System.out.println("Stockfish (giocatore) chiuso.");
        }

        if (moveCalculationTask != null && moveCalculationTask.isRunning()) {
            moveCalculationTask.cancel();
        }
        
        if (saveGame) {
             saveGameToJson("Partita interrotta.", openingPvc.getText(), "Player vs Stockfish livello " + skillLevel, "∞");
        }
        openingPvc.setText("");
    }

    @Override
    public void endGame(String endMessage, boolean saveGame) {
        // This method is required by AbstractGame but PvcGame has its own endGame logic
        // We can redirect to the boolean version or just implement the basics
        endGameWithMessage(endMessage);
    }

    private Move parseMoveUci(String uciMove) {
        Square from = Square.valueOf(uciMove.substring(0, 2).toUpperCase());
        Square to = Square.valueOf(uciMove.substring(2, 4).toUpperCase());
        return new Move(from, to);
    }
}
