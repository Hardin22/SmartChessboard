package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.scene.control.Label;

public class PvpGame extends AbstractGame {

    private Label openingNameLabel;
    private ChessTimer chessTimer;
    private int increment;
    private boolean isWhiteTurn;
    private int gameDuration;

    public PvpGame(ChessBoardUI chessBoardUI, Label evaluationLabel, EvalBar evalBar, Label move1Label, Label move2Label, Label move3Label, Label openingNameLabel, Label whiteLabel, Label blackLabel, int gameDuration, int increment) {
        super(chessBoardUI, evaluationLabel, evalBar, move1Label, move2Label, move3Label);
        this.gameDuration = gameDuration;
        this.increment = increment;
        this.openingNameLabel = openingNameLabel;
        this.chessTimer = new ChessTimer(whiteLabel, blackLabel, gameDuration, increment, this);
    }

    @Override
    public void startGame() {
        gameRunning = true;
        isWhiteTurn = true;
        chessTimer.initializetimer();
        move1Label.setText("");
        move2Label.setText("");
        move3Label.setText("");
        Platform.runLater(() -> chessTimer.startWhiteTimer());
        pgn.setLength(0);
        evaluatePositionAndMoves();
    }

    public String getEvaluationLabel(){
        return evaluationLabel.getText();
    }

    String openingName = "";

    @Override
    public void handleMoveInput(String moveInput) {
        if (!gameRunning) return;

        try {
            Move move = parseMoveInput(moveInput);

            if (move != null && MoveGenerator.generateLegalMoves(board).contains(move)) {
                board.doMove(move);
                updatePgn(move);

                Platform.runLater(() -> {
                    chessBoardUI.setPosition(board.getFen(), move);

                    openingName = stockfish.getOpeningName(board.getFen());
                    if (!openingName.equals("Unknown Opening") && !openingName.equals("Error in API Call")) {
                        openingNameLabel.setText(openingName);
                    }

                    if (board.isMated()) {
                        saveGame=false;
                        String winner = board.getSideToMove().flip() == Side.WHITE ? "Bianco" : "Nero";
                        endGame("Scaccomatto! Vince il " + winner + ".", true);
                    } else if (board.isDraw() || board.getHalfMoveCounter() >= 100) {
                        saveGame=false;
                        String drawReason = getDrawReason();
                        evaluationLabel.setText("0.00");
                        evalBar.updateEvaluation(0.00);
                        endGame(drawReason, true);
                    } else {
                        if (isWhiteTurn) {
                            chessTimer.addIncrementToWhite();
                            chessTimer.stopWhiteTimer();
                            chessTimer.startBlackTimer();
                        } else {
                            chessTimer.addIncrementToBlack();
                            chessTimer.stopBlackTimer();
                            chessTimer.startWhiteTimer();
                        }

                        isWhiteTurn = !isWhiteTurn;
                        evaluatePositionAndMoves();
                    }
                });
            } else {
                System.out.println("Mossa illegale o non valida, riprova.");
            }
        } catch (Exception e) {
           e.printStackTrace();
        }
    }

    public boolean isSaveGame() {
        return saveGame;
    }

    @Override
    public void endGame(String endMessage, boolean saveGame) {
        gameRunning = false;
        chessTimer.stopWhiteTimer();
        chessTimer.stopBlackTimer();

        if (pgn.length() < 20){
            System.out.println("Partita non salvata, mossa troppo breve");
            saveGame=false;
        }

        if (saveGame) {
            String timeControl = (gameDuration / 60) + ":" + String.format("%02d", gameDuration % 60) + "m + " + increment + "s";
            saveGameToJson(endMessage, openingNameLabel.getText(), "Player vs Player", timeControl);
        }

        if (moveCalculationTask != null && moveCalculationTask.isRunning()) {
            moveCalculationTask.cancel();
        }

        evaluationLabel.setText(endMessage);
        move1Label.setText("");
        move2Label.setText("");
        move3Label.setText("");
        openingNameLabel.setText("");
    }
}

