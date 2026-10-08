package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Play.GameClock;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.BoardStateManager;
import io.github.hardin22.javachess.Utils.ConfigManager;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Board vision: learning the names of the squares, the way it is done on a real board. Two exercises of 30
 * seconds, seen from White or from Black:
 * <ul>
 *   <li><b>Trova la casa</b>: the screen names a square ("e4") and the player touches it — on the physical board by
 *       placing a piece on it or lifting the one standing there (the sensors read it), or by tapping the screen;</li>
 *   <li><b>Nomina la casa</b>: a LED (and the screen) lights a square and the player picks its name among four.</li>
 * </ul>
 * Right answers flash green on the LEDs, wrong ones red with the right square green. View-model on the JavaFX
 * thread; the best score of each exercise and side is kept.
 */
public final class CoordinateTrainer {

    public static final int SECONDS = 30;
    static final long FLASH_MS = 600;

    public enum Mode {
        FIND("Trova la casa", "find"),
        NAME("Nomina la casa", "name");

        public final String label;
        final String key;

        Mode(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }

    public enum State { READY, PLAYING, FINISHED }

    private final Mode mode;
    private final boolean white;
    private final Random random;
    private final Supplier<GameClock> clockFactory;
    private final BoardStateManager board;
    private final LedRenderer leds;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.READY);
    private final ReadOnlyStringWrapper target = new ReadOnlyStringWrapper(this, "target", "");
    private final ReadOnlyObjectWrapper<List<String>> choices = new ReadOnlyObjectWrapper<>(this, "choices", List.of());
    private final ReadOnlyIntegerWrapper score = new ReadOnlyIntegerWrapper(this, "score");
    private final ReadOnlyIntegerWrapper mistakes = new ReadOnlyIntegerWrapper(this, "mistakes");
    private final ReadOnlyIntegerWrapper best = new ReadOnlyIntegerWrapper(this, "best");
    private final ReadOnlyBooleanWrapper newRecord = new ReadOnlyBooleanWrapper(this, "newRecord");
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private final ReadOnlyStringWrapper timeText = new ReadOnlyStringWrapper(this, "timeText", "00:30");
    private final ReadOnlyStringWrapper wrongSquare = new ReadOnlyStringWrapper(this, "wrongSquare");
    private GameClock clock;
    /** Square of the last answer on the board: the piece put back there is not a new answer. */
    private int undoSquare = -1;

    /** Trainer on the app's board (sensors and LEDs, when connected) and a 30-second clock. */
    public CoordinateTrainer(Mode mode, boolean white) {
        this(mode, white, new Random(), () -> new GameClock(new TimeControl(SECONDS, 0)),
                Hardware.boardState(), Hardware.leds());
    }

    /**
     * @param board sensors (may be null: screen only)
     * @param leds  LEDs (may be null)
     */
    public CoordinateTrainer(Mode mode, boolean white, Random random, Supplier<GameClock> clockFactory,
                             BoardStateManager board, LedRenderer leds) {
        this.mode = mode;
        this.white = white;
        this.random = random;
        this.clockFactory = clockFactory;
        this.board = board;
        this.leds = leds;
        best.set(ConfigManager.getIntProperty(recordKey(), 0));
        message.set(mode == Mode.FIND
                ? "Tocca la casa indicata: appoggia o solleva un pezzo, oppure toccala sullo schermo"
                : "Scegli il nome della casa accesa");
    }

    // ------------------------------------------------------------------ properties

    public Mode mode() {
        return mode;
    }

