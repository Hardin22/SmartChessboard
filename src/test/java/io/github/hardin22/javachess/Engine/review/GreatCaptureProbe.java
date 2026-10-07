package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier.Tuning;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import io.github.hardin22.javachess.review.EvalDump;
import io.github.hardin22.javachess.review.LabelledGame;
import io.github.hardin22.javachess.review.ReviewLabel;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Captures our classifier would call Great without G-E1 (no captures), with static features, against the chess.com
 * label: which captures are "found" moves and which are routine. Developer tool, current {@code -Djavachess.review.*}
 * knobs (run it with {@code greatNoCapture=0}).
 * <pre>java -cp ... io.github.hardin22.javachess.Engine.review.GreatCaptureProbe [dump dir] [labels dir] [budget]</pre>
 */
public final class GreatCaptureProbe {

    private GreatCaptureProbe() {
    }

    public static void main(String[] args) {
        Path dump = Path.of(args[0]);
        Path labels = Path.of(args[1]);
        String budget = args.length > 2 ? args[2] : "lite-block";
        Path data = labels.getParent();
        Map<String, LabelledGame> labelled = new HashMap<>();
        for (LabelledGame g : LabelledGame.loadAll(labels, data.resolve("games"))) {
            labelled.put(g.id(), g);
        }
        OpeningBook book = OpeningBook.standard();
        System.out.println("game\tply\tsan\tours\tcc\tcapturer\tcaptured\tsee_gain\trecapture\tprev_capture_elsewhere"
                + "\tcheck\topp_loss\tgap\tep_before");
        for (EvalDump.Game d : EvalDump.load(dump, budget)) {
            LabelledGame g = labelled.get(d.id());
            if (g == null) {
                continue;
            }
            ReviewInput base = d.input(EvalDump.Mode.PRODUCT, book);
            ReviewInput in = new ReviewInput(base.initialFen(), base.uciMoves(), base.positions(), book,
                    g.whiteRating(), g.blackRating());
            GameReview r = ReviewClassifier.classifyGame(in, Tuning.DEFAULT);
            GameReplay replay = GameReplay.of(in.initialFen(), in.uciMoves());
            for (int i = 0; i < r.moves().size(); i++) {
                MoveReview m = r.moves().get(i);
                ReviewLabel cc = g.labels().get(i);
                Board b0 = new Board();
                b0.loadFromFen(replay.fens().get(i));
                if (!Tactics.isCapture(b0, m.uci())) {
                    continue;
                }
                if (m.label() != MoveClassification.GREAT && cc != ReviewLabel.GREAT) {
                    continue;
                }
                Move mv = Tactics.find(b0, m.uci());
                int capturer = Tactics.value(b0.getPiece(mv.getFrom()));
                int captured = Math.max(1, Tactics.value(b0.getPiece(mv.getTo())));
                Board b1 = b0.clone();
                b1.doMove(mv);
                int seeGain = captured - Tactics.see(b1, mv.getTo());
                boolean recapture = false;
                boolean prevCaptureElsewhere = false;
                if (i > 0) {
                    Board bp = new Board();
                    bp.loadFromFen(replay.fens().get(i - 1));
                    String prev = replay.uci().get(i - 1);
                    boolean prevCapture = Tactics.isCapture(bp, prev);
                    recapture = prevCapture && prev.substring(2, 4).equals(m.uci().substring(2, 4));
                    prevCaptureElsewhere = prevCapture && !recapture;
                }
                double opp = i > 0 ? r.moves().get(i - 1).winLoss() : 0;
                EngineLine second = in.positions().get(i).secondBest();
                double gap = second == null ? Double.NaN
                        : m.winBefore() - ReviewClassifier.ep(second.eval(), m.whiteMoved(),
                        Tuning.DEFAULT.slope(m.whiteMoved() ? g.whiteRating() : g.blackRating()));
                System.out.printf(Locale.ROOT, "%s\t%d\t%s\t%s\t%s\t%d\t%d\t%d\t%b\t%b\t%b\t%.3f\t%.3f\t%.3f%n", d.id(),
                        i + 1, m.san(), m.label().name().toLowerCase(Locale.ROOT),
                        cc == null ? "" : cc.name().toLowerCase(Locale.ROOT), capturer, captured, seeGain, recapture,
                        prevCaptureElsewhere, b1.isKingAttacked(), opp, gap, m.winBefore());
            }
        }
    }
}
