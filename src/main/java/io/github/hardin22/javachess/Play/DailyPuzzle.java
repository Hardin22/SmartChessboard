package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Services.PuzzleService;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * "Puzzle del giorno", offline: one puzzle a day from the local database (the same for everybody on that day), a
 * small daily habit at the board, and how many days in a row it was solved. The online daily puzzle of lichess
 * ({@link PuzzleService#fetchDailyPuzzle()}) needs a network the Pi often has not.
 */
public final class DailyPuzzle {

    public static final String LAST_KEY = "puzzle.daily.last";
    public static final String STREAK_KEY = "puzzle.daily.streak";
    public static final String SOLVED_KEY = "puzzle.daily.solved";

    private final Clock clock;

    public DailyPuzzle() {
        this(Clock.systemDefaultZone());
    }

    public DailyPuzzle(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Today's puzzle (null without a puzzle database), read off the JavaFX thread. */
    public CompletableFuture<Puzzle> load() {
        LocalDate day = today();
        return CompletableFuture.supplyAsync(() -> PuzzleService.getInstance().dailyPuzzle(day), AppExecutors.io());
    }

    /** Today's puzzle has been played (solved or not). */
    public boolean doneToday() {
        return today().toString().equals(ConfigManager.getProperty(LAST_KEY, ""));
    }

    /** Solved today. */
    public boolean solvedToday() {
        return doneToday() && ConfigManager.getBooleanProperty(SOLVED_KEY, false);
    }

    /** Days in a row solved, ending today or yesterday (a streak not yet continued today is still alive). */
    public int streak() {
        String last = ConfigManager.getProperty(LAST_KEY, "");
        if (last.isEmpty() || !ConfigManager.getBooleanProperty(SOLVED_KEY, false)) {
            return 0;
        }
        LocalDate lastDay = LocalDate.parse(last);
        return lastDay.equals(today()) || lastDay.equals(today().minusDays(1))
                ? ConfigManager.getIntProperty(STREAK_KEY, 0) : 0;
    }

    /** Records today's result once (later tries the same day do not change it). */
    public void recordResult(boolean solved) {
        if (doneToday()) {
            return;
        }
        int streak = solved ? streak() + 1 : 0;
        ConfigManager.setProperties(Map.of(LAST_KEY, today().toString(), STREAK_KEY, String.valueOf(streak),
                SOLVED_KEY, String.valueOf(solved)));
    }

    /** "Risolto · 4 giorni di fila", "Risolto", "Non risolto · riprova domani", "Puzzle del giorno" (not played). */
    public String statusText() {
        if (!doneToday()) {
            int s = streak();
            return s > 0 ? "Da risolvere · serie di " + s + (s == 1 ? " giorno" : " giorni") : "Da risolvere";
        }
        if (!solvedToday()) {
            return "Non risolto · domani un altro";
        }
        int s = streak();
        return s > 1 ? "Risolto · " + s + " giorni di fila" : "Risolto";
    }
}
