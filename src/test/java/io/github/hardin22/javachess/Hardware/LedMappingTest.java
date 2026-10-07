package io.github.hardin22.javachess.Hardware;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedMappingTest {

    @Test
    void defaultSnakeMatchesTheWiredBoard() {
        LedMapping m = LedMapping.DEFAULT;
        // rank 1 left to right
        assertEquals(0, m.ledIndex(Squares.parse("a1")));
        assertEquals(7, m.ledIndex(Squares.parse("h1")));
        // rank 2 comes back
        assertEquals(8, m.ledIndex(Squares.parse("h2")));
        assertEquals(15, m.ledIndex(Squares.parse("a2")));
        assertEquals(16, m.ledIndex(Squares.parse("a3")));
        // same formula as the original firmware getSquareIndex()
        for (int square = 0; square < 64; square++) {
            int file = square % 8;
            int rank = square / 8;
            int expected = rank % 2 == 0 ? rank * 8 + file : rank * 8 + (7 - file);
            assertEquals(expected, m.ledIndex(square), Squares.name(square));
        }
        assertEquals(63, m.ledIndex(Squares.parse("a8")));
        assertEquals(56, m.ledIndex(Squares.parse("h8")));
    }

    @Test
    void everyMappingIsABijection() {
        for (LedMapping.Layout layout : LedMapping.Layout.values()) {
            for (String origin : new String[]{"a1", "h1", "a8", "h8"}) {
                for (LedMapping.Direction direction : LedMapping.Direction.values()) {
                    LedMapping m = new LedMapping(layout, origin, direction);
                    Set<Integer> seen = new HashSet<>();
                    for (int square = 0; square < 64; square++) {
                        int led = m.ledIndex(square);
                        seen.add(led);
                        assertEquals(square, m.square(led));
                    }
                    assertEquals(64, seen.size());
                    assertEquals(0, m.ledIndex(Squares.parse(origin)), "origin " + origin);
                }
            }
        }
    }

    @Test
    void otherOriginsAndDirections() {
        LedMapping h8Files = new LedMapping(LedMapping.Layout.SNAKE, "h8", LedMapping.Direction.ALONG_FILES);
        assertEquals(0, h8Files.ledIndex(Squares.parse("h8")));
        assertEquals(1, h8Files.ledIndex(Squares.parse("h7")));
        assertEquals(7, h8Files.ledIndex(Squares.parse("h1")));
        assertEquals(8, h8Files.ledIndex(Squares.parse("g1")));
        LedMapping rows = LedMapping.fromConfig("rows", "a1", "ranks");
        assertEquals(8, rows.ledIndex(Squares.parse("a2")));
        assertThrows(IllegalArgumentException.class, () -> new LedMapping(LedMapping.Layout.ROWS, "e4", LedMapping.Direction.ALONG_RANKS));
    }

    @Test
    void wireOrderReordersFrames() {
        int[] bySquare = new int[64];
        bySquare[Squares.parse("a2")] = 0x123456;
        int[] wire = LedMapping.DEFAULT.toWireOrder(bySquare);
        assertEquals(0x123456, wire[15]);
    }
}