    /** Board seen from White (true) or from Black: draw it that way, without coordinates. */
    public boolean white() {
        return white;
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** The square asked ("e4"): FIND shows it big; NAME highlights it on the drawn board. */
    public ReadOnlyStringProperty targetProperty() {
        return target.getReadOnlyProperty();
    }

    /** NAME: four names to choose from (one is right); empty in FIND. */
    public ReadOnlyObjectProperty<List<String>> choicesProperty() {
        return choices.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty scoreProperty() {
        return score.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty mistakesProperty() {
        return mistakes.getReadOnlyProperty();
    }

    /** Best score of this exercise and side. */
    public ReadOnlyIntegerProperty bestProperty() {
        return best.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty newRecordProperty() {
        return newRecord.getReadOnlyProperty();
    }

    /** "Giusto!", "No: quella è d5, cercavi e4", "Tempo scaduto: 17 giuste, 2 errori"… */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** "00:24" while playing. */
    public ReadOnlyStringProperty timeTextProperty() {
        return timeText.getReadOnlyProperty();
    }

    /** FIND: the square touched by the last wrong answer (red on the drawn board), null otherwise. */
    public ReadOnlyStringProperty wrongSquareProperty() {
        return wrongSquare.getReadOnlyProperty();
    }

    // ------------------------------------------------------------------ actions

    /** Starts (or restarts) the 30 seconds. */
    public void start() {
        stopClock();
        score.set(0);
        mistakes.set(0);
        newRecord.set(false);
        wrongSquare.set(null);
        undoSquare = -1;
        if (board != null) {
            board.stopGameMode(); // the board is free: no moves, no set-up (its "LEDs off" comes later)
            board.setSquareListener(this::onSquare);
        }
        clock = clockFactory.get();
        timeText.bind(clock.whiteTextProperty());
        clock.setOnFlag(side -> finish()); // called on the JavaFX thread
        state.set(State.PLAYING);
        message.set("");
        next();
        clock.start(Side.WHITE);
        if (board != null) {
            // the board turns its LEDs off on its own thread: light the square again once that is done
            board.runAfterPending(this::relightTarget);
        }
    }

    /** An answer: a square name ("e4"), touched (FIND) or chosen (NAME). */
    public void answer(String square) {
        if (state.get() != State.PLAYING || square == null) {
            return;
        }
        String given = square.toLowerCase(Locale.ROOT);
        String asked = target.get();
        boolean right = given.equals(asked);
        // LEDs, texts and the next square first, the counters last: whoever watches a counter sees the rest done
        if (right) {
            wrongSquare.set(null);
            message.set("Giusto!");
            flash(asked, LedColors.GOOD, null, 0);
        } else if (mode == Mode.FIND) {
            wrongSquare.set(given);
            message.set("No: quella è " + given + ", cercavi " + asked);
            flash(asked, LedColors.GOOD, given, LedColors.ERROR);
        } else {
            message.set("No: era " + asked);
            flash(asked, LedColors.GOOD, null, 0);
        }
        next();
        if (right) {
            score.set(score.get() + 1);
        } else {
            mistakes.set(mistakes.get() + 1);
        }
    }

    /** Ends early (leaving the screen) or when the time is up. */
    public void finish() {
        if (state.get() != State.PLAYING) {
            return;
        }
        stopClock();
        state.set(State.FINISHED);
        clearLeds();
        if (board != null) {
            board.setSquareListener(null);
        }
        int s = score.get();
        String result = s + (s == 1 ? " giusta" : " giuste") + ", " + mistakes.get()
                + (mistakes.get() == 1 ? " errore" : " errori");
        if (s > best.get()) {
            newRecord.set(best.get() > 0);
            best.set(s);
            ConfigManager.setProperty(recordKey(), String.valueOf(s));
        }
        message.set((newRecord.get() ? "Nuovo record! " : "Tempo scaduto: ") + result);
    }

    /** Leaves the screen: clock stopped, LEDs off, sensors released. */
    public void close() {
        if (state.get() == State.PLAYING) {
            stopClock();
            state.set(State.FINISHED);
        }
        clearLeds();
        if (board != null) {
            board.setSquareListener(null);
        }
    }

    // ------------------------------------------------------------------ internals

    private void onSquare(int square, boolean occupied) {
        if (mode != Mode.FIND || state.get() != State.PLAYING) {
            return;
        }
        if (square == undoSquare) {
            undoSquare = -1; // the piece put back (or lifted again) after the answer
            return;
        }
        undoSquare = square;
        answer(Squares.name(square));
    }

    private void next() {
        String previous = target.get();
        String square;
        do {
            square = Squares.name(random.nextInt(64)).toLowerCase(Locale.ROOT);
        } while (square.equals(previous));
        target.set(square);
        if (mode == Mode.NAME) {
            choices.set(choicesFor(square));
            if (leds != null) {
                leds.replace(LedRenderer.Layer.HINT, Map.of(Squares.parse(square), LedColors.BEST));
            }
        }
    }

    /** The right name and three near ones (same file or rank, or a neighbour): the usual confusions. */
    /** NAME: the asked square lit again (after the board cleared its LEDs). */
    private void relightTarget() {
        if (mode == Mode.NAME && state.get() == State.PLAYING && leds != null) {
            leds.replace(LedRenderer.Layer.HINT, Map.of(Squares.parse(target.get()), LedColors.BEST));
        }
    }

    private List<String> choicesFor(String square) {
        int file = square.charAt(0) - 'a';
        int rank = square.charAt(1) - '1';
        List<String> near = new ArrayList<>();
        for (int df = -2; df <= 2; df++) {
            for (int dr = -2; dr <= 2; dr++) {
                int f = file + df;
                int r = rank + dr;
                if ((df != 0 || dr != 0) && f >= 0 && f < 8 && r >= 0 && r < 8) {
                    near.add("" + (char) ('a' + f) + (char) ('1' + r));
                }
            }
        }
        // the mirrored square: what a player who reads the board from the wrong side answers
        near.add("" + (char) ('a' + 7 - file) + (char) ('1' + 7 - rank));
        Collections.shuffle(near, random);
        List<String> out = new ArrayList<>(near.subList(0, 3));
        out.add(square);
        Collections.shuffle(out, random);
        return List.copyOf(out);
    }

    private void flash(String good, int goodColor, String bad, int badColor) {
        if (leds == null) {
            return;
        }
        Map<Integer, Integer> colors = bad == null
                ? Map.of(Squares.parse(good), goodColor)
                : Map.of(Squares.parse(good), goodColor, Squares.parse(bad), badColor);
        leds.showFor(LedRenderer.Layer.VERDICT, colors, FLASH_MS);
    }

    private void clearLeds() {
        if (leds != null) {
            leds.clear(LedRenderer.Layer.HINT);
            leds.clear(LedRenderer.Layer.VERDICT);
        }
    }

    private void stopClock() {
        if (clock != null) {
            clock.setOnFlag(null);
            clock.stop();
            timeText.unbind();
            clock = null;
        }
    }

    private String recordKey() {
        return "training.coordinates." + mode.key + "." + (white ? "white" : "black") + ".best";
    }
}
