package io.github.hardin22.javachess.Controllers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupTextTest {

    @Test
    void guidedStepsAreSplitIntoPieceSquaresAndStep() {
        SetupText t = SetupText.parse("Posiziona le Torri nere: a8, f8 · passo 4 di 9");
        assertTrue(t.guided());
        assertEquals("Posiziona le Torri nere", t.title());
        assertEquals("a8, f8", t.squares());
        assertEquals("passo 4 di 9", t.step());
        assertNull(t.rest());
    }

    @Test
    void singlePieceWithInAndARestSentence() {
        SetupText t = SetupText.parse("Posiziona il Re bianco in g1 · passo 1 di 9, togli i pezzi sulle case rosse (1)");
        assertEquals("Posiziona il Re bianco", t.title());
        assertEquals("g1", t.squares());
        assertEquals("passo 1 di 9", t.step());
        assertEquals("Togli i pezzi sulle case rosse (1)", t.rest());
    }

    @Test
    void piecesToTakeAwayComeFirst() {
        SetupText t = SetupText.parse(
                "Posiziona i pezzi: togli quelli sulle case rosse (2), poi il Re bianco in g1 · passo 1 di 6");
        assertTrue(t.guided());
        assertEquals("Togli 2 pezzi dalle case rosse", t.title());
        assertNull(t.squares());
        assertEquals("passo 1 di 6", t.step());
        assertEquals("Poi il Re bianco in g1", t.rest());
    }

    @Test
    void onlyPiecesToTakeAway() {
        String m = "Posiziona i pezzi: togli quelli sulle case rosse (1)";
        SetupText t = SetupText.parse(m);
        assertFalse(t.guided());
        assertTrue(t.known(m));
        assertEquals("Togli il pezzo dalla casa rossa", t.title());
    }

    @Test
    void piecesMissingAndToTakeAway() {
        SetupText t = SetupText.parse("Posiziona i pezzi: mancano 6, da togliere 2 (in rosso)");
        assertEquals("Mancano 6 pezzi", t.title());
        assertEquals("Togli 2 pezzi dalle case rosse", t.rest());
        assertEquals("Manca 1 pezzo", SetupText.parse("Posiziona i pezzi: mancano 1").title());
    }

    @Test
    void otherMessagesStayWhole() {
        String m = "Posiziona i pezzi...";
        SetupText t = SetupText.parse(m);
        assertFalse(t.guided());
        assertFalse(t.known(m));
        assertEquals(m, t.title());
        assertNull(t.squares());
    }
}
