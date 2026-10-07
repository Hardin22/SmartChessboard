package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import org.example.javachess.Services.EngineService;
import org.example.javachess.Utils.ConfigManager;

public class PvcGame extends AbstractGame {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PvcGame.class);

    private Label openingPvc;
    private UCIEngine playerStockfish;
    private boolean isPlayerWhite;
    private int skillLevel;
    private EngineService.EngineType botType;

    public PvcGame(ChessBoardUI chessBoardUI, EvalBar evalBar, Label openingPvc, boolean isPlayerWhite,
            int skillLevel, EngineService.EngineType botType) {
        super(chessBoardUI, evalBar);
        this.openingPvc = openingPvc;
        this.isPlayerWhite = isPlayerWhite;
        this.skillLevel = skillLevel;
        this.botType = botType;

        EngineService service = EngineService.getInstance();
        this.playerStockfish = service.createEngineInstance(botType);
        this.playerStockfish.setDebug(true); // Enable debug output for the bot
        System.out.println("Bot Instance Created: " + botType);

        // Only set skill level if using Stockfish (Maia levels are separate
        // engines/weights)
        if (botType == EngineService.EngineType.STOCKFISH) {
            this.playerStockfish.sendOption("Skill Level", String.valueOf(skillLevel));
        }
    }

    @Override
    public void startGame() {
        gameRunning = true;
        updateStatus("");

        Platform.runLater(() -> chessBoardUI.setPosition(board.getFen(), null));
        evaluatePositionAndMoves();

        // Listen for physical moves and setup
        org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();

        manager.setLogicalBoard(board); // Sync initial board state
        manager.setPhysicalMoveSide(isPlayerWhite ? Side.WHITE : Side.BLACK); // bot moves are only replicated

        manager.setListener(new org.example.javachess.Services.BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                log.debug("Physical move {}{}", from, to);
                handleMoveInput(from + to);
            }

            @Override
            public void onBoardSetupComplete() {
                updateStatus("SCACCHIERA PRONTA! Partita Iniziata");
                manager.startGameMode(); // ACTIVATE GAME MODE

                // Start the actual game logic here if needed, or just let the user move
                if (!isPlayerWhite) {
                    handleComputerMove();
                }
            }

            @Override
            public void onSetupProgress(String message) {
                updateStatus(message);
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
                // Called on the FX thread: mirror the physical board
                chessBoardUI.setPosition(fen, null);
                if (errorSquare != null) {
                    chessBoardUI.highlightErrorSquare(errorSquare);
                    updateStatus("ERRORE: Controlla " + errorSquare);
                }
            }

            @Override
            public void onBotMoveReplicated() {
                updateStatus("Mossa Bot Replicata! Tocca a te");
                // Game continues naturally as we are now listening for user moves again
            }
        });

        // Start Setup Mode
        manager.startSetupMode();
        updateStatus("Posiziona i pezzi...");
    }

    public boolean isSaveGame() {
        return saveGame;
    }

    String OpeningName = "";

    @Override
    public void handleMoveInput(String moveInput) {
        if (!gameRunning)
            return;
        if (board.getSideToMove() != (isPlayerWhite ? Side.WHITE : Side.BLACK)) {
            log.info("Ignoring {}: it is the bot's turn", moveInput);
            return;
        }

        try {
            Move move = parseMoveInput(moveInput);

            // Handle Promotion (Auto-Queen)
            if (move != null && isPromotionMove(move)) {
                Side side = board.getSideToMove();
                Piece promotionPiece = side == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
                move = new Move(move.getFrom(), move.getTo(), promotionPiece);
            }

            if (move != null && MoveGenerator.generateLegalMoves(board).contains(move)) {
                board.doMove(move);
                updatePgn(move);

                // Sync logical board to manager
                org.example.javachess.Controllers.ArduinoController.getInstance()
                        .getBoardStateManager()
                        .setLogicalBoard(board);

                final Move finalMove = move;
                Platform.runLater(() -> chessBoardUI.setPosition(board.getFen(), finalMove));
                OpeningName = stockfish.getOpeningName(board.getFen());
                if (!OpeningName.equals("Unknown Opening") && !OpeningName.equals("Error in API Call")) {
                    openingPvc.setText(OpeningName);
                }

                if (board.isMated()) {
                    notifyMate(); // Trigger Victory Animation
                    String winner = board.getSideToMove().flip() == Side.WHITE ? "Bianco" : "Nero";
                    endGameWithMessage("Scaccomatto! Vince il " + winner + ".");
                } else if (board.isDraw()) {
                    String drawReason = getDrawReason();
                    endGameWithMessage("Partita patta per " + drawReason + ".");
                } else {
                    handleComputerMove();
                }
            } else {
                log.info("Illegal or invalid move: {}", moveInput);
            }

        } catch (RuntimeException e) {
            log.error("Move {} failed", moveInput, e);
        }
    }

    private void handleComputerMove() {
        if (!gameRunning)
            return;

        Task<Void> computerMoveTask = new Task<Void>() {
            @Override
            protected Void call() {
                int movetime = ConfigManager.getIntProperty("game.bot.movetime", 2000);
                String bestMoveUci;
                if (botType != EngineService.EngineType.STOCKFISH) {
                    // Maia: Use nodes=1 to get the raw policy network output (human-like move)
                    System.out.println("Bot (" + botType + ") using nodes=1 (Human-like)...");
                    bestMoveUci = playerStockfish.getBestMoveFixedNodes(board.getFen(), 1);
                } else {
                    // Stockfish: Use time-based search
                    System.out.println("Bot (" + botType + ") using movetime=" + movetime);
                    bestMoveUci = playerStockfish.getBestMoveByTime(board.getFen(), movetime);
                }
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

                    // Sync logical board to manager
                    org.example.javachess.Controllers.ArduinoController.getInstance()
                            .getBoardStateManager()
                            .setLogicalBoard(board);

                    Platform.runLater(() -> {
                        chessBoardUI.setPosition(board.getFen(), finalBestMove);
                        OpeningName = stockfish.getOpeningName(board.getFen());
                        if (!OpeningName.equals("Unknown Opening") && !OpeningName.equals("Error in API Call")) {
                            openingPvc.setText(OpeningName);
                        }
                        if (board.isMated()) {
                            notifyMate(); // Trigger Victory Animation
                            String winner = board.getSideToMove().flip() == Side.WHITE ? "Bianco" : "Nero";
                            endGameWithMessage("Scaccomatto! Vince il " + winner + ".");
                        } else if (board.isDraw()) {
                            String drawReason = getDrawReason();
                            endGameWithMessage("Partita patta per " + drawReason + ".");
                        } else {
                            // Trigger Physical Replication FIRST
                            org.example.javachess.Controllers.ArduinoController.getInstance()
                                    .getBoardStateManager()
                                    .startBotMoveReplication(finalBestMove.getFrom().name(),
                                            finalBestMove.getTo().name());

                            evaluatePositionAndMoves();

                            // LED: Notify Opponent Move (Check/Mate only now)
                            notifyOpponentMove(finalBestMove.getFrom().name(), finalBestMove.getTo().name());
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
        log.info(message);
        updateStatus(message);

        saveGameToJson(message, openingPvc.getText(), "Player vs Stockfish livello " + skillLevel, "∞");

        saveGame = false;
        endGame(false);
    }

    public void endGame(boolean saveGame) {
        gameRunning = false;

        if (pgn.length() < 10) {
            saveGame = false;
            log.info("Game too short, not saved");
        }

        // Note: stockfish (analysis) is managed by AbstractGame/StockfishService,
        // but playerStockfish is local to this game instance.
        if (playerStockfish != null) {
            playerStockfish.close();
            playerStockfish = null;
            System.out.println("Stockfish (giocatore) chiuso.");
        }

        if (moveCalculationTask != null) {
            // Ensure this runs on FX thread or just ignore if already stopped
            Platform.runLater(() -> {
                if (moveCalculationTask != null && moveCalculationTask.isRunning()) {
                    moveCalculationTask.cancel();
                }
            });
        }

        if (saveGame) {
            final boolean finalSave = saveGame;
            Platform.runLater(() -> saveGameToJson("Partita interrotta.", openingPvc.getText(),
                    "Player vs Stockfish livello " + skillLevel, "∞"));
        }
        openingPvc.setText("");
    }

    @Override
    public void endGame(String endMessage, boolean saveGame) {
        // If game is already stopped, don't save again unless explicitly forced (which
        // shouldn't happen here)
        if (!gameRunning) {
            return;
        }

        if (saveGame) {
            endGameWithMessage(endMessage);
        } else {
            // Just stop without saving
            this.saveGame = false;
            endGame(false);
        }
    }

    private Move parseMoveUci(String uciMove) {
        Square from = Square.valueOf(uciMove.substring(0, 2).toUpperCase());
        Square to = Square.valueOf(uciMove.substring(2, 4).toUpperCase());
        return new Move(from, to);
    }
}
