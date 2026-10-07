package io.github.hardin22.javachess.review;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Engine.review.EngineLine;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.EvalCache;
import io.github.hardin22.javachess.Engine.review.GameReplay;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier.Tuning;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Offline calibration of the label rules against the chess.com labels: classifies stored evaluations (no engine)
 * with {@link ReviewClassifier#classifyGame(ReviewInput, Tuning)}, cross-validated by game. Opt-in:
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=LabelTuner -Dreview.tune=true -Dreview.evals=target/review-cache/n200000-66666 \
 *     [-Dreview.grid="greatGap=0.1,0.15,0.2;missNoWorse=0.05,0.1"] [-Dreview.folds=5]
 * </pre>
 * Writes {@code target/tune/{report.md,plies.csv}}: exact agreement, agreement within one level, the moves two or more
 * levels away (ACCEPTANCE.md scale) and, with a grid, the cross-validated numbers of a coordinate search.
 */
@EnabledIfSystemProperty(named = "review.tune", matches = "true")
class LabelTuner {

    /** One labelled game with its stored evaluations. */
    record Sample(ChessComDataset.Game game, ReviewInput input) {
    }

    /** Agreement of a set of games. */
    record Score(int plies, int exact, int within1, List<String> far) {
        double exactRate() {
            return plies == 0 ? 0 : (double) exact / plies;
        }

        double within1Rate() {
            return plies == 0 ? 0 : (double) within1 / plies;
        }

        /** Selection objective: exact matches, a move two levels away costs three exact matches. */
        double objective() {
            return exact - 3.0 * far.size();
        }

        Score plus(Score o) {
            List<String> f = new ArrayList<>(far);
            f.addAll(o.far);
            return new Score(plies + o.plies, exact + o.exact, within1 + o.within1, f);
        }
    }

    static final Score EMPTY = new Score(0, 0, 0, List.of());

    @Test
    void tune() throws IOException {
        List<Sample> samples = load();
        assertFalse(samples.isEmpty(), "no labelled game with evaluations");
        int folds = Integer.getInteger("review.folds", 5);
        Path out = Path.of(System.getProperty("review.tuneOut", "target/tune"));
        Files.createDirectories(out);

        StringBuilder md = new StringBuilder();
        Score base = score(samples, Tuning.DEFAULT, true);
        md.append(String.format(Locale.ROOT, "# Label tuning (%d games, %d plies)%n%n", samples.size(), base.plies));
        md.append(String.format(Locale.ROOT, "default: exact %.1f%%, within 1 level %.1f%%, >=2 levels %d%n%n",
                100 * base.exactRate(), 100 * base.within1Rate(), base.far.size()));
        md.append(confusion(samples, Tuning.DEFAULT)).append('\n');
        md.append("## Moves two or more levels away (default)\n\n");
        base.far.forEach(f -> md.append("- ").append(f).append('\n'));
        writePlies(samples, Tuning.DEFAULT, out.resolve("plies.csv"));

        Map<String, double[]> grid = grid(System.getProperty("review.grid", ""));
        if (!grid.isEmpty()) {
            Score cv = EMPTY;
            md.append("\n## Cross-validation by game (").append(folds).append(" folds)\n\n");
            for (int f = 0; f < folds; f++) {
                List<Sample> train = new ArrayList<>();
                List<Sample> test = new ArrayList<>();
                for (Sample s : samples) {
                    (fold(s.game().id(), folds) == f ? test : train).add(s);
                }
                Tuning t = search(train, grid);
                Score tr = score(train, t, false);
                Score te = score(test, t, true);
                cv = cv.plus(te);
                md.append(String.format(Locale.ROOT, "- fold %d: %s; train exact %.1f%% far %d; test exact %.1f%% "
                        + "within1 %.1f%% far %d%n", f, t, 100 * tr.exactRate(), tr.far.size(), 100 * te.exactRate(),
                        100 * te.within1Rate(), te.far.size()));
            }
            md.append(String.format(Locale.ROOT, "%n**CV (held-out folds): exact %.1f%%, within 1 level %.1f%%, "
                    + ">=2 levels %d**%n", 100 * cv.exactRate(), 100 * cv.within1Rate(), cv.far.size()));
            Tuning all = search(samples, grid);
            Score in = score(samples, all, false);
            md.append(String.format(Locale.ROOT, "%nAll games: %s, in-sample exact %.1f%% far %d%n", all,
                    100 * in.exactRate(), in.far.size()));
        }
        Files.writeString(out.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
    }

    /** Coordinate search over the grid (two sweeps), maximising {@link Score#objective()}. */
    static Tuning search(List<Sample> games, Map<String, double[]> grid) {
        Tuning best = Tuning.DEFAULT;
        double bestObj = score(games, best, false).objective();
        for (int sweep = 0; sweep < 2; sweep++) {
            for (Map.Entry<String, double[]> e : grid.entrySet()) {
                for (double v : e.getValue()) {
                    Tuning t = best.with(e.getKey(), v);
                    double o = score(games, t, false).objective();
                    if (o > bestObj + 1e-9) {
                        bestObj = o;
                        best = t;
                    }
                }
            }
        }
        return best;
    }

    static Score score(List<Sample> games, Tuning t, boolean details) {
        int plies = 0;
        int exact = 0;
        int within1 = 0;
        List<String> far = new ArrayList<>();
        for (Sample s : games) {
            GameReview r = ReviewClassifier.classifyGame(s.input(), t);
            for (int i = 0; i < r.moves().size(); i++) {
                ReviewLabel theirs = s.game().labels().get(i);
                if (theirs == null) {
                    continue;
                }
                ReviewLabel ours = ReviewLabel.of(r.moves().get(i).label());
                int d = distance(ours, theirs);
                plies++;
                if (ours == theirs) {
                    exact++;
                }
                if (d <= 1) {
                    within1++;
                } else if (details) {
                    far.add(describe(s, r, i, ours, theirs));
                } else {
                    far.add("");
                }
            }
        }
        return new Score(plies, exact, within1, far);
    }

    /** ACCEPTANCE.md level of a label; Miss sits with Mistake but is one level from Blunder and Inaccuracy. */
    static int level(ReviewLabel l) {
        return switch (l) {
            case BRILLIANT -> 0;
            case GREAT -> 1;
            case BEST, BOOK, FORCED -> 2;
            case EXCELLENT -> 3;
            case GOOD -> 4;
            case INACCURACY -> 5;
            case MISTAKE, MISS -> 6;
            case BLUNDER -> 7;
        };
    }

    static int distance(ReviewLabel a, ReviewLabel b) {
        if (a == ReviewLabel.MISS || b == ReviewLabel.MISS) {
            ReviewLabel o = a == ReviewLabel.MISS ? b : a;
            if (o == ReviewLabel.BLUNDER || o == ReviewLabel.INACCURACY) {
                return 1;
            }
        }
        return Math.abs(level(a) - level(b));
    }

    static String describe(Sample s, GameReview r, int i, ReviewLabel ours, ReviewLabel theirs) {
        MoveReview m = r.moves().get(i);
        PositionEval p0 = s.input().positions().get(i);
        EngineLine second = p0.secondBest();
        double oppLoss = i > 0 ? r.moves().get(i - 1).winLoss() : 0;
        return String.format(Locale.ROOT, "%s ply %d %s: ours %s, chess.com %s | best %s (%s) -> played %s | "
                        + "EP %.3f -> %.3f loss %.3f, oppLoss %.3f, 2nd %s", s.game().id(), i + 1, m.san(),
                ours.abbrev(), theirs.abbrev(), m.before().format(), m.bestMove(), m.after().format(), m.winBefore(),
                m.winAfter(), m.winLoss(), oppLoss, second == null ? "-" : second.move() + " " + second.eval().format());
    }

    static String confusion(List<Sample> games, Tuning t) {
        ReviewLabel[] order = {ReviewLabel.BRILLIANT, ReviewLabel.GREAT, ReviewLabel.BEST, ReviewLabel.BOOK,
                ReviewLabel.FORCED, ReviewLabel.EXCELLENT, ReviewLabel.GOOD, ReviewLabel.INACCURACY,
                ReviewLabel.MISTAKE, ReviewLabel.MISS, ReviewLabel.BLUNDER};
        int[][] c = new int[ReviewLabel.values().length][ReviewLabel.values().length];
        for (Sample s : games) {
            GameReview r = ReviewClassifier.classifyGame(s.input(), t);
            for (int i = 0; i < r.moves().size(); i++) {
                ReviewLabel theirs = s.game().labels().get(i);
                if (theirs != null) {
                    c[theirs.ordinal()][ReviewLabel.of(r.moves().get(i).label()).ordinal()]++;
                }
            }
        }
        StringBuilder sb = new StringBuilder("rows chess.com, columns ours\n\n| |");
        for (ReviewLabel l : order) {
            sb.append(' ').append(l.abbrev()).append(" |");
        }
        sb.append("\n|---|");
        sb.append("---:|".repeat(order.length)).append('\n');
        for (ReviewLabel row : order) {
            sb.append("| ").append(row.abbrev()).append(" |");
            for (ReviewLabel col : order) {
                sb.append(' ').append(c[row.ordinal()][col.ordinal()]).append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static void writePlies(List<Sample> games, Tuning t, Path file) throws IOException {
        StringBuilder sb = new StringBuilder("game,ply,san,ours,chesscom,before,after,best,second,second_eval,"
                + "ep_before,ep_after,loss,opp_loss,top,depth\n");
        for (Sample s : games) {
            GameReview r = ReviewClassifier.classifyGame(s.input(), t);
            for (int i = 0; i < r.moves().size(); i++) {
                MoveReview m = r.moves().get(i);
                PositionEval p0 = s.input().positions().get(i);
                EngineLine second = p0.secondBest();
                ReviewLabel theirs = s.game().labels().get(i);
                sb.append(String.format(Locale.ROOT, "%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%.4f,%.4f,%.4f,%.4f,%d,%d%n",
                        s.game().id(), i + 1, m.san(), ReviewLabel.of(m.label()), theirs == null ? "" : theirs,
                        m.before().format(), m.after().format(), Objects.toString(m.bestMove(), ""),
                        second == null ? "" : second.move(), second == null ? "" : second.eval().format(),
                        m.winBefore(), m.winAfter(), m.winLoss(), i > 0 ? r.moves().get(i - 1).winLoss() : 0,
                        m.uci().equals(m.bestMove()) ? 1 : 0, p0.depth()));
            }
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Deterministic fold of a game (until the validator's fold definition replaces it). */
    static int fold(String id, int folds) {
        return Math.floorMod(id.hashCode(), folds);
    }

    /** "name=v1,v2;name2=v3" */
    static Map<String, double[]> grid(String spec) {
        Map<String, double[]> g = new LinkedHashMap<>();
        for (String part : spec.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && !kv[0].isBlank()) {
                String[] vs = kv[1].split(",");
                double[] d = new double[vs.length];
                for (int i = 0; i < vs.length; i++) {
                    d[i] = Double.parseDouble(vs[i].trim());
                }
                g.put(kv[0].trim(), d);
            }
        }
        return g;
    }

    // ------------------------------------------------------------------------------------------
    // Evaluations

    /** Labelled games with stored evaluations for every non-terminal position. */
    static List<Sample> load() {
        Path dir = Path.of(System.getProperty("review.evals", "target/review-cache/n200000-66666"));
        Function<String, PositionEval> lookup = cacheLookup(dir);
        OpeningBook book = OpeningBook.standard();
        List<Sample> out = new ArrayList<>();
        for (ChessComDataset.Game g : ChessComDataset.load()) {
            if (!g.hasLabels() || !g.labels().stream().allMatch(Objects::nonNull)) {
                continue;
            }
            List<PositionEval> pos = positions(g, lookup);
            if (pos != null) {
                out.add(new Sample(g, new ReviewInput(null, g.uci(), pos, book, g.whiteRating(), g.blackRating())));
            }
        }
        return out;
    }

    static List<PositionEval> positions(ChessComDataset.Game g, Function<String, PositionEval> lookup) {
        GameReplay replay = GameReplay.of(null, g.uci());
        List<PositionEval> pos = new ArrayList<>();
        for (int i = 0; i < replay.fens().size(); i++) {
            String fen = replay.fens().get(i);
            Board b = new Board();
            b.loadFromFen(fen);
            if (b.legalMoves().isEmpty()) {
                pos.add(PositionEval.terminal(fen, Eval.terminal(b).orElse(Eval.DRAW)));
            } else if (replay.drawn().get(i)) {
                pos.add(PositionEval.terminal(fen, Eval.DRAW));
            } else {
                PositionEval p = lookup.apply(fen);
                if (p == null) {
                    return null;
                }
                pos.add(p);
            }
        }
        return pos;
    }

    /** Evaluations from the review harness disk cache ({@link EvalCache} files in {@code dir}). */
    static Function<String, PositionEval> cacheLookup(Path dir) {
        List<EvalCache> caches = new ArrayList<>();
        try (var files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".tsv")).forEach(f -> caches.add(new EvalCache(f)));
        } catch (IOException e) {
            throw new IllegalStateException("no evaluations in " + dir, e);
        }
        return fen -> {
            for (EvalCache c : caches) {
                PositionEval p = c.get(fen, 1, 0);
                if (p != null) {
                    return p;
                }
            }
            return null;
        };
    }
}
