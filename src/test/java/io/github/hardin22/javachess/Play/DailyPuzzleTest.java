package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Utils.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyPuzzleTest {

    @BeforeEach
    void reset() {
        if (System.getProperty("javachess.home") == null) {
            System.setProperty("javachess.home", System.getProperty("java.io.tmpdir") + "/javachess-test-home");
            ConfigManager.reload();
        }
        Map<String, String> clear = new HashMap<>();
        clear.put(DailyPuzzle.LAST_KEY, null);
        clear.put(DailyPuzzle.STREAK_KEY, null);
        clear.put(DailyPuzzle.SOLVED_KEY, null);
        ConfigManager.setProperties(clear);
    }

    static DailyPuzzle on(String day) {
        return new DailyPuzzle(Clock.fixed(Instant.parse(day + "T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void solvingOnConsecutiveDaysMakesAStreak() {
        DailyPuzzle mon = on("2026-10-05");
        assertEquals("Da risolvere", mon.statusText());
        mon.recordResult(true);
        assertTrue(mon.solvedToday());
        assertEquals("Risolto", mon.statusText());
        mon.recordResult(false); // a later try the same day changes nothing
        assertTrue(mon.solvedToday());

        DailyPuzzle tue = on("2026-10-06");
        assertFalse(tue.doneToday());
        assertEquals(1, tue.streak(), "yesterday's streak is still alive");
        assertEquals("Da risolvere · serie di 1 giorno", tue.statusText());
        tue.recordResult(true);
        assertEquals("Risolto · 2 giorni di fila", tue.statusText());

        DailyPuzzle fri = on("2026-10-09"); // two days missed
        assertEquals(0, fri.streak());
        fri.recordResult(false);
        assertEquals("Non risolto · domani un altro", fri.statusText());
        assertEquals(0, on("2026-10-10").streak());
    }
}
