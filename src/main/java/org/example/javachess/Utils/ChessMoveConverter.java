package org.example.javachess.Utils;
import java.util.List;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveException;

public class ChessMoveConverter {

    public String convertToAlgebraicNotation(String uciMove, String fen) throws MoveException {
        Board board = new Board();
        board.loadFromFen(fen);

        Square from = Square.valueOf(uciMove.substring(0, 2).toUpperCase());
        Square to = Square.valueOf(uciMove.substring(2, 4).toUpperCase());
        Move move = new Move(from, to);
        List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);

        for (Move legalMove : legalMoves) {
            if (legalMove.equals(move)) {
                return getAlgebraicNotation(legalMove, board);
            }
        }
        return uciMove;
    }

    private String getAlgebraicNotation(Move move, Board board) throws MoveException {
        Square from = move.getFrom();
        Square to = move.getTo();
        Piece movingPiece = board.getPiece(from);
        StringBuilder notation = new StringBuilder();

        // Castling
        if (movingPiece == Piece.WHITE_KING || movingPiece == Piece.BLACK_KING) {
            if (from == Square.E1 && to == Square.G1 || from == Square.E8 && to == Square.G8) {
                return "O-O";
            } else if (from == Square.E1 && to == Square.C1 || from == Square.E8 && to == Square.C8) {
                return "O-O-O";
            }
        }

        // Add piece symbol (nothing for pawns)

        notation.append(getPieceSymbol(movingPiece));


        // Capture
        if (board.getPiece(to) != Piece.NONE) {
            if (movingPiece == Piece.WHITE_PAWN || movingPiece == Piece.BLACK_PAWN) {
                notation.append(from.toString().charAt(0)); // For pawn captures, indicate file of origin
            }
            notation.append("x");
        }

        // Add destination square
        notation.append(to.toString().toLowerCase());

        // Promotion
        if (move.getPromotion() != Piece.NONE) {
            notation.append("=").append(getPieceSymbol(move.getPromotion()));
        }

        // Disambiguation
        List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
        for (Move legalMove : legalMoves) {
            if (legalMove.getTo() == to && legalMove.getFrom() != from
                    && board.getPiece(legalMove.getFrom()) == movingPiece) {
                if (from.toString().charAt(0) != legalMove.getFrom().toString().charAt(0)) {
                    notation.insert(1, from.toString().charAt(0));
                } else {
                    notation.insert(1, from.toString().charAt(1));
                }
                break;
            }
        }

        // Check or Checkmate
        board.doMove(move);
        if (board.isMated()) {
            notation.append("#");
        } else if (board.isKingAttacked()) {
            notation.append("+");
        }

        return notation.toString();
    }

    private String getPieceSymbol(Piece piece) {
        switch (piece) {
            case WHITE_KNIGHT:
                return "\u2658"; // Cavallo Bianco
            case BLACK_KNIGHT:
                return "\u265E"; // Cavallo Nero
            case WHITE_BISHOP:
                return "\u2657"; // Alfiere Bianco
            case BLACK_BISHOP:
                return "\u265D"; // Alfiere Nero
            case WHITE_ROOK:
                return "\u2656"; // Torre Bianca
            case BLACK_ROOK:
                return "\u265C"; // Torre Nera
            case WHITE_QUEEN:
                return "\u2655"; // Regina Bianca
            case BLACK_QUEEN:
                return "\u265B"; // Regina Nera
            case WHITE_KING:
                return "\u2654"; // Re Bianco
            case BLACK_KING:
                return "\u265A"; // Re Nero
            case WHITE_PAWN:
                return "\u2659"; // Pedone Bianco
            case BLACK_PAWN:
                return "\u265F"; // Pedone Nero
            default:
                return ""; // Caso di errore o pezzo non riconosciuto
        }
    }

}
