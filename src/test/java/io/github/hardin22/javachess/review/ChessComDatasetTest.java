package io.github.hardin22.javachess.review;

import com.github.bhlangonijr.chesslib.Board;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The ground-truth fixture is readable, legal and consistent (fast, no engine). */
class ChessComDatasetTest {

    @Test
    void everyGameReplaysToItsFinalPosition() throws Exception {
        List<ChessComDataset.Game> games = ChessComDataset.load();
        assertTrue(games.size() >= 40, "fixture has " + games.size() + " games");
        Set<String> ids = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ChessComDataset.class.getResourceAsStream(ChessComDataset.RESOURCE), StandardCharsets.UTF_8))) {
            int i = 0;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) {
                    continue;
                }
                JSONObject o = new JSONObject(line);
                ChessComDataset.Game g = games.get(i++);
                assertTrue(ids.add(g.id()), "duplicate " + g.id());
                assertEquals(g.san().size(), g.plies(), g.id());
                assertTrue(g.plies() > 0, g.id());
                if (o.has("final_fen")) {
                    assertEquals(placement(o.getString("final_fen")), placement(g.fens().get(g.plies())), g.id());
                }
                if (o.has("moves_uci")) {
                    assertEquals(ChessComDataset.strings(o.getJSONArray("moves_uci")), g.uci(), g.id());
                }
                assertTrue(g.whiteAccuracy() >= 0 && g.whiteAccuracy() <= 100, g.id());
                assertTrue(g.blackAccuracy() >= 0 && g.blackAccuracy() <= 100, g.id());
                if (g.hasLabels()) {
                    assertEquals(g.plies(), g.labels().size(), g.id());
                }
            }
        }
    }

    @Test
    void fixtureHoldsNoUsernames() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ChessComDataset.class.getResourceAsStream(ChessComDataset.RESOURCE), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                JSONObject o = new JSONObject(line);
                for (String k : new String[] { "white", "black", "pgn", "termination", "source", "uuid" }) {
                    assertFalse(o.has(k), o.optString("id") + " has personal field " + k);
                }
            }
        }
    }

    @Test
    void mateFlagsAgreeWithTheLastPosition() {
        for (ChessComDataset.Game g : ChessComDataset.load()) {
            Board b = new Board();
            b.loadFromFen(g.fens().get(g.plies()));
            if (b.isMated()) {
                assertEquals(b.getSideToMove().name().equals("WHITE") ? "0-1" : "1-0", g.result(), g.id());
            }
        }
    }

    @Test
    void labelSpellingsAreParsed() {
        assertEquals(ReviewLabel.BRILLIANT, ReviewLabel.parse("Brilliant"));
        assertEquals(ReviewLabel.GREAT, ReviewLabel.parse("great_find"));
        assertEquals(ReviewLabel.BEST, ReviewLabel.parse("bestmove"));
        assertEquals(ReviewLabel.BOOK, ReviewLabel.parse("Book Move"));
        assertEquals(ReviewLabel.MISS, ReviewLabel.parse("miss"));
        assertEquals(ReviewLabel.BLUNDER, ReviewLabel.parse("??"));
        assertNull(ReviewLabel.parse("whatever"));
    }

    private static String placement(String fen) {
        return fen.split(" ")[0];
    }
}
