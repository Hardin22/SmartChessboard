package org.example.javachess.Vision;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses the rules of chess to read the screen reliably: instead of trusting the classifier square by square, it
 * scores every position that can legally follow the known one (no move, one move, or two moves when the screen
 * changed twice between two frames) against the probabilities of a {@link BoardReading} and keeps the most likely.
 * A misclassified square or two no longer breaks the synchronisation, and impossible readings are rejected.
 */
public final class PositionResolver {

    /** Outcome of a match. */
    public record Resolution(List<Move> moves, double cost, double margin, int mismatches, boolean confident) {
        /** True when the most likely position is the known one (nothing happened). */
        public boolean unchanged() {
            return moves.isEmpty();
        }
    }

    /** Minimum log-likelihood advantage of the winner over the runner-up (nats). */
    private final double minMargin;
    /** Maximum number of squares where the winner disagrees with the classifier's best guess. */
    private final int maxMismatches;

    public PositionResolver() {
        // A move changes 2-4 squares; up to 6 misread squares are tolerated when the winner is clearly ahead.
        this(2.0, 6);
    }

    public PositionResolver(double minMargin, int maxMismatches) {
        this.minMargin = minMargin;
        this.maxMismatches = maxMismatches;
    }

    /**
     * Finds the most likely continuation of {@code current} given what the camera/screen shows.
     *
     * @param twoPlies also consider two consecutive moves (slower: ~1000 candidates)
     */
    public Resolution resolve(Board current, BoardReading reading, boolean twoPlies) {
        double bestCost = Double.MAX_VALUE;
        double secondCost = Double.MAX_VALUE;
        List<Move> best = List.of();

        double still = cost(current, reading);
        bestCost = still;

        List<Move> first;
        try {
            first = MoveGenerator.generateLegalMoves(current);
        } catch (RuntimeException e) {
            first = List.of();
        }
        for (Move m : first) {
            Board b = current.clone();
            b.doMove(m);
            double c = cost(b, reading);
            if (c < bestCost) {
                secondCost = bestCost;
                bestCost = c;
                best = List.of(m);
            } else if (c < secondCost) {
                secondCost = c;
            }
            if (twoPlies) {
                List<Move> replies;
                try {
                    replies = MoveGenerator.generateLegalMoves(b);
                } catch (RuntimeException e) {
                    continue;
                }
                for (Move r : replies) {
                    Board b2 = b.clone();
                    b2.doMove(r);
                    double c2 = cost(b2, reading) + 0.5; // small prior against assuming two moves
                    if (c2 < bestCost) {
                        secondCost = bestCost;
                        bestCost = c2;
                        best = List.of(m, r);
                    } else if (c2 < secondCost) {
                        secondCost = c2;
                    }
                }
            }
        }
        Board winner = current.clone();
        for (Move m : best) {
            winner.doMove(m);
        }
        int mismatches = mismatches(winner, reading);
        double margin = secondCost == Double.MAX_VALUE ? Double.MAX_VALUE : secondCost - bestCost;
        boolean confident = mismatches <= maxMismatches && margin >= minMargin;
        return new Resolution(best, bestCost, margin, mismatches, confident);
    }

    /** Negative log-likelihood of a position under the reading. */
    static double cost(Board board, BoardReading reading) {
        double sum = 0;
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                float p = reading.probability(f, r, symbolAt(board, f, r));
                sum -= Math.log(Math.max(1e-4, p));
            }
        }
        return sum;
    }

    static int mismatches(Board board, BoardReading reading) {
        int n = 0;
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                if (reading.pieceAt(f, r) != symbolAt(board, f, r)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Squares where the board and the reading disagree (e.g. "e4"), useful for status messages. */
    public static List<String> differences(Board board, BoardReading reading) {
        List<String> out = new ArrayList<>();
        for (int r = 7; r >= 0; r--) {
            for (int f = 0; f < 8; f++) {
                if (reading.pieceAt(f, r) != symbolAt(board, f, r)) {
                    out.add(BoardReading.squareName(f, r));
                }
            }
        }
        return out;
    }

    static char symbolAt(Board board, int file, int rank) {
        Piece p = board.getPiece(Square.squareAt(rank * 8 + file));
        if (p == null || p == Piece.NONE) {
            return BoardReading.EMPTY;
        }
        return p.getFenSymbol().charAt(0);
    }
}
