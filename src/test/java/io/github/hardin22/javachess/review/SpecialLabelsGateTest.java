package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 4 gate for Brilliant and Great: classifies the 177 games we have chess.com labels for (142 labelled games,
 * reference chess.com Stockfish 16 depth 22, plus 35 famous games) from the stored product evaluations (Stockfish 19
 * Lite, block hash) with {@link ReviewClassifier#classifyGame} and compares the special labels. No engine: a few
 * seconds. Skipped when the review team's data folder is missing (CI).
 *
 * <p>Fails when a Brilliant/Great error appears that is neither in {@code special-baseline.tsv} (the errors accepted
 * today, which only shrinks) nor in {@code special-allowlist.tsv} (documented "different engine" exceptions), or when
 * the exact agreement of all labels on the 142 games drops more than 0.3 points below the baseline. Writes
 * {@code target/special/report.md} and {@code target/special/errors.tsv}.</p>
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=SpecialLabelsGateTest                       # the gate
 * ./mvnw test -DskipE2E=true -Dtest=SpecialLabelsGateTest -Dreview.special.regen=true
 *     # after FIXING errors: rewrites the baseline without them (refused if it would add an error;
 *     # -Dreview.special.regenForce=true only to create the first baseline)
 * </pre>
 */
class SpecialLabelsGateTest {

    static final Path RESOURCES = Path.of("src/test/resources/review");
    static final Path BASELINE = RESOURCES.resolve("special-baseline.tsv");
    static final Path ALLOWLIST = RESOURCES.resolve("special-allowlist.tsv");
    /**
     * {@code -Dreview.special.defaultRatingAll=true}: every game as if unrated (the product's default rating), to see
     * what is lost without ratings. Report only (target/special-default/), never fails, never touches the baseline.
     */
    static final boolean DEFAULT_RATING_ALL = Boolean.getBoolean("review.special.defaultRatingAll");
    static final Path OUT = Path.of(DEFAULT_RATING_ALL ? "target/special-default" : "target/special");
    /**
     * "Definition" decisions (PHASE4_DEFINITIONS.md): errors where our label follows the definition of Great /
     * Brilliant applied to our engine (a twin with the opposite chess.com label, or a clear real sacrifice with its
     * card). Fed by the shards; read for the third scoreboard column only, never for pass/fail.
     */
    static final Path DEFINITIONS = Path.of(System.getProperty("review.special.definitions",
            EvalDumpTest.TEAM_DATA.getParent().resolve("notes/special/DEFINITION_DECISIONS.tsv").toString()));
    /** Main metrics: only moves by players rated at least this (the gate itself still covers every move). */
    static final int MAIN_MIN_RATING = 1000;
    /** Row of the main metrics in the tables (sorted first). */
    static final String MAIN_KEY = "**MAIN: movers ≥1000 (177 games)**";
    /** Rating counted for unrated players (the famous games), the product default. */
    static final int MAIN_UNRATED = 2500;
    /** Largest allowed drop of the exact agreement on the 142 labelled games (fraction). */
    static final double EXACT_TOLERANCE = 0.003;

    /** One Brilliant/Great disagreement. kind: fp-brilliant, fn-brilliant, fp-great, fn-great. */
    /** @param main true when the mover's rating (unrated = the product default 2500) is at least 1000 */
    record Error(String set, String id, int ply, String san, String kind, String ours, String cc, boolean main) {
        String key() {
            return id + "\t" + ply + "\t" + kind;
        }
    }

    /** Counts of one set. */
    static final class Tally {
        final Map<String, int[]> pr = new LinkedHashMap<>(); // class -> tp, fp, fn
        int plies;
        int exact;
        int within1;
        int far;

        void add(ReviewLabel ours, ReviewLabel cc) {
            plies++;
            exact += ours == cc ? 1 : 0;
            int d = LabelTuner.distance(ours, cc);
            within1 += d <= 1 ? 1 : 0;
            far += d >= 2 ? 1 : 0;
            for (ReviewLabel c : List.of(ReviewLabel.BRILLIANT, ReviewLabel.GREAT)) {
                int[] v = pr.computeIfAbsent(c.name().toLowerCase(Locale.ROOT), k -> new int[3]);
                if (ours == c && cc == c) {
                    v[0]++;
                } else if (ours == c) {
                    v[1]++;
                } else if (cc == c) {
                    v[2]++;
                }
            }
        }
    }

    @Test
    void brilliantAndGreatMatchChessCom() throws Exception {
        Path data = Path.of(System.getProperty("review.data", EvalDumpTest.TEAM_DATA.toString()));
        Path labels = data.resolve("labels_chesscom_sf22");
        Path famous = data.resolve("famous_chesscom");
        Path evals = data.resolve("evals_labeled");
        assumeTrue(Files.isDirectory(labels) && Files.isDirectory(famous) && Files.isDirectory(evals.resolve("lite-block"))
                && Files.isDirectory(data.resolve("evals_famous").resolve("lite")),
                "review team data not found in " + data + ": special labels gate skipped");

        Map<String, Integer> folds = ReviewCv.folds(evals.resolve("folds.json"));
        Map<String, String> famousKind = new HashMap<>();
        for (Path f : Files.list(famous).filter(p -> p.toString().endsWith(".json")).toList()) {
            famousKind.put(f.getFileName().toString().replace(".json", ""),
                    new JSONObject(Files.readString(f)).optString("kind", ""));
        }
        Map<String, Integer> shard = shards(data.getParent().resolve("notes/special/shards.json"));

        List<Game> games = new ArrayList<>();
        Map<String, EvalDump.Game> dumps = new HashMap<>();
        for (EvalDump.Game d : EvalDump.load(evals, "lite-block")) {
            dumps.put(d.id(), d);
        }
        for (EvalDump.Game d : EvalDump.load(evals.resolve("holdout"), "lite-block")) {
            dumps.put(d.id(), d);
        }
        for (LabelledGame g : LabelledGame.loadAll(labels, data.resolve("games"))) {
            String set = folds.containsKey(g.id()) ? "cv93" : "ex-holdout49";
            games.add(new Game(set, g, dumps.get(g.id())));
        }
        Map<String, EvalDump.Game> famousDumps = new HashMap<>();
        for (EvalDump.Game d : EvalDump.load(data.resolve("evals_famous"), "lite")) {
            famousDumps.put(d.id(), d);
        }
        for (LabelledGame g : LabelledGame.loadAll(famous, data.resolve("famous"))) {
            games.add(new Game("famous-" + famousKind.getOrDefault(g.id(), "?"), g, famousDumps.get(g.id())));
        }

        OpeningBook book = OpeningBook.standard();
        // after-capture searches of the "threat ignored" candidates (Phase 4)
        Map<String, Map<Integer, io.github.hardin22.javachess.Engine.review.EngineLine>> captures =
                CaptureEvals.load(CaptureEvals.FILE);
        List<Error> errors = new ArrayList<>();
        Map<String, Tally> tallies = new TreeMap<>();
        Tally labelled142 = new Tally();
        Tally mainTally = new Tally();
        List<String> missing = new ArrayList<>();
        for (Game x : games) {
            if (x.dump == null) {
                missing.add(x.game.id());
                continue;
            }
            if (!x.game.uci().equals(x.dump.uci())) {
                throw new IllegalStateException(x.game.id() + ": dump moves differ from the labelled game");
            }
            ReviewInput base = x.dump.input(EvalDump.Mode.PRODUCT, book);
            // real ratings where known; unrated (famous PGNs) get the product's default rating
            ReviewInput in = new ReviewInput(base.initialFen(), base.uciMoves(), base.positions(), base.book(),
                    DEFAULT_RATING_ALL ? 0 : x.game.whiteRating(), DEFAULT_RATING_ALL ? 0 : x.game.blackRating());
            in = CaptureEvals.attach(in, x.game.id(), captures);
            GameReview r = ReviewClassifier.classifyGame(in);
            Tally t = tallies.computeIfAbsent(x.set, k -> new Tally());
            for (int i = 0; i < r.moves().size(); i++) {
                MoveReview m = r.moves().get(i);
                ReviewLabel ours = ReviewLabel.of(m.label());
                ReviewLabel cc = x.game.labels().get(i);
                t.add(ours, cc);
                // main metrics (user, 23:00): moves of players rated 1000+, unrated famous games at 2500
                int rating = m.whiteMoved() ? x.game.whiteRating() : x.game.blackRating();
                boolean main = (rating > 0 ? rating : MAIN_UNRATED) >= MAIN_MIN_RATING;
                if (main) {
                    mainTally.add(ours, cc);
                }
                if (!x.set.startsWith("famous")) {
                    labelled142.add(ours, cc);
                }
                for (ReviewLabel c : List.of(ReviewLabel.BRILLIANT, ReviewLabel.GREAT)) {
                    String name = c.name().toLowerCase(Locale.ROOT);
                    if (ours == c && cc != c) {
                        errors.add(new Error(x.set, x.game.id(), i + 1, m.san(), "fp-" + name, lower(ours), lower(cc),
                                main));
                    } else if (cc == c && ours != c) {
                        errors.add(new Error(x.set, x.game.id(), i + 1, m.san(), "fn-" + name, lower(ours), lower(cc),
                                main));
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "no product evaluations (lite-block / evals_famous/lite) for " + missing);

        Baseline baseline = Baseline.read(BASELINE);
        Map<String, Entry> allow = allowlist(ALLOWLIST);
        Map<String, Entry> definition = allowlist(DEFINITIONS);
        List<Error> fresh = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Error e : errors) {
            seen.add(e.key());
            if (!baseline.keys.contains(e.key()) && !allow.containsKey(e.key())) {
                fresh.add(e);
            }
        }
        List<String> fixed = baseline.keys.stream().filter(k -> !seen.contains(k)).toList();
        double exact = (double) labelled142.exact / Math.max(1, labelled142.plies);
        tallies.put(MAIN_KEY, mainTally);

        Files.createDirectories(OUT);
        writeErrors(errors, baseline, allow, shard);
        Files.writeString(OUT.resolve("report.md"), report(tallies, labelled142, errors, fresh, fixed, baseline, allow,
                shard, exact) + columns(tallies, errors, allow, definition, shard), StandardCharsets.UTF_8);

        if (DEFAULT_RATING_ALL) {
            System.out.printf(Locale.ROOT, "special labels with the default rating for all games: %d errors, exact on "
                    + "142 %.4f -> %s%n", errors.size(), exact, OUT.resolve("report.md").toAbsolutePath());
            return;
        }
        if (Boolean.getBoolean("review.special.regen")) {
            if (!fresh.isEmpty() && !Boolean.getBoolean("review.special.regenForce")) {
                throw new AssertionError("refusing to regenerate the baseline: " + fresh.size()
                        + " NEW errors would be accepted (fix them, or allowlist them with a justification): "
                        + fresh.stream().map(Error::key).toList());
            }
            writeBaseline(errors, allow, exact, labelled142.plies);
            System.out.println("special baseline regenerated: " + (errors.size()) + " errors, exact "
                    + String.format(Locale.ROOT, "%.4f", exact) + " -> " + BASELINE.toAbsolutePath());
            return;
        }
        System.out.printf(Locale.ROOT, "special gate: %d errors (%d new, %d fixed vs baseline), exact on 142 %.4f "
                + "(baseline %.4f) -> %s%n", errors.size(), fresh.size(), fixed.size(), exact, baseline.exact,
                OUT.resolve("report.md").toAbsolutePath());
        assertTrue(fresh.isEmpty(), fresh.size() + " NEW Brilliant/Great errors (not in the baseline nor in the "
                + "allowlist), see target/special/report.md:\n" + fresh.stream()
                .map(e -> "  " + e.set + " " + e.id + " ply " + e.ply + " " + e.san + ": " + e.kind + " (ours " + e.ours
                        + ", chess.com " + e.cc + ")").reduce("", (a, b) -> a + b + "\n"));
        assertTrue(Double.isNaN(baseline.exact) || exact >= baseline.exact - EXACT_TOLERANCE, String.format(Locale.ROOT,
                "exact agreement on the 142 labelled games dropped: %.4f < baseline %.4f - %.3f", exact, baseline.exact,
                EXACT_TOLERANCE));
    }

    record Game(String set, LabelledGame game, EvalDump.Game dump) {
    }

    /** The accepted errors and the exact agreement they were measured with. */
    record Baseline(Set<String> keys, double exact) {
        static Baseline read(Path f) throws IOException {
            Set<String> keys = new LinkedHashSet<>();
            double exact = Double.NaN;
            if (Files.exists(f)) {
                for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (l.startsWith("# exact")) {
                        exact = Double.parseDouble(l.split("\\s+")[2]);
                    } else if (!l.isBlank() && !l.startsWith("#") && !l.startsWith("id\t")) {
                        String[] c = l.split("\t");
                        keys.add(c[0] + "\t" + c[1] + "\t" + c[2]);
                    }
                }
            }
            return new Baseline(keys, exact);
        }
    }

    /** One proposed exception (allowlist) or definition decision, with its independent verification. */
    record Entry(String why, String category, String proposedBy, String verifiedBy, String verdict) {
        boolean ok() {
            return "OK".equalsIgnoreCase(verdict);
        }
    }

    /** id, ply, kind -> entry; columns by the header line ({@code id ply kind ...}), missing ones empty. */
    static Map<String, Entry> allowlist(Path f) throws IOException {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (!Files.exists(f)) {
            return out;
        }
        List<String> cols = List.of("id", "ply", "kind", "justification");
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) {
                continue;
            }
            if (l.startsWith("id\t")) {
                cols = List.of(l.split("\t"));
                continue;
            }
            String[] c = l.split("\t", -1);
            Map<String, String> v = new HashMap<>();
            for (int i = 0; i < Math.min(c.length, cols.size()); i++) {
                v.put(cols.get(i), c[i].trim());
            }
            String why = v.getOrDefault("justification", v.getOrDefault("reason", ""));
            if (v.containsKey("evidence") && !v.get("evidence").isEmpty()) {
                why += " [" + v.get("evidence") + "]";
            }
            out.put(c[0] + "\t" + c[1] + "\t" + c[2], new Entry(why, v.getOrDefault("category", ""),
                    v.getOrDefault("proposed_by", ""), v.getOrDefault("verified_by", ""),
                    v.getOrDefault("verdict", "pending").isEmpty() ? "pending" : v.get("verdict")));
        }
        return out;
    }

    static Map<String, Integer> shards(Path f) throws IOException {
        Map<String, Integer> out = new HashMap<>();
        if (Files.exists(f)) {
            JSONObject s = new JSONObject(Files.readString(f)).getJSONObject("shards");
            for (String k : s.keySet()) {
                JSONArray a = s.getJSONArray(k);
                for (int i = 0; i < a.length(); i++) {
                    out.put(a.getString(i), Integer.parseInt(k));
                }
            }
        }
        return out;
    }

    private static void writeBaseline(List<Error> errors, Map<String, Entry> allow, double exact, int plies)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Brilliant/Great errors accepted today by SpecialLabelsGateTest (Phase 4). It only shrinks: regenerate\n")
                .append("# (-Dreview.special.regen=true) after fixing errors. Allowlisted exceptions are not repeated here.\n")
                .append(String.format(Locale.ROOT, "# exact %.4f on %d plies of the 142 labelled games (chess.com SF16 d22)%n",
                        exact, plies))
                .append("id\tply\tkind\tset\tsan\tours\tchesscom\n");
        for (Error e : errors) {
            if (!allow.containsKey(e.key())) {
                sb.append(String.join("\t", e.id, String.valueOf(e.ply), e.kind, e.set, e.san, e.ours, e.cc))
                        .append('\n');
            }
        }
        Files.writeString(BASELINE, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeErrors(List<Error> errors, Baseline baseline, Map<String, Entry> allow,
                                    Map<String, Integer> shard) throws IOException {
        StringBuilder sb = new StringBuilder("shard\tset\tid\tply\tsan\tkind\tours\tchesscom\tstatus\tmain\n");
        for (Error e : errors) {
            sb.append(String.join("\t", String.valueOf(shard.getOrDefault(e.id, 0)), e.set, e.id,
                    String.valueOf(e.ply), e.san, e.kind, e.ours, e.cc, status(e, baseline, allow),
                    String.valueOf(e.main))).append('\n');
        }
        Files.writeString(OUT.resolve("errors.tsv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String status(Error e, Baseline baseline, Map<String, Entry> allow) {
        return allow.containsKey(e.key()) ? "allowlist" : baseline.keys.contains(e.key()) ? "baseline" : "NEW";
    }

    private static String report(Map<String, Tally> tallies, Tally labelled142, List<Error> errors, List<Error> fresh,
                                 List<String> fixed, Baseline baseline, Map<String, Entry> allow,
                                 Map<String, Integer> shard, double exact) {
        StringBuilder sb = new StringBuilder("# Special labels gate (Brilliant / Great vs chess.com SF16 d22)"
                + (DEFAULT_RATING_ALL ? " — ALL GAMES AT THE DEFAULT RATING (report only)" : "") + "\n\n");
        sb.append(String.format(Locale.ROOT, "Errors %d: **%d NEW**, %d in baseline, %d allowlisted; %d fixed since "
                        + "the baseline (regenerate it). Exact on the 142 labelled games %.2f%% (baseline %.2f%%), "
                        + "within 1 level %.2f%%, cases >=2 levels %d.%n%n", errors.size(), fresh.size(),
                errors.stream().filter(e -> baseline.keys.contains(e.key())).count(),
                errors.stream().filter(e -> allow.containsKey(e.key())).count(), fixed.size(), 100 * exact,
                100 * baseline.exact, 100.0 * labelled142.within1 / Math.max(1, labelled142.plies), labelled142.far));
        sb.append("| set | plies | exact | Brilliant TP/FP/FN | P | R | Great TP/FP/FN | P | R |\n")
                .append("|---|---:|---:|---|---:|---:|---|---:|---:|\n");
        Map<String, Tally> rows = new LinkedHashMap<>(tallies);
        rows.put("**142 labelled**", labelled142);
        for (Map.Entry<String, Tally> e : rows.entrySet()) {
            Tally t = e.getValue();
            int[] b = t.pr.get("brilliant");
            int[] g = t.pr.get("great");
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f%% | %d/%d/%d | %s | %s | %d/%d/%d | %s | %s |%n",
                    e.getKey(), t.plies, 100.0 * t.exact / Math.max(1, t.plies), b[0], b[1], b[2], p(b), r(b), g[0],
                    g[1], g[2], p(g), r(g)));
        }
        Map<Integer, int[]> byShard = new TreeMap<>();
        for (Error e : errors) {
            int[] v = byShard.computeIfAbsent(shard.getOrDefault(e.id, 0), k -> new int[4]);
            v[List.of("fp-brilliant", "fn-brilliant", "fp-great", "fn-great").indexOf(e.kind)]++;
        }
        sb.append("\n| shard | FP Brilliant | FN Brilliant | FP Great | FN Great |\n|---:|---:|---:|---:|---:|\n");
        byShard.forEach((k, v) -> sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d |%n",
                k == 0 ? "-" : k, v[0], v[1], v[2], v[3])));
        if (!fresh.isEmpty()) {
            sb.append("\n## NEW errors (the gate fails)\n");
            fresh.forEach(e -> sb.append(line(e, shard)));
        }
        if (!fixed.isEmpty()) {
            sb.append("\n## Fixed since the baseline\n");
            fixed.forEach(k -> sb.append("- ").append(k.replace('\t', ' ')).append('\n'));
        }
        sb.append("\n## All errors by game\n");
        String last = "";
        for (Error e : errors) {
            if (!e.id.equals(last)) {
                sb.append("\n**").append(e.id).append("** (").append(e.set).append(", shard ")
                        .append(shard.getOrDefault(e.id, 0)).append(")\n");
                last = e.id;
            }
            sb.append(line(e, shard).replace("\n", "")).append(" — ").append(status(e, baseline, allow)).append('\n');
        }
        return sb.toString();
    }

    /**
     * The three Phase 4 columns per set and class: (1) raw agreement with chess.com, (2) after the "different engine"
     * exceptions (allowlist), (3) after the "definition" decisions too, plus the list of column-3 errors (true bugs).
     * An accepted false positive counts as a true positive, an accepted false negative leaves the positives.
     */
    static String columns(Map<String, Tally> tallies, List<Error> errors, Map<String, Entry> allow,
                          Map<String, Entry> definition, Map<String, Integer> shard) {
        StringBuilder sb = new StringBuilder("\n## Three columns (PHASE4_DEFINITIONS.md)\n\n")
                .append("(1) raw vs chess.com · (2) after the 'different engine' allowlist · (3) after the 'definition' "
                        + "decisions too (`notes/special/DEFINITION_DECISIONS.tsv`). Cells: FP/FN, P, R.\n\n")
                .append("| set | class | (1) raw | (2) − engine | (3) − definition |\n|---|---|---|---|---|\n");
        Map<String, Tally> rows = new LinkedHashMap<>(tallies);
        Tally all = new Tally();
        for (Map.Entry<String, Tally> te : tallies.entrySet()) {
            if (te.getKey().equals(MAIN_KEY)) {
                continue;
            }
            Tally t = te.getValue();
            for (String c : List.of("brilliant", "great")) {
                int[] a = all.pr.computeIfAbsent(c, k -> new int[3]);
                int[] v = t.pr.get(c);
                for (int i = 0; i < 3; i++) {
                    a[i] += v[i];
                }
            }
        }
        rows.put("**all 177**", all);
        int[] totals = new int[6];
        for (Map.Entry<String, Tally> e : rows.entrySet()) {
            for (String c : List.of("brilliant", "great")) {
                int[] v = e.getValue().pr.get(c);
                boolean total = e.getKey().startsWith("**all");
                boolean mainRow = e.getKey().equals(MAIN_KEY);
                List<Error> mine = errors.stream().filter(x -> (mainRow ? x.main : total || x.set.equals(e.getKey()))
                        && x.kind.endsWith(c)).toList();
                int[] fpx = new int[3];
                int[] fnx = new int[3];
                for (Error x : mine) {
                    // only an independently verified OK counts as correct; pending and REJECTED stay errors
                    Entry ae = allow.get(x.key());
                    Entry de = definition.get(x.key());
                    int col = ae != null && ae.ok() ? 1 : de != null && de.ok() ? 2 : 0;
                    if (col > 0 && x.kind.startsWith("fp")) {
                        fpx[col]++;
                    } else if (col > 0) {
                        fnx[col]++;
                    }
                }
                // cumulative: column 3 also excludes the allowlist
                String[] cells = new String[3];
                for (int col = 0; col < 3; col++) {
                    int fpEx = col == 0 ? 0 : fpx[1] + (col == 2 ? fpx[2] : 0);
                    int fnEx = col == 0 ? 0 : fnx[1] + (col == 2 ? fnx[2] : 0);
                    int tp = v[0] + fpEx;
                    int fp = v[1] - fpEx;
                    int fn = v[2] - fnEx;
                    cells[col] = String.format(Locale.ROOT, "%d/%d · %s · %s", fp, fn,
                            tp + fp == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) tp / (tp + fp)),
                            tp + fn == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) tp / (tp + fn)));
                    if (total && !mainRow) {
                        totals[col * 2] += fp;
                        totals[col * 2 + 1] += fn;
                    }
                }
                sb.append("| ").append(e.getKey()).append(" | ").append(c).append(" | ").append(cells[0])
                        .append(" | ").append(cells[1]).append(" | ").append(cells[2]).append(" |\n");
            }
        }
        sb.append(String.format(Locale.ROOT, "%nOnly proposals with verdict OK (verified by another agent) count in "
                + "columns 2-3.%n"));
        sb.append(String.format(Locale.ROOT, "%nCOLUMNS errors (FP+FN, Brilliant+Great): raw %d, after engine %d, "
                + "after definition %d%n", totals[0] + totals[1], totals[2] + totals[3], totals[4] + totals[5]));
        List<String> unknown = definition.keySet().stream().filter(k -> errors.stream().noneMatch(x -> x.key().equals(k)))
                .toList();
        if (!unknown.isEmpty()) {
            sb.append("\nDefinition decisions that are no longer errors (fixed, remove them): ").append(unknown.size())
                    .append(" — ").append(String.join(", ", unknown.stream().map(k -> k.replace('\t', ' ')).toList()))
                    .append('\n');
        }
        List<Error> bugs = errors.stream().filter(x -> !(allow.containsKey(x.key()) && allow.get(x.key()).ok())
                && !(definition.containsKey(x.key()) && definition.get(x.key()).ok())).toList();
        // verification status of the proposals, by category
        Map<String, int[]> byCat = new TreeMap<>();
        for (Map<String, Entry> m : List.of(allow, definition)) {
            m.forEach((k, en) -> {
                if (errors.stream().anyMatch(x -> x.key().equals(k))) {
                    int[] v = byCat.computeIfAbsent(en.category.isEmpty() ? "?" : en.category, c -> new int[3]);
                    v[en.ok() ? 0 : "REJECTED".equalsIgnoreCase(en.verdict) ? 1 : 2]++;
                }
            });
        }
        sb.append("\n| category | OK (verified) | REJECTED | pending |\n|---|---:|---:|---:|\n");
        int[] tot = new int[3];
        byCat.forEach((c, v) -> {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d |%n", c, v[0], v[1], v[2]));
            for (int i = 0; i < 3; i++) {
                tot[i] += v[i];
            }
        });
        sb.append(String.format(Locale.ROOT, "| **all** | %d | %d | %d |%n", tot[0], tot[1], tot[2]))
                .append(String.format(Locale.ROOT, "VERIFICATION ok %d rejected %d pending %d%n", tot[0], tot[1], tot[2]));
        sb.append("\n## Column 3: remaining errors = true bugs (").append(bugs.size()).append(")\n");
        bugs.forEach(x -> {
            Entry en = allow.containsKey(x.key()) ? allow.get(x.key()) : definition.get(x.key());
            sb.append(line(x, shard).replace("\n", "")).append(" — shard ").append(shard.getOrDefault(x.id, 0))
                    .append(en == null ? "" : " — proposed (" + en.category + ", " + en.proposedBy + "): " + en.verdict
                            + (en.verifiedBy.isEmpty() ? "" : " by " + en.verifiedBy)).append('\n');
        });
        sb.append("\n## Definition decisions (").append(definition.size()).append(")\n");
        definition.forEach((k, en) -> sb.append("- ").append(k.replace('\t', ' ')).append(" [").append(en.verdict)
                .append(en.verifiedBy.isEmpty() ? "" : " by " + en.verifiedBy).append(", proposed by ")
                .append(en.proposedBy).append("]: ").append(en.why).append('\n'));
        return sb.toString();
    }

    private static String line(Error e, Map<String, Integer> shard) {
        int mv = (e.ply + 1) / 2;
        String san = e.ply % 2 == 1 ? mv + "." + e.san : mv + "..." + e.san;
        return String.format(Locale.ROOT, "- %s ply %d %s: %s (ours %s, chess.com %s)%n", e.id, e.ply, san, e.kind,
                e.ours, e.cc);
    }

    private static String p(int[] v) {
        return v[0] + v[1] == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) v[0] / (v[0] + v[1]));
    }

    private static String r(int[] v) {
        return v[0] + v[2] == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) v[0] / (v[0] + v[2]));
    }

    private static String lower(ReviewLabel l) {
        return l.name().toLowerCase(Locale.ROOT);
    }
}
