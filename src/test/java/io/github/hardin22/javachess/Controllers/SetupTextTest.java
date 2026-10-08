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
    void otherMessagesStayWhole() {
        SetupText t = SetupText.parse("Posiziona i pezzi: mancano 6");
        assertFalse(t.guided());
        assertEquals("Posiziona i pezzi: mancano 6", t.title());
        assertNull(t.squares());
    }
}
