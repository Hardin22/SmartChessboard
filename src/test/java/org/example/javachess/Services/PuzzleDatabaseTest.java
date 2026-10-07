package org.example.javachess.Services;

import org.example.javachess.Oggetti.Puzzle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuzzleDatabaseTest {

    @TempDir
    static Path dir;
    static Path db;
    static final Map<String, String> csvById = new HashMap<>();

    private static final String[] THEMES = {"fork", "pin", "mate mateIn1", "endgame short", "underPromotion", "crushing long"};

    @BeforeAll
    static void build() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags");
        // 1000 puzzles, ratings 600..2595 in shuffled order, one rare theme
        for (int i = 0; i < 1000; i++) {
            int rating = 600 + (i * 389) % 1000 * 2;
            String themes = i == 777 ? "underPromotion endgame" : THEMES[i % 4] + (i % 2 == 0 ? " crushing" : "");
            String id = String.format("P%04d", i);
            String line = id + ",r6k/pp2r2p/4Rp1Q/3p4/8/1N1P2R1/PqP2bPP/7K b - - 0 " + (i + 1)
                    + ",f2g3 e6e7 b2b1," + rating + ",76," + (i % 100) + "," + (1000 + i) + "," + themes
                    + ",https://lichess.org/787zsVup/black#" + i + "," + (i % 3 == 0 ? "Sicilian_Defense" : "");
            lines.add(line);
            csvById.put(id, line);
        }
        Path csv = dir.resolve("puzzles.csv");
        Files.write(csv, lines);
        db = dir.resolve("puzzles.db");
        PuzzleIndexer.Stats stats = new PuzzleIndexer(-101).build(csv, db);
        assertEquals(1000, stats.puzzles());
    }

    @Test
    void recordsRoundTrip() throws IOException {
        try (PuzzleDatabase database = PuzzleDatabase.open(db)) {
            assertEquals(1000, database.size());
            int previousRating = 0;
            for (int i = 0; i < database.size(); i++) {
                Puzzle p = database.read(i);
                assertTrue(p.getRating() >= previousRating, "sorted by rating");
                previousRating = p.getRating();
                Puzzle expected = PuzzleService.parseCsvLine(csvById.get(p.getId()));
                assertNotNull(expected);
                assertEquals(expected.getFen(), p.getFen());
                assertEquals(expected.getMoves(), p.getMoves());
                assertEquals(expected.getRating(), p.getRating());
                assertEquals(expected.getPopularity(), p.getPopularity());
                assertEquals(expected.getNbPlays(), p.getNbPlays());
                assertEquals(expected.getGameUrl(), p.getGameUrl());
                assertEquals(expected.getOpeningTags(), p.getOpeningTags());
                assertEquals(expected.getThemes().stream().sorted().toList(), p.getThemes().stream().sorted().toList());
            }
        }
    }

    @Test
    void searchByRatingAndTheme() throws IOException {
        try (PuzzleDatabase database = PuzzleDatabase.open(db)) {
            for (int i = 0; i < 50; i++) {
                Puzzle p = database.random(1500, 100, List.of("pin", "fork"));
                assertNotNull(p);
                assertTrue(Math.abs(p.getRating() - 1500) <= 100, "rating " + p.getRating());
                assertTrue(p.getThemes().contains("pin") || p.getThemes().contains("fork"), p.getThemes().toString());
            }
            Puzzle any = database.random(1000, 0, List.of("Tutti"));
            assertNotNull(any);
            assertEquals(1000, any.getRating());
            // the only underPromotion puzzle, found by the linear scan after the random probes miss
            Puzzle rare = database.random(1800, 1500, List.of("underPromotion"));
            assertNotNull(rare);
            assertEquals("P0777", rare.getId());
            assertNull(database.random(1500, 100, List.of("smotheredMate")), "unknown theme");
            assertNull(database.random(3000, 10, null), "no puzzle that strong");
        }
    }

    @Test
    void ratingWindowIsContiguous() throws IOException {
        try (PuzzleDatabase database = PuzzleDatabase.open(db)) {
            int[] window = database.ratingRange(1000, 1100);
            for (int i = window[0]; i < window[1]; i++) {
                int rating = database.read(i).getRating();
                assertTrue(rating >= 1000 && rating <= 1100);
            }
            assertEquals(51, window[1] - window[0]); // even ratings 1000..1100
        }
    }

    @Test
    void rejectsOtherFiles() throws IOException {
        Path junk = dir.resolve("junk.db");
        Files.writeString(junk, "not a database at all, sorry");
        try {
            PuzzleDatabase.open(junk).close();
            throw new AssertionError("should fail");
        } catch (IOException expected) {
            // ok
        }
    }
}
