package io.github.hardin22.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import io.github.hardin22.javachess.Utils.ConfigManager;
import io.github.hardin22.javachess.Utils.PgnCodec;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays one Lichess game through the Board API: streams the game state, mirrors it on the physical board and
 * sends the moves made on the physical board.
 *
 * <p>Threading: the stream runs on a daemon thread; moves are sent on a single background thread; every
 * {@link UiCallback} method is invoked on the JavaFX thread.
 */
public class LichessGameManager {

    private static final Logger log = LoggerFactory.getLogger(LichessGameManager.class);
    private static final int MAX_RECONNECTS = 5;

    private final String gameId;
    private final BoardStateManager boardStateManager;
    private final LichessClient client;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lichess-moves");
        t.setDaemon(true);
        return t;
    });

    private volatile Board board = PgnCodec.boardOrStart(PgnCodec.START_FEN);
    private volatile String initialFen = PgnCodec.START_FEN;
    private volatile List<String> moves = List.of();
    private volatile boolean isWhite = true;
    private volatile boolean colorKnown;
    private volatile boolean isRunning;
    private volatile boolean gameOver;
    private volatile String result = "*";
    private volatile String termination = "";
    private volatile String whiteName = "?";
    private volatile String blackName = "?";
    private volatile int replicatedMoves = -1;
    private volatile LichessClient.SeekHandle streamHandle;
    private Thread streamThread;
    private UiCallback uiCallback;

    public LichessGameManager(String gameId, BoardStateManager boardStateManager) {
        this(gameId, boardStateManager, new LichessClient());
    }

    public LichessGameManager(String gameId, BoardStateManager boardStateManager, LichessClient client) {
        this.gameId = gameId;
        this.boardStateManager = boardStateManager;
        this.client = client;
    }

    /** Callbacks for the game screen; all of them run on the JavaFX thread. */
    public interface UiCallback {
        void onConnected();

        void onStatusMessage(String message);

        void onMoveMade(String lastMove);

        void onBoardUpdated(String fen, String lastMove, String errorSquare);

        void onBotMoveReplicated();

        /** The game is over: {@code result} is "1-0", "0-1", "1/2-1/2" or "*" (aborted). */
        void onGameEnd(String result);

        void onError(String message);
    }

    public void setUiCallback(UiCallback callback) {
        this.uiCallback = callback;
    }

    public void startGame() {
        if (isRunning) {
            return;
        }
        isRunning = true;
        streamThread = new Thread(this::streamLoop, "lichess-stream-" + gameId);
        streamThread.setDaemon(true);
        streamThread.start();

        if (boardStateManager != null) {
            boardStateManager.setListener(new BoardStateManager.BoardMoveListener() {
                @Override
                public void onPhysicalMoveDetected(String from, String to) {
                    onPhysicalMove(from, to);
                }

                @Override
                public void onBoardSetupComplete() {
                }

                @Override
                public void onSetupProgress(String message) {
                    ui(cb -> cb.onStatusMessage(message));
                }

                @Override
                public void onBoardStateUpdated(String fen, String errorSquare) {
                    ui(cb -> cb.onBoardUpdated(fen, null, errorSquare));
                }

                @Override
                public void onBotMoveReplicated() {
                    log.debug("Opponent move replicated on the board");
                    ui(UiCallback::onBotMoveReplicated);
                }
            });
            boardStateManager.setLogicalBoard(board);
        }
    }

    private void onPhysicalMove(String from, String to) {
        if (gameOver || !colorKnown) {
            return;
        }
        if (!isMyTurn()) {
            log.info("Ignoring physical move {}{}: not our turn", from, to);
            resyncBoardManager();
            return;
        }
        Move move = PgnCodec.fromUci(board, (from + to).toLowerCase()); // pawn to last rank -> queen
        if (move == null) {
            ui(cb -> cb.onStatusMessage("Mossa non valida: " + from.toLowerCase() + to.toLowerCase()));
            resyncBoardManager();
            return;
        }
        sendMove(PgnCodec.toUci(move));
    }

    // ------------------------------------------------------------------ stream

    private void streamLoop() {
        int failures = 0;
        while (isRunning && !gameOver) {
            LichessClient.SeekHandle handle = new LichessClient.SeekHandle();
            streamHandle = handle;
            if (!isRunning) {
                handle.close(); // stop() ran while we were creating the handle
                break;
            }
            try {
                log.info("Connecting to Lichess game {}", gameId);
                client.streamGame(gameId, handle, event -> {
                    try {
                        processEvent(event);
                    } catch (RuntimeException e) {
                        log.error("Cannot process Lichess event {}", event.optString("type"), e);
                    }
                });
                failures = 0; // the server closed the stream normally
            } catch (LichessClient.LichessException e) {
                failures++;
                log.warn("Lichess stream error ({}): {}", failures, e.getMessage());
                boolean fatal = e.getStatus() == 401 || e.getStatus() == 403 || e.getStatus() == 404;
                if (fatal || failures > MAX_RECONNECTS) {
                    ui(cb -> cb.onError(e.getMessage()));
                    isRunning = false;
                    break;
                }
                ui(cb -> cb.onStatusMessage("Connessione persa, nuovo tentativo... (" + e.getMessage() + ")"));
            }
            if (isRunning && !gameOver) {
                try {
                    Thread.sleep(Math.min(10_000, 1000L << Math.min(failures, 4)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Lichess stream for {} finished", gameId);
    }

    void processEvent(JSONObject event) {
        String type = event.optString("type", "");
        switch (type) {
            case "gameFull" -> {
                LichessClient.Player white = LichessClient.parsePlayer(event.optJSONObject("white"));
                LichessClient.Player black = LichessClient.parsePlayer(event.optJSONObject("black"));
                whiteName = white.displayName();
                blackName = black.displayName();
                String fen = event.optString("initialFen", "startpos");
                initialFen = "startpos".equals(fen) || !PgnCodec.isValidFen(fen) ? PgnCodec.START_FEN : fen;
                resolveColor(white, black);
                JSONObject state = event.optJSONObject("state");
                applyState(state == null ? new LichessClient.GameState(List.of(), "started", null, -1, -1)
                        : LichessClient.parseGameState(state), true);
                ui(UiCallback::onConnected);
            }
            case "gameState" -> applyState(LichessClient.parseGameState(event), false);
            case "chatLine", "opponentGone" -> log.debug("Lichess event {}", type);
            default -> log.debug("Ignoring Lichess event type '{}'", type);
        }
    }

    private void resolveColor(LichessClient.Player white, LichessClient.Player black) {
        String me = ConfigManager.getProperty("lichess.username", "").trim().toLowerCase();
        if (me.isEmpty()) {
            try {
                me = client.getAccountId().toLowerCase();
            } catch (LichessClient.LichessException e) {
                log.warn("Cannot read the Lichess account: {}", e.getMessage());
            }
        }
        if (!me.isEmpty() && (me.equals(white.id()) || me.equals(black.id()))) {
            isWhite = me.equals(white.id());
            colorKnown = true;
        } else if (!colorKnown) {
            // Unknown account: assume white so the game is still usable, and tell the user.
            isWhite = true;
            colorKnown = true;
            ui(cb -> cb.onStatusMessage("Impossibile capire il tuo colore: controlla il nome utente Lichess nelle "
                    + "Impostazioni."));
        }
        log.info("Lichess game {}: playing as {}", gameId, isWhite ? "white" : "black");
        if (boardStateManager != null) {
            // only our pieces' moves are taken from the board; the opponent's are replicated
            boardStateManager.setPhysicalMoveSide(isWhite ? Side.WHITE : Side.BLACK);
        }
    }

    private void applyState(LichessClient.GameState state, boolean full) {
        PgnCodec.Replay replay = PgnCodec.replay(initialFen, state.movesUci());
        if (!replay.complete()) {
            log.error("Lichess sent a move we cannot play: {} (game {})", replay.rejectedToken(), gameId);
        }
        board = replay.board();
        moves = List.copyOf(replay.uciMoves());
        String lastMove = moves.isEmpty() ? null : moves.get(moves.size() - 1);
        String fen = board.getFen();
        if (boardStateManager != null) {
            boardStateManager.setLogicalBoard(board);
        }
        ui(cb -> cb.onBoardUpdated(fen, lastMove, null));

        if (state.isOver()) {
            finish(state);
            return;
        }
        replicatedMoves = Math.min(replicatedMoves, moves.size()); // takebacks shrink the move list
        // Opponent just moved (or had moved before we connected): show it on the physical board.
        if (lastMove != null && isMyTurn() && moves.size() > replicatedMoves && boardStateManager != null) {
            replicatedMoves = moves.size();
            String from = lastMove.substring(0, 2).toUpperCase();
            String to = lastMove.substring(2, 4).toUpperCase();
            log.info("Opponent played {}, replicating on the board", lastMove);
            runOnFx(() -> boardStateManager.startBotMoveReplication(from, to));
        } else if (full) {
            replicatedMoves = moves.size();
        }
    }

    private void finish(LichessClient.GameState state) {
        if (gameOver) {
            return;
        }
        gameOver = true;
        result = state.result();
        termination = state.termination();
        log.info("Lichess game {} over: {} ({})", gameId, result, termination);
        String finalResult = result;
        ui(cb -> cb.onGameEnd(finalResult));
        stop();
    }

    /** The board manager applies a physical move at once; when it does not count, give it the real position back. */
    private void resyncBoardManager() {
        if (boardStateManager != null) {
            boardStateManager.setLogicalBoard(board);
        }
    }

    // ------------------------------------------------------------------ commands

    /** Sends a move (UCI) in the background; failures are reported through {@link UiCallback#onError}. */
    public void sendMove(String uciMove) {
        sender.execute(() -> {
            try {
                client.makeMove(gameId, uciMove);
                log.info("Move {} sent", uciMove);
                ui(cb -> cb.onMoveMade(uciMove));
            } catch (LichessClient.LichessException e) {
                log.warn("Move {} rejected: {}", uciMove, e.getMessage());
                resyncBoardManager(); // the board manager already applied it: go back to the real position
                ui(cb -> cb.onError("Mossa " + uciMove + " non accettata: " + e.getMessage()));
            }
        });
    }

    /** Resigns the game (in the background). */
    public void resign() {
        command("abbandono", () -> client.resign(gameId));
    }

    /** Offers (or accepts) a draw. */
    public void offerDraw() {
        command("proposta di patta", () -> client.offerDraw(gameId));
    }

    /** Aborts the game (only possible before both players have moved). */
    public void abort() {
        command("annullamento", () -> client.abort(gameId));
    }

    private interface LichessCall {
        void run() throws LichessClient.LichessException;
    }

    private void command(String what, LichessCall call) {
        sender.execute(() -> {
            try {
                call.run();
            } catch (LichessClient.LichessException e) {
                ui(cb -> cb.onError("Impossibile inviare " + what + ": " + e.getMessage()));
            }
        });
    }

    /** Stops the stream (closing the connection) and the background threads. Idempotent. */
    public void stop() {
        isRunning = false;
        LichessClient.SeekHandle handle = streamHandle;
        if (handle != null) {
            handle.close(); // unblocks the reader
        }
        sender.shutdown();
    }

    // ------------------------------------------------------------------ state

    private boolean isMyTurn() {
        return board.getSideToMove() == (isWhite ? Side.WHITE : Side.BLACK);
    }

    public boolean isWhite() {
        return isWhite;
    }

    public void setPlayerColor(boolean isWhite) {
        this.isWhite = isWhite;
        this.colorKnown = true;
        if (boardStateManager != null) {
            boardStateManager.setPhysicalMoveSide(isWhite ? Side.WHITE : Side.BLACK);
        }
    }

    public String getGameId() {
        return gameId;
    }

    /** Moves played so far (UCI). */
    public List<String> getMovesUci() {
        return new ArrayList<>(moves);
    }

    public String getInitialFen() {
        return initialFen;
    }

    public String getFen() {
        return board.getFen();
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public String getResult() {
        return result;
    }

    public String getTermination() {
        return termination;
    }

    public String getWhiteName() {
        return whiteName;
    }

    public String getBlackName() {
        return blackName;
    }

    // ------------------------------------------------------------------ helpers

    private void ui(java.util.function.Consumer<UiCallback> action) {
        UiCallback cb = uiCallback;
        if (cb != null) {
            runOnFx(() -> action.accept(cb));
        }
    }

    private static void runOnFx(Runnable r) {
        try {
            Platform.runLater(r);
        } catch (IllegalStateException toolkitNotRunning) {
            r.run(); // unit tests
        }
    }
}
