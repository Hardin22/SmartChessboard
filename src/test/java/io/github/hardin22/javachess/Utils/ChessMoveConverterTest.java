package io.github.hardin22.javachess.Utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChessMoveConverterTest {

    private final ChessMoveConverter converter = new ChessMoveConverter();

    @Test
    void figurineNotation() throws Exception {
        assertEquals("e4", converter.convertToAlgebraicNotation("e2e4", PgnCodec.START_FEN));
        assertEquals("♘f3", converter.convertToAlgebraicNotation("g1f3", PgnCodec.START_FEN));
        assertEquals("♞f6", converter.convertToAlgebraicNotation("g8f6",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"));
    }

    @Test
    void enPassantPromotionAndCastling() throws Exception {
        assertEquals("exd6", converter.convertToAlgebraicNotation("e5d6", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2"));
        assertEquals("a8=♕+", converter.convertToAlgebraicNotation("a7a8q", "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"));
        assertEquals("a8=♘", converter.convertToAlgebraicNotation("a7a8n", "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"));
        assertEquals("O-O", converter.convertToAlgebraicNotation("e1g1", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"));
    }

    @Test
    void illegalOrInvalidInputIsReturnedUnchanged() throws Exception {
        assertEquals("e2e5", converter.convertToAlgebraicNotation("e2e5", PgnCodec.START_FEN));
        assertEquals("e2e4", converter.convertToAlgebraicNotation("e2e4", "not a fen"));
        assertNull(converter.convertToAlgebraicNotation(null, PgnCodec.START_FEN));
    }
}
