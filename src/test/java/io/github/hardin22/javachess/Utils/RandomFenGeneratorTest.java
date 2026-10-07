package io.github.hardin22.javachess.Utils;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RandomFenGeneratorTest {

    @RepeatedTest(25)
    void producesLegalReachablePositions() {
        String fen = RandomFenGenerator.generateRandomFen(30);
        assertTrue(PgnCodec.isValidFen(fen), fen);
    }

    @Test
    void zeroMovesIsTheStartPosition() {
        assertEquals(PgnCodec.START_FEN, RandomFenGenerator.generateRandomFen(0));
    }
}
