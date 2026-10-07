package io.github.hardin22.javachess.review;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Agreement of our review with chess.com Game Review: accuracy error (MAE, bias, RMSE, correlation, by time class and
 * rating band), per-label agreement with confusion matrix (games with chess.com labels only), engine-free mate sanity
 * checks and timing. Pure aggregation: no engine here.
 */
public final class AgreementReport {

    /** One reviewed game. */
    public record Entry(ChessComDataset.Game game, Reviewer.Result ours) {
    }

    /** A mate sanity violation or a label disagreement worth a look. */
    public record Finding(String gameId, String url, int ply, String san, String fenBefore, ReviewLabel ours,
                          ReviewLabel theirs, String why) {
        String moveText() {
            return (ply / 2 + 1) + (ply % 2 == 0 ? ". " : "... ") + san;
        }
    }

    private final String reviewer;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Finding> mateViolations = new ArrayList<>();
    private int matesGiven;
    private int mateInOneAllowed;
    private int mateInOneAllowedCalledBlunder;

    public AgreementReport(String reviewer) {
        this.reviewer = reviewer;
    }

    public void add(ChessComDataset.Game game, Reviewer.Result ours) {
        if (ours.labels().size() != game.plies()) {
            throw new IllegalArgumentException(game.id() + ": " + ours.labels().size() + " labels for " + game.plies()
                    + " plies");
        }
        entries.add(new Entry(game, ours));
        checkMates(game, ours);
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<Finding> mateViolations() {
        return mateViolations;
    }

    // ------------------------------------------------------------------ accuracy

    /** Signed errors (ours - chess.com), two per game. */
    public List<Double> accuracyErrors() {
        List<Double> e = new ArrayList<>();
        for (Entry x : entries) {
            e.add(x.ours().whiteAccuracy() - x.game().whiteAccuracy());
            e.add(x.ours().blackAccuracy() - x.game().blackAccuracy());
        }
        return e;
    }

    public double accuracyMae() {
        return accuracyErrors().stream().mapToDouble(Math::abs).average().orElse(Double.NaN);
    }

    public double accuracyBias() {
        return accuracyErrors().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    public double accuracyRmse() {
        return Math.sqrt(accuracyErrors().stream().mapToDouble(v -> v * v).average().orElse(Double.NaN));
    }

    /** Pearson correlation between our and chess.com accuracies. */
    public double accuracyCorrelation() {
        List<double[]> p = new ArrayList<>();
        for (Entry x : entries) {
            p.add(new double[] { x.ours().whiteAccuracy(), x.game().whiteAccuracy() });
            p.add(new double[] { x.ours().blackAccuracy(), x.game().blackAccuracy() });
        }
        int n = p.size();
        if (n < 2) {
            return Double.NaN;
        }
        double mx = p.stream().mapToDouble(a -> a[0]).average().orElse(0);
        double my = p.stream().mapToDouble(a -> a[1]).average().orElse(0);
        double sxy = 0, sxx = 0, syy = 0;
        for (double[] a : p) {
            sxy += (a[0] - mx) * (a[1] - my);
            sxx += (a[0] - mx) * (a[0] - mx);
            syy += (a[1] - my) * (a[1] - my);
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    /** Share of players whose accuracy is within {@code points} of chess.com. */
    public double accuracyWithin(double points) {
        List<Double> e = accuracyErrors();
        return e.isEmpty() ? Double.NaN : e.stream().filter(v -> Math.abs(v) <= points).count() / (double) e.size();
    }

    // ------------------------------------------------------------------ labels

    public boolean hasLabels() {
        return entries.stream().anyMatch(e -> e.game().hasLabels());
    }

    /** confusion[theirs][ours] over plies where chess.com gave a known label. */
    public int[][] confusion() {
        int n = ReviewLabel.values().length;
        int[][] m = new int[n][n];
        for (Entry x : entries) {
            if (!x.game().hasLabels()) {
                continue;
            }
            for (int i = 0; i < x.game().plies(); i++) {
                ReviewLabel t = x.game().labels().get(i);
                if (t != null) {
                    m[t.ordinal()][x.ours().labels().get(i).ordinal()]++;
                }
            }
        }
        return m;
    }

    public double labelAgreement() {
        int[][] m = confusion();
        long all = 0, same = 0;
        for (int i = 0; i < m.length; i++) {
            for (int j = 0; j < m.length; j++) {
                all += m[i][j];
                if (i == j) {
                    same += m[i][j];
                }
            }
        }
        return all == 0 ? Double.NaN : same / (double) all;
    }

    /** Agreement on coarse buckets (best-ish / good / book / inaccuracy / mistake+miss / blunder). */
    public double bucketAgreement() {
        int[][] m = confusion();
        ReviewLabel[] v = ReviewLabel.values();
        long all = 0, same = 0;
        for (int i = 0; i < m.length; i++) {
            for (int j = 0; j < m.length; j++) {
                all += m[i][j];
                if (v[i].bucket() == v[j].bucket()) {
                    same += m[i][j];
                }
            }
        }
        return all == 0 ? Double.NaN : same / (double) all;
    }

    /** Worst label disagreements: bucket distance &ge; 2, worst first. */
    public List<Finding> labelDisagreements() {
        List<Finding> out = new ArrayList<>();
        for (Entry x : entries) {
            if (!x.game().hasLabels()) {
                continue;
            }
            for (int i = 0; i < x.game().plies(); i++) {
                ReviewLabel t = x.game().labels().get(i);
                ReviewLabel o = x.ours().labels().get(i);
                if (t != null && Math.abs(t.bucket() - o.bucket()) >= 2) {
                    out.add(finding(x.game(), i, o, t, "chess.com " + t.abbrev() + " vs ours " + o.abbrev()));
                }
            }
        }
        out.sort(Comparator.comparingInt((Finding f) -> -Math.abs(f.theirs().bucket() - f.ours().bucket())));
        return out;
    }

    // ------------------------------------------------------------------ mate sanity (engine-free truth)

    private void checkMates(ChessComDataset.Game g, Reviewer.Result ours) {
        for (int i = 0; i < g.plies(); i++) {
            ReviewLabel o = ours.labels().get(i);
            ReviewLabel t = g.hasLabels() ? g.labels().get(i) : null;
            String after = g.fens().get(i + 1);
            if (MateProbe.isMate(after)) {
                matesGiven++;
                if (o.isError()) {
                    mateViolations.add(finding(g, i, o, t, "gives mate, labelled " + o));
                }
            } else if (MateProbe.mateInOneFor(after) != null && couldAvoidMateInOne(g.fens().get(i))) {
                mateInOneAllowed++;
                if (o == ReviewLabel.BLUNDER) {
                    mateInOneAllowedCalledBlunder++;
                }
                if (o.isGood()) {
                    mateViolations.add(finding(g, i, o, t, "allows mate in one, labelled " + o));
                }
            }
        }
    }

    /** True when some legal move does not leave a mate in one to the opponent. */
    static boolean couldAvoidMateInOne(String fen) {
        com.github.bhlangonijr.chesslib.Board b = new com.github.bhlangonijr.chesslib.Board();
        b.loadFromFen(fen);
        for (com.github.bhlangonijr.chesslib.move.Move m : b.legalMoves()) {
            b.doMove(m);
            String f = b.getFen();
            b.undoMove();
            if (MateProbe.isMate(f) || MateProbe.mateInOneFor(f) == null) {
                return true;
            }
        }
        return false;
    }

    private static Finding finding(ChessComDataset.Game g, int ply, ReviewLabel ours, ReviewLabel theirs, String why) {
        return new Finding(g.id(), g.url(), ply, g.san().get(ply), g.fens().get(ply), ours, theirs, why);
    }

    // ------------------------------------------------------------------ timing

    public long totalMs() {
        return entries.stream().mapToLong(e -> e.ours().elapsedMs()).sum();
    }

    public int totalPlies() {
        return entries.stream().mapToInt(e -> e.game().plies()).sum();
    }

    public double msPerPly() {
        return totalPlies() == 0 ? Double.NaN : totalMs() / (double) totalPlies();
    }

    /** Wall time of a typical 80-ply game (40 moves) at the measured rate. */
    public double msPer40MoveGame() {
        return msPerPly() * 80;
    }

    // ------------------------------------------------------------------ output

    public JSONObject summaryJson() {
        JSONObject o = new JSONObject();
        o.put("reviewer", reviewer);
        o.put("games", entries.size());
        o.put("plies", totalPlies());
        o.put("accuracyMae", round(accuracyMae()));
        o.put("accuracyBias", round(accuracyBias()));
        o.put("accuracyRmse", round(accuracyRmse()));
        o.put("accuracyR", round(accuracyCorrelation()));
        o.put("accuracyWithin5", round(accuracyWithin(5)));
        o.put("labelAgreement", round(labelAgreement()));
        o.put("bucketAgreement", round(bucketAgreement()));
        o.put("matesGiven", matesGiven);
        o.put("mateInOneAllowed", mateInOneAllowed);
        o.put("mateInOneAllowedCalledBlunder", mateInOneAllowedCalledBlunder);
        o.put("mateViolations", mateViolations.size());
        o.put("msPerPly", round(msPerPly()));
        o.put("totalMs", totalMs());
        return o;
    }

    public String markdown(int worst) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(reviewer).append("\n\n");
        sb.append(f("Games %d, plies %d. Review time %.1f s total, %.1f ms/ply (%.1f s per 40-move game).%n%n",
                entries.size(), totalPlies(), totalMs() / 1000.0, msPerPly(), msPer40MoveGame() / 1000.0));

        sb.append("**Accuracy vs chess.com** (ours - chess.com, per player)\n\n");
        sb.append("| set | n | MAE | bias | RMSE | r | within 5 |\n|---|---:|---:|---:|---:|---:|---:|\n");
        sb.append(accuracyRow("all", entries));
        groupBy(e -> e.game().timeClass()).forEach((k, v) -> sb.append(accuracyRow(k, v)));
        groupBy(e -> e.game().ratingBand()).forEach((k, v) -> sb.append(accuracyRow("rating " + k, v)));
        sb.append('\n');

        sb.append("**Mate sanity** (engine-free truth): mates given ").append(matesGiven)
                .append(", moves allowing an avoidable mate in one ").append(mateInOneAllowed)
                .append(" (called blunder: ").append(mateInOneAllowedCalledBlunder).append("), violations ")
                .append(mateViolations.size()).append(".\n\n");
        appendFindings(sb, mateViolations, worst);

        if (hasLabels()) {
            sb.append(f("**Labels vs chess.com**: exact %.1f%%, bucket %.1f%%%n%n", 100 * labelAgreement(),
                    100 * bucketAgreement()));
            appendConfusion(sb);
            sb.append("\nWorst label disagreements:\n\n");
            appendFindings(sb, labelDisagreements(), worst);
        } else {
            sb.append("**Labels vs chess.com**: no per-move chess.com labels in the dataset yet.\n\n");
        }

        sb.append("Worst accuracy errors:\n\n| game | tc | ratings | chess.com W/B | ours W/B |\n|---|---|---|---|---|\n");
        entries.stream()
                .sorted(Comparator.comparingDouble((Entry e) -> -Math.max(
                        Math.abs(e.ours().whiteAccuracy() - e.game().whiteAccuracy()),
                        Math.abs(e.ours().blackAccuracy() - e.game().blackAccuracy()))))
                .limit(worst)
                .forEach(e -> sb.append(f("| [%s](%s) | %s | %d/%d | %.1f / %.1f | %.1f / %.1f |%n", e.game().id(),
                        e.game().url(), e.game().timeClass(), e.game().whiteRating(), e.game().blackRating(),
                        e.game().whiteAccuracy(), e.game().blackAccuracy(), e.ours().whiteAccuracy(),
                        e.ours().blackAccuracy())));
        return sb.toString();
    }

    private Map<String, List<Entry>> groupBy(Function<Entry, String> key) {
        Map<String, List<Entry>> m = new TreeMap<>();
        for (Entry e : entries) {
            m.computeIfAbsent(key.apply(e), k -> new ArrayList<>()).add(e);
        }
        return m;
    }

    private String accuracyRow(String name, List<Entry> subset) {
        AgreementReport r = new AgreementReport(name);
        r.entries.addAll(subset);
        return f("| %s | %d | %.2f | %+.2f | %.2f | %.3f | %.0f%% |%n", name, 2 * subset.size(), r.accuracyMae(),
                r.accuracyBias(), r.accuracyRmse(), r.accuracyCorrelation(), 100 * r.accuracyWithin(5));
    }

    private void appendConfusion(StringBuilder sb) {
        int[][] m = confusion();
        ReviewLabel[] v = ReviewLabel.values();
        Map<ReviewLabel, Integer> rowTotals = new EnumMap<>(ReviewLabel.class);
        sb.append("| chess.com \\ ours |");
        for (ReviewLabel l : v) {
            sb.append(' ').append(l.abbrev()).append(" |");
        }
        sb.append(" recall |\n|---|");
        sb.append("---:|".repeat(v.length + 1)).append('\n');
        for (int i = 0; i < v.length; i++) {
            int total = 0;
            for (int j = 0; j < v.length; j++) {
                total += m[i][j];
            }
            rowTotals.put(v[i], total);
            if (total == 0) {
                continue;
            }
            sb.append("| ").append(v[i].abbrev()).append(" |");
            for (int j = 0; j < v.length; j++) {
                sb.append(' ').append(m[i][j] == 0 ? "" : m[i][j]).append(" |");
            }
            sb.append(f(" %.0f%% |%n", 100.0 * m[i][i] / total));
        }
        sb.append("| precision |");
        for (int j = 0; j < v.length; j++) {
            int col = 0;
            for (int[] row : m) {
                col += row[j];
            }
            sb.append(col == 0 ? " |" : f(" %.0f%% |", 100.0 * m[j][j] / col));
        }
        sb.append(" |\n");
    }

    private static void appendFindings(StringBuilder sb, List<Finding> list, int worst) {
        if (list.isEmpty()) {
            return;
        }
        sb.append("| game | move | ours | chess.com | why | FEN before |\n|---|---|---|---|---|---|\n");
        Map<String, Integer> perGame = new LinkedHashMap<>();
        int shown = 0;
        for (Finding x : list) {
            if (shown >= worst) {
                break;
            }
            if (perGame.merge(x.gameId(), 1, Integer::sum) > 3) {
                continue; // keep the list varied
            }
            sb.append(f("| [%s](%s) | %s | %s | %s | %s | `%s` |%n", x.gameId(), x.url(), x.moveText(),
                    x.ours() == null ? "" : x.ours().abbrev(), x.theirs() == null ? "" : x.theirs().abbrev(), x.why(),
                    x.fenBefore()));
            shown++;
        }
        sb.append('\n');
    }

    /** Rounded to 3 decimals; NaN (nothing to measure) becomes JSON null. */
    private static Object round(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : JSONObject.NULL;
    }

    private static String f(String fmt, Object... args) {
        return String.format(Locale.ROOT, fmt, args);
    }
}
