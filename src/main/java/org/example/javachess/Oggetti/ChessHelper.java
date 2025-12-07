package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import java.util.List;
import java.util.stream.Collectors;

public class ChessHelper {

    public List<Square> getLegalMovesForPiece(Board board, Square square) {
        Piece piece = board.getPiece(square);
        if (piece == Piece.NONE) {
            return List.of(); // Nessun pezzo su questo quadrato
        }

        // Ottieni tutte le mosse legali dalla posizione corrente
        List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);

        // Filtra le mosse per ottenere solo quelle relative al pezzo specifico
        return legalMoves.stream()
                .filter(move -> move.getFrom().equals(square))
                .map(Move::getTo)
                .collect(Collectors.toList());
    }
}
