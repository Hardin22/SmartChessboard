package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Oggetti.Puzzle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuzzleReviewTest {

    @TempDir
    Path dir;

    static Puzzle puzzle(String id) {
        return new Puzzle(id, "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
                List.of("f3e5", "c6e5"), 1400, 80, 90, 1000, List.of("fork", "short"), "https://lichess.org/x", "");
    }

    @Test
    void failedPuzzlesComeBackUntilSolvedCleanly() {
        PuzzleReview r = new PuzzleReview(dir.resolve("puzzle-review.json"), Runnable::run);
        r.onAttempt(puzzle("a"), false);
        r.onAttempt(puzzle("b"), true); // solved cleanly: never enters
        r.onAttempt(puzzle("c"), false);
        assertEquals(2, r.size());
        r.onAttempt(puzzle("a"), false);
        assertEquals(2, r.items().stream().filter(i -> i.puzzle().getId().equals("a")).findFirst().orElseThrow()
                .failures());
        r.onAttempt(puzzle("c"), true);
        assertEquals(1, r.size());

        PuzzleReview reloaded = new PuzzleReview(dir.resolve("puzzle-review.json"), Runnable::run);
        assertEquals(1, reloaded.size());
        Puzzle back = reloaded.next().orElseThrow();
        assertEquals("a", back.getId());
        assertEquals(List.of("f3e5", "c6e5"), back.getMoves());
        assertEquals(List.of("fork", "short"), back.getThemes());
        assertEquals(1400, back.getRating());
    }

    @Test
    void justFailedPuzzlesAreSpacedOut() {
        PuzzleReview r = new PuzzleReview(dir.resolve("p.json"), Runnable::run);
        for (String id : List.of("1", "2", "3", "4", "5")) {
            r.onAttempt(puzzle(id), false);
        }
        // 3, 4, 5 were just failed: the oldest not recent first
        assertEquals("1", r.next().orElseThrow().getId());
        assertEquals("2", r.next().orElseThrow().getId());
        r.clear();
        assertTrue(r.next().isEmpty());
    }
}
