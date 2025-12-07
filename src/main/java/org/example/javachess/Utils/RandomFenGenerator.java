package org.example.javachess.Utils;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import java.util.List;
import java.util.Random;

public class RandomFenGenerator {

    public static String generateRandomFen(int movesCount) {
        Board board = new Board();
        Random random = new Random();

        for (int i = 0; i < movesCount; i++) {
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            if (legalMoves.isEmpty()) {
                break; // Nessuna mossa legale disponibile
            }
            Move randomMove = legalMoves.get(random.nextInt(legalMoves.size()));
            board.doMove(randomMove);
        }

        return board.getFen();
    }

    public static void main(String[] args) {
        String randomFen = generateRandomFen(10); // Genera una posizione dopo 10 mosse
        System.out.println("FEN casuale: " + randomFen);
    }
}
