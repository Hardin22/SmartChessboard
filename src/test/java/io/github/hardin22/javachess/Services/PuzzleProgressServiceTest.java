package io.github.hardin22.javachess.Services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PuzzleProgressServiceTest {

    @TempDir
    Path dir;

    private PuzzleProgressService service() {
        return new PuzzleProgressService(dir.resolve("puzzle-progress.json"), dir.resolve("backups"));
    }

    @Test
    void ratingMovesWithResultsAndPersists() {
        PuzzleProgressService s = service();
        assertEquals(PuzzleProgressService.INITIAL_RATING, s.getRating());
        s.record("a1", 1500, List.of("fork"), true);
        int afterWin = s.getRating();
        assertEquals(1520, afterWin, "K=40, expected 0.5");
        s.record("a2", 1500, List.of("fork", "mateIn2"), false);
        assertTrue(s.getRating() < afterWin);
        s.record("a3", 2400, List.of("mateIn2"), true);

        PuzzleProgressService reloaded = service();
        assertEquals(s.getRating(), reloaded.getRating());
        PuzzleProgressService.Stats st = reloaded.getStats();
        assertEquals(3, st.attempts());
        assertEquals(2, st.solved());
        assertEquals(1, st.currentStreak());
        assertEquals(1, st.bestStreak());
        assertArrayEquals(new int[]{2, 1}, st.byTheme().get("fork"));
        assertArrayEquals(new int[]{2, 1}, st.byTheme().get("mateIn2"));
        assertTrue(reloaded.isSolved("a1"));
        assertFalse(reloaded.isSolved("a2"));
        assertEquals("a3", reloaded.recentAttempts(1).get(0).puzzleId());
        assertEquals(2.0 / 3, st.successRate(), 1e-9);
    }

    @Test
    void beatingAHardPuzzleIsWorthMore() {
        PuzzleProgressService easy = new PuzzleProgressService(dir.resolve("e.json"), dir.resolve("b"));
        PuzzleProgressService hard = new PuzzleProgressService(dir.resolve("h.json"), dir.resolve("b"));
        easy.record("x", 1000, List.of(), true);
        hard.record("y", 2000, List.of(), true);
        assertTrue(hard.getRating() > easy.getRating());
    }

    @Test
    void corruptFileStartsFreshAndIsKept() throws Exception {
        Files.writeString(dir.resolve("puzzle-progress.json"), "{oops");
        PuzzleProgressService s = service();
        assertEquals(0, s.getStats().attempts());
        try (var files = Files.list(dir.resolve("backups"))) {
            assertEquals(1, files.count());
        }
        s.record("z", 1500, null, true);
        assertEquals(1, service().getStats().attempts());
    }

    @Test
    void resetKeepsABackup() throws Exception {
        PuzzleProgressService s = service();
        s.record("a", 1500, List.of(), true);
        s.reset();
        assertEquals(0, service().getStats().attempts());
        assertEquals(PuzzleProgressService.INITIAL_RATING, service().getRating());
        try (var files = Files.list(dir.resolve("backups"))) {
            assertEquals(1, files.count());
        }
    }
}
