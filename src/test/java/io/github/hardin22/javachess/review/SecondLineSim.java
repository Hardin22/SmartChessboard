package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier.Tuning;
import io.github.hardin22.javachess.Engine.review.ReviewInput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Simulates a re-search of the Great/Brilliant candidates (the positions {@link ReviewClassifier#needsSecondLine}
 * picks) with a deeper budget: their Lite evaluation and second line are replaced by the deep dump's, everything else
 * stays Lite. Prints precision/recall of Brilliant, Great and Miss, exact, cases two levels away and the extra engine
 * work, for the current {@code -Djavachess.review.*} knobs. Developer tool:
 * <pre>java -cp ... io.github.hardin22.javachess.review.SecondLineSim [dump dir] [labels dir] [lite budget] [deep budget] [mode]</pre>
 * mode: {@code lite} (no re-search), {@code second} (candidates get the deep second line when the deep best move is
 * the Lite one), {@code full} (candidates get the deep evaluation and second line).
 */
public final class SecondLineSim {

    private SecondLineSim() {
    }

    public static void main(String[] args) throws Exception {
        Path data = EvalDumpTest.TEAM_DATA;
        Path dump = Path.of(args.length > 0 ? args[0] : data.resolve("evals_labeled").toString());
        Path labels = Path.of(args.length > 1 ? args[1] : data.resolve("labels_chesscom_sf22").toString());
        String liteBudget = args.length > 2 ? args[2] : "lite-block";
        String deepBudget = args.length > 3 ? args[3] : "deep";
        String mode = args.length > 4 ? args[4] : "full";
        Map<String, Integer> folds = ReviewCv.folds(dump.resolve("folds.json"));
        Map<String, LabelledGame> labelled = new HashMap<>();
        for (LabelledGame g : LabelledGame.loadAll(labels, data.resolve("games"))) {
            labelled.put(g.id(), g);
        }
        Map<String, EvalDump.Game> deep = new HashMap<>();
        for (EvalDump.Game d : EvalDump.load(dump, deepBudget)) {
            deep.put(d.id(), d);
        }
        OpeningBook book = OpeningBook.standard();
        Tuning t = Tuning.DEFAULT;
        int plies = 0;
        int exact = 0;
        int far = 0;
        Map<String, int[]> pr = new HashMap<>(); // tp, fp, fn
        long liteNodes = 0;
        long extraNodes = 0;
        int candidates = 0;
        int games = 0;
        for (EvalDump.Game lite : EvalDump.load(dump, liteBudget)) {
            LabelledGame g = labelled.get(lite.id());
            EvalDump.Game d = deep.get(lite.id());
            if (g == null || d == null || (!folds.isEmpty() && !folds.containsKey(lite.id()))) {
                continue;
            }
            games++;
            ReviewInput base = lite.input(EvalDump.Mode.PRODUCT, book);
            List<PositionEval> ps = new ArrayList<>(base.positions());
            BitSet need = ReviewClassifier.needsSecondLine(new ReviewInput(base.initialFen(), base.uciMoves(),
                    lite.input(EvalDump.Mode.SECOND_EVERYWHERE, book).positions(), book));
            // candidates: every position the product searches a second line for (computed on the plain Lite input)
            BitSet cand = new BitSet();
            List<PositionEval> plain = new ArrayList<>();
            for (EvalDump.Position p : lite.positions()) {
                plain.add(p.main());
            }
            cand.or(ReviewClassifier.needsSecondLine(new ReviewInput(base.initialFen(), base.uciMoves(), plain,
                    book)));
            for (PositionEval p : ps) {
                liteNodes += p.nodes();
            }
            for (int i = cand.nextSetBit(0); i >= 0; i = cand.nextSetBit(i + 1)) {
                candidates++;
                EvalDump.Position dp = d.positions().get(i);
                if (dp.terminal()) {
                    continue;
                }
                if ("full".equals(mode)) {
                    ps.set(i, dp.withSecond());
                    extraNodes += dp.nodes() + (dp.second() == null ? 0 : dp.second().nodes());
                } else if ("second".equals(mode) && dp.second() != null && dp.pv().get(0).equals(
                        ps.get(i).bestMove())) {
                    PositionEval p = ps.get(i);
                    ps.set(i, new PositionEval(p.fen(), p.eval(), List.of(p.best(), dp.second().engineLine()),
                            p.depth(), p.nodes(), false));
                    extraNodes += dp.second().nodes();
                }
            }
            GameReview r = ReviewClassifier.classifyGame(new ReviewInput(base.initialFen(), base.uciMoves(), ps, book,
                    g.whiteRating(), g.blackRating()), t);
            for (int i = 0; i < r.moves().size(); i++) {
                ReviewLabel ours = ReviewLabel.of(r.moves().get(i).label());
                ReviewLabel theirs = g.labels().get(i);
                plies++;
                exact += ours == theirs ? 1 : 0;
                far += LabelTuner.distance(ours, theirs) >= 2 ? 1 : 0;
                for (ReviewLabel c : List.of(ReviewLabel.BRILLIANT, ReviewLabel.GREAT, ReviewLabel.MISS)) {
                    int[] v = pr.computeIfAbsent(c.name(), x -> new int[3]);
                    if (ours == c && theirs == c) {
                        v[0]++;
                    } else if (ours == c) {
                        v[1]++;
                    } else if (theirs == c) {
                        v[2]++;
                    }
                }
            }
            if (need.isEmpty()) {
                continue;
            }
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%s: %d games %d plies exact %.3f far %d | candidates %.1f/game, extra nodes %+.0f%%", mode, games,
                plies, (double) exact / plies, far, (double) candidates / Math.max(1, games),
                100.0 * extraNodes / Math.max(1, liteNodes)));
        for (String c : List.of("BRILLIANT", "GREAT", "MISS")) {
            int[] v = pr.get(c);
            sb.append(String.format(Locale.ROOT, " | %s %d/%d P %.2f R %.2f", c.toLowerCase(Locale.ROOT), v[0], v[1],
                    v[0] / (double) Math.max(1, v[0] + v[1]), v[0] / (double) Math.max(1, v[0] + v[2])));
        }
        System.out.println(sb);
    }
}
