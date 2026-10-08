package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Hardware.SetupGuide;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Services.BoardStateManager;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Keeps the physical board and an analysis in step, in both directions:
 * <ul>
 *   <li>a move made with the real pieces is played in the analysis (a variation when it is not the game's move);</li>
 *   <li>when the analysis moves on the screen, the LEDs guide the pieces to the new position: one move forward is
 *       shown as a move to reproduce (from → to, like the computer's moves in a game), one move back as the same
 *       move made backwards (and the captured piece to put back), anything else (jumps, another line) as a
 *       position to set up (squares to fill and to empty, piece by piece for big changes).</li>
 * </ul>
 * The sensors only see where pieces are, not which ones: the guide is by occupancy, as in the game set-up.
 * Callbacks of the board manager arrive on the JavaFX thread, where this class lives.
 */
public final class BoardFollower {

    private static final Logger log = LoggerFactory.getLogger(BoardFollower.class);

    /** What the board is doing. */
    public enum State {
        /** Not following (the board is free). */
        OFF,
        /** Waiting for the pieces to be placed as in the analysis (LEDs on the squares to fix). */
        PLACING,
        /** Waiting for the player to make one move on the board (LEDs from → to). */
        REPLICATING,
        /** In step: a move made on the board is played in the analysis. */
        FOLLOWING
    }

    /** Receives the moves made on the board; returns the position reached, or null when refused. */
    public interface MoveSink {
        String onBoardMove(String uci);
    }

    private final BoardStateManager manager;
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.OFF);
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private MoveSink sink;
    /** Position the board shows, or is being guided to. */
    private String target;
    /** Message of the current state (shown again when a sensor error is fixed). */
    private String baseMessage = "";

    public BoardFollower(BoardStateManager manager) {
        this.manager = manager;
    }

    /** OFF, PLACING, REPLICATING or FOLLOWING. */
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** What to do on the board, in Italian ("Posiziona i pezzi: mancano 3", "Esegui sulla scacchiera: 12. Cf3"). */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    public boolean isOn() {
        return state.get() != State.OFF;
    }

    /** Starts following: the board is guided to {@code fen}, then its moves go to {@code sink}. */
    public void start(String fen, MoveSink sink) {
        this.sink = sink;
        manager.setListener(listener);
        manager.setPhysicalMoveSide(null);
        place(fen);
    }

    /**
     * The analysis moved on the screen to {@code fen}. {@code fromFen} and {@code uci}: the position before and the
     * move, when it was a single move forward (otherwise null).
     */
    public void positionChanged(String fen, String fromFen, String uci) {
        if (!isOn() || fen == null || samePosition(fen, target)) {
            return;
        }
        if (uci != null && fromFen != null && samePosition(fromFen, target)
                && (state.get() == State.FOLLOWING || state.get() == State.REPLICATING)) {
            replicate(fen, fromFen, uci);
        } else if (state.get() == State.FOLLOWING || state.get() == State.REPLICATING) {
            String undone = moveBetween(fen, target);
            if (undone != null) {
                takeBack(fen, undone);
            } else {
                place(fen);
            }
        } else {
            place(fen);
        }
    }

    /** Stops following: LEDs off, the board is free. */
    public void stop() {
        if (!isOn()) {
            return;
        }
        manager.stopGameMode();
        sink = null;
        target = null;
        state.set(State.OFF);
        say("");
    }

    // ------------------------------------------------------------------ internals

    private void place(String fen) {
        target = fen;
        manager.setLogicalBoard(board(fen));
        manager.setSetupTargetFen(fen);
        manager.startSetupMode();
        state.set(State.PLACING);
        say("Disponi i pezzi come sullo schermo: i LED indicano le case");
    }

    private void replicate(String fen, String fromFen, String uci) {
        target = fen;
        manager.setLogicalBoard(board(fen));
        String from = uci.substring(0, 2).toUpperCase(Locale.ROOT);
        String to = uci.substring(2, 4).toUpperCase(Locale.ROOT);
        manager.startBotMoveReplication(from, to);
        state.set(State.REPLICATING);
        say("Esegui sulla scacchiera: " + MoveText.numbered(fromFen, uci));
    }

    /** One move back: the piece goes back from → to on the LEDs, a captured piece comes back on its square. */
    private void takeBack(String fen, String undone) {
        long shownOccupancy = BoardStateManager.occupancy(board(target));
        target = fen;
        Board before = board(fen);
        manager.setLogicalBoard(before);
        String from = undone.substring(2, 4);
        String to = undone.substring(0, 2);
        manager.startBotMoveReplication(from.toUpperCase(Locale.ROOT), to.toUpperCase(Locale.ROOT));
        state.set(State.REPLICATING);
        StringBuilder text = new StringBuilder("Riporta indietro sulla scacchiera: ")
                .append(MoveText.numbered(fen, undone)).append(", da ").append(from).append(" a ").append(to);
        // the captured piece has to come back (next to the destination for en passant)
        int captured = before.getPiece(Square.valueOf(from.toUpperCase(Locale.ROOT))) != Piece.NONE
                ? Squares.parse(from) : -1;
        long returning = BoardStateManager.occupancy(before) & ~shownOccupancy & ~Squares.bit(Squares.parse(to));
        if (captured < 0 && returning != 0) {
            captured = Long.numberOfTrailingZeros(returning);
        }
        if (captured >= 0) {
            text.append(", poi rimetti ").append(SetupGuide.name(before.getPiece(Square.squareAt(captured)), false))
                    .append(" in ").append(Squares.name(captured).toLowerCase(Locale.ROOT));
        }
        say(text.toString());
    }

    /** The legal move that leads from {@code fen} to {@code next}, or null. */
    static String moveBetween(String fen, String next) {
        if (fen == null || next == null) {
            return null;
        }
        Board b = board(fen);
        for (var move : b.legalMoves()) {
            Board copy = b.clone();
            copy.doMove(move);
            if (samePosition(copy.getFen(), next)) {
                return move.toString();
            }
        }
        return null;
    }

    private void following() {
        state.set(State.FOLLOWING);
        say("Muovi i pezzi per provare una variante");
    }

    private void say(String text) {
        baseMessage = text;
        message.set(text);
    }

    private final BoardStateManager.BoardMoveListener listener = new BoardStateManager.BoardMoveListener() {
        @Override
        public void onPhysicalMoveDetected(String fromSquare, String toSquare) {
            if (state.get() != State.FOLLOWING || sink == null) {
                return;
            }
            String uci = (fromSquare + toSquare).toLowerCase(Locale.ROOT);
            String before = target;
            target = after(before, uci); // what the board shows now (the sink may send it elsewhere)
            String reached = sink.onBoardMove(uci);
            if (reached == null) {
                log.info("board move {} refused by the analysis: guiding the board back", uci);
                if (!samePosition(target, before)) { // nobody has moved the analysis back yet
                    String undone = moveBetween(before, target);
                    if (undone != null) {
                        takeBack(before, undone);
                    } else {
                        place(before);
                    }
                }
            } else if (state.get() == State.FOLLOWING) {
                target = reached;
            }
        }

        @Override
        public void onBoardSetupComplete() {
            if (state.get() != State.PLACING) {
                return;
            }
            manager.setLogicalBoard(board(target));
            manager.startGameMode();
            following();
        }

        @Override
        public void onSetupProgress(String text) {
            if (state.get() == State.PLACING && text != null && !text.isBlank()) {
                message.set(text);
            }
        }

        @Override
        public void onBoardStateUpdated(String fen, String errorSquare) {
            if (state.get() == State.OFF) {
                return;
            }
            if (errorSquare != null) {
                message.set("Controlla la casa " + errorSquare.toUpperCase(Locale.ROOT));
            } else if (message.get().startsWith("Controlla la casa")) {
                message.set(baseMessage);
            }
        }

        @Override
        public void onBotMoveReplicated() {
            if (state.get() == State.REPLICATING) {
                following();
            }
        }
    };

    /** Position after {@code uci} from {@code fen} (the same position when the move cannot be read). */
    private static String after(String fen, String uci) {
        Board b = board(fen);
        var m = MoveText.legal(b, uci);
        if (m == null) {
            return fen;
        }
        b.doMove(m);
        return b.getFen();
    }

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    /** Same placement and side to move (counters and castling rights do not matter for the pieces). */
    static boolean samePosition(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String[] x = a.trim().split("\\s+");
        String[] y = b.trim().split("\\s+");
        return x[0].equals(y[0]) && (x.length < 2 || y.length < 2 || x[1].equals(y[1]));
    }
}
