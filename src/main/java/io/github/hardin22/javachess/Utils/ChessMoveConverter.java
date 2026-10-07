package io.github.hardin22.javachess.Utils;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveException;

/**
 * Converts engine moves (UCI) to algebraic notation with chess figurines for display (e.g. "♘f3", "exd6", "e8=♕+").
 * The notation itself comes from {@link PgnCodec#toSan}, so promotions, en passant, castling and disambiguation
 * follow the standard rules; pawns get no figurine.
 */
public class ChessMoveConverter {

    /**
     * @return the move in figurine algebraic notation, or {@code uciMove} unchanged when it is not legal in
     *         {@code fen}
     */
    public String convertToAlgebraicNotation(String uciMove, String fen) throws MoveException {
        Board board = PgnCodec.boardFromFen(fen);
        if (board == null || uciMove == null) {
            return uciMove;
        }
        Move move = PgnCodec.fromUci(board, uciMove.trim());
        if (move == null) {
            return uciMove;
        }
        return toFigurine(PgnCodec.toSan(board, move), board.getSideToMove());
    }

    /** Replaces the piece letters of a SAN move with figurines of the given side. */
    static String toFigurine(String san, Side side) {
        if (san.startsWith("O-O")) {
            return san;
        }
        StringBuilder out = new StringBuilder(san.length());
        for (int i = 0; i < san.length(); i++) {
            char c = san.charAt(i);
            out.append(switch (c) {
                case 'K' -> side == Side.WHITE ? "\u2654" : "\u265A";
                case 'Q' -> side == Side.WHITE ? "\u2655" : "\u265B";
                case 'R' -> side == Side.WHITE ? "\u2656" : "\u265C";
                case 'B' -> side == Side.WHITE ? "\u2657" : "\u265D";
                case 'N' -> side == Side.WHITE ? "\u2658" : "\u265E";
                default -> String.valueOf(c);
            });
        }
        return out.toString();
    }
}
