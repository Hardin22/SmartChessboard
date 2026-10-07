package io.github.hardin22.javachess.Stats;

import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ArchivedGame.GameMode;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStatsTest {

    static final PlayerStats.Identity ME = new PlayerStats.Identity(Set.of("giocatore", "tu", "hardin22"));
    static int nextId = 1;

    static final String[] FIRST = {"a2a3", "a2a4", "b2b3", "b2b4", "c2c3", "c2c4", "d2d3", "d2d4", "e2e3", "e2e4",
            "f2f3", "f2f4", "g2g3", "g2g4", "h2h3", "h2h4"};

    /** Games differ by their first move (identical games share their review). */
    static ArchivedGame game(GameMode mode, String white, String black, String result, String opening, int day) {
        return new ArchivedGame(nextId++, mode, "", white, black, result, "", opening, "",
                LocalDateTime.of(2026, 9, 1, 12, 0).plusDays(day), "", "", List.of(FIRST[day % FIRST.length], "e7e5"));
    }

    static ReviewStore.Summary summary(double w, double b) {
        return new ReviewStore.Summary(w, b, Map.of(), Map.of(MoveClassification.BLUNDER, 1), List.of(), "", null);
    }

    @Test
    void resultsFromMySide() {
        List<ArchivedGame> games = List.of(
                game(GameMode.PVC, "Giocatore", "Stockfish (1350)", "1-0", "C50 Italian Game: Giuoco Piano", 1),
                game(GameMode.PVC, "Maia 1500", "Giocatore", "1-0", "B20 Sicilian Defense", 2),
                game(GameMode.PVC, "Giocatore", "Maia 1500", "1/2-1/2", "C50 Italian Game", 3),
                game(GameMode.LICHESS, "someone (1700)", "hardin22 (1650)", "0-1", "B20 Sicilian Defense", 4),
                game(GameMode.PVP, "Bianco", "Nero", "1-0", "", 5), // nobody is "me"
                game(GameMode.PVC, "Giocatore", "Stockfish (1350)", "*", "", 6)); // interrupted
        PlayerStats.Stats s = PlayerStats.compute(games, Map.of(), ME);
        assertEquals(new PlayerStats.Score(2, 1, 1), s.total());
        assertEquals(new PlayerStats.Score(1, 1, 0), s.asWhite());
        assertEquals(new PlayerStats.Score(1, 0, 1), s.asBlack());
        assertEquals("63%", s.total().percentText());
        assertEquals(1, s.unfinished());
        assertEquals(List.of(1, 0, -1, 1), s.form(), "newest first");
        assertEquals(1, s.currentStreak());
        assertEquals(1, s.bestStreak());
        assertEquals("Contro il computer", s.byMode().get(0).name());
        assertEquals(3, s.byMode().get(0).score().games());
        assertEquals("Maia 1500", s.byOpponent().get(0).name());
        assertEquals(new PlayerStats.Score(0, 1, 1), s.byOpponent().get(0).score());
        assertEquals(2, s.openings().size());
        assertTrue(s.openings().stream().anyMatch(r -> r.name().equals("Italian Game") && r.score().games() == 2));
        assertTrue(Double.isNaN(s.accuracy()));
        assertEquals("—", s.accuracyText());
    }

    @Test
    void accuracyFromSavedReviews() {
        List<ArchivedGame> games = new ArrayList<>();
        Map<String, ReviewStore.Summary> reviews = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            ArchivedGame g = game(GameMode.PVC, "Giocatore", "Stockfish (1700)", i % 3 == 0 ? "0-1" : "1-0", "", i);
            games.add(g);
            // older games 70, newer 80 (from my side, White)
            reviews.put(ReviewStore.key(g), summary(i < 6 ? 70 : 80, 50));
        }
        PlayerStats.Stats s = PlayerStats.compute(games, reviews, ME);
        assertEquals(12, s.reviewed());
        assertEquals(75.0, s.accuracy());
        assertEquals(80.0, s.entries().get(0).accuracy());
        assertEquals("+10,0", s.trendText(), "last 6 against the 6 before");
        assertEquals(76.0, s.recentAccuracy(), "last ten: six at 80 and four at 70");
        assertEquals("75,0", s.byOpponent().get(0).accuracyText());
    }

    @Test
    void streaksAndOpeningFamilies() {
        List<ArchivedGame> games = new ArrayList<>();
        String[] results = {"1-0", "1-0", "1-0", "0-1", "1-0", "1-0"}; // oldest first
        for (int i = 0; i < results.length; i++) {
            games.add(game(GameMode.PVC, "Tu", "Maia 1100", results[i], "", i));
        }
        PlayerStats.Stats s = PlayerStats.compute(games, Map.of(), ME);
        assertEquals(2, s.currentStreak());
        assertEquals(3, s.bestStreak());
        assertEquals("Italian Game", PlayerStats.openingFamily("C50 Italian Game: Giuoco Piano, Main Line"));
        assertEquals("Queen's Gambit Declined", PlayerStats.openingFamily("D30 Queen's Gambit Declined"));
        assertEquals("Sconosciuta", PlayerStats.openingFamily(""));
        assertEquals("Sconosciuta", PlayerStats.openingFamily("Opening Name"));
    }

    @Test
    void identity() {
        assertTrue(ME.is("hardin22 (1650)"));
        assertTrue(ME.is("Giocatore"));
        assertFalse(ME.is("Stockfish (1350)"));
        assertFalse(ME.is(null));
        assertEquals(null, PlayerStats.mySide(game(GameMode.PVC, "Giocatore", "Tu", "1-0", "", 0), ME),
                "both names are mine: cannot tell");
    }
}
