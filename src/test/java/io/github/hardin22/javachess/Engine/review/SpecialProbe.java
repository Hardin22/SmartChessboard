package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The numbers behind a Brilliant / Great decision of {@link ReviewClassifier#classifyGame} (validation tools: the
 * false positive / negative lists). Recomputes the inputs of the special-label rules with the product
 * {@link ReviewClassifier.Tuning#DEFAULT}; it does not change the classification.
 */
public final class SpecialProbe {

    private SpecialProbe() {
    }

    /** Ordered name -> value pairs describing move {@code i} of a classified game. */
    public static Map<String, String> explain(ReviewInput in, GameReview r, int i) {
        ReviewClassifier.Tuning t = ReviewClassifier.Tuning.DEFAULT;
        MoveReview m = r.moves().get(i);
        PositionEval p0 = in.positions().get(i);
        boolean me = m.whiteMoved();
        double k = t.slope(me ? in.whiteRating() : in.blackRating());
        boolean top = m.uci().equals(p0.bestMove());
        EngineLine second = p0.secondBest();
        Eval alt = top ? (second == null ? null : second.eval()) : p0.eval();
        double epB = m.winBefore();
        double epA = m.winAfter();
        double oppLoss = i > 0 ? Math.max(0, r.moves().get(i - 1).winBefore() - r.moves().get(i - 1).winAfter()) : 0;
        double gap = top && second != null ? epB - ReviewClassifier.ep(second.eval(), me, k) : Double.NaN;

        Board b0 = new Board();
        b0.loadFromFen(m.fenBefore());
        Board b1 = b0.clone();
        Move mv = Tactics.find(b0, m.uci());
        if (mv != null) {
            b1.doMove(mv);
        }
        Side side = me ? Side.WHITE : Side.BLACK;
        ReviewClassifier.Sacrifice sac = ReviewClassifier.Sacrifice.of(b0, m.uci(), me);

        Map<String, String> o = new LinkedHashMap<>();
        o.put("fen_after", b1.getFen());
        o.put("material_before", String.valueOf(Tactics.material(b0, side) - Tactics.material(b0, side.flip())));
        o.put("material_after", String.valueOf(Tactics.material(b1, side) - Tactics.material(b1, side.flip())));
        o.put("is_top", String.valueOf(top));
        o.put("in_check", String.valueOf(b0.isKingAttacked()));
        o.put("capture", String.valueOf(Tactics.isCapture(b0, m.uci())));
        o.put("epB", f(epB));
        o.put("epA", f(epA));
        o.put("loss", f(epB - epA));
        o.put("alt_eval", alt == null ? "" : alt.format());
        o.put("alt_ep", alt == null ? "" : f(ReviewClassifier.ep(alt, me, k)));
        o.put("gap", f(gap));
        o.put("opp_loss", f(oppLoss));
        o.put("sac_value", String.valueOf(sac.value()));
        o.put("sac_regain", String.valueOf(sac.regain()));
        o.put("rule", rule(m.label(), t, top, epB, epA, alt, me, k, sac, gap, oppLoss));
        return o;
    }

    private static String rule(MoveClassification label, ReviewClassifier.Tuning t, boolean top, double epB, double epA,
                               Eval alt, boolean me, double k, ReviewClassifier.Sacrifice sac, double gap,
                               double oppLoss) {
        if (label == MoveClassification.BRILLIANT) {
            return String.format(java.util.Locale.ROOT,
                    "brilliant rule %d: sacrifice %d >= %.0f (regain %d < value+%.0f), loss %.3f <= %.2f%s, "
                            + "epA %.3f >= %.2f, alt ep %s <= %.2f",
                    t.brilliantRule, sac.value(), t.sacMin, sac.regain(), t.fakeRegain, epB - epA, t.brilliantMaxLoss,
                    top ? "" : String.format(java.util.Locale.ROOT, " (non-top <= %.2f)", t.brilliantNonTopLoss), epA,
                    t.brilliantMinEpAfter, alt == null ? "-" : f(ReviewClassifier.ep(alt, me, k)), t.brilliantMaxAlt);
        }
        if (label == MoveClassification.GREAT) {
            boolean punishes = oppLoss >= t.greatOpponentLoss && gap >= t.greatPunishGap;
            return punishes
                    ? String.format(java.util.Locale.ROOT, "great punish: oppLoss %.3f >= %.2f and gap %.3f >= %.2f",
                    oppLoss, t.greatOpponentLoss, gap, t.greatPunishGap)
                    : String.format(java.util.Locale.ROOT, "great gap: gap %.3f >= %.2f (epB %.3f in %.2f..%.2f)", gap,
                    t.greatGap, epB, t.greatMinEp, t.greatMaxEp);
        }
        return "";
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "" : String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
