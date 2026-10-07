package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Services.PuzzleService;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A timed series of puzzles (like Puzzle Rush / Puzzle Storm): easy at first, a little harder after each solved
 * one; a wrong move fails the puzzle and the next one comes; three failures, or the end of the time, end the series.
 * The best score of each mode is remembered. The puzzles are played with the normal puzzle screen ({@code PuzzleGame}
 * with {@code setRated(false)}): the view reports {@link #solved()} / {@link #failed()} and shows {@link #currentProperty()}.
 * View-model: JavaFX thread.
 */
public final class PuzzleRush {

    private static final Logger log = LoggerFactory.getLogger(PuzzleRush.class);
    public static final int MAX_FAILURES = 3;
    /** Rating added after each solved puzzle. */
    static final int STEP = 60;

    /** Kinds of series. */
    public enum Mode {
        THREE_MINUTES("3 minuti", 180), FIVE_MINUTES("5 minuti", 300), SURVIVAL("Sopravvivenza", 0);

        private final String italian;
        private final int seconds;

        Mode(String italian, int seconds) {
            this.italian = italian;
            this.seconds = seconds;
        }

        public String italian() {
            return italian;
        }

        /** Duration, 0 for no clock (survival: only the three failures end it). */
        public int seconds() {
            return seconds;
        }

        String recordKey() {
            return "puzzle.rush.best." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum State { READY, LOADING, PLAYING, FINISHED }

    private final Mode mode;
    private final Function<Integer, CompletableFuture<Puzzle>> puzzles;
    private final Supplier<GameClock> clockFactory;
    private final Executor fx;
    private final int startRating;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.READY);
    private final ReadOnlyObjectWrapper<Puzzle> current = new ReadOnlyObjectWrapper<>(this, "current");
    private final ReadOnlyIntegerWrapper score = new ReadOnlyIntegerWrapper(this, "score");
    private final ReadOnlyIntegerWrapper failures = new ReadOnlyIntegerWrapper(this, "failures");
    private final ReadOnlyIntegerWrapper best = new ReadOnlyIntegerWrapper(this, "best");
    private final ReadOnlyBooleanWrapper newRecord = new ReadOnlyBooleanWrapper(this, "newRecord");
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private final ReadOnlyStringWrapper timeText = new ReadOnlyStringWrapper(this, "timeText", "");
    private final ReadOnlyBooleanWrapper timeLow = new ReadOnlyBooleanWrapper(this, "timeLow");
    private final Set<String> seen = new HashSet<>();
    private GameClock clock;
    private int generation;

    /** A series on the app's puzzle database, starting below the player's puzzle rating. */
    public PuzzleRush(Mode mode, int playerRating) {
        this(mode, playerRating, target -> PuzzleService.getInstance().findPuzzleAsync(target, 100, null),
                () -> new GameClock(new TimeControl(mode.seconds(), 0)), AppExecutors::runOnFx);
    }

    /**
     * @param puzzles      a puzzle around a rating
     * @param clockFactory the countdown (only for timed modes)
     */
    public PuzzleRush(Mode mode, int playerRating, Function<Integer, CompletableFuture<Puzzle>> puzzles,
                      Supplier<GameClock> clockFactory, Executor fx) {
        this.mode = mode;
        this.puzzles = puzzles;
        this.clockFactory = clockFactory;
        this.fx = fx;
        this.startRating = Math.max(500, Math.min(1200, playerRating - 600));
        this.best.set(ConfigManager.getIntProperty(mode.recordKey(), 0));
        this.timeText.set(mode.seconds() > 0 ? GameClockText.of(mode.seconds()) : "");
    }

    // ------------------------------------------------------------------ properties

    public Mode mode() {
        return mode;
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** The puzzle to play (load it in the puzzle screen when it changes). */
    public ReadOnlyObjectProperty<Puzzle> currentProperty() {
        return current.getReadOnlyProperty();
    }

    /** Puzzles solved. */
    public ReadOnlyIntegerProperty scoreProperty() {
        return score.getReadOnlyProperty();
    }

    /** Puzzles failed (the series ends at {@value #MAX_FAILURES}). */
    public ReadOnlyIntegerProperty failuresProperty() {
        return failures.getReadOnlyProperty();
    }

    /** Best score of this mode so far. */
    public ReadOnlyIntegerProperty bestProperty() {
        return best.getReadOnlyProperty();
    }

    /** True at the end when the score beat the record. */
    public ReadOnlyBooleanProperty newRecordProperty() {
        return newRecord.getReadOnlyProperty();
    }

    /** "Serie finita: 14 puzzle risolti", "Tempo scaduto: …", "Nuovo record!"… */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** Remaining time "02:41" (timed modes), "" in survival. */
    public ReadOnlyStringProperty timeTextProperty() {
        return timeText.getReadOnlyProperty();
    }

    /** Less than 20 seconds left. */
    public ReadOnlyBooleanProperty timeLowProperty() {
        return timeLow.getReadOnlyProperty();
    }

    /** Rating the next puzzle is chosen around. */
    public int targetRating() {
        return startRating + score.get() * STEP;
    }

    // ------------------------------------------------------------------ actions

    /** Starts (or restarts) the series. */
    public void start() {
        generation++;
        stopClock();
        score.set(0);
        failures.set(0);
        newRecord.set(false);
        seen.clear();
        message.set("");
        if (mode.seconds() > 0) {
            clock = clockFactory.get();
            timeText.bind(clock.whiteTextProperty());
            timeLow.bind(clock.whiteLowProperty());
            clock.setOnFlag(side -> finish("Tempo scaduto"));
        }
        loadNext(true);
    }

    /** The current puzzle was solved. */
    public void solved() {
        if (state.get() != State.PLAYING) {
            return;
        }
        score.set(score.get() + 1);
        loadNext(false);
    }

    /** The current puzzle was failed (a wrong move, or skipped). */
    public void failed() {
        if (state.get() != State.PLAYING) {
            return;
        }
        failures.set(failures.get() + 1);
        if (failures.get() >= MAX_FAILURES) {
            finish("Tre errori");
            return;
        }
        loadNext(false);
    }

    /** Ends the series now. */
    public void stop() {
        if (state.get() == State.PLAYING || state.get() == State.LOADING) {
            finish("Serie interrotta");
        }
    }

    // ------------------------------------------------------------------ internals

    private void loadNext(boolean first) {
        int gen = generation;
        state.set(State.LOADING);
        if (clock != null && !first) {
            clock.pause(); // loading time is not the player's
        }
        fetch(targetRating(), 3).whenComplete((p, err) -> fx.execute(() -> {
            if (gen != generation || state.get() != State.LOADING) {
                return;
            }
            if (p == null) {
                log.info("no puzzle for the series: {}", err == null ? "none found" : err.toString());
                finish(score.get() == 0 ? "Nessun puzzle disponibile" : "Puzzle esauriti");
                return;
            }
            seen.add(p.getId());
            current.set(p);
            state.set(State.PLAYING);
            if (clock != null) {
                clock.start(Side.WHITE);
            }
        }));
    }

    /** A puzzle not seen in this series (a few tries). */
    private CompletableFuture<Puzzle> fetch(int target, int tries) {
        return puzzles.apply(target).thenCompose(p -> {
            if (p != null && seen.contains(p.getId()) && tries > 1) {
                return fetch(target + 10, tries - 1);
            }
            return CompletableFuture.completedFuture(p);
        });
    }

    private void finish(String why) {
        generation++;
        stopClock();
        state.set(State.FINISHED);
        current.set(null);
        int s = score.get();
        boolean record = s > best.get();
        if (record) {
            best.set(s);
            newRecord.set(true);
            ConfigManager.setProperty(mode.recordKey(), String.valueOf(s));
        }
        message.set(why + ": " + s + (s == 1 ? " puzzle risolto" : " puzzle risolti") + (record ? " · nuovo record!" : ""));
    }

    private void stopClock() {
        if (clock != null) {
            timeText.unbind();
            timeLow.unbind();
            clock.stop();
            clock = null;
        }
    }

    /** "03:00" for a number of seconds. */
    static final class GameClockText {
        static String of(int seconds) {
            return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        }
    }
}
