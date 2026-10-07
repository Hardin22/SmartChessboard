package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Services.EngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PvcGame extends AbstractGame {

    private static final Logger log = LoggerFactory.getLogger(PvcGame.class);

    private Label openingPvc;
    private boolean isPlayerWhite;
    private int skillLevel;
    private EngineService.EngineType botType;
    /** A bot move has been asked for and not applied yet. */
    private boolean botThinking;
    /** Consecutive failed bot moves (engine missing or crashed): the retries get further apart. */
    private int botFailures;
    private static final long[] BOT_RETRY_DELAYS_MS = { 2_000, 5_000, 15_000, 30_000 };
    /** Choosing another engine while the bot cannot move retries at once. */
    private final javafx.beans.value.ChangeListener<io.github.hardin22.javachess.Engine.EngineProfile> profileListener =
            (obs, o, n) -> {
                if (botFailures > 0) {
                    retryBotMove();
                }
            };

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
        EngineManager.get().activeProfileProperty().addListener(profileListener);

        Platform.runLater(() -> chessBoardUI.setPosition(board.getFen(), null));
        evaluatePositionAndMoves();

        // Listen for physical moves and setup
        io.github.hardin22.javachess.Services.BoardStateManager manager = io.github.hardin22.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();

        manager.setLogicalBoard(board); // Sync initial board state
        manager.setPhysicalMoveSide(isPlayerWhite ? Side.WHITE : Side.BLACK); // bot moves are only replicated

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
                // Called on the FX thread: mirror the physical board (skip if the UI already shows it,
                // which also keeps the last-move highlight)
                if (!fen.split(" ")[0].equals(chessBoardUI.getFen().split(" ")[0])) {
                    chessBoardUI.setPosition(fen, null);
                }
                if (errorSquare != null) {
                    chessBoardUI.highlightErrorSquare(errorSquare);
                    if (gameRunning) { // after the end the result stays on screen
                        updateStatus("ERRORE: Controlla " + errorSquare);
                    }
                }
            }

            @Override
            public void onBotMoveReplicated() {
                if (gameRunning) {
                    updateStatus("Mossa Bot Replicata! Tocca a te");
                }
            }
        });

        // Start Setup Mode (explicit target: a previous puzzle or browser game may have left another one)
        manager.setSetupTargetFen(board.getFen());
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
            // promotion: the piece chosen on the screen ("e7e8n"), a queen for a move from the board
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
                log.info("Illegal or invalid move: {}", moveInput);
            }

        } catch (RuntimeException e) {
            log.error("Move {} failed", moveInput, e);
        }
    }

    private void handleComputerMove() {
        if (!gameRunning)
            return;

        botThinking = true;
        final String requestedFen = board.getFen();
        // Asynchronous: the engine layer never blocks this thread nor the FX thread.
        EngineManager.get().botMove(requestedFen, skillLevel).whenComplete((bestMoveUci, err) -> {
            // Board is not thread-safe: the outcome is handled on the FX thread.
            if (err != null) {
                Platform.runLater(() -> onBotMoveFailed(requestedFen, err));
            } else {
                Platform.runLater(() -> applyBotMove(requestedFen, bestMoveUci));
            }
        });
    }

    /** The engine could not move (missing, crashed, timed out): tell the player and try again later. */
    private void onBotMoveFailed(String requestedFen, Throwable err) {
        if (!gameRunning || !requestedFen.equals(board.getFen())) {
            return;
        }
        botThinking = false;
        long delay = BOT_RETRY_DELAYS_MS[Math.min(botFailures, BOT_RETRY_DELAYS_MS.length - 1)];
        botFailures++;
        log.error("bot move failed ({} in a row), retrying in {} ms: {}", botFailures, delay, err.toString());
        var status = EngineManager.get().statusProperty().get();
        String reason = status != null && !status.message().isBlank() ? status.message() : "nessuna risposta";
        updateStatus("Motore non disponibile: " + reason + ". Nuovo tentativo tra " + delay / 1000 + " s");
        runLaterOnFx(delay, () -> {
            if (gameRunning && !botThinking && requestedFen.equals(board.getFen())) {
                handleComputerMove();
            }
        });
    }

    /** Asks the bot again for its move now (e.g. "Riprova" after an engine failure). No effect on the human's turn. */
    public void retryBotMove() {
        if (gameRunning && !botThinking && board.getSideToMove() != (isPlayerWhite ? Side.WHITE : Side.BLACK)) {
            handleComputerMove();
        }
    }

    /** True while it is the human's turn and nothing else is pending (moves from the screen are accepted). */
    @Override
    public boolean isAwaitingHumanMove() {
        return gameRunning && !botThinking && board.getSideToMove() == (isPlayerWhite ? Side.WHITE : Side.BLACK);
    }

    private void applyBotMove(String requestedFen, String bestMoveUci) {
        if (!gameRunning || !requestedFen.equals(board.getFen())) {
            return; // game ended or position changed meanwhile
        }
        botThinking = false;
        botFailures = 0;
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
        io.github.hardin22.javachess.Controllers.ArduinoController.getInstance()
                .getBoardStateManager()
                .setLogicalBoard(board);

        chessBoardUI.setPosition(board.getFen(), finalBestMove);
        updateOpeningLabel(openingPvc);
        // The player reproduces the bot's move on the board, also the one that ends the game.
        io.github.hardin22.javachess.Controllers.ArduinoController.getInstance()
                .getBoardStateManager()
                .startBotMoveReplication(finalBestMove.getFrom().name(), finalBestMove.getTo().name());
        if (board.isMated()) {
            notifyMate(); // Trigger Victory Animation
            String winner = board.getSideToMove().flip() == Side.WHITE ? "Bianco" : "Nero";
            endGameWithMessage("Scaccomatto! Vince il " + winner + ".");
        } else if (board.isDraw()) {
            String drawReason = getDrawReason();
            endGameWithMessage("Partita patta per " + drawReason + ".");
        } else {
            evaluatePositionAndMoves();

            // LED: Notify Opponent Move (Check/Mate only now)
            notifyOpponentMove(finalBestMove.getFrom().name(), finalBestMove.getTo().name());
        }
    }

    private String botName() {
        if (botType == null || botType == EngineService.EngineType.STOCKFISH) {
            return "Stockfish livello " + skillLevel;
        }
        return "Maia " + botType.name().replace("MAIA_", "");
    }

    @Override
    protected String whitePlayerName() {
        return isPlayerWhite ? "Giocatore" : botName();
    }

    @Override
    protected String blackPlayerName() {
        return isPlayerWhite ? botName() : "Giocatore";
    }

    private boolean isPromotionMove(Move move) {
        Piece piece = board.getPiece(move.getFrom());
        return piece.getPieceType() == PieceType.PAWN &&
                (move.getTo().getRank() == Rank.RANK_8 || move.getTo().getRank() == Rank.RANK_1);
    }

    private void endGameWithMessage(String message) {
        log.info(message);
        updateStatus(message);

        saveGameToJson(message, openingPvc.getText(), "Player vs " + botName(), "");

        saveGame = false;
        endGame(false);
    }

    public void endGame(boolean saveGame) {
        gameRunning = false;
        botThinking = false;
        cancelPendingActions(); // pending bot retries
        EngineManager.get().activeProfileProperty().removeListener(profileListener);

        if (pgn.length() < 10) {
            saveGame = false;
            log.info("Game too short, not saved");
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
                    "Player vs " + botName(), ""));
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
