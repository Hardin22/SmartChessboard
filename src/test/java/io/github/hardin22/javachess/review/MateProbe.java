package io.github.hardin22.javachess.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

/** Engine-free mate facts (pure move generation), the ground truth of the mate sanity checks. */
public final class MateProbe {

    private MateProbe() {
    }

    /** True when the side to move is checkmated. */
    public static boolean isMate(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b.isMated();
    }

    /** A move (UCI) that mates at once for the side to move, or null. */
    public static String mateInOneFor(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        for (Move m : b.legalMoves()) {
            b.doMove(m);
            boolean mate = b.isMated();
            b.undoMove();
            if (mate) {
                return m.toString();
            }
        }
        return null;
    }
}
