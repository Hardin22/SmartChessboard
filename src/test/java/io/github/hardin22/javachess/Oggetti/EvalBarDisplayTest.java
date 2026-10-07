package io.github.hardin22.javachess.Oggetti;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure mapping score -> bar (no JavaFX toolkit needed). */
class EvalBarDisplayTest {

    @Test
    void finishedMatesFillTheBarWithTheWinnersColour() {
        assertEquals(new EvalBar.Display(1, "1-0"), EvalBar.display(1000.0));
        assertEquals(new EvalBar.Display(0, "0-1"), EvalBar.display(-1000.0));
        assertEquals(new EvalBar.Display(1, "1-0"), EvalBar.display(Double.POSITIVE_INFINITY));
        assertEquals(new EvalBar.Display(0, "0-1"), EvalBar.display(Double.NEGATIVE_INFINITY));
    }

    @Test
    void forcedMates() {
        assertEquals(new EvalBar.Display(1, "M1"), EvalBar.display(999.0));
        assertEquals(new EvalBar.Display(0, "-M3"), EvalBar.display(-997.0));
    }

    @Test
    void centipawnsAreSoftClamped() {
        assertEquals(0.5, EvalBar.display(0).whiteShare(), 1e-9);
        assertEquals("0.0", EvalBar.display(-0.04).text());
        assertTrue(EvalBar.display(1.5).whiteShare() > 0.6);
        assertTrue(EvalBar.display(-1.5).whiteShare() < 0.4);
        assertEquals("+1.5", EvalBar.display(1.5).text());
        assertEquals(0.5, EvalBar.display(Double.NaN).whiteShare(), 1e-9);
    }

    @Test
    void labelKeepsResults() {
        assertEquals("1-0", EvalBar.label("1-0"));
        assertEquals("0-1", EvalBar.label("0-1"));
        assertEquals("M2", EvalBar.label("-M2"));
        assertEquals("1.5", EvalBar.label("+1.5"));
    }
}
