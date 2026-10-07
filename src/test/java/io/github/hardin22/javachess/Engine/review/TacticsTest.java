package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Static exchange evaluation and piece safety used by Brilliant / Great / Blunder. */
class TacticsTest {

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    @Test
    void hangingQueenIsLost() {
        Board b = board("4k3/8/8/4p3/3Q4/8/8/4K3 b - - 0 1");
        assertEquals(9, Tactics.see(b, Square.D4));
        assertFalse(Tactics.isSafe(b, Square.D4));
        // same position with White to move: safety is judged for the opponent (null move)
        assertFalse(Tactics.isSafe(board("4k3/8/8/4p3/3Q4/8/8/4K3 w - - 0 1"), Square.D4));
    }

    @Test
    void defendedKnightAttackedByBishopIsSafe() {
        // black bishop b6 attacks the knight d4, defended by the pawn c3
        Board b = board("4k3/8/1b6/8/3N4/2P5/8/4K3 b - - 0 1");
        assertEquals(0, Tactics.see(b, Square.D4));
        assertTrue(Tactics.isSafe(b, Square.D4));
    }

    @Test
    void xrayRecaptureCounts() {
        // white rooks d1+d2 vs black rook d8 on the pawn d5 defended by ... nothing: Rxd5 Rxd5 Rxd5 wins a pawn
        Board b = board("3r2k1/8/8/3p4/8/8/3R4/3R2K1 w - - 0 1");
        assertEquals(1, Tactics.see(b, Square.D5));
    }

    @Test
    void unsafePiecesAndMaterial() {
        Board b = board("4k3/8/8/4p3/3Q4/8/8/4K3 b - - 0 1");
        List<Square> unsafe = Tactics.unsafePieces(b, Side.WHITE, 0);
        assertEquals(List.of(Square.D4), unsafe);
        assertEquals(8, Tactics.material(b, Side.WHITE));
        assertEquals(-1, Tactics.materialAfter("4k3/8/8/4p3/3Q4/8/8/4K3 b - - 0 1", List.of("e5d4"), Side.WHITE, 6));
    }
}
