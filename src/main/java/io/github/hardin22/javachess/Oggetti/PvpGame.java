package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.scene.control.Label;

public class PvpGame extends AbstractGame {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PvpGame.class);

    private Label openingNameLabel;
    private ChessTimer chessTimer;
    private int increment;
    private boolean isWhiteTurn;
    private int gameDuration;

    public PvpGame(ChessBoardUI chessBoardUI, EvalBar evalBar, Label openingNameLabel,
            Label whiteLabel, Label blackLabel, int gameDuration, int increment) {
        super(chessBoardUI, evalBar);
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
        pgn.setLength(0);
        evaluatePositionAndMoves();

        // Listen for physical moves and setup
        io.github.hardin22.javachess.Services.BoardStateManager manager = io.github.hardin22.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();

        manager.setLogicalBoard(board); // Sync initial board state
        manager.setPhysicalMoveSide(null); // both players move on the board

        manager.setListener(new io.github.hardin22.javachess.Services.BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                log.debug("Physical move {}{}", from, to);
                handleMoveInput(from + to);
            }

            @Override
            public void onBoardSetupComplete() {
                updateStatus("SCACCHIERA PRONTA! Partita Iniziata");
                manager.startGameMode(); // ACTIVATE GAME MODE
                chessTimer.startWhiteTimer();
            }

            @Override
            public void onSetupProgress(String message) {
                if (gameRunning) { // after the end the result stays on screen (the LEDs still guide)
                    updateStatus(message);
                }
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
                // Called on the FX thread: mirror the physical board (skip if the UI already shows it,
                // which also keeps the last-move highlight)
                if (!fen.split(" ")[0].equals(chessBoardUI.getFen().split(" ")[0])) {
                    chessBoardUI.setPosition(fen, null);
                }
                if (errorSquare != null) {
                    chessBoardUI.highlightErrorSquare(errorSquare);
                    if (gameRunning) {
                        updateStatus("ERRORE: Controlla " + errorSquare);
                    }
                }
            }

            @Override
            public void onBotMoveReplicated() {
                // Not used in PvP
            }
        });

        // Start Setup Mode (explicit target: a previous puzzle or browser game may have left another one)
        manager.setSetupTargetFen(board.getFen());
        manager.startSetupMode();
        updateStatus("Posiziona i pezzi...");
    }

    // Removed getEvaluationLabel

    String openingName = "";

    @Override
    public void handleMoveInput(String moveInput) {
        if (!gameRunning)
            return;

        try {
            Move move = withAutoQueen(parseMoveInput(moveInput));

            if (move != null && MoveGenerator.generateLegalMoves(board).contains(move)) {
                String fenBefore = board.getFen();
                board.doMove(move);
                onHumanMove(fenBefore, move);
                updatePgn(move);

                // Sync logical board to manager
                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance()
                        .getBoardStateManager()
                        .setLogicalBoard(board);

                Platform.runLater(() -> {
                    chessBoardUI.setPosition(board.getFen(), move);

                    updateOpeningLabel(openingNameLabel);

                    if (board.isMated()) {
                        notifyMate(); // Trigger Victory Animation
                        saveGame = false;
                        String winner = board.getSideToMove().flip() == Side.WHITE ? "Bianco" : "Nero";
                        endGame("Scaccomatto! Vince il " + winner + ".", true);
                    } else if (board.isDraw() || board.getHalfMoveCounter() >= 100) {
                        saveGame = false;
                        String drawReason = getDrawReason();
                        updateStatus("0.00");
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
                log.info("Illegal or invalid move: {}", moveInput);
            }
        } catch (RuntimeException e) {
            log.error("Move {} failed", moveInput, e);
        }
    }

    public boolean isSaveGame() {
        return saveGame;
    }

    private ChessClock.Side pausedSide;

    /** Stops the running clock (pause button of the two-player screen). A move or {@link #resumeClock()} restarts it. */
    public synchronized void pauseClock() {
        ChessClock.Side running = chessTimer.clock().running();
        if (!gameRunning || running == null) {
            return;
        }
        pausedSide = running;
        if (running == ChessClock.Side.WHITE) {
            chessTimer.stopWhiteTimer();
        } else {
            chessTimer.stopBlackTimer();
        }
    }

    /** Restarts the clock stopped by {@link #pauseClock()}. */
    public synchronized void resumeClock() {
        ChessClock.Side side = pausedSide;
        pausedSide = null;
        if (!gameRunning || side == null || chessTimer.clock().running() != null) {
            return;
        }
        if (side == ChessClock.Side.WHITE) {
            chessTimer.startWhiteTimer();
        } else {
            chessTimer.startBlackTimer();
        }
    }

    /** True while the clocks are paused. */
    public synchronized boolean isClockPaused() {
        return pausedSide != null && chessTimer.clock().running() == null;
    }

    @Override
    protected String whitePlayerName() {
        return "Bianco";
    }

    @Override
    protected String blackPlayerName() {
        return "Nero";
    }

    @Override
    public void endGame(String endMessage, boolean saveGame) {
        if (!gameRunning) {
            return; // already ended (mate, flag): do not save twice
        }
        gameRunning = false;
        chessTimer.stopWhiteTimer();
        chessTimer.stopBlackTimer();

        // An interrupted game needs a few moves to be worth keeping; one with a result (mate, draw, time,
        // resignation, draw by agreement) is kept from the first move, as in the game against the bot.
        int plies = board.getBackup().size();
        boolean interrupted = endMessage == null || endMessage.startsWith("Partita interrotta");
        if (plies == 0 || (interrupted && plies < 4)) {
            log.info("Game too short, not saved ({} plies)", plies);
            saveGame = false;
        }

        if (saveGame) {
            String timeControl = (gameDuration / 60) + "+" + increment; // minutes+seconds, e.g. 10+5
            saveGameToJson(endMessage, openingNameLabel.getText(), "Player vs Player", timeControl);
        }

        if (moveCalculationTask != null && moveCalculationTask.isRunning()) {
            moveCalculationTask.cancel();
        }
        stopAnalysis();

        updateStatus(endMessage);
        // Removed label clearing
        openingNameLabel.setText("");
    }
}
