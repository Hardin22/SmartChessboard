package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Simulates a targeted re-search offline: the Lite evaluations of the dump, with the positions a policy picks replaced
 * by their deep evaluations, and the agreement with chess.com and the extra engine work it costs. Developer tool:
 * <pre>java -cp ... io.github.hardin22.javachess.review.ResearchSim</pre>
 */
public final class ResearchSim {

    private record Pair(LabelledGame g, EvalDump.Game lite, EvalDump.Game deep) {
    }

    private ResearchSim() {
    }

    public static void main(String[] args) {
        Path data = EvalDumpTest.TEAM_DATA;
        Path dump = data.resolve("evals_labeled");
        Map<String, LabelledGame> labelled = new HashMap<>();
        for (LabelledGame g : LabelledGame.loadAll(data.resolve("labels_chesscom"), data.resolve("games"))) {
            labelled.put(g.id(), g);
        }
        Map<String, EvalDump.Game> deep = new HashMap<>();
        for (EvalDump.Game d : EvalDump.load(dump, "deep")) {
            deep.put(d.id(), d);
        }
        List<Pair> pairs = new ArrayList<>();
        for (EvalDump.Game l : EvalDump.load(dump, "lite")) {
            if (deep.containsKey(l.id()) && labelled.containsKey(l.id())) {
                pairs.add(new Pair(labelled.get(l.id()), l, deep.get(l.id())));
            }
        }
        OpeningBook book = OpeningBook.standard();
        System.out.printf(Locale.ROOT, "%d games with lite and deep evaluations%n", pairs.size());

        Map<String, BiFunction<GameReview, Integer, Boolean>> policies = new java.util.LinkedHashMap<>();
        policies.put("none", (r, i) -> false);
        policies.put("all", (r, i) -> true);
        for (double d : new double[]{0.005, 0.01, 0.02}) {
            policies.put("near threshold " + d, (r, i) -> nearThreshold(r.moves().get(i), d));
        }
        policies.put("errors (?! or worse)", (r, i) -> severity(r.moves().get(i).label()) >= 3);
        policies.put("non-top", (r, i) -> !r.moves().get(i).uci().equals(r.moves().get(i).bestMove()));
        policies.put("near threshold 0.01 + errors", (r, i) -> nearThreshold(r.moves().get(i), 0.01)
                || severity(r.moves().get(i).label()) >= 3);

        for (Map.Entry<String, BiFunction<GameReview, Integer, Boolean>> p : policies.entrySet()) {
            int plies = 0;
            int exact = 0;
            int far = 0;
            long liteNodes = 0;
            long extra = 0;
            for (Pair pair : pairs) {
                ReviewInput lite = input(pair, pair.lite(), book);
                GameReview r0 = ReviewClassifier.classifyGame(lite);
                int n = r0.moves().size();
                BitSet pick = new BitSet(n + 1);
                for (int i = 0; i < n; i++) {
                    if (r0.moves().get(i).label() != MoveClassification.BOOK_MOVE
                            && r0.moves().get(i).label() != MoveClassification.FORCED && p.getValue().apply(r0, i)) {
                        pick.set(i);
                        pick.set(i + 1);
                    }
                }
                List<EvalDump.Position> mixed = new ArrayList<>();
                for (int j = 0; j <= n; j++) {
                    EvalDump.Position lp = pair.lite().positions().get(j);
                    liteNodes += lp.nodes();
                    if (pick.get(j) && !lp.terminal()) {
                        mixed.add(pair.deep().positions().get(j));
                        extra += pair.deep().positions().get(j).nodes();
                    } else {
                        mixed.add(lp);
                    }
                }
                EvalDump.Game g = new EvalDump.Game(pair.lite().id(), pair.lite().initialFen(), pair.lite().uci(),
                        "mixed", 0, 0, mixed);
                GameReview r = ReviewClassifier.classifyGame(input(pair, g, book));
                for (int i = 0; i < n; i++) {
                    ReviewLabel theirs = pair.g().labels().get(i);
                    ReviewLabel ours = ReviewLabel.of(r.moves().get(i).label());
                    plies++;
                    exact += ours == theirs ? 1 : 0;
                    far += LabelTuner.distance(ours, theirs) >= 2 ? 1 : 0;
                }
            }
            System.out.printf(Locale.ROOT, "%-32s exact %.1f%%  >=2 %3d  extra nodes %+5.0f%%%n", p.getKey(),
                    100.0 * exact / plies, far, 100.0 * extra / liteNodes);
        }
    }

    private static ReviewInput input(Pair pair, EvalDump.Game g, OpeningBook book) {
        ReviewInput in = g.input(EvalDump.Mode.PRODUCT, book);
        return new ReviewInput(in.initialFen(), in.uciMoves(), in.positions(), book, pair.g().whiteRating(),
                pair.g().blackRating());
    }

    /** The move's win chance loss is within {@code d} of a label boundary (Best/Excellent counts at loss 0). */
    static boolean nearThreshold(MoveReview m, double d) {
        double loss = m.winLoss();
        boolean top = m.uci().equals(m.bestMove());
        if (!top && loss < d) {
            return true;
        }
        for (double t : new double[]{ReviewClassifier.EXCELLENT_MAX, ReviewClassifier.GOOD_MAX,
                ReviewClassifier.INACCURACY_MAX, ReviewClassifier.MISTAKE_MAX}) {
            if (Math.abs(loss - t) < d) {
                return true;
            }
        }
        return false;
    }

    static int severity(MoveClassification c) {
        return switch (c) {
            case BRILLIANT, GREAT, BEST, BOOK_MOVE, FORCED -> 0;
            case EXCELLENT -> 1;
            case GOOD -> 2;
            case INACCURACY -> 3;
            case MISTAKE, MISS -> 4;
            case BLUNDER -> 5;
        };
    }
}
