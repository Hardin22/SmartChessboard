package org.example.javachess.Oggetti;

import javafx.application.Platform;
import javafx.scene.control.Label;
import org.example.javachess.Utils.AppExecutors;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Clocks of a player-vs-player game shown in two labels.
 *
 * <p>Time comes from {@link ChessClock} (monotonic, no drift). The shared scheduler wakes up only when the
 * visible text can change (every second, every tenth in the last ten seconds) and the label is touched only
 * if its text really changed, so the clock costs one small repaint per second on the Raspberry Pi.</p>
 */
public class ChessTimer {

    private static final String ACTIVE_STYLE = "stopwatch-active";

    private final Label whiteLabel;
    private final Label blackLabel;
    private final PvpGame pvpGame;
    private final ChessClock clock;
    private ScheduledFuture<?> tick;
    private boolean flagged;

    public ChessTimer(Label whiteLabel, Label blackLabel, int gameDurationSeconds, int incrementSeconds, PvpGame pvpGame) {
        this.whiteLabel = whiteLabel;
        this.blackLabel = blackLabel;
        this.pvpGame = pvpGame;
        this.clock = new ChessClock(gameDurationSeconds, incrementSeconds);
    }

    public void initializetimer() {
        refreshLabels();
    }

    public synchronized void startWhiteTimer() {
        start(ChessClock.Side.WHITE);
    }

    public synchronized void startBlackTimer() {
        start(ChessClock.Side.BLACK);
    }

    public synchronized void stopWhiteTimer() {
        stop(ChessClock.Side.WHITE);
    }

    public synchronized void stopBlackTimer() {
        stop(ChessClock.Side.BLACK);
    }

    public void addIncrementToWhite() {
        clock.addIncrement(ChessClock.Side.WHITE);
        refreshLabels();
    }

    public void addIncrementToBlack() {
        clock.addIncrement(ChessClock.Side.BLACK);
        refreshLabels();
    }

    public ChessClock clock() {
        return clock;
    }

    private void start(ChessClock.Side side) {
        if (flagged) {
            return;
        }
        clock.start(side);
        setActive(side);
        scheduleTick();
    }

    private void stop(ChessClock.Side side) {
        clock.stop(side);
        if (clock.running() == null) {
            cancelTick();
            setActive(null);
        }
        refreshLabels();
    }

    private synchronized void scheduleTick() {
        cancelTick();
        long delay = clock.nanosUntilDisplayChange();
        if (delay < 0) {
            return;
        }
        tick = AppExecutors.scheduler().schedule(this::onTick, delay, TimeUnit.NANOSECONDS);
    }

    private synchronized void cancelTick() {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
    }

    private void onTick() {
        ChessClock.Side running = clock.running();
        if (running == null) {
            return;
        }
        refreshLabels();
        if (clock.isFlagged(running)) {
            synchronized (this) {
                if (flagged) {
                    return;
                }
                flagged = true;
                clock.stop(null);
                cancelTick();
            }
            String message = running == ChessClock.Side.WHITE ? "Il Nero vince per tempo" : "Il Bianco vince per tempo";
            Platform.runLater(() -> {
                setActive(null);
                pvpGame.endGame(message, true);
            });
            return;
        }
        scheduleTick();
    }

    private void refreshLabels() {
        String white = clock.display(ChessClock.Side.WHITE);
        String black = clock.display(ChessClock.Side.BLACK);
        AppExecutors.runOnFx(() -> {
            if (!white.equals(whiteLabel.getText())) {
                whiteLabel.setText(white);
            }
            if (!black.equals(blackLabel.getText())) {
                blackLabel.setText(black);
            }
        });
    }

    private void setActive(ChessClock.Side side) {
        AppExecutors.runOnFx(() -> {
            setStyle(whiteLabel, side == ChessClock.Side.WHITE);
            setStyle(blackLabel, side == ChessClock.Side.BLACK);
        });
    }

    private static void setStyle(Label label, boolean active) {
        if (active && !label.getStyleClass().contains(ACTIVE_STYLE)) {
            label.getStyleClass().add(ACTIVE_STYLE);
        } else if (!active) {
            label.getStyleClass().removeAll(ACTIVE_STYLE);
        }
    }
}
