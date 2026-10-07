package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Utils.PgnCodec;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.PositionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Keeps the physical board in step with the game on the page.
 *
 * <ul>
 *   <li><b>Setup</b>: the first position seen on the page becomes the target; the LEDs show where pieces are missing
 *       or extra until the board matches it.</li>
 *   <li><b>Moves on the board</b> (the user's side, or both on an analysis board) are played on the page and stay
 *       "pending" until the page shows them; a move the page does not show within a few seconds is sent again,
 *       then reported as not accepted and taken back on the LEDs.</li>
 *   <li><b>Moves on the page</b> (the opponent's, or the user's own made on the screen) are recognised among the
 *       legal continuations ({@link PositionResolver}, tolerant to a few misread squares) and shown on the board
 *       for the user to reproduce.</li>
 *   <li><b>Two readings</b>: the primary reading (vision by default) is confirmed by the other one (the page's
 *       markup) when available. When they disagree nothing is applied for {@value #DISAGREE_MS} ms; if they still
 *       disagree the page's markup wins. A misread frame therefore never becomes a move.</li>
 *   <li><b>Recovery</b>: a position that no legal move explains is a take-back when it matches an earlier position
 *       of the game, otherwise the board is set up again (at once for a certain reading, after
 *       {@value #UNRESOLVED_BEFORE_RESYNC} identical readings for vision).</li>
 *   <li><b>End</b>: mate/stalemate or the result shown by the page end the game. Real games (game page, standard
 *       start, at least {@value #MIN_PLIES_TO_SAVE} plies) are archived, also when the synchronisation stops
 *       mid-game; analysis, puzzles and positions joined midway are not ({@link #isArchivable()}).</li>
 * </ul>
 *
 * <p>Not thread-safe: every method must be called on the owner's thread; asynchronous completions and board events
 * are brought back to it through the {@code owner} executor.</p>
 */
public final class OnlineGameSync {

    private static final Logger log = LoggerFactory.getLogger(OnlineGameSync.class);

    public enum Mode {
        /** A game: only the user's side (the side at the bottom of the screen) is moved on the board. */
        PLAY,
        /** An analysis board: both sides are moved on the board. */
        ANALYSIS
    }

    public enum Phase {
        IDLE,
        /** Waiting for the first position from the page. */
        WAITING_BOARD,
        /** The user is placing the pieces as on the screen. */
        SETUP,
        /** The user may move on the board. */
        MY_TURN,
        /** The user's move is being played on the page. */
        SENDING,
        /** Waiting for the opponent's move on the page. */
        OPPONENT_TURN,
        /** A move made on the page must be reproduced on the board. */
        REPLICATE,
        /** The page did not take the user's move: it must be taken back on the board. */
        NOT_ACCEPTED,
        /** The page shows a position no move explains (vision misreading or something unexpected). */
        UNCERTAIN,
        GAME_OVER
    }

    /** What the synchronisation is doing, for the status shown to the user. */
    public record State(Phase phase, Mode mode, Side mySide, Side toMove, String lastMove, String from, String to,
                        String detail, String result, int plies, boolean boardConnected, boolean saved) {
    }

    public interface Listener {
        void onSyncState(State state);
    }

    /** Plays a move (UCI) on the page; completes when it was sent. */
    public interface MoveSender {
        CompletableFuture<Void> send(String uci);
    }

    static final long CONFIRM_MS = 3000;
    /** Vision needs still frames to read a move (and the opponent may answer at once): wait longer. */
    static final long CONFIRM_VISION_MS = 5000;
    static final int SEND_ATTEMPTS = 2;
    static final long DISAGREE_MS = 1500;
    static final int UNRESOLVED_BEFORE_RESYNC = 3;
    static final int MIN_PLIES_TO_SAVE = 6;
    private static final int MAX_TAKEBACK = 6;

    private final PhysicalBoard board;
    private final MoveSender sender;
    private final Consumer<ArchivedGame> archive;
    private final Listener listener;
    private final LongSupplier clock;
    private final Executor owner;
    private final PositionResolver resolver = new PositionResolver();

    private Mode mode = Mode.PLAY;
    private PageInfo page = PageInfo.of("");
    private Phase phase = Phase.IDLE;
    private Side mySide = Side.WHITE;
    private long session;

    private SetupPosition.Result setup;
    private Board internal = new Board();
    private String initialFen = PgnCodec.START_FEN;
    private final List<String> moves = new ArrayList<>();

    private Move pending;
    private long pendingSince;
    private int attempts;

    private String lastSan;
    private String lastFrom;
    private String lastTo;
    private String detail;
    private String result;
    private boolean saved;

    private String unresolvedPlacement;
    private int unresolvedCount;
    private long disagreeSince = -1;
    private BoardWatcher.PositionUpdate lastUpdate;
    private BoardWatcher.ReadMode readMode = BoardWatcher.ReadMode.PAGE;
    private State lastState;
    private int pageFallbacks;

    /**
     * @param owner executor of the thread that owns this object (board events and send results are posted to it)
     */
    public OnlineGameSync(PhysicalBoard board, MoveSender sender, Consumer<ArchivedGame> archive, Listener listener,
                          LongSupplier clock, Executor owner) {
        this.board = board;
        this.sender = sender;
        this.archive = archive;
        this.listener = listener;
        this.clock = clock;
        this.owner = owner;
    }

    public Phase phase() {
        return phase;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    /** Position being followed (a copy). */
    public Board position() {
        return internal.clone();
    }

    public List<String> moves() {
        return List.copyOf(moves);
    }

    /** How many times the page's markup had to stand in for a disagreeing vision reading. */
    public int pageFallbacks() {
        return pageFallbacks;
    }

    // ------------------------------------------------------------------ control

    public void start(Mode mode, PageInfo page) {
        if (phase != Phase.IDLE) {
            stop();
        }
        this.mode = mode;
        this.page = page;
        long id = ++session;
        resetGame();
        phase = Phase.WAITING_BOARD;
        board.attach(new PhysicalBoard.Listener() {
            @Override
            public void onPhysicalMove(String from, String to) {
                owner.execute(() -> {
                    if (session == id) {
                        physicalMove(from, to);
                    }
                });
            }

            @Override
            public void onSetupComplete() {
                owner.execute(() -> {
                    if (session == id) {
                        setupComplete();
                    }
                });
            }

            @Override
            public void onSetupProgress(String message) {
                owner.execute(() -> {
                    if (session == id && phase == Phase.SETUP) {
                        detail = message;
                        emit();
                    }
                });
            }

            @Override
            public void onReplicated() {
                owner.execute(() -> {
                    if (session == id && phase == Phase.REPLICATE) {
                        phase = turnPhase();
                        emit();
                    }
                });
            }
        });
        log.info("Online synchronisation started ({}, {})", mode, page.url());
        emit();
    }

    /** Ends the synchronisation; a game in progress is archived as interrupted. */
    public void stop() {
        if (phase == Phase.IDLE) {
            return;
        }
        session++;
        archiveIfNeeded();
        board.detach();
        phase = Phase.IDLE;
        log.info("Online synchronisation stopped");
        emit();
    }

    private void resetGame() {
        setup = null;
        internal = new Board();
        initialFen = PgnCodec.START_FEN;
        moves.clear();
        pending = null;
        lastSan = null;
        lastFrom = null;
        lastTo = null;
        detail = null;
        result = null;
        saved = false;
        unresolvedPlacement = null;
        unresolvedCount = 0;
        disagreeSince = -1;
        lastUpdate = null;
    }

    // ------------------------------------------------------------------ page

    /** A change of the position on the page. */
    public void onPosition(BoardWatcher.PositionUpdate update) {
        if (phase == Phase.IDLE || update.snapshot().board() == null || update.primary() == null) {
            return;
        }
        lastUpdate = update;
        readMode = update.mode();
        BoardSnapshot.BoardView view = update.snapshot().board();
        if (mode == Mode.PLAY && view.bottomSide() != mySide && moves.isEmpty()) {
            mySide = view.bottomSide(); // the site turns the board when the game starts
            log.info("Playing {} (board orientation)", mySide);
            if ((phase == Phase.MY_TURN || phase == Phase.OPPONENT_TURN) && pending == null) {
                board.play(internal, movingSide());
                phase = turnPhase();
            }
        }
        switch (phase) {
            case WAITING_BOARD, SETUP -> {
                BoardReading reading = decidePlacement(update);
                if (reading != null) {
                    beginSetup(reading.placement(), view);
                }
            }
            case GAME_OVER -> {
                BoardReading reading = decidePlacement(update);
                if (reading != null && !reading.placement().equals(SetupPosition.placement(internal))) {
                    log.info("New position after the end of the game: new game");
                    beginSetup(reading.placement(), view);
                }
            }
            default -> follow(update);
        }
    }

    /** Called a few times per second: resends moves the page did not show, settles disagreements. */
    public void tick() {
        if (phase == Phase.IDLE) {
            return;
        }
        long now = clock.getAsLong();
        long confirm = readMode == BoardWatcher.ReadMode.PAGE ? CONFIRM_MS : CONFIRM_VISION_MS;
        if (pending != null && now - pendingSince >= confirm) {
            if (attempts < SEND_ATTEMPTS) {
                attempts++;
                pendingSince = now;
                log.info("The page does not show {} yet: sending it again", PgnCodec.toUci(pending));
                send(pending);
            } else {
                notAccepted();
            }
        }
        if (disagreeSince >= 0 && now - disagreeSince >= DISAGREE_MS && lastUpdate != null) {
            onPosition(lastUpdate);
        }
    }

    /**
     * The placement to use for a setup: the two readings must agree; when they keep disagreeing the page's
     * markup (certain) wins. Null = wait.
     */
    private BoardReading decidePlacement(BoardWatcher.PositionUpdate update) {
        BoardReading primary = update.primary();
        BoardReading check = update.check();
        if (check == null || check.placement().equals(primary.placement())) {
            disagreeSince = -1;
            return primary;
        }
        BoardReading page = update.page() != null ? update.page() : check;
        if (update.mode() == BoardWatcher.ReadMode.PAGE) {
            disagreeSince = -1;
            return page;
        }
        return waitOrPage(page, "setup " + primary.placement() + " vs " + page.placement());
    }

    private BoardReading waitOrPage(BoardReading page, String what) {
        long now = clock.getAsLong();
        if (disagreeSince < 0) {
            disagreeSince = now;
            return null;
        }
        if (now - disagreeSince < DISAGREE_MS) {
            return null;
        }
        disagreeSince = -1;
        pageFallbacks++;
        log.warn("Vision and page still disagree after {} ms, using the page ({})", DISAGREE_MS, what);
        return page;
    }

    private void follow(BoardWatcher.PositionUpdate update) {
        BoardReading primary = update.primary();
        BoardReading check = update.check();
        PositionResolver.Resolution res = resolver.resolve(internal, primary, true);
        boolean certain = primary == update.page();
        if (check != null) {
            PositionResolver.Resolution other = resolver.resolve(internal, check, true);
            if (sameOutcome(res, other)) {
                disagreeSince = -1;
                if (!res.confident() && check == update.page()) {
                    res = other; // neither explains the position: the page's reading is the exact one
                    primary = check;
                    certain = true;
                }
            } else if (update.mode() == BoardWatcher.ReadMode.PAGE) {
                disagreeSince = -1;
                log.info("Vision disagrees with the page: vision {} ({}), page {} ({})", outcome(other),
                        check.placement(), outcome(res), primary.placement());
            } else {
                BoardReading page = waitOrPage(check, "vision " + outcome(res) + ", page " + outcome(other));
                if (page == null) {
                    return; // wait for the readings to agree
                }
                res = other;
                primary = page;
                certain = true;
            }
        }
        BoardSnapshot.BoardView view = update.snapshot().board();
        if (res.unchanged() && res.confident()) {
            unresolvedCount = 0;
            if (phase == Phase.UNCERTAIN) {
                phase = pending != null ? Phase.SENDING : turnPhase();
                detail = null;
                emit();
            }
            checkPageResult(view);
            return;
        }
        if (!res.confident() || res.moves().isEmpty()) {
            unresolved(primary, certain, view);
            return;
        }
        unresolvedCount = 0;
        apply(res.moves());
        checkPageResult(view);
    }

    private static boolean sameOutcome(PositionResolver.Resolution a, PositionResolver.Resolution b) {
        if (a.confident() != b.confident()) {
            return a.unchanged() && b.unchanged();
        }
        return !a.confident() || a.moves().equals(b.moves());
    }

    private static String outcome(PositionResolver.Resolution r) {
        if (r.unchanged()) {
            return "no move";
        }
        return (r.confident() ? "" : "uncertain ") + r.moves();
    }

    private void apply(List<Move> seen) {
        Move toReplicate = null;
        for (Move m : seen) {
            String san = PgnCodec.toSan(internal, m);
            internal.doMove(m);
            moves.add(PgnCodec.toUci(m));
            lastSan = san;
            lastFrom = m.getFrom().name().toLowerCase();
            lastTo = m.getTo().name().toLowerCase();
            if (pending != null && m.equals(pending)) {
                log.info("The page shows the move played on the board: {}", san);
                pending = null;
            } else {
                log.info("Move on the page: {}", san);
                toReplicate = m;
            }
        }
        if (pending != null) {
            // the page shows another move than the one played on the board (e.g. made on the screen)
            log.info("The page shows {} instead of {}", seen, pending);
            pending = null;
        }
        detail = null;
        String forced = PgnCodec.forcedResultOf(internal);
        if (toReplicate != null) {
            phase = Phase.REPLICATE; // before asking: a board without sensors answers at once
            board.replicate(internal, toReplicate.getFrom().name(), toReplicate.getTo().name());
        } else {
            phase = turnPhase();
        }
        if (forced != null) {
            finish(forced);
            return;
        }
        emit();
    }

    private void unresolved(BoardReading reading, boolean certain, BoardSnapshot.BoardView view) {
        String placement = reading.placement();
        int back = takebackPlies(placement);
        if (back > 0) {
            log.info("Position of {} plies ago on the page: take-back", back);
            takeBack(back, view);
            return;
        }
        if (placement.equals(unresolvedPlacement)) {
            unresolvedCount++;
        } else {
            unresolvedPlacement = placement;
            unresolvedCount = 1;
        }
        if (certain || unresolvedCount >= UNRESOLVED_BEFORE_RESYNC) {
            log.info("The page shows a position no move explains ({}): setting the board up again", placement);
            unresolvedCount = 0;
            beginSetup(placement, view);
            return;
        }
        List<String> diff = PositionResolver.differences(internal, reading);
        detail = String.join(" ", diff.subList(0, Math.min(6, diff.size())));
        log.info("Uncertain reading: {} differs on {}", placement, diff);
        phase = Phase.UNCERTAIN;
        emit();
    }

    /** How many plies back the game was in this placement (0 when it never was, within a few plies). */
    private int takebackPlies(String placement) {
        if (moves.isEmpty()) {
            return 0;
        }
        Board b = PgnCodec.boardOrStart(initialFen);
        List<String> placements = new ArrayList<>();
        placements.add(SetupPosition.placement(b));
        for (String uci : moves) {
            Move m = PgnCodec.fromUci(b, uci);
            if (m == null) {
                return 0;
            }
            b.doMove(m);
            placements.add(SetupPosition.placement(b));
        }
        int last = placements.size() - 1;
        for (int k = 1; k <= Math.min(MAX_TAKEBACK, last); k++) {
            if (placements.get(last - k).equals(placement)) {
                return k;
            }
        }
        return 0;
    }

    private void takeBack(int plies, BoardSnapshot.BoardView view) {
        List<String> kept = new ArrayList<>(moves.subList(0, moves.size() - plies));
        PgnCodec.Replay replay = PgnCodec.replay(initialFen, kept);
        pending = null;
        setup = new SetupPosition.Result(replay.board(), initialFen, kept, "take-back");
        startSetup(view);
    }

    private void checkPageResult(BoardSnapshot.BoardView view) {
        if (view.result() != null && phase != Phase.GAME_OVER) {
            finish(view.result());
        }
    }

    // ------------------------------------------------------------------ setup

    private void beginSetup(String placement, BoardSnapshot.BoardView view) {
        if (phase == Phase.SETUP && setup != null && SetupPosition.placement(setup.board()).equals(placement)) {
            return;
        }
        SetupPosition.Result target = SetupPosition.build(placement, view, page,
                mode == Mode.PLAY ? mySide : Side.WHITE);
        if (target == null) {
            log.info("Not a valid position on the page: {}", placement);
            detail = null;
            phase = Phase.UNCERTAIN;
            emit();
            return;
        }
        archiveIfNeeded(); // the page moved on to another game or position
        resetGame();
        setup = target;
        log.info("Board setup: {} (turn from {}{})", target.board().getFen(), target.turnSource(),
                target.moves().isEmpty() ? "" : ", " + target.moves().size() + " moves known");
        startSetup(view);
    }

    private void startSetup(BoardSnapshot.BoardView view) {
        phase = Phase.SETUP;
        detail = null;
        result = null;
        board.setup(setup.board().getFen());
        emit();
    }

    private void setupComplete() {
        if (phase != Phase.SETUP || setup == null) {
            return;
        }
        internal = setup.board().clone();
        initialFen = setup.initialFen();
        moves.clear();
        moves.addAll(setup.moves());
        detail = null;
        board.play(internal, movingSide());
        phase = turnPhase();
        log.info("Board set up, {} to move", internal.getSideToMove());
        String forced = PgnCodec.forcedResultOf(internal);
        if (forced != null) {
            finish(forced);
            return;
        }
        emit();
    }

    // ------------------------------------------------------------------ board

    private void physicalMove(String from, String to) {
        boolean allowed = phase == Phase.MY_TURN || phase == Phase.NOT_ACCEPTED
                || (mode == Mode.ANALYSIS && (phase == Phase.OPPONENT_TURN || phase == Phase.UNCERTAIN));
        if (!allowed) {
            log.info("Move {}-{} on the board ignored ({})", from, to, phase);
            board.setPosition(internal);
            return;
        }
        Move move = PgnCodec.fromUci(internal, (from + to).toLowerCase());
        if (move == null) {
            log.info("Illegal move on the board: {}-{}", from, to);
            board.setPosition(internal);
            return;
        }
        pending = move;
        pendingSince = clock.getAsLong();
        attempts = 1;
        lastSan = PgnCodec.toSan(internal, move);
        lastFrom = from.toLowerCase();
        lastTo = to.toLowerCase();
        detail = null;
        phase = Phase.SENDING;
        log.info("Move on the board: {}, playing it on the page", lastSan);
        emit();
        send(move);
    }

    private void send(Move move) {
        long id = session;
        sender.send(PgnCodec.toUci(move)).whenComplete((v, error) -> {
            if (error != null) {
                owner.execute(() -> {
                    if (session == id && Objects.equals(pending, move)) {
                        log.warn("Could not play {} on the page: {}", PgnCodec.toUci(move), error.toString());
                        if (attempts >= SEND_ATTEMPTS) {
                            notAccepted();
                        }
                    }
                });
            }
        });
    }

    private void notAccepted() {
        log.warn("The page did not take {}: taking it back on the board", PgnCodec.toUci(pending));
        detail = lastFrom;
        pending = null;
        board.setPosition(internal);
        phase = Phase.NOT_ACCEPTED;
        emit();
    }

    // ------------------------------------------------------------------ end

    private void finish(String gameResult) {
        result = gameResult;
        pending = null;
        log.info("Game over: {}", gameResult);
        archiveIfNeeded();
        phase = Phase.GAME_OVER;
        emit();
    }

    /**
     * Only real games go to the archive: a game page (not analysis, puzzles, editor or TV), followed from the
     * standard start position (or with the whole history read from the page's move list) and long enough.
     */
    boolean isArchivable() {
        return mode == Mode.PLAY && page.kind() == PageInfo.Kind.GAME
                && PgnCodec.START_FEN.split(" ")[0].equals(initialFen.split(" ")[0])
                && moves.size() >= MIN_PLIES_TO_SAVE;
    }

    private void archiveIfNeeded() {
        if (saved || moves.isEmpty()) {
            return;
        }
        if (!isArchivable()) {
            log.info("Not archived ({} on a {} page, {} plies from {})", mode, page.kind(), moves.size(),
                    initialFen);
            return;
        }
        saved = true;
        String label = switch (page.site()) {
            case CHESS_COM -> "Chess.com";
            case LICHESS -> "Lichess (browser)";
            case OTHER -> "Online (browser)";
        };
        ArchivedGame game = new ArchivedGame(0, ArchivedGame.GameMode.BROWSER, label, "", "",
                result != null ? result : "*", result != null ? "" : "Interrotta", "", "", LocalDateTime.now(),
                initialFen, internal.getFen(), List.copyOf(moves));
        log.info("Online game archived ({} plies, {})", moves.size(), game.result());
        archive.accept(game);
    }

    // ------------------------------------------------------------------ state

    private Side movingSide() {
        return mode == Mode.ANALYSIS ? null : mySide;
    }

    private Phase turnPhase() {
        if (result != null) {
            return Phase.GAME_OVER;
        }
        if (mode == Mode.ANALYSIS) {
            return Phase.MY_TURN;
        }
        return internal.getSideToMove() == mySide ? Phase.MY_TURN : Phase.OPPONENT_TURN;
    }

    public State state() {
        return new State(phase, mode, mySide, internal.getSideToMove(), lastSan, lastFrom, lastTo, detail, result,
                moves.size(), board.isConnected(), saved);
    }

    private void emit() {
        State s = state();
        if (!s.equals(lastState)) {
            lastState = s;
            listener.onSyncState(s);
        }
    }
}
