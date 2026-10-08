package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Analysis.AnalysisTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvcRematchTest {

    @Test
    void theRematchSwapsTheColoursAndKeepsTheRest() {
        BotLevels.Level level = BotLevels.ALL.get(3);
        TimeControl clock = new TimeControl(600, 5);
        String odds = OddsPresets.byId("queen").orElseThrow().fen();
        PvcRematch first = new PvcRematch(level, true, clock, odds);
        PvcRematch again = first.swapped();
        assertFalse(again.humanWhite());
        assertEquals(level, again.level());
        assertEquals(clock, again.timeControl());
        assertEquals(odds, again.startFen());
        assertEquals("La rivincita: giochi con il Nero", again.colourText());
        assertTrue(again.swapped().humanWhite());
    }

    @Test
    void theUsualStartAndNoClockAreNormalised() {
        PvcRematch r = new PvcRematch(BotLevels.ALL.get(0), false, null, AnalysisTree.START_FEN);
        assertNull(r.startFen());
        assertEquals(TimeControl.UNLIMITED, r.timeControl());
        assertEquals("La rivincita: giochi con il Bianco", r.swapped().colourText());
    }
}
