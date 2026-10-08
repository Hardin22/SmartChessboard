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
    /** Id of the latest bot request: answers to older requests (position changed meanwhile) are ignored. */
    private int botRequestId;
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
        EngineManager.get().setBotStrength(null); // the skill level above, unless a level in Elo is chosen
    }

    /**
     * A game against a level of the ladder ({@code Play.BotLevels}), with a clock or without
     * ({@code TimeControl.UNLIMITED}).
     */
    public PvcGame(ChessBoardUI chessBoardUI, EvalBar evalBar, Label openingPvc, boolean isPlayerWhite,
            io.github.hardin22.javachess.Play.BotLevels.Level level,
            io.github.hardin22.javachess.Play.TimeControl timeControl) {
        this(chessBoardUI, evalBar, openingPvc, isPlayerWhite, level.strength().skillLevel(), level.engine());
        this.level = level;
        this.timeControl = timeControl == null ? io.github.hardin22.javachess.Play.TimeControl.UNLIMITED : timeControl;
        if (!this.timeControl.isUnlimited()) {
            this.clock = new io.github.hardin22.javachess.Play.GameClock(this.timeControl);
            this.clock.setOnFlag(this::onFlag);
        }
        EngineManager.get().setBotStrength(level.strength());
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
                boardReady = true;
                startTurnClock();
                updateTakebackGesture();

                // the bot moves first when it is its turn (Black chosen, a position or a resumed game)
                if (board.getSideToMove() != humanSide()) {
                    handleComputerMove();
                }
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
                    if (gameRunning) { // after the end the result stays on screen
                        updateStatus("ERRORE: Controlla " + errorSquare);
                    }
                }
            }

            @Override
            public void onBotMoveReplicated() {
                startTurnClock();
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
                updateTakebackGesture();

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
        final int requestId = ++botRequestId;
        final String requestedFen = board.getFen();
        // Asynchronous: the engine layer never blocks this thread nor the FX thread.
        EngineManager.get().botMove(requestedFen, skillLevel).whenComplete((bestMoveUci, err) -> {
            // Board is not thread-safe: the outcome is handled on the FX thread.
            Platform.runLater(() -> {
                if (requestId != botRequestId) {
                    return; // a newer request (or a position reset) superseded this one
                }
                botThinking = false;
                if (err != null) {
                    onBotMoveFailed(requestedFen, err);
                } else {
                    applyBotMove(requestedFen, bestMoveUci);
                }
            });
        });
    }

    /**
     * The position was changed from outside the normal flow (e.g. a takeback): a bot answer still on its way is
     * ignored and the human may move again.
     */
    protected void onPositionReset() {
        botRequestId++;
        botThinking = false;
        botFailures = 0;
    }

    /** The engine could not move (missing, crashed, timed out): tell the player and try again later. */
    private void onBotMoveFailed(String requestedFen, Throwable err) {
        if (!gameRunning || !requestedFen.equals(board.getFen())) {
            return;
        }
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
        updateTakebackGesture();
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
        if (level != null) {
            return level.playerName();
        }
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

        saveGameToJson(message, openingPvc.getText(), "Player vs " + botName(), timeControl.archiveForm());

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

        endFeatures();
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

    // --- clock, take-back, hints, draw offers, resuming ---------------------------------------------------

    private io.github.hardin22.javachess.Play.BotLevels.Level level;
    private io.github.hardin22.javachess.Play.TimeControl timeControl = io.github.hardin22.javachess.Play.TimeControl.UNLIMITED;
    private io.github.hardin22.javachess.Play.GameClock clock;
    private io.github.hardin22.javachess.Play.HintAdvisor hintAdvisor;
    private io.github.hardin22.javachess.Play.BotDrawPolicy drawPolicy;
    private int takebacks;
    private java.time.LocalDateTime startedAt = java.time.LocalDateTime.now();

    private Side humanSide() {
        return isPlayerWhite ? Side.WHITE : Side.BLACK;
    }

    /** The level of the ladder, or null for an old-style skill level. */
    public io.github.hardin22.javachess.Play.BotLevels.Level getLevel() {
        return level;
    }

    public io.github.hardin22.javachess.Play.TimeControl getTimeControl() {
        return timeControl;
    }

    /** The clock (observable texts for the two player rows), or null for a game without time. */
    public io.github.hardin22.javachess.Play.GameClock getClock() {
        return clock;
    }

    /** Hints on request (observable level and text for the hint button and card). */
    public io.github.hardin22.javachess.Play.HintAdvisor hints() {
        if (hintAdvisor == null) {
            hintAdvisor = new io.github.hardin22.javachess.Play.HintAdvisor();
        }
        return hintAdvisor;
    }

    /** Asks for a hint (first the piece, then the move). Only on the player's turn. */
    public void requestHint() {
        if (gameRunning && board.getSideToMove() == humanSide()) {
            hints().request(board.getFen());
        }
    }

    /** Moves taken back so far. */
    public int getTakebacks() {
        return takebacks;
    }

    /** True when {@link #takeBack()} would do something. */
    public boolean canTakeBack() {
        return gameRunning && pliesToTakeBack() > 0;
    }

    /** True once the pieces are set up (a take-back during the set-up would be undone by the board). */
    private volatile boolean boardReady;

    /** Half-moves a take-back removes now: the player's last move, and the bot's answer if it already came. */
    private int pliesToTakeBack() {
        if (!boardReady) {
            return 0;
        }
        int plies = board.getSideToMove() == humanSide() ? 2 : 1;
        return movesUci.size() >= plies ? plies : 0;
    }

    /**
     * Takes back the player's last move (and the bot's answer): the position goes back, the LEDs show which pieces
     * to put back on the board, and it is the player's turn again. A bot answer still being computed is dropped.
     * Clocks keep their times. Returns false when there is nothing to take back.
     */
    public boolean takeBack() {
        return takeBack(false);
    }

    /** {@code onBoard}: the player already put the pieces back (take-back made with the pieces). */
    private boolean takeBack(boolean onBoard) {
        int plies = gameRunning ? pliesToTakeBack() : 0;
        if (plies == 0 || !undoPlies(plies)) {
            return false;
        }
        onPositionReset();
        takebacks++;
        hints().clear();
        if (clock != null) {
            clock.start(humanSide());
        }
        updateOpeningLabel(openingPvc);
        updateStatus(onBoard ? "Mossa annullata sulla scacchiera: tocca a te"
                : "Mossa annullata: rimetti i pezzi come sullo schermo");
        evaluatePositionAndMoves();
        saveSnapshot();
        updateTakebackGesture();
        return true;
    }

    /**
     * Take-back with the pieces (as on a DGT board): putting the computer's answer and then the player's own move
     * back on the board takes them back, like the Annulla button. The board manager is told the positions on the
     * way; with the pieces, the answer has to go back first (the player's own move back alone would be a legal
     * move). While the computer thinks, the player's move back alone is enough.
     */
    private void updateTakebackGesture() {
        io.github.hardin22.javachess.Services.BoardStateManager manager =
                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager();
        int plies = gameRunning ? pliesToTakeBack() : 0;
        if (plies == 0) {
            manager.setTakebackGesture(java.util.List.of(), null);
            return;
        }
        java.util.List<Board> path = new java.util.ArrayList<>();
        for (int k = 1; k <= plies; k++) {
            path.add(positionBefore(k));
        }
        manager.setTakebackGesture(path, () -> {
            if (gameRunning && pliesToTakeBack() == plies) {
                takeBack(true);
            }
        });
    }

    /** The position {@code plies} half-moves ago, replayed from the start. */
    private Board positionBefore(int plies) {
        Board replay = new Board();
        replay.loadFromFen(initialFen);
        for (int i = 0; i < movesUci.size() - plies; i++) {
            replay.doMove(io.github.hardin22.javachess.Analysis.MoveText.legal(replay, movesUci.get(i)));
        }
        return replay;
    }

    /**
     * Offers a draw to the bot. It accepts only when it does not think it is better (and not in the opening); an
     * accepted draw ends the game ("Patta d'accordo"). The future completes on the JavaFX thread.
     */
    public java.util.concurrent.CompletableFuture<io.github.hardin22.javachess.Play.BotDrawPolicy.Decision> offerDraw() {
        if (!gameRunning) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new io.github.hardin22.javachess.Play.BotDrawPolicy.Decision(false, "La partita è finita"));
        }
        if (drawPolicy == null) {
            drawPolicy = new io.github.hardin22.javachess.Play.BotDrawPolicy();
        }
        String fen = board.getFen();
        java.util.concurrent.CompletableFuture<io.github.hardin22.javachess.Play.BotDrawPolicy.Decision> answer =
                new java.util.concurrent.CompletableFuture<>();
        // messages name the opponent as the screen does ("Circolo"); the archive keeps "Stockfish (1350)"
        String opponent = level != null ? level.name() : botName();
        drawPolicy.offer(fen, movesUci.size(), humanSide().flip(), opponent).thenAccept(d -> Platform.runLater(() -> {
            if (!d.accepted()) {
                answer.complete(d);
            } else if (gameRunning && fen.equals(board.getFen())) {
                endGame("Patta d'accordo", true);
                answer.complete(d);
            } else {
                // a move was made (or the game ended) while the bot was thinking about it
                drawPolicy.forgetLastOffer();
                answer.complete(new io.github.hardin22.javachess.Play.BotDrawPolicy.Decision(false,
                        "La posizione è cambiata: riproponi la patta"));
            }
        }));
        return answer;
    }

    /** True when a draw may be offered now (not right after the previous offer). */
    public boolean canOfferDraw() {
        return gameRunning && (drawPolicy == null || drawPolicy.canOffer(movesUci.size()));
    }

    /** Puts a saved game back (call before {@link #startGame()}): position, moves, clocks, counters. */
    public void resume(io.github.hardin22.javachess.Play.GameSnapshot snapshot) {
        setStartPosition(snapshot.initialFen());
        replayMoves(snapshot.moves());
        takebacks = snapshot.takebacks();
        hints().setUsed(snapshot.hints());
        if (snapshot.startedAt() != null) {
            startedAt = snapshot.startedAt();
        }
        if (clock != null) {
            clock.restore(snapshot.whiteMillis(), snapshot.blackMillis());
        }
        replaceArchivedCopyOf(snapshot);
    }

    /** A game against the computer rebuilt from a saved one, ready for {@link #startGame()}. */
    public static PvcGame fromSnapshot(io.github.hardin22.javachess.Play.GameSnapshot s, ChessBoardUI chessBoardUI,
            EvalBar evalBar, Label openingPvc) {
        PvcGame game;
        var level = s.botLevelId() == null ? java.util.Optional.<io.github.hardin22.javachess.Play.BotLevels.Level>empty()
                : io.github.hardin22.javachess.Play.BotLevels.byId(s.botLevelId());
        if (level.isPresent()) {
            game = new PvcGame(chessBoardUI, evalBar, openingPvc, s.humanWhite(), level.get(), s.timeControl());
        } else {
            EngineService.EngineType type;
            try {
                type = s.botEngine() == null ? EngineService.EngineType.STOCKFISH
                        : EngineService.EngineType.valueOf(s.botEngine());
            } catch (IllegalArgumentException e) {
                type = EngineService.EngineType.STOCKFISH;
            }
            game = new PvcGame(chessBoardUI, evalBar, openingPvc, s.humanWhite(), s.skillLevel(), type);
        }
        game.resume(s);
        return game;
    }

    @Override
    protected io.github.hardin22.javachess.Play.GameSnapshot snapshot() {
        long white = clock == null ? 0 : clock.remainingMillis(Side.WHITE);
        long black = clock == null ? 0 : clock.remainingMillis(Side.BLACK);
        return new io.github.hardin22.javachess.Play.GameSnapshot(io.github.hardin22.javachess.Play.GameSnapshot.Mode.PVC,
                initialFen, movesUci, isPlayerWhite, level == null ? null : level.id(),
                botType == null ? null : botType.name(), skillLevel, timeControl, white, black, takebacks,
                hintAdvisor == null ? 0 : hintAdvisor.usedProperty().get(), startedAt, java.time.LocalDateTime.now());
    }

    /** After the player's move: the hint goes, the player's clock stops (+ increment) and the bot's starts. */
    @Override
    protected void onHumanMove(String fenBefore, Move move) {
        super.onHumanMove(fenBefore, move);
        if (hintAdvisor != null) {
            hintAdvisor.clear();
        }
        if (clock != null) {
            clock.moveMade(humanSide());
            if (gameRunning) {
                clock.start(humanSide().flip());
            }
            budgetBotTime();
        }
    }

    /** After the bot's move: its clock stops (+ increment); the player's starts once the move is on the board. */
    @Override
    protected void notifyOpponentMove(String from, String to) {
        super.notifyOpponentMove(from, to);
        if (clock != null) {
            clock.moveMade(humanSide().flip());
            boolean board = io.github.hardin22.javachess.Controllers.ArduinoController.getInstance()
                    .getBoardStateManager().isHardwareConnected();
            // With a board the player's time starts once the bot's move is reproduced on it (setting
            // game.clock.replicationFree=false makes it start at once, as in an online game)
            if (!board || !io.github.hardin22.javachess.Utils.ConfigManager.getBooleanProperty(
                    REPLICATION_FREE_KEY, true)) {
                startTurnClock();
            }
        }
    }

    /** Setting: the time spent reproducing the bot's move on the board is not counted (default true). */
    public static final String REPLICATION_FREE_KEY = "game.clock.replicationFree";

    /** Starts the clock of the side to move (game start, board in step again, bot move reproduced). */
    private void startTurnClock() {
        if (clock == null || !gameRunning) {
            return;
        }
        Side toMove = board.getSideToMove();
        if (toMove == humanSide() && clock.runningProperty().get() != humanSide()) {
            clock.start(toMove);
        } else if (toMove != humanSide()) {
            clock.start(toMove);
            budgetBotTime();
        }
    }

    /** The bot thinks within its clock: about 1/30 of its time plus most of the increment, at most the usual time. */
    private void budgetBotTime() {
        if (clock == null || level == null) {
            return;
        }
        long left = clock.remainingMillis(humanSide().flip());
        int configured = io.github.hardin22.javachess.Utils.ConfigManager.getIntProperty("game.bot.movetime", 2000);
        long budget = left / 30 + timeControl.incrementSeconds() * 800L;
        int movetime = (int) Math.max(100, Math.min(configured, budget));
        EngineManager.get().setBotStrength(level.strength().withMovetime(movetime));
    }

    /** A clock ran out: the result (FIDE 6.9: a draw if the other side cannot mate). */
    private void onFlag(Side flagged) {
        if (gameRunning) {
            endGame(io.github.hardin22.javachess.Play.GameClock.flagResult(board, flagged), true);
        }
    }

    /** End of the game: clock stopped, hint removed, the bot strength back to the plain skill level. */
    private void endFeatures() {
        if (clock != null) {
            clock.stop();
        }
        if (level != null) {
            EngineManager.get().setBotStrength(null);
        }
        if (hintAdvisor != null) {
            hintAdvisor.clear();
        }
    }
}
