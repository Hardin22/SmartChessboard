package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Oggetti.ChessClock;
import io.github.hardin22.javachess.Utils.AppExecutors;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Observable chess clock for any game (used by the game against the computer; the two-player screen has its own).
 * Time from {@link ChessClock} (monotonic). The timer wakes up only when a text can change (every second, every
 * tenth in the last ten seconds), so it costs one small repaint per second on the Raspberry Pi. Properties change
 * on the JavaFX thread; the control methods may be called from any thread.
 */
public final class GameClock {

    /** Below this the clock is shown as "low" (red in the interface). */
    public static final long LOW_TIME_MS = 20_000;

    private final TimeControl control;
    private final ChessClock clock;
    private final Executor fx;
    private final ScheduledExecutorService scheduler;
    private final ReadOnlyStringWrapper whiteText = new ReadOnlyStringWrapper(this, "whiteText");
    private final ReadOnlyStringWrapper blackText = new ReadOnlyStringWrapper(this, "blackText");
    private final ReadOnlyObjectWrapper<Side> running = new ReadOnlyObjectWrapper<>(this, "running");
    private final ReadOnlyBooleanWrapper whiteLow = new ReadOnlyBooleanWrapper(this, "whiteLow");
    private final ReadOnlyBooleanWrapper blackLow = new ReadOnlyBooleanWrapper(this, "blackLow");
    private final ReadOnlyObjectWrapper<Side> flagged = new ReadOnlyObjectWrapper<>(this, "flagged");
    private Consumer<Side> onFlag = s -> { };
    private ScheduledFuture<?> tick;
    private boolean over;

    public GameClock(TimeControl control) {
        this(control, System::nanoTime, AppExecutors::runOnFx, AppExecutors.scheduler());
    }

    /** For tests: a fake time source, executor and timer (scheduler may be null: call {@link #tick()} by hand). */
    public GameClock(TimeControl control, LongSupplier nanoTime, Executor fx, ScheduledExecutorService scheduler) {
        if (control.isUnlimited()) {
            throw new IllegalArgumentException("no clock for an unlimited game");
        }
        this.control = control;
        this.clock = new ChessClock(control.initialSeconds(), control.incrementSeconds(), nanoTime);
        this.fx = fx;
        this.scheduler = scheduler;
        publish();
    }

    // ------------------------------------------------------------------ properties

