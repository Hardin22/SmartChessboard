package io.github.hardin22.javachess.Hardware;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupGuideTest {

    /** Lucena-like rook ending: kings, rooks and one pawn. */
    static final String ENDING = "1K1k4/1P6/8/8/8/8/r7/2R5 w - - 0 1";

    static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    static long bits(String... squares) {
        long b = 0;
        for (String s : squares) {
            b |= Squares.bit(Squares.parse(s));
        }
        return b;
    }

    @Test
    void groupsGoKingsFirstThenPiecesByValuePawnsLast() {
        SetupGuide guide = new SetupGuide(board(ENDING));
        assertEquals(java.util.List.of(Piece.WHITE_KING, Piece.BLACK_KING, Piece.WHITE_ROOK, Piece.BLACK_ROOK,
                Piece.WHITE_PAWN), guide.groups().stream().map(SetupGuide.Group::piece).toList());
    }

    @Test
    void asksForOneKindOfPieceAtATime() {
        SetupGuide guide = new SetupGuide(board(ENDING));
        SetupGuide.Step step = guide.step(0);
        assertEquals(1, step.index());
        assertEquals(5, step.total());
        assertEquals(bits("b8"), step.missing());
        assertEquals("Posiziona il Re bianco in b8 · passo 1 di 5", step.message());

        step = guide.step(bits("b8"));
        assertEquals("Posiziona il Re nero in d8 · passo 2 di 5", step.message());

        // pieces of later groups placed early are not asked for again
        step = guide.step(bits("b8", "d8", "c1", "b7"));
        assertEquals(Piece.BLACK_ROOK, step.piece());
        assertEquals("Posiziona la Torre nera in a2 · passo 4 di 5", step.message());

        assertNull(guide.step(BoardStateManager.occupancy(board(ENDING))));
    }

    @Test
    void namesSeveralSquaresAndUsesItalianArticles() {
        SetupGuide guide = new SetupGuide(new Board());
        SetupGuide.Step pawns = guide.step(BoardStateManager.occupancy(new Board()) & ~bits("a7", "f7"));
        assertEquals("Posiziona i Pedoni neri: a7, f7 · passo 12 di 12", pawns.message());
        assertEquals("gli Alfieri bianchi", SetupGuide.name(Piece.WHITE_BISHOP, true));
        assertEquals("l'Alfiere nero", SetupGuide.name(Piece.BLACK_BISHOP, false));
        assertEquals("le Donne bianche", SetupGuide.name(Piece.WHITE_QUEEN, true));
        assertEquals("la Donna nera", SetupGuide.name(Piece.BLACK_QUEEN, false));
        assertEquals("i Cavalli neri", SetupGuide.name(Piece.BLACK_KNIGHT, true));
    }

    @Test
    void theStartingPositionAndSmallChangesAreNotGuided() {
        assertFalse(SetupGuide.worthGuiding(new Board(), 0));
        Board ending = board(ENDING);
        long all = BoardStateManager.occupancy(ending);
        assertTrue(SetupGuide.worthGuiding(ending, 0));
        assertFalse(SetupGuide.worthGuiding(ending, all & ~bits("b8", "d8", "b7")), "three squares: shown together");
        // a position after a few moves, set up from the starting position: 2 squares to fill
        Board afterE4E5 = board("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2");
        assertFalse(SetupGuide.worthGuiding(afterE4E5, BoardStateManager.occupancy(new Board())));
    }
}
