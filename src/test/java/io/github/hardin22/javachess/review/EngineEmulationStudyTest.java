package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.EngineLine;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import io.github.hardin22.javachess.Engine.review.ReviewSettings;
import org.json.JSONArray;
import org.json.JSONObject;
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
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Engine side of the chess.com label study (phase 3): how much of the disagreement with chess.com comes from the
 * engine. Every variant reviews the games with chess.com labels with the product classifier
 * ({@link ReviewClassifier#classifyGame}, unchanged: no fitting here, so in-sample numbers compare engines fairly)
 * and only the engine side changes. Opt-in:
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=EngineEmulationStudyTest -Demu.study=true \
 *     -Demu.variants=sf19:n200000,sf19:d18,lite:d18,lite:d18:mpv3 [-Demu.processes=4] [-Demu.rerun=false] \
 *     [-Demu.research=sf19:n200000>sf19:n1000000] [-Demu.out=target/emulation]
 * </pre>
 * Variant syntax {@code engine:limit[:mpvK][:2nd<limit>]}: engine {@code sf19} (native Stockfish 19,
 * {@code -Dstockfish.path}), {@code lite} (chess.com's stockfish.js lite WASM under node, {@code -Demu.liteJs}) or
 * {@code sflite} (the same lite net built natively, {@code -Demu.litePath}: same nodes and moves as the WASM build);
 * limit {@code n<nodes>} or {@code d<depth>}; the second line (Great/Brilliant) defaults to half the nodes or the same
 * depth. Each variant's evaluations are dumped to {@code <out>/<variant>.jsonl} and reused on the next run.
 */
@EnabledIfSystemProperty(named = "emu.study", matches = "true")
class EngineEmulationStudyTest {

    private static final Path OUT = Path.of(System.getProperty("emu.out", "target/emulation"));

    @Test
    void study() throws Exception {
        List<ChessComDataset.Game> games = labelled();
        assertFalse(games.isEmpty(), "no labelled games");
        Files.createDirectories(OUT);
        StringBuilder table = new StringBuilder(header());
        Map<String, Dump> dumps = new LinkedHashMap<>();
        for (String v : System.getProperty("emu.variants", "sf19:n200000").split(",")) {
            Dump d = dump(v.trim(), games);
            dumps.put(v.trim(), d);
            table.append(row(v.trim(), score(games, d.positions), d));
        }
        String research = System.getProperty("emu.research", "");
        if (!research.isBlank()) {
            String[] p = research.split(">");
            Dump base = dumps.containsKey(p[0]) ? dumps.get(p[0]) : dump(p[0], games);
            Dump deep = dumps.containsKey(p[1]) ? dumps.get(p[1]) : dump(p[1], games);
            for (double margin : new double[] {0.005, 0.01, 0.02}) {
                table.append(research(games, base, deep, "near threshold ±" + margin, nearThreshold(margin)));
            }
            table.append(research(games, base, deep, "best/excellent only", bestOrExcellent()));
            table.append(research(games, base, deep, "every move", (in, i) -> true));
        }
        System.out.println(table);
        Files.writeString(OUT.resolve("study.md"), table.toString(), StandardCharsets.UTF_8);
        Files.writeString(OUT.resolve("plies.csv"), plies(games, dumps), StandardCharsets.UTF_8);
    }

    /**
     * One row per ply: chess.com's label and ours with every variant, plus how many variants are 2+ levels away (a
     * case far with every engine is a rule problem, one far with some engines only is engine noise).
     */
    private static String plies(List<ChessComDataset.Game> games, Map<String, Dump> dumps) {
        List<List<GameReview>> reviews = new ArrayList<>();
        for (Dump d : dumps.values()) {
            List<GameReview> rs = new ArrayList<>();
            for (int g = 0; g < games.size(); g++) {
                rs.add(ReviewClassifier.classifyGame(new ReviewInput(null, games.get(g).uci(), d.positions.get(g),
                        OpeningBook.standard())));
            }
            reviews.add(rs);
        }
        StringBuilder sb = new StringBuilder("game,ply,san,chesscom," + String.join(",", dumps.keySet())
                + ",far_variants,loss_first\n");
        for (int g = 0; g < games.size(); g++) {
            ChessComDataset.Game game = games.get(g);
            for (int i = 0; i < game.plies(); i++) {
                ReviewLabel cc = game.labels().get(i);
                sb.append(game.id()).append(',').append(i + 1).append(',').append(game.san().get(i)).append(',')
                        .append(cc.abbrev());
                int far = 0;
                for (List<GameReview> rs : reviews) {
                    ReviewLabel ours = ReviewLabel.of(rs.get(g).moves().get(i).label());
                    far += Math.abs(level(ours) - level(cc)) >= 2 ? 1 : 0;
                    sb.append(',').append(ours.abbrev());
                }
                MoveReview m = reviews.get(0).get(g).moves().get(i);
                sb.append(',').append(far).append(',').append(String.format(Locale.ROOT, "%.4f",
                        m.winBefore() - m.winAfter())).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Variants and dumps

    /** Evaluations of every labelled game for one engine variant, plus what they cost. */
    record Dump(List<List<PositionEval>> positions, long nodes, long wallMs, long searches) {
    }

    static List<ChessComDataset.Game> labelled() {
        List<ChessComDataset.Game> out = new ArrayList<>();
        for (ChessComDataset.Game g : ChessComDataset.load()) {
            if (g.hasLabels() && g.labels().stream().allMatch(java.util.Objects::nonNull)) {
                out.add(g);
            }
        }
        int limit = Integer.getInteger("emu.limit", out.size());
        return out.subList(0, Math.min(limit, out.size()));
    }

    static Dump dump(String variant, List<ChessComDataset.Game> games) throws Exception {
        Path file = OUT.resolve(variant.replace(':', '_') + ".jsonl");
        if (Files.exists(file) && !Boolean.getBoolean("emu.rerun")) {
            return read(file);
        }
        String[] parts = variant.split(":");
        int depth = 0;
        long nodes = 0;
        int mpv = 1;
        int depth2 = -1;
        long nodes2 = -1;
        for (int i = 1; i < parts.length; i++) {
            String s = parts[i];
            if (s.startsWith("n")) {
                nodes = Long.parseLong(s.substring(1));
            } else if (s.startsWith("d")) {
                depth = Integer.parseInt(s.substring(1));
            } else if (s.startsWith("mpv")) {
                mpv = Integer.parseInt(s.substring(3));
            } else if (s.startsWith("2nd")) {
                String l = s.substring(3);
                if (l.startsWith("n")) {
                    nodes2 = Long.parseLong(l.substring(1));
                    depth2 = 0;
                } else {
                    depth2 = Integer.parseInt(l.substring(1));
                    nodes2 = 0;
                }
            }
        }
        if (depth2 < 0) {
            depth2 = depth;
            nodes2 = nodes / 2;
        }
        List<String> cmd = switch (parts[0]) {
            case "sf19" -> List.of(System.getProperty("stockfish.path",
                    System.getProperty("user.home") + "/Developer/javaChess/engines/stockfish/stockfish"));
            case "lite" -> List.of(System.getProperty("emu.node", "/opt/homebrew/bin/node"),
                    System.getProperty("emu.liteJs"));
            // native build of the same lite net (sscg13/Stockfish sf19-1mb): bit-identical searches, ~2.6x faster
            case "sflite" -> List.of(System.getProperty("emu.litePath"));
            default -> List.of(parts[0]); // any UCI executable
        };
        int processes = Integer.getInteger("emu.processes", 4);
        var limit = new EmulationEvaluator.Limit(depth, nodes, mpv, depth2, nodes2);
        List<List<PositionEval>> all = new ArrayList<>();
        long t0 = System.nanoTime();
        EmulationEvaluator ev = new EmulationEvaluator(variant, cmd, processes, Integer.getInteger("emu.hash", 64),
                limit);
        // the reviewer asks for a node budget: the evaluator applies the variant's own limits instead
        try (GameReviewer reviewer = new GameReviewer(ev, new ReviewSettings(Math.max(nodes, 1000),
                Math.max(nodes2, 1000), processes, 64), OpeningBook.standard())) {
            int done = 0;
            for (ChessComDataset.Game g : games) {
                all.add(reviewer.review(null, g.uci(), null).positions());
                if (++done % 10 == 0) {
                    System.out.printf(Locale.ROOT, "%s: %d/%d games, %.0fs%n", variant, done, games.size(),
                            (System.nanoTime() - t0) / 1e9);
                }
            }
        }
        Dump d = new Dump(all, ev.nodes.get(), (System.nanoTime() - t0) / 1_000_000, ev.searches.get());
        write(file, games, d);
        return d;
    }

    private static void write(Path file, List<ChessComDataset.Game> games, Dump d) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(new JSONObject().put("meta", true).put("nodes", d.nodes).put("wallMs", d.wallMs)
                .put("searches", d.searches)).append('\n');
        for (int g = 0; g < games.size(); g++) {
            JSONArray ps = new JSONArray();
            for (PositionEval p : d.positions.get(g)) {
                JSONArray lines = new JSONArray();
                for (EngineLine l : p.lines()) {
                    lines.put(new JSONObject().put("m", l.move()).put("e", eval(l.eval())).put("d", l.depth())
                            .put("pv", new JSONArray(l.pv())));
                }
                ps.put(new JSONObject().put("fen", p.fen()).put("e", eval(p.eval())).put("d", p.depth())
                        .put("n", p.nodes()).put("t", p.terminal()).put("l", lines));
            }
            sb.append(new JSONObject().put("id", games.get(g).id()).put("positions", ps)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    static Dump read(Path file) throws IOException {
        List<List<PositionEval>> all = new ArrayList<>();
        long nodes = 0;
        long wall = 0;
        long searches = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JSONObject o = new JSONObject(line);
            if (o.optBoolean("meta")) {
                nodes = o.getLong("nodes");
                wall = o.getLong("wallMs");
                searches = o.getLong("searches");
                continue;
            }
            List<PositionEval> ps = new ArrayList<>();
            JSONArray a = o.getJSONArray("positions");
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                List<EngineLine> lines = new ArrayList<>();
                JSONArray la = p.getJSONArray("l");
                for (int k = 0; k < la.length(); k++) {
                    JSONObject l = la.getJSONObject(k);
                    lines.add(new EngineLine(l.getString("m"), eval(l.getString("e")), ChessComDataset.strings(
                            l.getJSONArray("pv")), l.getInt("d")));
                }
                ps.add(new PositionEval(p.getString("fen"), eval(p.getString("e")), lines, p.getInt("d"),
                        p.getLong("n"), p.getBoolean("t")));
            }
            all.add(ps);
        }
        return new Dump(all, nodes, wall, searches);
    }

    private static String eval(Eval e) {
        return switch (e.kind()) {
            case CP -> "cp " + e.value();
            case WHITE_MATES -> "wm " + e.value();
            case BLACK_MATES -> "bm " + e.value();
        };
    }

    private static Eval eval(String s) {
        String[] p = s.split(" ");
        int v = Integer.parseInt(p[1]);
        return switch (p[0]) {
            case "wm" -> Eval.whiteMates(v);
            case "bm" -> Eval.blackMates(v);
            default -> Eval.cp(v);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Scoring

    /** ACCEPTANCE.md level scale: brilliant 0 .. blunder 7 (book and forced as best, miss as mistake). */
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

    /** Agreement of one set of evaluations with chess.com. */
    record Score(int plies, int exact, int within1, int far, int bestExc, int excBest, int bestTotal,
                 List<String> farCases) {

        double exactPct() {
            return 100.0 * exact / plies;
        }

        double within1Pct() {
            return 100.0 * within1 / plies;
        }
    }

    static Score score(List<ChessComDataset.Game> games, List<List<PositionEval>> positions) {
        int plies = 0;
        int exact = 0;
        int within1 = 0;
        int bestExc = 0;
        int excBest = 0;
        int bestTotal = 0;
        List<String> far = new ArrayList<>();
        for (int g = 0; g < games.size(); g++) {
            ChessComDataset.Game game = games.get(g);
            GameReview r = ReviewClassifier.classifyGame(new ReviewInput(null, game.uci(), positions.get(g),
                    OpeningBook.standard()));
            for (MoveReview m : r.moves()) {
                ReviewLabel ours = ReviewLabel.of(m.label());
                ReviewLabel cc = game.labels().get(m.ply());
                plies++;
                int dist = Math.abs(level(ours) - level(cc));
                if (ours == cc) {
                    exact++;
                }
                if (dist <= 1) {
                    within1++;
                } else {
                    far.add(String.format(Locale.ROOT, "%s ply %d %s: ours %s, chess.com %s (loss %.3f)", game.id(),
                            m.ply() + 1, game.san().get(m.ply()), ours.abbrev(), cc.abbrev(),
                            m.winBefore() - m.winAfter()));
                }
                if (cc == ReviewLabel.BEST) {
                    bestTotal++;
                    if (ours == ReviewLabel.EXCELLENT) {
                        bestExc++;
                    }
                }
                if (cc == ReviewLabel.EXCELLENT && ours == ReviewLabel.BEST) {
                    excBest++;
                }
            }
        }
        return new Score(plies, exact, within1, far.size(), bestExc, excBest, bestTotal, far);
    }

    private static String header() {
        return "| variant | exact | within 1 | ≥2 levels | cc best→our exc | cc exc→our best | nodes/ply | searches/ply"
                + " | Mac wall s | Pi 5 s/40 moves (big net, 350k nps x 3) |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n";
    }

    private static String row(String name, Score s, Dump d) {
        double perPly = (double) d.nodes / s.plies;
        return String.format(Locale.ROOT, "| %s | %.1f%% | %.1f%% | %d | %d/%d | %d | %.0fk | %.2f | %.0f | %.1f |%n",
                name, s.exactPct(), s.within1Pct(), s.far, s.bestExc, s.bestTotal, s.excBest, perPly / 1000,
                (double) d.searches / s.plies, d.wallMs / 1000.0, perPly * 80 / (350_000.0 * 3));
    }

    // ------------------------------------------------------------------------------------------
    // Targeted re-search

    /** Selects the plies whose two positions (before and after) are re-searched deeper. */
    private static String research(List<ChessComDataset.Game> games, Dump base, Dump deep, String rule,
                                   BiPredicate<GameReview, Integer> select) {
        List<List<PositionEval>> mixed = new ArrayList<>();
        long extra = 0;
        int plies = 0;
        int picked = 0;
        for (int g = 0; g < games.size(); g++) {
            ChessComDataset.Game game = games.get(g);
            List<PositionEval> b = base.positions.get(g);
            List<PositionEval> d = deep.positions.get(g);
            GameReview r = ReviewClassifier.classifyGame(new ReviewInput(null, game.uci(), b, OpeningBook.standard()));
            List<PositionEval> m = new ArrayList<>(b);
            boolean[] done = new boolean[b.size()];
            for (int i = 0; i < r.moves().size(); i++) {
                plies++;
                if (!select.test(r, i)) {
                    continue;
                }
                picked++;
                for (int k = i; k <= i + 1; k++) {
                    if (!done[k] && !b.get(k).terminal()) {
                        done[k] = true;
                        m.set(k, d.get(k));
                        extra += d.get(k).nodes();
                    }
                }
            }
            mixed.add(m);
        }
        Score s = score(games, mixed);
        double perPly = (double) (base.nodes + extra) / plies;
        return String.format(Locale.ROOT, "| re-search %s (%d/%d plies) | %.1f%% | %.1f%% | %d | %d/%d | %d | %.0fk | "
                        + "| | %.1f |%n", rule, picked, plies, s.exactPct(), s.within1Pct(), s.far, s.bestExc,
                s.bestTotal, s.excBest, perPly / 1000, perPly * 80 / (350_000.0 * 3));
    }

    /** The move's win chance loss lies within {@code margin} of a label boundary (Best/Excellent included). */
    private static BiPredicate<GameReview, Integer> nearThreshold(double margin) {
        double[] bounds = {0.0, ReviewClassifier.EXCELLENT_MAX, ReviewClassifier.GOOD_MAX,
                ReviewClassifier.INACCURACY_MAX, ReviewClassifier.MISTAKE_MAX};
        return (r, i) -> {
            MoveReview m = r.moves().get(i);
            if (m.label() == io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification.BOOK_MOVE
                    || m.label() == io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification.FORCED) {
                return false;
            }
            double loss = Math.max(0, m.winBefore() - m.winAfter());
            boolean top = m.uci().equals(m.bestMove());
            for (double b : bounds) {
                if (b == 0.0 ? !top && loss < margin : Math.abs(loss - b) < margin) {
                    return true;
                }
            }
            return false;
        };
    }

    /** Only the moves we call Best or Excellent (who is the engine's top move decides between the two). */
    private static BiPredicate<GameReview, Integer> bestOrExcellent() {
        return (r, i) -> {
            var l = r.moves().get(i).label();
            return l == io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification.BEST
                    || l == io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification.EXCELLENT;
        };
    }
}
