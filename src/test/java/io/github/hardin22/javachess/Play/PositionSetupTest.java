package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionSetupTest {

    @Test
    void standardPositionIsAccepted() {
        PositionSetup.Result r = PositionSetup.check("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        assertTrue(r.ok());
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", r.fen());
        assertTrue(r.notes().isEmpty());
    }

    @Test
    void placementOnlyGetsDefaults() {
        PositionSetup.Result r = PositionSetup.check("4k3/8/8/8/8/8/4P3/4K3");
        assertTrue(r.ok());
        assertEquals("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1", r.fen());
        PositionSetup.Result black = PositionSetup.check("r3k2r/8/8/8/8/8/8/R3K2R b");
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1", black.fen(), "rights inferred from king and rooks");
    }

    @Test
    void impossibleCastlingRightsAreRemovedWithANote() {
        PositionSetup.Result r = PositionSetup.check("4k3/8/8/8/8/8/8/R3K3 w KQkq - 0 1");
        assertTrue(r.ok());
        assertEquals("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1", r.fen());
        assertEquals(1, r.notes().size());
        assertTrue(r.notes().get(0).startsWith("Arrocco tolto (Kkq)"));
    }

    @Test
    void errorsAreExplained() {
        assertFalse(PositionSetup.check("8/8/8/8/8/8/8/4K3 w - - 0 1").ok());
        assertTrue(PositionSetup.check("8/8/8/8/8/8/8/4K3 w - - 0 1").errors().contains("Manca il re per il Nero"));
        assertTrue(PositionSetup.check("4k3/8/8/8/8/8/8/3KK3 w - - 0 1").errors().get(0).startsWith("Troppi re"));
        assertTrue(PositionSetup.check("P3k3/8/8/8/8/8/8/4K3 w - - 0 1").errors().get(0).startsWith("Pedone su a8"));
        assertTrue(PositionSetup.check("4k3/8/8/8/8/8/8 w").errors().get(0).startsWith("La posizione deve avere 8"));
        assertTrue(PositionSetup.check("4k3/8/8/8/8/8/8/4K4 w").errors().get(0).startsWith("La traversa 1 ha 9"));
        assertTrue(PositionSetup.check("4k3/8/8/8/8/8/8/4X3 w").errors().get(0).startsWith("Simbolo non valido"));
        assertTrue(PositionSetup.check("4k3/8/8/8/8/8/8/4K3 x").errors().get(0).startsWith("Tratto non valido"));
        assertEquals("Posizione vuota", PositionSetup.check(" ").errors().get(0));
    }

    @Test
    void sideNotToMoveInCheckAndTouchingKings() {
        // Black king attacked by the rook with White to move: impossible
        PositionSetup.Result r = PositionSetup.check("4k3/8/8/8/8/8/8/4R1K1 w - - 0 1");
        assertFalse(r.ok());
        assertEquals("Il re nero è sotto scacco ma non tocca a lui muovere", r.errors().get(0));
        assertTrue(PositionSetup.check("4k3/8/8/8/8/8/8/4R1K1 b - - 0 1").ok(), "Black to move, in check: fine");
        assertEquals("I due re non possono stare su case vicine",
                PositionSetup.check("8/8/8/8/8/3k4/3K4/8 w - - 0 1").errors().get(0));
    }

    @Test
    void finishedPositionsCannotStartAGame() {
        assertEquals("La partita è già finita: scacco matto",
                PositionSetup.check("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3").errors().get(0));
        assertEquals("La partita è già finita: stallo",
                PositionSetup.check("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").errors().get(0));
    }

    @Test
    void enPassantOnlyWhenPossible() {
        PositionSetup.Result ok = PositionSetup.check("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
        assertEquals("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", ok.fen());
        PositionSetup.Result dropped = PositionSetup.check("4k3/8/8/4P3/8/8/8/4K3 w - d6 0 1");
        assertEquals("4k3/8/8/4P3/8/8/8/4K3 w - - 0 1", dropped.fen());
        assertTrue(dropped.notes().get(0).startsWith("Presa en passant ignorata"));
    }

    @Test
    void fromTheEditor() {
        Piece[] pieces = new Piece[64];
        pieces[Square.E1.ordinal()] = Piece.WHITE_KING;
        pieces[Square.H1.ordinal()] = Piece.WHITE_ROOK;
        pieces[Square.E8.ordinal()] = Piece.BLACK_KING;
        pieces[Square.D7.ordinal()] = Piece.BLACK_PAWN;
        PositionSetup.Result r = PositionSetup.fromEditor(pieces, Side.BLACK);
        assertTrue(r.ok());
        assertEquals("4k3/3p4/8/8/8/8/8/4K2R b K - 0 1", r.fen());
        assertTrue(r.notes().isEmpty(), "inferred rights are not a correction");
    }
}
