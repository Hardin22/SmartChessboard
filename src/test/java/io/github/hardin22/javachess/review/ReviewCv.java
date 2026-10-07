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
        Map<String, Integer> folds = folds(Path.of(a.getOrDefault("folds", dumpDir.resolve("folds.json").toString())));
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
        String recheck = a.get("recheck");
        String deepBudget = a.getOrDefault("deepBudget", "deep");
        Map<String, EvalDump.Game> deepGames = new HashMap<>();
        if (recheck != null) {
            for (EvalDump.Game g : EvalDump.load(dumpDir, deepBudget)) {
                deepGames.put(g.id(), g);
            }
            for (EvalDump.Game g : EvalDump.load(dumpDir.resolve("holdout"), deepBudget)) {
                deepGames.put(g.id(), g);
            }
        }
        StringBuilder plies = new StringBuilder(String.join("\t", "game", "fold", "ply", "color", "san", "uci",
                "ours", "cc", "eval_before", "eval_played", "ep_before", "ep_after", "ep_loss", "best", "is_top",
                "second", "second_eval", "second_ep", "legal", "depth", "mate_check")).append('\n');
        StringBuilder gamesTsv = new StringBuilder(String.join("\t", "game", "fold", "time_class", "avg_rating",
                "plies", "cc_white", "cc_black", "ours_white", "ours_black", "product_nodes", "second_lines"))
                .append('\n');
        // --explain: the numbers behind every Brilliant / Great, ours or chess.com's (false positive / negative lists)
        boolean explain = Boolean.parseBoolean(a.getOrDefault("explain", "false"));
        // --features: the same numbers for EVERY ply (twin finder)
        boolean features = Boolean.parseBoolean(a.getOrDefault("features", "false"));
        StringBuilder specials = new StringBuilder();
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
            // --recheck second|full: the candidates' second line (or whole evaluation) from the deep dump, a proxy of a
            // deeper re-search of the Great/Brilliant candidates only
            long[] extra = new long[1];
            EvalDump.Game deepGame = recheck == null ? null : deepGames.get(d.id());
            if (recheck != null && deepGame == null) {
                throw new IllegalStateException(d.id() + ": no " + deepBudget + " dump for --recheck");
            }
            ReviewInput base = deepGame == null ? d.input(mode, book) : d.recheckedInput(deepGame, recheck, book, extra);
            // the players' ratings (chess.com judges a loss of win chance by the player's level)
            // cv.py -D ratings=none: every player unknown, as for a local game in the app
            boolean ratings = !"none".equals(System.getProperty("javachess.review.ratings"));
            ReviewInput in = new ReviewInput(base.initialFen(), base.uciMoves(), base.positions(), base.book(),
                    ratings ? g.whiteRating() : 0, ratings ? g.blackRating() : 0);
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
                String cc = g.labels().get(i).name();
                if ((explain && (isSpecial(ours.name()) || isSpecial(cc))) || (features && !p0.terminal())) {
                    Map<String, String> x = io.github.hardin22.javachess.Engine.review.SpecialProbe.explain(in, r, i);
                    if (specials.isEmpty()) {
                        specials.append(String.join("\t", "game", "fold", "ply", "color", "san", "uci", "ours", "cc",
                                "fen_before", "eval_before", "eval_played", "best", "best_pv", "second",
                                "second_eval", "second_pv")).append('\t').append(String.join("\t", x.keySet()))
                                .append('\n');
                    }
                    EngineLine b = p0.best();
                    specials.append(String.join("\t", d.id(), fold, String.valueOf(i + 1), me ? "w" : "b", m.san(),
                            m.uci(), ours.name().toLowerCase(Locale.ROOT), cc.toLowerCase(Locale.ROOT), m.fenBefore(),
                            p0.eval().format(), m.after().format(), String.valueOf(p0.bestMove()),
                            b == null ? "" : String.join(" ", b.pv()), s == null ? "" : s.move(),
                            s == null ? "" : s.eval().format(), s == null ? "" : String.join(" ", s.pv())))
                            .append('\t').append(String.join("\t", x.values())).append('\n');
                }
            }
            gamesTsv.append(String.join("\t", d.id(), fold, g.timeClass(),
                    String.valueOf((g.whiteRating() + g.blackRating()) / 2), String.valueOf(g.uci().size()),
                    f(g.whiteAccuracy()), f(g.blackAccuracy()), f(r.whiteAccuracy()), f(r.blackAccuracy()),
                    String.valueOf(d.productNodes(book) + extra[0]), String.valueOf(second))).append('\n');
            n++;
        }
        Files.writeString(out.resolve("plies.tsv"), plies.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("games.tsv"), gamesTsv.toString(), StandardCharsets.UTF_8);
        if (explain || features) {
            Files.writeString(out.resolve("specials.tsv"), specials.toString(), StandardCharsets.UTF_8);
        }
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

    private static boolean isSpecial(String label) {
        return "BRILLIANT".equals(label) || "GREAT".equals(label);
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
