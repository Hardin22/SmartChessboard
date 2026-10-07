package io.github.hardin22.javachess.Services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A first start without the puzzle files must be told apart from "no puzzle matches the filters". */
class PuzzleServiceDataTest {

    @TempDir
    Path dir;

    @Test
    void noDatabaseAndNoCsvMeansNoPuzzleData() {
        assertFalse(PuzzleService.hasPuzzleData(List.of(dir.resolve("puzzles.db")), List.of(dir.resolve("puzzles.csv"))));
    }

    @Test
    void eitherFileIsEnough() throws Exception {
        Path csv = Files.writeString(dir.resolve("puzzles.csv"), "PuzzleId,FEN\n");
        assertTrue(PuzzleService.hasPuzzleData(List.of(dir.resolve("missing.db")), List.of(csv)));
        Path db = Files.writeString(dir.resolve("puzzles.db"), "x");
        assertTrue(PuzzleService.hasPuzzleData(List.of(db), List.of(dir.resolve("missing.csv"))));
        Files.createDirectories(dir.resolve("folder.db"));
        assertFalse(PuzzleService.hasPuzzleData(List.of(dir.resolve("folder.db")), List.of()), "a folder is not a file");
    }
}