    /** "05:00", "00:09.4". */
    public ReadOnlyStringProperty whiteTextProperty() {
        return whiteText.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty blackTextProperty() {
        return blackText.getReadOnlyProperty();
    }

    /** Whose clock is running (null: none). */
    public ReadOnlyObjectProperty<Side> runningProperty() {
        return running.getReadOnlyProperty();
    }

    /** Less than {@value #LOW_TIME_MS} ms left. */
    public ReadOnlyBooleanProperty whiteLowProperty() {
        return whiteLow.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty blackLowProperty() {
        return blackLow.getReadOnlyProperty();
    }

    /** Side that ran out of time (null while nobody did). */
    public ReadOnlyObjectProperty<Side> flaggedProperty() {
        return flagged.getReadOnlyProperty();
    }

    public TimeControl control() {
        return control;
    }

    /** Called once (on the JavaFX thread) when a side runs out of time. */
    public void setOnFlag(Consumer<Side> handler) {
        this.onFlag = handler == null ? s -> { } : handler;
    }

    public long remainingMillis(Side side) {
        return clock.remainingMillis(cs(side));
    }

    // ------------------------------------------------------------------ control

    /** Starts {@code side}'s clock (stopping the other). Ignored once a side has flagged or the clock is stopped. */
    public synchronized void start(Side side) {
        if (over) {
            return;
        }
        clock.start(cs(side));
        publish();
        schedule();
    }

    /** Stops whichever clock runs. */
    public synchronized void pause() {
        clock.stop(null);
        cancel();
        publish();
    }

    /** End of a move by {@code side}: its clock stops and gets the increment. */
    public synchronized void moveMade(Side side) {
        if (over) {
            return;
        }
        clock.stop(cs(side));
        clock.addIncrement(cs(side));
        if (clock.running() == null) {
            cancel();
        }
        publish();
    }

    /** End of the game: everything stops for good. */
    public synchronized void stop() {
        over = true;
        clock.stop(null);
        cancel();
        publish();
    }

    /** Puts back saved times (a resumed game). Nothing runs afterwards. */
    public synchronized void restore(long whiteMillis, long blackMillis) {
        clock.stop(null);
        cancel();
        clock.setRemainingMillis(ChessClock.Side.WHITE, whiteMillis);
        clock.setRemainingMillis(ChessClock.Side.BLACK, blackMillis);
        publish();
    }

    /** Checks the running clock now (called by the timer; tests call it by hand). */
    public void tick() {
        Side flaggedSide = null;
        synchronized (this) {
            ChessClock.Side r = clock.running();
            if (r == null || over) {
                return;
            }
            if (clock.isFlagged(r)) {
                over = true;
                clock.stop(null);
                cancel();
                flaggedSide = side(r);
            } else {
                schedule();
            }
        }
        publish();
        if (flaggedSide != null) {
            Side s = flaggedSide;
            fx.execute(() -> {
                flagged.set(s);
                onFlag.accept(s);
            });
        }
    }

    // ------------------------------------------------------------------ results

    /**
     * Result text when {@code flaggedSide} runs out of time: a draw when the opponent cannot possibly mate
     * (FIDE 6.9), otherwise a win for the opponent.
     */
    public static String flagResult(Board board, Side flaggedSide) {
        Side winner = flaggedSide.flip();
        if (!canMate(board, winner)) {
            return "Patta: tempo scaduto e materiale insufficiente";
        }
        return winner == Side.WHITE ? "Il Bianco vince per tempo" : "Il Nero vince per tempo";
    }

    /** True when {@code side} still has mating material against any defence (FIDE 6.9 helper). */
    public static boolean canMate(Board board, Side side) {
        int minors = 0;
        boolean opponentHasOnlyKing = true;
        for (Square square : Square.values()) {
            if (square == Square.NONE) {
                continue;
            }
            Piece piece = board.getPiece(square);
            if (piece == Piece.NONE || piece.getPieceType() == PieceType.KING) {
                continue;
            }
            if (piece.getPieceSide() != side) {
                opponentHasOnlyKing = false;
                continue;
            }
            switch (piece.getPieceType()) {
                case PAWN, ROOK, QUEEN -> {
                    return true;
                }
                default -> minors++;
            }
        }
        return minors >= 2 || (minors == 1 && !opponentHasOnlyKing);
    }

    // ------------------------------------------------------------------ internals

    private void schedule() {
        cancel();
        if (scheduler == null) {
            return;
        }
        long delay = clock.nanosUntilDisplayChange();
        if (delay >= 0) {
            tick = scheduler.schedule(this::tick, delay, TimeUnit.NANOSECONDS);
        }
    }

    private void cancel() {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
    }

    private void publish() {
        String w = clock.display(ChessClock.Side.WHITE);
        String b = clock.display(ChessClock.Side.BLACK);
        boolean wl = clock.remainingMillis(ChessClock.Side.WHITE) < LOW_TIME_MS;
        boolean bl = clock.remainingMillis(ChessClock.Side.BLACK) < LOW_TIME_MS;
        ChessClock.Side r = clock.running();
        fx.execute(() -> {
            whiteText.set(w);
            blackText.set(b);
            whiteLow.set(wl);
            blackLow.set(bl);
            running.set(r == null ? null : side(r));
        });
    }

    private static ChessClock.Side cs(Side side) {
        return side == Side.WHITE ? ChessClock.Side.WHITE : ChessClock.Side.BLACK;
    }

    private static Side side(ChessClock.Side s) {
        return s == ChessClock.Side.WHITE ? Side.WHITE : Side.BLACK;
    }
}
