package org.example.javachess.Vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoardReadingTest {

    @Test
    void placementRoundTrip() {
        String start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
        BoardReading r = BoardReading.certain(start);
        assertEquals(start, r.placement());
        assertEquals('K', r.pieceAt(4, 0));
        assertEquals('k', r.pieceAt(4, 7));
        assertEquals(BoardReading.EMPTY, r.pieceAt(4, 3));
        assertEquals(1f, r.minConfidence());
        assertTrue(r.uncertainSquares(0.9f).isEmpty());
    }

    @Test
    void malformedPlacementGivesEmptyBoardNotACrash() {
        assertEquals("8/8/8/8/8/8/8/8", BoardReading.certain("xyz?").placement());
        assertEquals("8/8/8/8/8/8/8/8", BoardReading.certain("").placement());
    }

    @Test
    void tooManyPawnsAreTrimmedByConfidence() {
        float[][][] p = BoardReading.certain("4k3/8/8/8/8/PPPPPPPP/P7/4K3").probabilities();
        // the a2 pawn is the least certain one: it must be the one removed
        java.util.Arrays.fill(p[0][1], 0f);
        p[0][1][BoardReading.SYMBOLS.indexOf('P')] = 0.6f;
        p[0][1][BoardReading.SYMBOLS.indexOf('B')] = 0.4f;
        BoardReading fixed = new BoardReading(p, true, 0).withPlacementRules();
        assertEquals("4k3/8/8/8/8/PPPPPPPP/B7/4K3", fixed.placement());
    }
}
