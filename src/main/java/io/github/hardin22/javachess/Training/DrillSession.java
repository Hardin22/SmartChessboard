package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Analysis.BoardFollower;
import io.github.hardin22.javachess.Analysis.MoveText;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Engine.Score;
import io.github.hardin22.javachess.Utils.AppExecutors;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * One endgame drill against the engine (full strength: it defends as well as it can), on the screen or with the
 * real pieces. The drill succeeds or fails by itself:
 * <ul>
 *   <li>{@link EndgameDrills.Goal#MATE}: checkmate within the moves; stalemate, a draw or running out of moves
 *       fail;</li>
 *   <li>{@link EndgameDrills.Goal#PROMOTE}: a promotion the opponent cannot take at once; losing the pawn (a draw)
 *       or running out of moves fail;</li>
 *   <li>{@link EndgameDrills.Goal#DRAW}: a draw by the rules, or the moves held without the opponent promoting
 *       or getting mate, and the engine agreeing the position is still a draw (at most
 *       {@link #DRAW_MARGIN_CP} for the opponent).</li>
 * </ul>
 * View-model on the JavaFX thread; engine answers come back through {@code fx}.
 */
public final class DrillSession {

    private static final Logger log = LoggerFactory.getLogger(DrillSession.class);

    /** The held position still counts as a draw while the engine gives the opponent at most this. */
    public static final int DRAW_MARGIN_CP = 150;
    static final long NODES = 300_000;
    static final long CAP_MS = 3_000;

    /** Best move of the side to move and its value for that side (centipawns, mate = ±{@link Score#MATE_CP}). */
    public record Reply(String move, int cp) {
    }

    @FunctionalInterface
    public interface Opponent {
        CompletableFuture<Reply> reply(String fen);
    }

    public enum State {
        /** Waiting for the player's move. */
        YOUR_MOVE,
        /** The engine is thinking (its move, a hint or the final check). */
        THINKING,
        SUCCESS,
        FAILED
    }

    private final EndgameDrills.Drill drill;
    private final Opponent opponent;
    private final Executor fx;
    private final BoardFollower follower;
    private final DrillProgress progress;
    private final Side playerSide;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.YOUR_MOVE);
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private final ReadOnlyStringWrapper fen = new ReadOnlyStringWrapper(this, "fen");
    private final ReadOnlyStringWrapper lastMove = new ReadOnlyStringWrapper(this, "lastMove");
    private final ReadOnlyStringWrapper shownMove = new ReadOnlyStringWrapper(this, "shownMove");
    private final ReadOnlyIntegerWrapper movesLeft = new ReadOnlyIntegerWrapper(this, "movesLeft");
    private final ReadOnlyIntegerWrapper hints = new ReadOnlyIntegerWrapper(this, "hints");
    private final List<String> played = new ArrayList<>();
    /** The game so far (kept as one board so repetitions are seen). */
    private Board game;
    private int playerMoves;
    private int generation;

    /** Drill on the app's analysis engine; {@code follower} (may be null) brings in the real pieces. */
    public DrillSession(EndgameDrills.Drill drill, BoardFollower follower, DrillProgress progress) {
        this(drill, DrillSession::engineReply, AppExecutors::runOnFx, follower, progress);
    }

    public DrillSession(EndgameDrills.Drill drill, Opponent opponent, Executor fx, BoardFollower follower,
                        DrillProgress progress) {
        this.drill = drill;
        this.opponent = opponent;
        this.fx = fx;
        this.follower = follower;
        this.progress = progress;
        this.playerSide = drill.white() ? Side.WHITE : Side.BLACK;
        restart();
    }

    // ------------------------------------------------------------------ properties

    public EndgameDrills.Drill drill() {
        return drill;
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** "Dai scacco matto in 15 mosse", "Il computer pensa…", "Scacco matto! Ce l'hai fatta in 9 mosse"… */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty fenProperty() {
        return fen.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty lastMoveProperty() {
        return lastMove.getReadOnlyProperty();
    }

    /** Hint arrow (UCI) or null. */
    public ReadOnlyStringProperty shownMoveProperty() {
        return shownMove.getReadOnlyProperty();
    }

    /** Moves of the player still allowed (MATE, PROMOTE) or still to hold (DRAW). */
    public ReadOnlyIntegerProperty movesLeftProperty() {
        return movesLeft.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty hintsProperty() {
        return hints.getReadOnlyProperty();
    }

    public List<String> playedMoves() {
        return List.copyOf(played);
    }

    // ------------------------------------------------------------------ actions

    /** Starts the drill again from its position. */
    public void restart() {
        generation++;
        played.clear();
        playerMoves = 0;
        game = board(drill.fen());
        fen.set(drill.fen());
        lastMove.set(null);
        shownMove.set(null);
        hints.set(0);
        movesLeft.set(drill.moves());
        if (follower != null && follower.isOn()) {
            follower.positionChanged(drill.fen(), null, null);
        }
        if (game.getSideToMove() != playerSide) {
            opponentMove();
        } else {
            state.set(State.YOUR_MOVE);
            message.set(drill.task());
        }
    }

    /** A move of the player on the screen; true when it was played. */
    public boolean play(String uci) {
        return tryMove(uci) != null;
    }

    /** The engine's best move for the player, as an arrow. */
    public void hint() {
        if (state.get() != State.YOUR_MOVE) {
            return;
        }
        int gen = generation;
        state.set(State.THINKING);
        message.set("Cerco la mossa migliore…");
        opponent.reply(fen.get()).whenComplete((r, error) -> fx.execute(() -> {
            if (gen != generation || state.get() != State.THINKING) {
                return;
            }
            state.set(State.YOUR_MOVE);
            if (error != null || r == null || r.move() == null) {
                message.set("Suggerimento non disponibile");
                return;
            }
            hints.set(hints.get() + 1);
            shownMove.set(r.move());
            message.set("Prova " + MoveText.numbered(fen.get(), r.move()));
        }));
    }

    /** Uses the physical board: the LEDs set the position up and show the engine's moves. */
    public void useBoard(boolean on) {
        if (follower == null) {
            return;
        }
        if (on && !follower.isOn()) {
            follower.start(fen.get(), this::tryMove);
        } else if (!on) {
            follower.stop();
        }
    }

    public void close() {
        generation++;
        if (follower != null) {
            follower.stop();
        }
    }

    // ------------------------------------------------------------------ internals

    private String tryMove(String uci) {
        if (state.get() != State.YOUR_MOVE) {
            return null;
        }
        Board b = game;
        Move move = MoveText.legal(b, uci);
        if (move == null) {
            return null;
        }
        String text = MoveText.numbered(fen.get(), move.toString());
        apply(b, move);
        playerMoves++;
        movesLeft.set(Math.max(0, drill.moves() - playerMoves));
        String after = fen.get();
        if (!judgeAfterPlayer(b, move, text)) {
            opponentMove();
        }
        return after;
    }

    /** True when the drill ended with the player's move. */
    private boolean judgeAfterPlayer(Board b, Move move, String text) {
        if (b.isMated()) {
            succeed("Scacco matto! " + (drill.goal() == EndgameDrills.Goal.MATE ? "Ce l'hai fatta in "
                    + moves(playerMoves) : "Hai fatto anche di meglio della patta"));
            return true;
        }
        if (b.isDraw()) {
            if (drill.goal() == EndgameDrills.Goal.DRAW) {
                succeed("Patta: " + drawReason(b) + ". Obiettivo raggiunto");
            } else {
                fail(capitalise(drawReason(b)) + ": la vittoria è sfumata");
            }
            return true;
        }
        if (drill.goal() == EndgameDrills.Goal.PROMOTE && move.getPromotion() != Piece.NONE
                && !attacked(b, move.getTo())) {
            succeed("Promosso! " + text + " in " + moves(playerMoves));
            return true;
        }
        if (playerMoves >= drill.moves()) {
            if (drill.goal() == EndgameDrills.Goal.DRAW) {
                finalDrawCheck();
            } else {
                fail("Mosse finite: riprova, puoi farcela in " + moves(drill.moves()));
            }
            return true;
        }
        return false;
    }

    private void opponentMove() {
        int gen = generation;
        state.set(State.THINKING);
        message.set("Il computer pensa…");
        opponent.reply(fen.get()).whenComplete((r, error) -> fx.execute(() -> {
            if (gen != generation) {
                return;
            }
            if (error != null || r == null || r.move() == null) {
                log.warn("drill {}: no engine move ({})", drill.id(), error == null ? "none" : error.toString());
                state.set(State.YOUR_MOVE);
                message.set("Il motore non risponde: tocca \"Ricomincia\"");
                return;
            }
            String before = fen.get();
            Board b = game;
            Move move = MoveText.legal(b, r.move());
            if (move == null) {
                state.set(State.YOUR_MOVE);
                message.set(drill.task());
                return;
            }
            String text = MoveText.numbered(before, move.toString());
            apply(b, move);
            if (follower != null && follower.isOn()) {
                follower.positionChanged(fen.get(), before, move.toString());
            }
            if (b.isMated()) {
                fail("Scacco matto: hai perso. Riprova");
            } else if (b.isDraw()) {
                if (drill.goal() == EndgameDrills.Goal.DRAW) {
                    succeed("Patta: " + drawReason(b) + ". Obiettivo raggiunto");
                } else {
                    fail(capitalise(drawReason(b)) + ": la vittoria è sfumata");
                }
            } else if (drill.goal() == EndgameDrills.Goal.DRAW && move.getPromotion() != Piece.NONE) {
                fail("Il pedone è arrivato a promozione: riprova");
            } else {
                state.set(State.YOUR_MOVE);
                message.set("Il computer ha giocato " + text + " · " + remaining());
            }
        }));
    }

    /** DRAW drills: the moves are held; the engine says whether the position is still a draw. */
    private void finalDrawCheck() {
        int gen = generation;
        state.set(State.THINKING);
        message.set("Controllo la posizione…");
        opponent.reply(fen.get()).whenComplete((r, error) -> fx.execute(() -> {
            if (gen != generation) {
                return;
            }
            if (error != null || r == null || r.cp() <= DRAW_MARGIN_CP) {
                succeed("Patta tenuta per " + moves(drill.moves()) + ". Obiettivo raggiunto");
            } else {
                fail("Hai resistito " + moves(drill.moves()) + ", ma ora la posizione è persa: riprova");
            }
        }));
    }

    private void apply(Board b, Move move) {
        b.doMove(move);
        played.add(move.toString());
        fen.set(b.getFen());
        lastMove.set(move.toString());
        shownMove.set(null);
    }

    private void succeed(String text) {
        state.set(State.SUCCESS);
        message.set(text);
        if (progress != null) {
            progress.record(drill.id(), true, playerMoves, hints.get());
        }
    }

    private void fail(String text) {
        state.set(State.FAILED);
        message.set(text);
        if (progress != null) {
            progress.record(drill.id(), false, playerMoves, hints.get());
        }
    }

    private String remaining() {
        int left = movesLeft.get();
        return switch (drill.goal()) {
            case DRAW -> left == 1 ? "manca 1 mossa" : "mancano " + left + " mosse";
            default -> left == 1 ? "ultima mossa" : "restano " + left + " mosse";
        };
    }

    /** True when the side to move can capture on {@code square}. */
    private static boolean attacked(Board b, Square square) {
        for (Move m : b.legalMoves()) {
            if (m.getTo() == square) {
                return true;
            }
        }
        return false;
    }

    private static String drawReason(Board b) {
        if (b.isStaleMate()) {
            return "stallo";
        }
        if (b.isInsufficientMaterial()) {
            return "materiale insufficiente";
        }
        if (b.isRepetition()) {
            return "ripetizione della posizione";
        }
        return "regola delle 50 mosse";
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String moves(int n) {
        return n == 1 ? "1 mossa" : n + " mosse";
    }

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    /** The app's analysis engine at full strength. */
    static CompletableFuture<Reply> engineReply(String fen) {
        try {
            return PositionAnalyzer.get().searchBest(fen, NODES, CAP_MS).thenApply(r -> {
                Score s = r.score();
                int cp = s == null ? 0 : s.mate() ? (s.value() > 0 || s.delivered() ? Score.MATE_CP : -Score.MATE_CP)
                        : s.value();
                return new Reply(r.bestMove(), cp);
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
