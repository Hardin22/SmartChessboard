package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.Map;

/** Scratch (shard 4, not committed): board facts for the features table. */
public final class Shard4Tactics {
    private Shard4Tactics() {
    }

    /** "movedHanging,hangBefore,hangAfter,see" for the move. */
    public static String facts(String fen, String uci, boolean white) {
        Board b0 = new Board();
        b0.loadFromFen(fen);
        Side side = white ? Side.WHITE : Side.BLACK;
        Move m = Tactics.find(b0, uci);
        Map<com.github.bhlangonijr.chesslib.Square, Integer> hb = Tactics.hanging(b0, side);
        boolean movedHanging = m != null && hb.containsKey(m.getFrom());
        Board b1 = b0.clone();
        if (m != null) {
            b1.doMove(m);
        }
        Map<com.github.bhlangonijr.chesslib.Square, Integer> ha = Tactics.hanging(b1, side);
        int hbMax = hb.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int haMax = ha.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int see = m == null ? 0 : Tactics.see(b0, m.getTo());
        int oppHangBefore = Tactics.hanging(b0, side.flip()).values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return movedHanging + "\t" + hbMax + "\t" + haMax + "\t" + see + "\t" + oppHangBefore;
    }
}
