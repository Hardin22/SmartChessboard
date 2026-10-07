package io.github.hardin22.javachess.Engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotStrengthTest {

    @Test
    void eloLevelsUseLimitStrength() {
        Map<String, String> o = EngineManager.strengthOptions(new BotStrength(5, 1700, 0, 0));
        assertEquals("true", o.get("UCI_LimitStrength"));
        assertEquals("1700", o.get("UCI_Elo"));
        assertEquals("20", o.get("Skill Level"), "skill is not stacked on top of the Elo limit");
    }

    @Test
    void skillLevelsSwitchTheLimitOff() {
        Map<String, String> o = EngineManager.strengthOptions(new BotStrength(3, 0, 3, 500));
        assertEquals("false", o.get("UCI_LimitStrength"));
        assertEquals("3", o.get("Skill Level"));
        assertEquals("3190", o.get("UCI_Elo"));
    }

    @Test
    void valuesAreClamped() {
        BotStrength s = new BotStrength(40, 900, -1, -5);
        assertEquals(20, s.skillLevel());
        assertEquals(BotStrength.MIN_ELO, s.uciElo());
        assertEquals(0, s.depth());
        assertEquals(0, s.movetimeMs());
        assertEquals(BotStrength.MAX_ELO, new BotStrength(0, 9999, 0, 0).uciElo());
        assertEquals(750, BotStrength.full().withMovetime(750).movetimeMs());
    }
}
