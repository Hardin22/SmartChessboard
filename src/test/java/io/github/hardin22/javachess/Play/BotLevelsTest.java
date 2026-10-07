package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Services.EngineService.EngineType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotLevelsTest {

    @Test
    void ladderIsOrderedWithUniqueIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < BotLevels.ALL.size(); i++) {
            assertTrue(ids.add(BotLevels.ALL.get(i).id()));
            if (i > 0) {
                assertTrue(BotLevels.ALL.get(i).elo() > BotLevels.ALL.get(i - 1).elo());
            }
        }
        assertTrue(BotLevels.byId(BotLevels.DEFAULT_ID).isPresent());
    }

    @Test
    void textsAndNames() {
        BotLevels.Level club = BotLevels.byId("club").orElseThrow();
        assertEquals("circa 1350", club.eloText());
        assertEquals("Stockfish (1350)", club.playerName());
        assertEquals(1350, io.github.hardin22.javachess.Engine.review.PlayerRating.of(club.playerName(), false));
        assertEquals(1320, club.strength().uciElo());
        BotLevels.Level max = BotLevels.byId("max").orElseThrow();
        assertEquals("oltre 3000", max.eloText());
        assertEquals("Stockfish (3200)", max.playerName());
        assertEquals("Maia 1500", BotLevels.byId("maia-1500").orElseThrow().playerName());
        assertEquals(1, BotLevels.byId("beginner").orElseThrow().strength().depth());
    }

    @Test
    void withoutMaiaTheLadderSkipsIt() {
        List<BotLevels.Level> sfOnly = BotLevels.available(l -> !l.isMaia());
        assertEquals(9, sfOnly.size());
        BotLevels.Level club = BotLevels.byId("club").orElseThrow();
        assertEquals("intermediate", BotLevels.step(club, 1, sfOnly).id());
        assertEquals("novice", BotLevels.step(club, -1, sfOnly).id());
        assertEquals("beginner", BotLevels.step(sfOnly.get(0), -1, sfOnly).id());
        BotLevels.Level maia = BotLevels.byId("maia-1500").orElseThrow();
        assertEquals("club", BotLevels.step(maia, 0, sfOnly).id(), "a level not in the list: the closest one");
        assertEquals("intermediate", BotLevels.closestTo(1650, sfOnly).id());
    }

    @Test
    void legacySettings() {
        assertEquals("maia-1900", BotLevels.fromLegacy(EngineType.MAIA_1900, 5).id());
        assertEquals("max", BotLevels.fromLegacy(EngineType.STOCKFISH, 20).id());
        assertEquals("club", BotLevels.fromLegacy(EngineType.STOCKFISH, 0).id());
        assertEquals("expert", BotLevels.fromLegacy(EngineType.STOCKFISH, 9).id());
        assertEquals("club", BotLevels.fromLegacy(null, 1).id());
    }
}
