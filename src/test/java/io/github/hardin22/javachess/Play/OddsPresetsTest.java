package io.github.hardin22.javachess.Play;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OddsPresetsTest {

    @Test
    void everyPresetIsAValidStartingPositionForBothSides() {
        for (OddsPresets.Preset p : OddsPresets.all()) {
            assertTrue(PositionSetup.check(p.fen()).ok(), p.id() + " " + PositionSetup.check(p.fen()).errors());
            assertTrue(PositionSetup.check(p.blackGives()).ok(), p.id() + " mirrored");
        }
    }

    @Test
    void blackGivesTheSameOdds() {
        OddsPresets.Preset rook = OddsPresets.byId("rook").orElseThrow();
        assertEquals("1nbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQk - 0 1", rook.blackGives());
        assertEquals("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                OddsPresets.byId("queen").orElseThrow().blackGives());
    }
}
