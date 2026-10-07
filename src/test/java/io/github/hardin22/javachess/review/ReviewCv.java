package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.EngineLine;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Engine-free classification of the labelled games from the evaluation dump ({@link EvalDump}), one row per ply,
 * for the cross-validation driver {@code scripts/review/cv.py} (which runs this once per parameter set, with the
 * {@code -Djavachess.review.*} knobs of {@link ReviewClassifier}, and computes the metrics).
 * <pre>
 * java -cp ... io.github.hardin22.javachess.review.ReviewCv --out DIR [--dump DIR] [--budget lite] [--mode product]
 *     [--set cv|holdout|all] [--labels DIR] [--games DIR]
 * </pre>
 * Writes {@code DIR/plies.tsv} and {@code DIR/games.tsv}.
 */
public final class ReviewCv {

    private ReviewCv() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        Path data = EvalDumpTest.TEAM_DATA;
        Path dumpDir = Path.of(a.getOrDefault("dump", data.resolve("evals_labeled").toString()));
        String budget = a.getOrDefault("budget", "lite");
        String set = a.getOrDefault("set", "cv");
        EvalDump.Mode mode = switch (a.getOrDefault("mode", "product")) {
            case "product" -> EvalDump.Mode.PRODUCT;
            case "second" -> EvalDump.Mode.SECOND_EVERYWHERE;
            case "mpv3" -> EvalDump.Mode.MPV3;
            default -> throw new IllegalArgumentException("mode: product, second, mpv3");
        };
        Path out = Path.of(a.getOrDefault("out", "target/cv/run"));
        Files.createDirectories(out);
        Map<String, Integer> folds = folds(dumpDir.resolve("folds.json"));
        Map<String, LabelledGame> labelled = new HashMap<>();
        for (LabelledGame g : LabelledGame.loadAll(Path.of(a.getOrDefault("labels",
                data.resolve("labels_chesscom").toString())), Path.of(a.getOrDefault("games",
                data.resolve("games").toString())))) {
            labelled.put(g.id(), g);
        }
        List<EvalDump.Game> games = new java.util.ArrayList<>();
        if (!"holdout".equals(set)) {
            games.addAll(EvalDump.load(dumpDir, budget));
        }
        if (!"cv".equals(set)) {
            games.addAll(EvalDump.load(dumpDir.resolve("holdout"), budget));
        }
        OpeningBook book = OpeningBook.standard();
        StringBuilder plies = new StringBuilder(String.join("\t", "game", "fold", "ply", "color", "san", "uci",
                "ours", "cc", "eval_before", "eval_played", "ep_before", "ep_after", "ep_loss", "best", "is_top",
                "second", "second_eval", "second_ep", "legal", "depth", "mate_check")).append('\n');
        StringBuilder gamesTsv = new StringBuilder(String.join("\t", "game", "fold", "time_class", "avg_rating",
                "plies", "cc_white", "cc_black", "ours_white", "ours_black", "product_nodes", "second_lines"))
                .append('\n');
        int n = 0;
        for (EvalDump.Game d : games) {
            LabelledGame g = labelled.get(d.id());
            if (g == null) {
                continue;
            }
            if (!g.uci().equals(d.uci())) {
                throw new IllegalStateException(d.id() + ": dump moves differ from the labelled game");
            }
            String fold = folds.containsKey(d.id()) ? String.valueOf(folds.get(d.id())) : "H";
            if ("cv".equals(set) && "H".equals(fold) || "holdout".equals(set) && !"H".equals(fold)) {
                continue;
            }
            ReviewInput in = d.input(mode, book);
            GameReview r = ReviewClassifier.classifyGame(in);
            int second = 0;
            for (int i = 0; i < r.moves().size(); i++) {
                MoveReview m = r.moves().get(i);
                PositionEval p0 = in.positions().get(i);
                EngineLine s = p0.secondBest();
                if (s != null) {
                    second++;
                }
                boolean me = m.whiteMoved();
                ReviewLabel ours = ReviewLabel.of(m.label());
                plies.append(String.join("\t", d.id(), fold, String.valueOf(i + 1), me ? "w" : "b", m.san(), m.uci(),
                        ours.name().toLowerCase(Locale.ROOT), g.labels().get(i).name().toLowerCase(Locale.ROOT),
                        p0.eval().format(), m.after().format(), f(m.winBefore()), f(m.winAfter()), f(m.winLoss()),
                        String.valueOf(p0.bestMove()), String.valueOf(m.uci().equals(p0.bestMove())),
                        s == null ? "" : s.move(), s == null ? "" : s.eval().format(),
                        s == null ? "" : f(s.eval().winChance(me)), String.valueOf(d.positions().get(i).legal()),
                        String.valueOf(p0.depth()), mateCheck(r, i, ours))).append('\n');
            }
            gamesTsv.append(String.join("\t", d.id(), fold, g.timeClass(),
                    String.valueOf((g.whiteRating() + g.blackRating()) / 2), String.valueOf(g.uci().size()),
                    f(g.whiteAccuracy()), f(g.blackAccuracy()), f(r.whiteAccuracy()), f(r.blackAccuracy()),
                    String.valueOf(d.productNodes(book)), String.valueOf(second))).append('\n');
            n++;
        }
        Files.writeString(out.resolve("plies.tsv"), plies.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("games.tsv"), gamesTsv.toString(), StandardCharsets.UTF_8);
        System.out.println("classified " + n + " games -> " + out);
    }

    /**
     * Engine-free mate sanity (same rules as {@link AgreementReport}): a move that mates must not be an error; a move
     * that allows a mate in one when some move avoided it, from a position not already lost to a forced mate, must not
     * be a good move. Empty when fine.
     */
    static String mateCheck(GameReview r, int i, ReviewLabel ours) {
        String after = i + 1 < r.positions().size() ? r.positions().get(i + 1).fen() : null;
        if (after == null) {
            return "";
        }
        if (MateProbe.isMate(after)) {
            return ours.isError() ? "VIOLATION gives mate, labelled " + ours : "";
        }
        MoveReview m = r.moves().get(i);
        boolean alreadyLost = m.winBefore() <= 1e-9;
        if (!alreadyLost && MateProbe.mateInOneFor(after) != null
                && AgreementReport.couldAvoidMateInOne(m.fenBefore()) && ours.isGood()) {
            return "VIOLATION allows mate in one, labelled " + ours;
        }
        return "";
    }

    static Map<String, Integer> folds(Path file) throws java.io.IOException {
        Map<String, Integer> out = new HashMap<>();
        if (Files.exists(file)) {
            JSONObject f = new JSONObject(Files.readString(file)).getJSONObject("folds");
            for (String k : f.keySet()) {
                out.put(k, f.getInt(k));
            }
        }
        return out;
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
    }
}
