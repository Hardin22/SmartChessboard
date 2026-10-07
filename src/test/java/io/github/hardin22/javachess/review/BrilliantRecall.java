package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Brilliant recall on the Chessigma benchmark (chess.com-certified Brilliants, one per game, other moves unlabelled):
 * classifies the dumped evaluations with the current {@code -Djavachess.review.*} knobs and reports how many
 * certified Brilliants we find, our label for the missed ones, and how many other moves of those games we call
 * Brilliant (a rough upper bound of false positives: chess.com may have given more than one). Developer tool:
 * <pre>java -cp ... io.github.hardin22.javachess.review.BrilliantRecall [dump dir] [mode product|second] [budget]</pre>
 * Hold-out games (folds.json) are skipped.
 */
public final class BrilliantRecall {

    private BrilliantRecall() {
    }

    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0]
                : EvalDumpTest.TEAM_DATA.resolve("evals_chessigma").toString());
        EvalDump.Mode mode = args.length > 1 && args[1].equals("second") ? EvalDump.Mode.SECOND_EVERYWHERE
                : EvalDump.Mode.PRODUCT;
        // hold-out games (folds.json "holdout") are never used for tuning
        java.util.Set<String> holdout = new java.util.HashSet<>();
        try {
            org.json.JSONArray h = new org.json.JSONObject(java.nio.file.Files.readString(
                    EvalDumpTest.TEAM_DATA.resolve("evals_labeled").resolve("folds.json"))).optJSONArray("holdout");
            for (int i = 0; h != null && i < h.length(); i++) {
                holdout.add(h.getString(i));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Map<String, ChessComDataset.Game> games = new HashMap<>();
        for (ChessComDataset.Game g : ChessComDataset.load()) {
            games.put(g.id(), g);
        }
        OpeningBook book = OpeningBook.standard();
        int positives = 0;
        int found = 0;
        int others = 0;
        int plies = 0;
        Map<String, Integer> missedAs = new TreeMap<>();
        int skipped = 0;
        for (EvalDump.Game d : EvalDump.load(dir, args.length > 2 ? args[2] : "lite")) {
            ChessComDataset.Game g = games.get(d.id());
            if (holdout.contains(d.id())) {
                skipped++;
                continue;
            }
            if (g == null || !g.hasLabels() || !g.uci().equals(d.uci())) {
                continue;
            }
            ReviewInput base = d.input(mode, book);
            GameReview r = ReviewClassifier.classifyGame(new ReviewInput(base.initialFen(), base.uciMoves(),
                    base.positions(), book, g.whiteRating(), g.blackRating()));
            for (int i = 0; i < r.moves().size(); i++) {
                boolean ours = r.moves().get(i).label() == MoveClassification.BRILLIANT;
                plies++;
                if (g.labels().get(i) == ReviewLabel.BRILLIANT) {
                    positives++;
                    if (ours) {
                        found++;
                    } else {
                        missedAs.merge(r.moves().get(i).label().name(), 1, Integer::sum);
                        System.out.printf(Locale.ROOT, "  missed %s ply %d %s -> %s%n", g.id(), i + 1,
                                g.san().get(i), r.moves().get(i).label());
                    }
                } else if (ours) {
                    others++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "(%d hold-out games skipped)%n", skipped);
        System.out.printf(Locale.ROOT, "Chessigma: %d/%d certified Brilliants found (recall %.2f), missed as %s; "
                + "%d other moves called Brilliant in %d plies%n", found, positives,
                positives == 0 ? 0 : (double) found / positives, missedAs, others, plies);
    }
}
