package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ratings of archived players for the review labels. */
class PlayerRatingTest {

    @Test
    void botsHaveTheirProfileStrength() {
        assertEquals(1500, PlayerRating.of("Maia 1500", false));
        assertEquals(1347, PlayerRating.of("Stockfish livello 0", false));
        assertEquals(2900, PlayerRating.of("Stockfish livello 20", false));
        int l10 = PlayerRating.of("Stockfish livello 10", false);
        assertTrue(l10 > 2000 && l10 < 2400, "level 10: " + l10);
        assertEquals(1700, PlayerRating.of("Stockfish livello 4", true)); // Lichess AI level 4
    }

    @Test
    void onlinePlayersKeepTheGameRatingAndOthersAreUnknown() {
        assertEquals(1834, PlayerRating.of("someone (1834)", true));
        assertEquals(0, PlayerRating.of("Giocatore", false));
        assertEquals(0, PlayerRating.of("?", false));
        assertEquals(0, PlayerRating.of(null, false));
        ArchivedGame g = new ArchivedGame(1, ArchivedGame.GameMode.PVC, "Maia 1100", "Giocatore", "Maia 1100", "1-0",
                "", "", "", null, "", "", List.of("e2e4"));
        assertEquals(0, g.whiteRating());
        assertEquals(1100, g.blackRating());
    }

    @Test
    void unknownRatingsUseTheDefaultCurve() {
        ReviewClassifier.Tuning t = ReviewClassifier.Tuning.DEFAULT;
        assertEquals(t.slope(0), t.slope((int) t.defaultRating), 1e-12);
        assertTrue(t.slope(2200) > t.slope(1000));
    }
}
