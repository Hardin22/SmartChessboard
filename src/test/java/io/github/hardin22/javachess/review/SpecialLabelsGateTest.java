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
    static final Path OUT = Path.of("target/special");
    /** Largest allowed drop of the exact agreement on the 142 labelled games (fraction). */
    static final double EXACT_TOLERANCE = 0.003;

    /** One Brilliant/Great disagreement. kind: fp-brilliant, fn-brilliant, fp-great, fn-great. */
    record Error(String set, String id, int ply, String san, String kind, String ours, String cc) {
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
        List<Error> errors = new ArrayList<>();
        Map<String, Tally> tallies = new TreeMap<>();
        Tally labelled142 = new Tally();
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
                    x.game.whiteRating(), x.game.blackRating());
            GameReview r = ReviewClassifier.classifyGame(in);
            Tally t = tallies.computeIfAbsent(x.set, k -> new Tally());
            for (int i = 0; i < r.moves().size(); i++) {
                MoveReview m = r.moves().get(i);
                ReviewLabel ours = ReviewLabel.of(m.label());
                ReviewLabel cc = x.game.labels().get(i);
                t.add(ours, cc);
                if (!x.set.startsWith("famous")) {
                    labelled142.add(ours, cc);
                }
                for (ReviewLabel c : List.of(ReviewLabel.BRILLIANT, ReviewLabel.GREAT)) {
                    String name = c.name().toLowerCase(Locale.ROOT);
                    if (ours == c && cc != c) {
                        errors.add(new Error(x.set, x.game.id(), i + 1, m.san(), "fp-" + name, lower(ours), lower(cc)));
                    } else if (cc == c && ours != c) {
                        errors.add(new Error(x.set, x.game.id(), i + 1, m.san(), "fn-" + name, lower(ours), lower(cc)));
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "no product evaluations (lite-block / evals_famous/lite) for " + missing);

        Baseline baseline = Baseline.read(BASELINE);
        Map<String, String> allow = allowlist(ALLOWLIST);
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

        Files.createDirectories(OUT);
        writeErrors(errors, baseline, allow, shard);
        Files.writeString(OUT.resolve("report.md"), report(tallies, labelled142, errors, fresh, fixed, baseline, allow,
                shard, exact), StandardCharsets.UTF_8);

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

    /** id, ply, kind -> justification. */
    static Map<String, String> allowlist(Path f) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (Files.exists(f)) {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!l.isBlank() && !l.startsWith("#") && !l.startsWith("id\t")) {
                    String[] c = l.split("\t", 4);
                    out.put(c[0] + "\t" + c[1] + "\t" + c[2], c.length > 3 ? c[3] : "");
                }
            }
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

    private static void writeBaseline(List<Error> errors, Map<String, String> allow, double exact, int plies)
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

    private static void writeErrors(List<Error> errors, Baseline baseline, Map<String, String> allow,
                                    Map<String, Integer> shard) throws IOException {
        StringBuilder sb = new StringBuilder("shard\tset\tid\tply\tsan\tkind\tours\tchesscom\tstatus\n");
        for (Error e : errors) {
            sb.append(String.join("\t", String.valueOf(shard.getOrDefault(e.id, 0)), e.set, e.id,
                    String.valueOf(e.ply), e.san, e.kind, e.ours, e.cc, status(e, baseline, allow))).append('\n');
        }
        Files.writeString(OUT.resolve("errors.tsv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String status(Error e, Baseline baseline, Map<String, String> allow) {
        return allow.containsKey(e.key()) ? "allowlist" : baseline.keys.contains(e.key()) ? "baseline" : "NEW";
    }

    private static String report(Map<String, Tally> tallies, Tally labelled142, List<Error> errors, List<Error> fresh,
                                 List<String> fixed, Baseline baseline, Map<String, String> allow,
                                 Map<String, Integer> shard, double exact) {
        StringBuilder sb = new StringBuilder("# Special labels gate (Brilliant / Great vs chess.com SF16 d22)\n\n");
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
