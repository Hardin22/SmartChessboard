package org.example.javachess.Components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotationTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void whiteLineIsNumberedWithDots() {
        assertEquals("1. e4 e5 2. Nf3", Notation.line(START, List.of("e2e4", "e7e5", "g1f3"), 10));
    }

    @Test
    void lineStartingWithBlackUsesEllipsis() {
        String fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
        assertEquals("1... e5 2. Nf3 Nc6", Notation.line(fen, List.of("e7e5", "g1f3", "b8c6"), 10));
    }

    @Test
    void castlingUsesLetterO() {
        String fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 8";
        assertEquals("8. O-O O-O-O", Notation.line(fen, List.of("e1g1", "e8c8"), 10));
        assertEquals("O-O-O", Notation.castling("0-0-0"));
    }

    @Test
    void stopsAtIllegalMovesAndLimitsLength() {
        assertEquals("1. e4", Notation.line(START, List.of("e2e4", "e2e4"), 10));
        assertEquals("1. e4 e5", Notation.line(START, List.of("e2e4", "e7e5", "g1f3"), 2));
    }
}
