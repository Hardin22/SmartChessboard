package org.example.javachess.Hardware;

import org.example.javachess.Engine.CandidateFeedback;
import org.example.javachess.Engine.MoveFeedback;
import org.example.javachess.Engine.MoveQuality;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Engine feedback events end up as LED colors on the right squares. */
class MoveLedsFeedbackTest {

    private final SimulatedBoard sim = new SimulatedBoard();
    private final LedRenderer leds = new LedRenderer(sim, LedMapping.DEFAULT);
    private final MoveLedsFeedback feedback = new MoveLedsFeedback(new MoveLeds(leds));

    @AfterEach
    void tearDown() {
        leds.shutdown();
    }

    private static int led(String square) {
        return LedMapping.DEFAULT.ledIndex(Squares.parse(square));
    }

    @Test
    void candidatesVerdictAndClear() throws InterruptedException {
        Map<String, MoveQuality> destinations = new LinkedHashMap<>();
        destinations.put("F3", MoveQuality.BEST);
        destinations.put("H3", MoveQuality.BLUNDER);
        feedback.onCandidates(new CandidateFeedback("fen", "G1", destinations, "g1f3", 12, 300));
        assertNotNull(sim.awaitFrame(f -> f[led("f3")] == LedColors.BEST && f[led("h3")] == LedColors.BLUNDER
                && f[led("g1")] == LedColors.SOURCE, 1000));

        feedback.onClear();
        assertNotNull(sim.awaitFrame(f -> f[led("f3")] == 0 && f[led("g1")] == 0, 1000));

        feedback.onMoveClassified(new MoveFeedback("fen", "g1h3", "G1", "H3", MoveQuality.INACCURACY, false, false,
                "g1f3", "G1", "F3", 8.0, 12, 150));
        assertNotNull(sim.awaitFrame(f -> f[led("h3")] == LedColors.INACCURACY && f[led("f3")] == LedColors.BEST, 1000));
        assertEquals(LedColors.INACCURACY, leds.composeNow()[Squares.parse("h3")]);
    }
}
