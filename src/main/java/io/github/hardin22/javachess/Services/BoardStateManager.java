package io.github.hardin22.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import io.github.hardin22.javachess.Hardware.BoardHardware;
import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.MoveLeds;
import io.github.hardin22.javachess.Hardware.SetupGuide;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Utils.AppExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns sensor events from the board into chess events: setup progress, moves played on the board and
 * completion of the opponent's move that the player has to reproduce.
 *
 * <h2>How moves are recognised</h2>
 * The manager keeps the logical position (from the game) and the physical occupancy (from the sensors). After
 * every sensor change it looks for the legal moves whose resulting occupancy equals the physical one; for a
 * capture the destination square must also have been touched (the captured piece was lifted), otherwise holding
 * the capturing piece in the air would already look like the capture. Castling, en passant and promotion
 * (queen by default) fall out of the same rule. A match is committed after a short settle time, longer for a
 * rook move that is also the first half of castling, so sliding pieces and rook-first castling do not produce
 * false moves. Pieces left on squares that should be empty are flagged after {@code errorSettleMs}.
 *
 * <h2>Board out of sync</h2>
 * When the board reconnects during a game, the first occupancy snapshot tells what happened meanwhile: a move
 * made on the board while it was offline is taken as a move; any other difference (the opponent's move not yet
 * reproduced, moves made on the screen, a takeback) puts the manager in {@link Mode#RESYNC}: the LEDs show the
 * missing and extra pieces as in the setup and the screen shows the game position until the board matches it.
 *
 * <h2>Threading</h2>
 * Sensor events and the public methods are serialised on one daemon thread ("board-events"); public methods
 * return immediately. Listener callbacks run on the JavaFX thread (tests can pass another executor).
 */
public class BoardStateManager implements BoardHardware.SensorListener {

    private static final Logger log = LoggerFactory.getLogger(BoardStateManager.class);

    public interface BoardMoveListener {
        void onPhysicalMoveDetected(String fromSquare, String toSquare);

        void onBoardSetupComplete();

        void onSetupProgress(String message);

        /** FEN of what is physically on the board (pieces taken from the logical position); errorSquare may be null. */
        void onBoardStateUpdated(String fen, String errorSquare);

        void onBotMoveReplicated();
    }

    /** Raw sensor changes, for trainers that use the squares themselves (coordinates) and not moves. */
    @FunctionalInterface
    public interface SquareListener {
        /** A piece was placed on ({@code occupied}) or lifted from {@code square} (0 = a1). */
        void onSquareChanged(int square, boolean occupied);
    }

    public interface EvaluationProvider {
        double getCurrentEvaluation();
    }

    /** Shows hints when a piece of the side to move is lifted (implemented with the engine). */
    public interface HintProvider {
        void pieceLifted(Board position, Square from, double currentEvaluation, String bestMove, boolean evaluate);

        void hintsCleared();
    }

    public enum Mode {
        IDLE, SETUP, PLAY, REPLICATE,
        /** The physical board must be brought back to the game position (after a reconnection or a takeback). */
        RESYNC
    }

    /** How long to wait for the first occupancy snapshot after a reconnection before using what is known. */
    private static final long SNAPSHOT_WAIT_MS = 2000;

    private final LedRenderer leds;
    private final MoveLeds moveLeds;
    private final ScheduledExecutorService events;
    private final Executor callbacks;
    private final HintProvider hints;

    private volatile long settleMs = 150;
    private volatile long castlingSettleMs = 1500;
    private volatile long errorSettleMs = 800;

    private volatile BoardMoveListener listener;
    private volatile SquareListener squareListener;
    private volatile EvaluationProvider evaluationProvider;
    private volatile String bestMove;
    private volatile boolean evaluationEnabled = true;
    private volatile boolean hardwareConnected;
    private volatile boolean guidedSetupEnabled = true;
    /** Step of the guided set-up shown now, or null (not guided, or not setting up). */
    private volatile SetupGuide.Step setupStep;

    // state below is only touched on the events thread
    private Mode mode = Mode.IDLE;
    private long physical;
    private Board logical = new Board();
    private Board setupTarget = new Board();
    /** Piece-by-piece guide of the current set-up, or null when all the squares are shown together. */
    private SetupGuide setupGuide;
    /**
     * Set-up: occupied squares known to hold another piece than the target wants (from the last position, when the
     * sensors still match it). They must be emptied first; a square leaves the set when its piece is lifted.
     */
    private long mustClear;
    /** Copy of {@link #mustClear} readable from any thread. */
    private volatile long setupWrong;
    /** Positions the board may go back through to take moves back (oldest last), and what to do then. */
    private List<Board> takebackPath = List.of();
    private Runnable takebackAction;
    private ScheduledFuture<?> pendingTakeback;
    private volatile long takebackSettleMs = 700;
    private Side physicalMoveSide;
    private long touched;
    private int liftedSquare = -1;
    private ScheduledFuture<?> pendingCommit;
    private ScheduledFuture<?> pendingCheck;
    private long replicationRequired;
    private int replicationFrom = -1;
    private int replicationTo = -1;
    private String lastFen;
    private String lastError;
    private String lastProgress;
    /** Reconnected during a game: moves are not evaluated until the board has sent its occupancy. */
    private boolean awaitingSnapshot;
    private ScheduledFuture<?> snapshotTimeout;
    /** The resync also completes a pending opponent-move replication. */
    private boolean resyncCompletesReplication;

    /** Production constructor: LEDs from the hardware layer, callbacks on the JavaFX thread, hints from the engine. */
    public BoardStateManager(LedRenderer leds, MoveLeds moveLeds) {
        this(leds, moveLeds, Platform::runLater, null,
                Executors.newSingleThreadScheduledExecutor(AppExecutors.daemonFactory("board-events")));
    }

    /**
     * @param callbacks executor for listener callbacks (JavaFX thread in the app)
     * @param hints     hint provider, or null to use the engine's MoveEvaluatorService (created on first use)
     * @param events    single-threaded executor that owns the state
     */
    public BoardStateManager(LedRenderer leds, MoveLeds moveLeds, Executor callbacks, HintProvider hints,
                             ScheduledExecutorService events) {
        this.leds = leds;
        this.moveLeds = moveLeds;
        this.callbacks = callbacks;
        this.hints = hints != null ? hints : new EngineHints(moveLeds, leds);
        this.events = events;
    }

    // --- configuration -------------------------------------------------------------------------------------

    /** Settle times in ms: normal moves, rook moves that start a castling, errors (stray pieces). */
    public void setTimings(long settleMs, long castlingSettleMs, long errorSettleMs) {
        this.settleMs = settleMs;
        this.castlingSettleMs = castlingSettleMs;
        this.errorSettleMs = errorSettleMs;
    }

    /**
     * Guided set-up (default on, {@code board.setup.guided}): positions other than the starting one are set up one
     * kind of piece at a time. Off: every empty square lights up together.
     */
    public void setGuidedSetup(boolean enabled) {
        guidedSetupEnabled = enabled;
        post(() -> {
            if (mode == Mode.SETUP) {
                chooseSetupGuide();
                refresh();
            }
        });
    }

    /**
     * The player takes the set-up in progress over: the piece-by-piece guide stops (every square to fill lights up
     * at once) and the pieces believed wrong are trusted as they stand. The next set-up is guided again.
     */
    public void skipSetupGuide() {
        post(() -> {
            if (mode == Mode.SETUP && (setupGuide != null || mustClear != 0)) {
                setupGuide = null;
                setupStep = null;
                mustClear = 0;
                setupWrong = 0;
                refresh();
            }
        });
    }

    /**
     * Set-up: squares holding a piece the target does not want there, as far as the manager knows (bit i = square
     * i). They are shown red; the piece has to be lifted and the right one placed.
     */
    public long setupWrongSquares() {
        return setupWrong;
    }

    /** Step of the guided set-up shown now (piece, squares, "passo 2 di 7"), or null. */
    public SetupGuide.Step setupStep() {
        return setupStep;
    }

    /**
     * Take-back made with the pieces (as on a DGT board): when the board shows the last of {@code positions} (the
     * position before the moves to take back) and no legal move explains it, {@code onTakeback} runs on the
     * callbacks thread, once. {@code positions} are the positions the board passes through, most recent first (e.g.
     * before the computer's answer, then before the player's move): on the way, a piece out of place is not flagged.
     * Cleared by every {@link #setLogicalBoard} and by the end of the game; an empty list turns it off.
     */
    public void setTakebackGesture(List<Board> positions, Runnable onTakeback) {
        List<Board> copies = new ArrayList<>();
        for (Board b : positions) {
            copies.add(b.clone());
        }
        post(() -> {
            clearTakeback();
            if (!copies.isEmpty() && onTakeback != null) {
                takebackPath = List.copyOf(copies);
                takebackAction = onTakeback;
                refresh();
            }
        });
    }

    /** Tests: how long the board must show the earlier position before the take-back counts. */
    public void setTakebackSettleMs(long ms) {
        takebackSettleMs = ms;
    }

    /** Receives every sensor change (on the callbacks thread); null to stop. Independent of the move listener. */
    public void setSquareListener(SquareListener listener) {
        squareListener = listener;
    }

    public void setListener(BoardMoveListener listener) {
        this.listener = listener;
    }

    public void setEvaluationProvider(EvaluationProvider provider) {
        this.evaluationProvider = provider;
    }

    public void setBestMove(String move) {
        this.bestMove = move;
    }

    public void setEvaluationEnabled(boolean enabled) {
        this.evaluationEnabled = enabled;
    }

    /**
     * Restricts move detection to one side (the human in a game against the bot); null accepts both sides.
     * While the other side is to move, changes on the board are only checked for errors.
     */
    public void setPhysicalMoveSide(Side side) {
        post(() -> physicalMoveSide = side);
    }

    // --- game control --------------------------------------------------------------------------------------

    public void setLogicalBoard(Board board) {
        Board copy = board.clone();
        post(() -> {
            logical = copy;
            clearTakeback(); // the game says again which take-back is possible for this position
            touched = 0;
            liftedSquare = -1;
            cancel(pendingCommit);
            refresh();
        });
    }

    public void setSetupTargetFen(String fen) {
        post(() -> {
            setupTarget = new Board();
            setupTarget.loadFromFen(fen);
            if (mode == Mode.SETUP) {
                chooseSetupGuide();
                refresh();
            }
        });
    }

    public void startSetupMode() {
        post(() -> {
            mode = Mode.SETUP;
            awaitingSnapshot = false;
            lastProgress = null;
            chooseSetupGuide();
            log.info("Setup mode{}", setupGuide != null ? " (guided piece by piece)" : "");
            refresh();
        });
    }

    public void startGameMode() {
        post(() -> {
            mode = Mode.PLAY;
            awaitingSnapshot = false;
            setupGuide = null;
            setupStep = null;
            mustClear = 0;
            setupWrong = mustClear;
            touched = 0;
            liftedSquare = -1;
            leds.clear(LedRenderer.Layer.BASE);
            log.info("Game mode");
            refresh();
        });
    }

    /** Ends the game: forgets the position, turns the LEDs off and detaches the listener. */
    public void stopGameMode() {
        listener = null;
        post(() -> {
            mode = Mode.IDLE;
            awaitingSnapshot = false;
            setupGuide = null;
            setupStep = null;
            mustClear = 0;
            setupWrong = mustClear;
            clearTakeback();
            cancel(pendingCommit);
            cancel(pendingCheck);
            cancel(snapshotTimeout);
            touched = 0;
            liftedSquare = -1;
            physicalMoveSide = null;
            replicationRequired = 0;
            logical = new Board();
            setupTarget = new Board();
            lastFen = null;
            lastError = null;
            hints.hintsCleared();
            leds.clearAll();
            log.info("Game mode stopped");
        });
    }

    /** Forgets any half-done move or replication. */
    public void reset() {
        post(() -> {
            touched = 0;
            liftedSquare = -1;
            cancel(pendingCommit);
            replicationRequired = 0;
            if (mode == Mode.REPLICATE || mode == Mode.RESYNC) {
                mode = Mode.PLAY;
            }
            leds.clear(LedRenderer.Layer.BASE);
            hints.hintsCleared();
        });
    }

    /**
     * Asks the player to reproduce the opponent's move {@code from}-{@code to} on the board. The logical board
     * must already contain the move ({@link #setLogicalBoard}).
     */
    public void startBotMoveReplication(String from, String to) {
        post(() -> {
            replicationFrom = Squares.parse(from);
            replicationTo = Squares.parse(to);
            if (replicationFrom < 0 || replicationTo < 0) {
                log.warn("Invalid move to replicate: {}-{}", from, to);
                return;
            }
            mode = Mode.REPLICATE;
            cancel(pendingCommit);
            touched = 0;
            liftedSquare = -1;
            hints.hintsCleared();
            replicationRequired = Squares.bit(replicationFrom) | Squares.bit(replicationTo)
                    | (physical ^ occupancy(logical));
            log.info("Waiting for the player to replicate {}-{}", from, to);
            refresh();
        });
    }

    /**
     * Asks the player to bring the physical board back to the logical position (e.g. after a takeback set with
     * {@link #setLogicalBoard}): missing and extra pieces are shown on the LEDs until the board matches. Does
     * nothing when the board already matches, is not connected or no game is being played.
     */
    public void resyncToLogical() {
        post(() -> {
            if ((mode == Mode.PLAY || mode == Mode.REPLICATE) && hardwareConnected
                    && physical != occupancy(logical)) {
                startResync(mode == Mode.REPLICATE);
            }
        });
    }

    /** Old entry point used by ArduinoController: "E2", true = piece placed. */
    public void updateSquare(String squareName, boolean isPiecePresent) {
        int square = Squares.parse(squareName);
        if (square < 0) {
            log.warn("Invalid square from the board: {}", squareName);
            return;
        }
        onSquareChanged(square, isPiecePresent);
    }

    // --- sensor events -------------------------------------------------------------------------------------

    @Override
    public void onSquareChanged(int square, boolean occupied) {
        post(() -> applyChange(square, occupied));
    }

    @Override
    public void onOccupancy(long occupied) {
        post(() -> {
            long changed = physical ^ occupied;
            if (changed == 0) {
                return;
            }
            // pieces removed first, then placed: the natural order of a move
            for (long bits = changed & physical; bits != 0; bits &= bits - 1) {
                applyChange(Long.numberOfTrailingZeros(bits), false);
            }
            for (long bits = changed & occupied; bits != 0; bits &= bits - 1) {
                applyChange(Long.numberOfTrailingZeros(bits), true);
            }
        });
        post(() -> {
            if (awaitingSnapshot) {
                checkSyncAfterReconnection();
            }
        });
    }

    @Override
    public void onConnectionChanged(boolean connected, String description) {
        boolean wasConnected = hardwareConnected;
        hardwareConnected = connected;
        log.info("Chessboard {}: {}", connected ? "connected" : "disconnected", description);
        post(() -> {
            cancel(snapshotTimeout);
            awaitingSnapshot = false;
            if (connected && !wasConnected && (mode == Mode.PLAY || mode == Mode.REPLICATE)) {
                // what is on the board now is only known from the next occupancy snapshot
                awaitingSnapshot = true;
                cancel(pendingCommit);
                cancel(pendingCheck);
                snapshotTimeout = schedule(this::checkSyncAfterReconnection, SNAPSHOT_WAIT_MS);
                return;
            }
            refresh();
        });
    }

    /** True when sensors are available (real board or simulator). */
    public boolean isHardwareConnected() {
        return hardwareConnected;
    }

    // --- state machine (events thread) ---------------------------------------------------------------------

    private void applyChange(int square, boolean occupied) {
        long bit = Squares.bit(square);
        if (((physical & bit) != 0) == occupied) {
            return;
        }
        physical = occupied ? physical | bit : physical & ~bit;
        touched |= bit;
        if (!occupied) {
            mustClear &= ~bit; // the wrong piece is gone: the square now waits for the right one
            setupWrong = mustClear;
        }
        log.debug("{} {} (mode {})", Squares.name(square), occupied ? "placed" : "lifted", mode);
        SquareListener raw = squareListener;
        if (raw != null) {
            callbacks.execute(() -> raw.onSquareChanged(square, occupied));
        }
        refresh();
    }

    private void refresh() {
        if (awaitingSnapshot) {
            return;
        }
        switch (mode) {
            case SETUP -> refreshSetup();
            case PLAY -> refreshPlay();
            case REPLICATE -> refreshReplication();
            case RESYNC -> refreshResync();
            case IDLE -> {
            }
        }
    }

    /**
     * First look at the board after a reconnection during a game: a single move made on it while it was offline
     * is taken as the move; any other difference starts the resync.
     */
    private void checkSyncAfterReconnection() {
        if (!awaitingSnapshot) {
            return;
        }
        awaitingSnapshot = false;
        cancel(snapshotTimeout);
        long logicalOcc = occupancy(logical);
        if (!hardwareConnected || (mode != Mode.PLAY && mode != Mode.REPLICATE) || physical == logicalOcc) {
            refresh();
            return;
        }
        if (mode == Mode.PLAY && (physicalMoveSide == null || physicalMoveSide == logical.getSideToMove())) {
            touched = physical ^ logicalOcc; // every square that changed while offline was touched
            List<Move> matches = matchingMoves();
            if (matches.size() == 1) {
                log.info("Move made while the board was offline");
                commit(matches.get(0));
                return;
            }
        }
        startResync(mode == Mode.REPLICATE);
    }

    private void startResync(boolean completesReplication) {
        mode = Mode.RESYNC;
        resyncCompletesReplication = completesReplication;
        cancel(pendingCommit);
        touched = 0;
        liftedSquare = -1;
        lastProgress = null;
        hints.hintsCleared();
        leds.clear(LedRenderer.Layer.ALERT);
        log.info("Board out of sync with the game: waiting for the pieces to be put back");
        refresh();
    }

    private void refreshResync() {
        cancel(pendingCheck);
        long target = occupancy(logical);
        long missing = target & ~physical;
        long wrong = physical & ~target;
        Map<Integer, Integer> base = new HashMap<>();
        if (hardwareConnected) {
            forEachSquare(missing, sq -> base.put(sq, LedColors.MISSING));
            forEachSquare(wrong, sq -> base.put(sq, LedColors.WRONG));
        }
        leds.replace(LedRenderer.Layer.BASE, base);
        publish(logical.getFen(), null); // the screen shows where the pieces go
        if (hardwareConnected && (missing != 0 || wrong != 0)) {
            StringBuilder message = new StringBuilder("Rimetti i pezzi come sullo schermo:");
            if (missing != 0) {
                message.append(" mancano ").append(Long.bitCount(missing));
            }
            if (wrong != 0) {
                message.append(missing != 0 ? "," : "").append(" da togliere ").append(Long.bitCount(wrong))
                        .append(" (in rosso)");
            }
            progress(message.toString());
            return;
        }
        long snapshot = physical;
        pendingCheck = schedule(() -> {
            if (mode == Mode.RESYNC && physical == snapshot) {
                log.info("Board back in sync with the game");
                mode = Mode.PLAY;
                touched = 0;
                replicationRequired = 0;
                updateBaseLayer(0, 0);
                publish(displayFen(occupancy(logical)), null);
                progress("Scacchiera allineata");
                if (resyncCompletesReplication) {
                    notifyListener(BoardMoveListener::onBotMoveReplicated);
                }
            }
        }, hardwareConnected ? settleMs : 0);
    }

    /**
     * Big set-ups of a position other than the starting one are guided one kind of piece at a time
     * ({@link SetupGuide}); the starting position and small changes show every square together.
     */
    private void chooseSetupGuide() {
        mustClear = hardwareConnected ? wrongPieces(logical, setupTarget, physical) : 0;
        setupWrong = mustClear;
        long seen = physical & ~mustClear;
        setupGuide = guidedSetupEnabled && hardwareConnected && SetupGuide.worthGuiding(setupTarget, seen)
                ? new SetupGuide(setupTarget) : null;
        setupStep = null;
    }

    /**
     * Squares occupied in both {@code believed} and {@code target} with different pieces, when {@code believed} is
     * what the board shows ({@code physical} matches its occupancy); 0 when nothing is known.
     */
    static long wrongPieces(Board believed, Board target, long physical) {
        if (believed == null || occupancy(believed) != physical) {
            return 0;
        }
        long wrong = 0;
        for (long bits = physical & occupancy(target); bits != 0; bits &= bits - 1) {
            int sq = Long.numberOfTrailingZeros(bits);
            Square square = Square.squareAt(sq);
            if (believed.getPiece(square) != target.getPiece(square)) {
                wrong |= Squares.bit(sq);
            }
        }
        return wrong;
    }

    private void refreshSetup() {
        long target = occupancy(setupTarget);
        long missing = target & ~physical;
        long wrong = (physical & ~target) | (physical & mustClear);
        SetupGuide.Step step = setupGuide != null && hardwareConnected ? setupGuide.step(physical & ~mustClear)
                : null;
        setupStep = step;
        long shown = step != null ? step.missing() : missing;
        Map<Integer, Integer> base = new HashMap<>();
        if (hardwareConnected) {
            forEachSquare(shown, sq -> base.put(sq, LedColors.MISSING));
            forEachSquare(wrong, sq -> base.put(sq, LedColors.WRONG));
        }
        leds.replace(LedRenderer.Layer.BASE, base);
        publish(setupFen(), null);
        boolean complete = !hardwareConnected || (missing == 0 && wrong == 0);
        if (!complete) {
            if (step != null) {
                // pieces to take away first, then the group asked: "…, poi il Re bianco in g1 · passo 1 di 6"
                progress(wrong == 0 ? step.message() : "Posiziona i pezzi: togli quelli sulle case rosse ("
                        + Long.bitCount(wrong) + "), poi " + step.message().substring("Posiziona ".length()));
            } else if (missing == 0 && setupGuide != null) {
                progress("Posiziona i pezzi: togli quelli sulle case rosse (" + Long.bitCount(wrong) + ")");
            } else {
                progress("Posiziona i pezzi: mancano " + Long.bitCount(missing)
                        + (wrong != 0 ? ", da togliere " + Long.bitCount(wrong) + " (in rosso)" : ""));
            }
            cancel(pendingCheck);
            return;
        }
        cancel(pendingCheck);
        long snapshot = physical;
        pendingCheck = schedule(() -> {
            if (mode == Mode.SETUP && physical == snapshot) {
                mode = Mode.IDLE;
                setupGuide = null;
                setupStep = null;
                mustClear = 0;
                setupWrong = mustClear;
                logical = setupTarget.clone(); // what the board shows now
                leds.clear(LedRenderer.Layer.BASE);
                log.info("Board setup complete");
                notifyListener(BoardMoveListener::onBoardSetupComplete);
            }
        }, hardwareConnected ? settleMs : 0);
    }

    private void refreshPlay() {
        cancel(pendingCommit);
        cancel(pendingCheck);
        long logicalOcc = occupancy(logical);
        long missing = logicalOcc & ~physical;
        long extra = physical & ~logicalOcc;
        updateBaseLayer(0, 0);

        if (!hardwareConnected || (missing == 0 && extra == 0)) {
            cancel(pendingTakeback);
            if (liftedSquare >= 0) {
                hints.hintsCleared();
                liftedSquare = -1;
            }
            touched = 0;
            leds.clear(LedRenderer.Layer.ALERT);
            publish(displayFen(logicalOcc), null);
            return;
        }

        if (extra == 0) {
            leds.clear(LedRenderer.Layer.ALERT);
        }
        boolean mayMove = physicalMoveSide == null || physicalMoveSide == logical.getSideToMove();
        if (mayMove) {
            trackLiftedPiece(missing, extra);
            List<Move> matches = matchingMoves();
            if (matches.size() == 1) {
                Move move = matches.get(0);
                long snapshot = physical;
                long delay = isCastlingPrefix(move) ? castlingSettleMs : settleMs;
                pendingCommit = schedule(() -> {
                    if (mode == Mode.PLAY && physical == snapshot) {
                        commit(move);
                    }
                }, delay);
            } else if (matches.isEmpty()) {
                checkTakeback();
            }
        } else {
            checkTakeback(); // the player's own move put back while the opponent thinks
        }
        publish(displayFen(logicalOcc), null);
        pendingCheck = schedule(this::checkForStrayPieces, errorSettleMs);
    }

    /** Shows hints when exactly one piece of the side to move is off the board (captured pieces may be too). */
    private void trackLiftedPiece(long missing, long extra) {
        if (extra != 0) {
            return;
        }
        long own = 0;
        for (long bits = missing; bits != 0; bits &= bits - 1) {
            int sq = Long.numberOfTrailingZeros(bits);
            Piece piece = logical.getPiece(Square.squareAt(sq));
            if (piece != Piece.NONE && piece.getPieceSide() == logical.getSideToMove()) {
                own |= Squares.bit(sq);
            }
        }
        if (Long.bitCount(own) != 1 || Long.numberOfTrailingZeros(own) == liftedSquare) {
            return;
        }
        int square = Long.numberOfTrailingZeros(own);
        liftedSquare = square;
        moveLeds.clearVerdict();
        EvaluationProvider provider = evaluationProvider;
        double eval = provider != null ? provider.getCurrentEvaluation() : 0.0;
        try {
            hints.pieceLifted(logical.clone(), Square.squareAt(square), eval, bestMove, evaluationEnabled);
        } catch (RuntimeException e) {
            log.warn("Hints for {} failed: {}", Squares.name(square), e.toString());
        }
    }

    /** Legal moves whose result matches the physical occupancy (promotions collapsed to the queen). */
    private List<Move> matchingMoves() {
        long logicalOcc = occupancy(logical);
        List<Move> result = new ArrayList<>();
        for (Move move : logical.legalMoves()) {
            if (move.getPromotion() != Piece.NONE && move.getPromotion().getPieceType() != PieceType.QUEEN) {
                continue;
            }
            int to = move.getTo().ordinal();
            boolean capture = (logicalOcc & Squares.bit(to)) != 0;
            if (capture && (touched & Squares.bit(to)) == 0) {
                continue;
            }
            if (occupancyAfter(logical, logicalOcc, move) == physical) {
                result.add(move);
            }
        }
        return result;
    }

    private void commit(Move move) {
        log.info("Move detected on the board: {}", move);
        logical.doMove(move);
        touched = 0;
        liftedSquare = -1;
        hints.hintsCleared();
        leds.clear(LedRenderer.Layer.ALERT);
        String from = move.getFrom().name();
        String to = move.getTo().name();
        notifyListener(l -> l.onPhysicalMoveDetected(from, to));
        updateBaseLayer(0, 0);
        publish(displayFen(occupancy(logical)), null);
    }

    private void checkForStrayPieces() {
        if (mode != Mode.PLAY) {
            return;
        }
        if (pendingCommit != null && !pendingCommit.isDone()) {
            // a move is about to be committed (e.g. rook-first castling): check again later
            pendingCheck = schedule(this::checkForStrayPieces, errorSettleMs);
            return;
        }
        long stray = physical & ~occupancy(logical);
        if (stray == 0 || onTakebackPath()) {
            leds.clear(LedRenderer.Layer.ALERT);
            return;
        }
        Map<Integer, Integer> alert = new HashMap<>();
        forEachSquare(stray, sq -> alert.put(sq, LedColors.WRONG));
        leds.replace(LedRenderer.Layer.ALERT, alert);
        String errorSquare = Squares.name(Long.numberOfTrailingZeros(stray));
        log.info("Unexpected piece on {}", errorSquare);
        publish(displayFen(occupancy(logical)), errorSquare);
    }

    private void refreshReplication() {
        cancel(pendingCheck);
        long logicalOcc = occupancy(logical);
        long wrongSquares = physical ^ logicalOcc;
        long untouched = replicationRequired & ~touched;
        long todo = (wrongSquares | untouched) & replicationRequired;
        long stray = wrongSquares & ~replicationRequired;
        updateBaseLayer(hardwareConnected ? todo : 0, hardwareConnected ? stray : 0);
        publish(displayFen(logicalOcc), stray != 0 ? Squares.name(Long.numberOfTrailingZeros(stray)) : null);

        boolean done = !hardwareConnected || (wrongSquares == 0 && untouched == 0);
        if (!done && checkTakeback()) {
            return; // the player is taking the move back instead of reproducing the answer
        }
        if (!done) {
            StringBuilder message = new StringBuilder("Muovi l'avversario: ");
            if ((physical & Squares.bit(replicationFrom)) != 0) {
                message.append("solleva da ").append(Squares.name(replicationFrom)).append(' ');
            }
            message.append("posiziona su ").append(Squares.name(replicationTo));
            progress(message.toString());
            return;
        }
        long snapshot = physical;
        pendingCheck = schedule(() -> {
            if (mode == Mode.REPLICATE && physical == snapshot) {
                log.info("Opponent move replicated on the board");
                mode = Mode.PLAY;
                touched = 0;
                replicationRequired = 0;
                updateBaseLayer(0, 0);
                notifyListener(BoardMoveListener::onBotMoveReplicated);
            }
        }, hardwareConnected ? settleMs : 0);
    }

    /** BASE layer: squares of the move to replicate, stray pieces and the king in check. */
    private void updateBaseLayer(long replicate, long stray) {
        Map<Integer, Integer> base = new HashMap<>();
        if (hardwareConnected && logical.isKingAttacked()) {
            base.put(logical.getKingSquare(logical.getSideToMove()).ordinal(), LedColors.CHECK);
        }
        forEachSquare(replicate, sq -> base.put(sq, LedColors.REPLICATE));
        forEachSquare(stray, sq -> base.put(sq, LedColors.WRONG));
        leds.replace(LedRenderer.Layer.BASE, base);
    }

    /** Starts (or keeps) the take-back countdown when the board shows the take-back position. Events thread. */
    private boolean checkTakeback() {
        if (takebackAction == null || takebackPath.isEmpty() || !hardwareConnected) {
            return false;
        }
        Board target = takebackPath.get(takebackPath.size() - 1);
        if (physical != occupancy(target)) {
            cancel(pendingTakeback);
            return false;
        }
        long snapshot = physical;
        cancel(pendingTakeback);
        pendingTakeback = schedule(() -> {
            Runnable action = takebackAction;
            if (action != null && physical == snapshot && (mode == Mode.PLAY || mode == Mode.REPLICATE)) {
                log.info("Take-back made with the pieces");
                clearTakeback();
                callbacks.execute(action);
            }
        }, takebackSettleMs);
        return true;
    }

    /** True when the board shows one of the take-back positions (no stray-piece alarm on the way). */
    private boolean onTakebackPath() {
        for (Board b : takebackPath) {
            if (physical == occupancy(b)) {
                return true;
            }
        }
        return false;
    }

    private void clearTakeback() {
        takebackPath = List.of();
        takebackAction = null;
        cancel(pendingTakeback);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** Occupancy bitboard of a position (bit i = square i). */
    public static long occupancy(Board board) {
        long bits = 0;
        for (int square = 0; square < 64; square++) {
            if (board.getPiece(Square.squareAt(square)) != Piece.NONE) {
                bits |= Squares.bit(square);
            }
        }
        return bits;
    }

    /** Occupancy after {@code move}, computed without touching the board (castling and en passant included). */
    static long occupancyAfter(Board board, long occupancy, Move move) {
        int from = move.getFrom().ordinal();
        int to = move.getTo().ordinal();
        long after = (occupancy & ~Squares.bit(from)) | Squares.bit(to);
        Piece piece = board.getPiece(move.getFrom());
        if (piece.getPieceType() == PieceType.KING && Math.abs(from % 8 - to % 8) == 2) {
            int rank = from / 8;
            boolean kingSide = to % 8 == 6;
            after &= ~Squares.bit(rank * 8 + (kingSide ? 7 : 0));
            after |= Squares.bit(rank * 8 + (kingSide ? 5 : 3));
        } else if (piece.getPieceType() == PieceType.PAWN && from % 8 != to % 8 && (occupancy & Squares.bit(to)) == 0) {
            after &= ~Squares.bit(from / 8 * 8 + to % 8);
        }
        return after;
    }

    /** True for a rook move that is also the rook half of a legal castling (the player may castle rook first). */
    private boolean isCastlingPrefix(Move move) {
        if (logical.getPiece(move.getFrom()).getPieceType() != PieceType.ROOK) {
            return false;
        }
        for (Move candidate : logical.legalMoves()) {
            int from = candidate.getFrom().ordinal();
            int to = candidate.getTo().ordinal();
            if (logical.getPiece(candidate.getFrom()).getPieceType() == PieceType.KING && Math.abs(from % 8 - to % 8) == 2) {
                int rank = from / 8;
                boolean kingSide = to % 8 == 6;
                int rookFrom = rank * 8 + (kingSide ? 7 : 0);
                int rookTo = rank * 8 + (kingSide ? 5 : 3);
                if (move.getFrom().ordinal() == rookFrom && move.getTo().ordinal() == rookTo) {
                    return true;
                }
            }
        }
        return false;
    }

    /** FEN of the physical board with the pieces of the logical position; stray pieces are left out. */
    private String displayFen(long logicalOcc) {
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int square = rank * 8 + file;
                boolean show = (physical & logicalOcc & Squares.bit(square)) != 0;
                if (mode == Mode.REPLICATE && (square == replicationFrom || square == replicationTo)) {
                    show = (logicalOcc & Squares.bit(square)) != 0;
                }
                if (!hardwareConnected) {
                    show = (logicalOcc & Squares.bit(square)) != 0;
                }
                if (show) {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(logical.getPiece(Square.squareAt(square)).getFenSymbol());
                } else {
                    empty++;
                }
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (rank > 0) {
                fen.append('/');
            }
        }
        String[] parts = logical.getFen().split(" ");
        boolean partial = !fen.toString().equals(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            // with pieces in the air (e.g. the king lifted) an en passant square makes the FEN unreadable for
            // chesslib (it checks the capture against the king): drop it from the picture of the board
            fen.append(' ').append(i == 3 && partial ? "-" : parts[i]);
        }
        return fen.toString();
    }

    /** FEN of the physical board during setup, with the pieces the target position has on those squares. */
    private String setupFen() {
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int square = rank * 8 + file;
                if ((physical & Squares.bit(square)) != 0) {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    Piece target = setupTarget.getPiece(Square.squareAt(square));
                    fen.append(target != Piece.NONE ? target.getFenSymbol() : "P");
                } else {
                    empty++;
                }
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (rank > 0) {
                fen.append('/');
            }
        }
        return fen.append(" w KQkq - 0 1").toString();
    }

    private void publish(String fen, String errorSquare) {
        if (fen.equals(lastFen) && java.util.Objects.equals(errorSquare, lastError)) {
            return;
        }
        lastFen = fen;
        lastError = errorSquare;
        notifyListener(l -> l.onBoardStateUpdated(fen, errorSquare));
    }

    private void progress(String message) {
        if (message.equals(lastProgress)) {
            return;
        }
        lastProgress = message;
        notifyListener(l -> l.onSetupProgress(message));
    }

    private void notifyListener(java.util.function.Consumer<BoardMoveListener> call) {
        BoardMoveListener l = listener;
        if (l == null) {
            return;
        }
        callbacks.execute(() -> {
            BoardMoveListener current = listener;
            if (current != null) {
                try {
                    call.accept(current);
                } catch (RuntimeException e) {
                    log.error("Board listener failed", e);
                }
            }
        });
    }

    private static void forEachSquare(long bits, java.util.function.IntConsumer action) {
        for (long b = bits; b != 0; b &= b - 1) {
            action.accept(Long.numberOfTrailingZeros(b));
        }
    }

    private ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        return events.schedule(() -> guarded(task), delayMs, TimeUnit.MILLISECONDS);
    }

    private void post(Runnable task) {
        try {
            events.execute(() -> guarded(task));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("Board events stopped, dropping task");
        }
    }

    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Board state update failed", e);
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    // --- for tests and diagnostics -------------------------------------------------------------------------

    /** Waits until every event posted so far has been processed. */
    public void awaitIdle() {
        try {
            events.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Board events thread stuck", e);
        }
    }

    public Mode mode() {
        return query(() -> mode);
    }

    public long physicalOccupancy() {
        return query(() -> physical);
    }

    public String logicalFen() {
        return query(() -> logical.getFen());
    }

    /** Position the set-up waits for (diagnostics, simulator autoplay). */
    public String setupTargetFen() {
        return query(() -> setupTarget.getFen());
    }

    private <T> T query(java.util.concurrent.Callable<T> callable) {
        try {
            return events.submit(callable).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    public void shutdown() {
        events.shutdownNow();
    }

    /** Default hints: engine-rated destinations via MoveEvaluatorService, or plain legal moves. */
    static final class EngineHints implements HintProvider {
        private final MoveLeds moveLeds;
        private final LedRenderer leds;
        private MoveEvaluatorService evaluator;

        EngineHints(MoveLeds moveLeds, LedRenderer leds) {
            this.moveLeds = moveLeds;
            this.leds = leds;
        }

        @Override
        public void pieceLifted(Board position, Square from, double currentEvaluation, String bestMove, boolean evaluate) {
            if (evaluate) {
                evaluator().startEvaluation(position, from, currentEvaluation, bestMove);
            } else {
                List<String> targets = position.legalMoves().stream()
                        .filter(m -> m.getFrom() == from)
                        .map(m -> m.getTo().name())
                        .distinct()
                        .toList();
                moveLeds.showLegalTargets(from.name(), targets);
            }
        }

        @Override
        public void hintsCleared() {
            if (evaluator != null) {
                evaluator.stopEvaluation();
            }
            moveLeds.clearCandidates();
            leds.clear(LedRenderer.Layer.LEGACY);
        }

        private MoveEvaluatorService evaluator() {
            if (evaluator == null) {
                evaluator = new MoveEvaluatorService();
            }
            return evaluator;
        }
    }
}
