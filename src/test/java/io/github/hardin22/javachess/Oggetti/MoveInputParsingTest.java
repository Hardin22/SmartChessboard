package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Moves typed or tapped on the screen reach the games through {@link AbstractGame#parseMoveInput}. */
class MoveInputParsingTest {

    /** No board on screen, no engine: only the parsing of the base class is exercised. */
    private static final class Game extends AbstractGame {
        Game(String fen) {
            super(null, null);
            board.loadFromFen(fen);
        }

        @Override
        public void startGame() {
        }

        @Override
        public void handleMoveInput(String moveInput) {
        }

        @Override
        public void endGame(String endMessage, boolean saveGame) {
        }

        Move parse(String text) {
            return parseMoveInput(text);
        }
    }

    @Test
    void uciWithThePromotionPieceChosenOnTheScreen() {
        Game white = new Game("7k/4P3/8/8/8/8/8/K7 w - - 0 1");
        assertEquals(new Move(Square.E7, Square.E8, Piece.WHITE_KNIGHT), white.parse("e7e8n"));
        assertEquals(new Move(Square.E7, Square.E8, Piece.WHITE_QUEEN), white.parse("e7e8Q"));
        Game black = new Game("k7/8/8/8/8/8/3p4/7K b - - 0 1");
        assertEquals(new Move(Square.D2, Square.D1, Piece.BLACK_ROOK), black.parse("d2d1r"));
    }

    @Test
    void plainUciAndShortForms() {
        Game game = new Game("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        assertEquals(new Move(Square.E2, Square.E4), game.parse("e2e4"));
        assertEquals(new Move(Square.G1, Square.F3), game.parse("Nf3"));
        assertEquals(new Move(Square.D2, Square.D4), game.parse("d4"));
    }

    @Test
    void garbageIsNotAMoveInsteadOfAnException() {
        Game game = new Game("7k/4P3/8/8/8/8/8/K7 w - - 0 1");
        assertNull(game.parse("z9z9"));
        assertNull(game.parse("e7e8x"), "x is not a promotion piece");
        assertNull(game.parse("c3c4q"), "no piece on c3");
        assertNull(game.parse("Xe4"));
        assertNull(game.parse(""));
        assertNull(game.parse(null));
    }
}
