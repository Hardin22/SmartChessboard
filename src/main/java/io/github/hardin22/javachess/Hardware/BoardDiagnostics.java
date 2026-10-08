package io.github.hardin22.javachess.Hardware;

import io.github.hardin22.javachess.Services.BoardStateManager;
import io.github.hardin22.javachess.Utils.AppExecutors;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Self-test of a board, for whoever builds one (the project is open source) or suspects a fault:
 * <ul>
 *   <li><b>LED test</b>: the whole board red, green, blue and white (a dead or dim LED shows at once), then one
 *       square at a time from a1 to h8 with its name on the screen, which checks the LED wiring order
 *       ({@code led.layout}, {@code led.origin}, {@code led.direction});</li>
 *   <li><b>sensor test</b>: the player places and lifts a piece on every square; a square turns green when its
 *       sensor has seen both, a square that reads "occupied" without a piece shows it at once. The screen lists the
 *       squares still to check.</li>
 * </ul>
 * The board is taken over while the test runs (no game, raw sensor events) and given back by {@link #stop()}.
 * View-model on the JavaFX thread.
 */
public final class BoardDiagnostics {

    public static final long COLOR_STEP_MS = 1_200;
    public static final long SQUARE_STEP_MS = 180;
    static final int[] COLORS = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};
    static final String[] COLOR_NAMES = {"Rosso", "Verde", "Blu", "Bianco"};
    /** A square whose sensor reads a piece that has not been checked yet. */
    static final int DETECTED = LedColors.MISSING;
    static final int CHECKED = LedColors.GOOD;

    public enum Phase {
        IDLE,
        /** Colours and the a1-h8 sweep are running. */
        LEDS,
        /** Waiting for a piece on every square. */
        SENSORS,
        /** The sensor test is complete (64 of 64). */
        DONE
    }

    private final BoardStateManager board;
    private final LedRenderer leds;
    private final ScheduledExecutorService timer;
    private final Executor fx;

    private final ReadOnlyObjectWrapper<Phase> phase = new ReadOnlyObjectWrapper<>(this, "phase", Phase.IDLE);
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private final ReadOnlyStringWrapper ledStep = new ReadOnlyStringWrapper(this, "ledStep", "");
    private final ReadOnlyIntegerWrapper checkedCount = new ReadOnlyIntegerWrapper(this, "checkedCount");
    private final ReadOnlyLongWrapper checked = new ReadOnlyLongWrapper(this, "checked");
    private final ReadOnlyLongWrapper occupied = new ReadOnlyLongWrapper(this, "occupied");
    private long placedSeen;
    private long liftedSeen;
    private ScheduledFuture<?> ledTask;
    /** The LED test's current frame (to draw it again). */
    private Map<Integer, Integer> lastLedFrame;
    private long colorStepMs = COLOR_STEP_MS;
    private long squareStepMs = SQUARE_STEP_MS;
    private int generation;

    /** Diagnostics of the app's board. */
    public BoardDiagnostics() {
        this(Hardware.boardState(), Hardware.leds(), AppExecutors.scheduler(), AppExecutors::runOnFx);
    }

    public BoardDiagnostics(BoardStateManager board, LedRenderer leds, ScheduledExecutorService timer, Executor fx) {
        this.board = board;
        this.leds = leds;
        this.timer = timer;
        this.fx = fx;
    }

    /** Tests: shorter LED steps. */
    void setTimings(long colorMs, long squareMs) {
        colorStepMs = colorMs;
        squareStepMs = squareMs;
    }

    // ------------------------------------------------------------------ properties

    public ReadOnlyObjectProperty<Phase> phaseProperty() {
        return phase.getReadOnlyProperty();
    }

    /** What to do or what was found, in Italian. */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** LED test: "Rosso", "Verde", "Blu", "Bianco", then the square lit now ("a1"... "h8"); "" otherwise. */
    public ReadOnlyStringProperty ledStepProperty() {
        return ledStep.getReadOnlyProperty();
    }

    /** Sensor test: squares checked (0-64). */
    public ReadOnlyIntegerProperty checkedCountProperty() {
        return checkedCount.getReadOnlyProperty();
    }

    /** Sensor test: checked squares (bit i = square i, a1 = 0), for the drawn board. */
    public ReadOnlyLongProperty checkedProperty() {
        return checked.getReadOnlyProperty();
    }

    /** Squares the sensors read as occupied now. */
    public ReadOnlyLongProperty occupiedProperty() {
        return occupied.getReadOnlyProperty();
    }

    /** Squares not checked yet ("a3, h7"), at most {@code max} names then "…". */
    public String uncheckedText(int max) {
        return unchecked(checked.get(), max);
    }

    private static String unchecked(long ok, int max) {
        List<String> names = new ArrayList<>();
        long todo = ~ok;
        for (int sq = 0; sq < 64; sq++) {
            if ((todo & Squares.bit(sq)) != 0) {
                names.add(Squares.name(sq).toLowerCase(Locale.ROOT));
            }
        }
        if (names.size() > max) {
            return String.join(", ", names.subList(0, max)) + "…";
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------ actions

    /** Runs the LED test (about 15 seconds); calls nothing when it ends, the phase goes back to IDLE. */
    public void startLedTest() {
        takeOver();
        phase.set(Phase.LEDS);
        message.set("Controlla che ogni casa si accenda del colore indicato");
        int gen = generation;
        runLedStep(gen, 0);
    }

    /** Starts the sensor test: every square has to see a piece placed and lifted. */
    public void startSensorTest() {
        takeOver();
        placedSeen = 0;
        liftedSeen = 0;
        checked.set(0);
        checkedCount.set(0);
        if (!board.isHardwareConnected()) {
            phase.set(Phase.IDLE);
            message.set("Scacchiera non collegata: collega il cavo e riprova");
            return;
        }
        phase.set(Phase.SENSORS);
        occupied.set(board.latestOccupancy()); // never waits for the board thread
        board.setSquareListener(this::onSquare);
        message.set("Appoggia un pezzo su ogni casa e toglilo: la casa diventa verde. Le case bianche risultano "
                + "occupate");
        showSensors(0, occupied.get());
    }

    /** Ends any test: LEDs off, the board given back (idle until a screen starts a game or a set-up). */
    public void stop() {
        generation++;
        cancelLeds();
        board.setSquareListener(null);
        leds.clear(LedRenderer.Layer.ANIMATION);
        leds.clear(LedRenderer.Layer.BASE);
        ledStep.set("");
        phase.set(Phase.IDLE);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Frees the board for the test. Never waits for the board thread (this runs on a tap): the board turns its LEDs
     * off a little later, so the test draws again once that is done.
     */
    private void takeOver() {
        stop();
        board.stopGameMode();
        int gen = generation;
        board.runAfterPending(() -> fx.execute(() -> {
            if (gen == generation) {
                redraw();
            }
        }));
    }

    /** Draws the current state again (after the board cleared its LEDs). */
    private void redraw() {
        if (phase.get() == Phase.LEDS && lastLedFrame != null) {
            leds.replace(LedRenderer.Layer.ANIMATION, lastLedFrame);
        } else if (phase.get() == Phase.SENSORS) {
            occupied.set(board.latestOccupancy()); // the board has caught up: this is what it reads now
            showSensors(checked.get(), occupied.get());
        }
    }

    private void runLedStep(int gen, int step) {
        if (gen != generation) {
            return;
        }
        int colorSteps = COLORS.length;
        if (step < colorSteps) {
            Map<Integer, Integer> all = new HashMap<>();
            for (int sq = 0; sq < 64; sq++) {
                all.put(sq, COLORS[step]);
            }
            lastLedFrame = all;
            leds.replace(LedRenderer.Layer.ANIMATION, all);
            ledStep.set(COLOR_NAMES[step]);
            ledTask = timer.schedule(() -> fx.execute(() -> runLedStep(gen, step + 1)), colorStepMs,
                    TimeUnit.MILLISECONDS);
        } else if (step < colorSteps + 64) {
            int sq = step - colorSteps;
            lastLedFrame = Map.of(sq, LedColors.WHITE);
            leds.replace(LedRenderer.Layer.ANIMATION, lastLedFrame);
            ledStep.set(Squares.name(sq).toLowerCase(Locale.ROOT));
            message.set("Ora si accende una casa alla volta, da a1 a h8: deve essere quella scritta sullo schermo");
            ledTask = timer.schedule(() -> fx.execute(() -> runLedStep(gen, step + 1)), squareStepMs,
                    TimeUnit.MILLISECONDS);
        } else {
            lastLedFrame = null;
            leds.clear(LedRenderer.Layer.ANIMATION);
            ledStep.set("");
            phase.set(Phase.IDLE);
            message.set("Prova dei LED finita. Se una casa non si è accesa o l'ordine era sbagliato, controlla "
                    + "i collegamenti o le impostazioni dei LED");
        }
    }

    private void onSquare(int square, boolean isOccupied) {
        if (phase.get() != Phase.SENSORS) {
            return;
        }
        long bit = Squares.bit(square);
        if (isOccupied) {
            placedSeen |= bit;
            occupied.set(occupied.get() | bit);
        } else {
            liftedSeen |= bit;
            occupied.set(occupied.get() & ~bit);
        }
        long ok = placedSeen & liftedSeen;
        // LEDs and text first, counters and phase last: whoever watches them finds the rest done
        showSensors(ok, occupied.get());
        if (ok == -1L) {
            board.setSquareListener(null);
            message.set("Tutti i 64 sensori funzionano");
            leds.playVictoryWave();
        } else {
            message.set(Long.bitCount(ok) + " case su 64 · mancano: " + unchecked(ok, 6));
        }
        checked.set(ok);
        checkedCount.set(Long.bitCount(ok));
        if (ok == -1L) {
            phase.set(Phase.DONE);
        }
    }

    /** Checked squares green, squares read as occupied (not yet checked) white. */
    private void showSensors(long ok, long occ) {
        Map<Integer, Integer> base = new HashMap<>();
        for (int sq = 0; sq < 64; sq++) {
            long bit = Squares.bit(sq);
            if ((ok & bit) != 0) {
                base.put(sq, CHECKED);
            } else if ((occ & bit) != 0) {
                base.put(sq, DETECTED);
            }
        }
        leds.replace(LedRenderer.Layer.BASE, base);
    }

    private void cancelLeds() {
        if (ledTask != null) {
            ledTask.cancel(false);
            ledTask = null;
        }
    }
}
