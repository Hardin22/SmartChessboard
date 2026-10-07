package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import org.example.javachess.Engine.AnalysisUpdate;
import org.example.javachess.Engine.EngineManager;
import org.example.javachess.Services.EngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PvcGame extends AbstractGame {

    private static final Logger log = LoggerFactory.getLogger(PvcGame.class);

    private Label openingPvc;
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

        // The bot is the active engine profile; the setup screen choice selects it. It can be changed
        // during the game (EngineSelection): the next bot move uses the new engine, the position is kept.
        if (botType != null) {
            EngineManager.get().select(botType.profileId());
        }
    }

    /** Arrows are suggestions for the human only, not for the bot's side. */
    @Override
    protected boolean shouldShowArrows(AnalysisUpdate update) {
        return update.whiteToMove() == isPlayerWhite;
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
        manager.startSetupMode(); // Start setup phase

        manager.setListener(new org.example.javachess.Services.BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                System.out.println("Physical Move: " + from + to);
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
                // Update the visual board to reflect physical state
                Platform.runLater(() -> {
                    chessBoardUI.setPosition(fen, null);
                    if (errorSquare != null) {
                        chessBoardUI.highlightErrorSquare(errorSquare);
                        updateStatus("ERRORE: Controlla " + errorSquare);
                    }
                });
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

        try {
            Move move = parseMoveInput(moveInput);

            // Handle Promotion (Auto-Queen)
            if (move != null && isPromotionMove(move)) {
                Side side = board.getSideToMove();
                Piece promotionPiece = side == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
                move = new Move(move.getFrom(), move.getTo(), promotionPiece);
            }

            if (move != null && MoveGenerator.generateLegalMoves(board).contains(move)) {
                String fenBefore = board.getFen();
                board.doMove(move);
                onHumanMove(fenBefore, move);
                updatePgn(move);

                // Sync logical board to manager
                org.example.javachess.Controllers.ArduinoController.getInstance()
                        .getBoardStateManager()
                        .setLogicalBoard(board);

                final Move finalMove = move;
                Platform.runLater(() -> chessBoardUI.setPosition(board.getFen(), finalMove));
                updateOpeningLabel(openingPvc);

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
                System.out.println("Mossa illegale o non valida, riprova.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleComputerMove() {
        if (!gameRunning)
            return;

        final String requestedFen = board.getFen();
        // Asynchronous: the engine layer never blocks this thread nor the FX thread.
        EngineManager.get().botMove(requestedFen, skillLevel).whenComplete((bestMoveUci, err) -> {
            if (err != null) {
                log.error("bot move failed: {}", err.toString());
                updateStatus("Motore non disponibile: " + EngineManager.get().statusProperty().get().message());
                return;
            }
            // Board is not thread-safe: apply the bot move on the FX thread.
            Platform.runLater(() -> applyBotMove(requestedFen, bestMoveUci));
        });
    }

    private void applyBotMove(String requestedFen, String bestMoveUci) {
        if (!gameRunning || !requestedFen.equals(board.getFen())) {
            return; // game ended or position changed meanwhile
        }
        Move bestMove = parseMoveUci(bestMoveUci);

        if (isPromotionMove(bestMove) && bestMove.getPromotion() == Piece.NONE) {
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

        chessBoardUI.setPosition(board.getFen(), finalBestMove);
        updateOpeningLabel(openingPvc);
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
    }

    private boolean isPromotionMove(Move move) {
        Piece piece = board.getPiece(move.getFrom());
        return piece.getPieceType() == PieceType.PAWN &&
                (move.getTo().getRank() == Rank.RANK_8 || move.getTo().getRank() == Rank.RANK_1);
    }

    private void endGameWithMessage(String message) {
        System.out.println(message);
        updateStatus(message);

        saveGameToJson(message, openingPvc.getText(), "Player vs Stockfish livello " + skillLevel, "∞");

        saveGame = false;
        endGame(false);
    }

    public void endGame(boolean saveGame) {
        gameRunning = false;

        if (pgn.length() < 10) {
            saveGame = false;
            System.out.println("Partita non salvata, mossa minima non raggiunta.");
        }

        // Engine processes are owned and reused by EngineManager: just stop the live analysis.
        stopAnalysis();

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
        return new Move(uciMove, board.getSideToMove()); // keeps the promotion piece (e7e8n)
    }
}
