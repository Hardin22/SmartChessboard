package io.github.hardin22.javachess.Engine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How deep must the LED verdict search be? Opt-in ({@code -Dbench=led}, ~15-30 min), on real human games
 * (chess.com dataset of the review team, {@code -Dled.data=<dir with games/*.json>}).
 *
 * <p>For every sampled move: a reference verdict (position before and after at depth {@value #REF_D}, fresh hash), and
 * the LED scenario: the live analysis searched the position before to depth B while the player thought, then the
 * position after the move is searched with that warm hash. One iterative-deepening search gives every depth d
 * (single thread: deterministic, the depth-d prefix is what a depth-d search would print). Raw data is cached in
 * {@code target/led-study.jsonl} ({@code -Dled.reuse=true} re-runs the statistics only, e.g. with another
 * classifier). Report: {@code target/led-study.txt}.</p>
 */
@EnabledIfSystemProperty(named = "bench", matches = "led")
class LedDepthStudyTest {

    static final int REF_D = Integer.getInteger("led.refDepth", 20);
    static final int MAX_D = 16;
    static final int SAMPLES = Integer.getInteger("led.samples", 600);
    static final int WORKERS = Integer.getInteger("led.workers", 4);
    /** Pi 5 core vs one M4 performance core (NNUE nps): ~1/4 (see the report header). */
    static final double PI5 = Double.parseDouble(System.getProperty("led.pi5", "0.25"));
    static final int[] BEFORE_DEPTHS = { 10, 12, 16 };
    static final int[] AFTER_DEPTHS = { 6, 8, 9, 10, 11, 12, 13, 14, 16 };

    record Sample(String game, int ply, String fenBefore, String move, String fenAfter, String tag) {
    }

    record Point(Score score, String best, long nodes) {
    }

    /** Raw measurements of one sample. */
    record Row(Sample s, Score refBest, String refBestMove, Score refPlayed,
               Map<Integer, Point> before, Map<Integer, Point> after, Score terminalPlayed) {
    }

    /** A LED classifier: scores from the mover's point of view. */
    interface Classifier {
        MoveQuality classify(Score bestBefore, Score playedAfter, boolean playedIsBest);
    }

    static final Map<String, Classifier> CLASSIFIERS = new LinkedHashMap<>();

    static {
        CLASSIFIERS.put("led", (b, p, best) -> MoveClassifier.classify(b, p, best).quality());
    }

    final StringBuilder report = new StringBuilder();

    @Test
    void study() throws Exception {
        Path cache = Path.of("target", "led-study.jsonl");
        List<Row> rows;
        if (Boolean.getBoolean("led.reuse") && Files.exists(cache)) {
            rows = readCache(cache);
        } else {
            StockfishTestSupport.requireStockfish();
            List<Sample> samples = samples();
            rows = measure(samples);
            writeCache(cache, rows);
        }
        out("LED depth study " + java.time.LocalDateTime.now() + ": " + rows.size() + " moves of real games, reference depth "
                + REF_D + ", Pi 5 = " + PI5 + " x one M4 core");
        for (Map.Entry<String, Classifier> c : CLASSIFIERS.entrySet()) {
            stats(c.getKey(), c.getValue(), rows);
        }
        cost(rows);
        Path file = Path.of("target", "led-study.txt");
        Files.writeString(file, report.toString());
        System.out.println(report);
    }

    // ------------------------------------------------------------------------------------------ samples

    List<Sample> samples() throws Exception {
        Path dir = Path.of(System.getProperty("led.data",
                System.getProperty("user.home") + "/.javachess-orchestrator/review-team/data")).resolve("games");
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = new ArrayList<>(s.filter(p -> p.toString().endsWith(".json")).sorted().toList());
        }
        List<Sample> all = new ArrayList<>();
        List<Sample> mates = new ArrayList<>();
        for (Path f : files) {
            JSONObject g = new JSONObject(Files.readString(f));
            List<String> uci = movesOf(g);
            if (uci == null) {
                continue;
            }
            Board b = new Board();
            List<Sample> game = new ArrayList<>();
            for (int i = 0; i < uci.size(); i++) {
                String before = b.getFen();
                String mv = uci.get(i);
                b.doMove(new Move(mv, b.getSideToMove()));
                game.add(new Sample(g.getString("id"), i, before, mv, b.getFen(), ""));
            }
            if (b.isMated() && !game.isEmpty()) {
                // the mating move and the move that allowed it
                Sample last = game.remove(game.size() - 1);
                mates.add(new Sample(last.game, last.ply, last.fenBefore, last.move, last.fenAfter, "mates"));
                if (!game.isEmpty()) {
                    Sample prev = game.remove(game.size() - 1);
                    mates.add(new Sample(prev.game, prev.ply, prev.fenBefore, prev.move, prev.fenAfter, "allows-mate"));
                }
            }
            all.addAll(game);
        }
        Collections.shuffle(all, new Random(42));
        List<Sample> out = new ArrayList<>(all.subList(0, Math.min(SAMPLES, all.size())));
        Collections.shuffle(mates, new Random(7));
        out.addAll(mates.subList(0, Math.min(mates.size(), Integer.getInteger("led.mateSamples", 80))));
        out.addAll(userBug());
        return out;
    }

    /** UCI moves of a dataset game ({@code moves_uci}, else the SAN of the PGN movetext), null if unreadable. */
    static List<String> movesOf(JSONObject g) {
        List<String> out = new ArrayList<>();
        if (g.has("moves_uci")) {
            JSONArray a = g.getJSONArray("moves_uci");
            for (int i = 0; i < a.length(); i++) {
                out.add(a.getString(i));
            }
            return out;
        }
        try {
            String pgn = g.getString("pgn");
            String text = pgn.substring(pgn.lastIndexOf(']') + 1).replaceAll("\\{[^}]*}", " ")
                    .replaceAll("\\d+\\.+", " ").replaceAll("1-0|0-1|1/2-1/2|\\*", " ").trim();
            MoveList ml = new MoveList();
            ml.loadFromSan(text);
            for (Move m : ml) {
                out.add(m.toString());
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 1.e4 d5 2.exd5 Bd7 3.d4 a5 4.c4 f6 5.Qh5+ g6 6.Be2 gxh5?? 7.Bxh5#: 6...gxh5 must be a blunder. */
    static List<Sample> userBug() {
        MoveList ml = new MoveList();
        ml.loadFromSan("e4 d5 exd5 Bd7 d4 a5 c4 f6 Qh5+ g6 Be2 gxh5 Bxh5#");
        Board b = new Board();
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < ml.size(); i++) {
            String before = b.getFen();
            b.doMove(ml.get(i));
            if (i >= 10) {
                out.add(new Sample("user-bug", i, before, ml.get(i).toString(), b.getFen(),
                        i == 11 ? "allows-mate" : i == 12 ? "mates" : ""));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ measuring

    List<Row> measure(List<Sample> samples) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<UciClient> clients = Collections.synchronizedList(new ArrayList<>());
        ThreadLocal<UciClient[]> engines = ThreadLocal.withInitial(() -> {
            UciClient ref = StockfishTestSupport.client("ref", 1, 128);
            UciClient led = StockfishTestSupport.client("led", 1, 64);
            clients.add(ref);
            clients.add(led);
            return new UciClient[] { ref, led };
        });
        AtomicInteger done = new AtomicInteger();
        long t0 = System.nanoTime();
        List<Future<Row>> futures = new ArrayList<>();
        for (Sample s : samples) {
            futures.add(pool.submit(() -> {
                UciClient[] e = engines.get();
                Row r = measureOne(e[0], e[1], s);
                int n = done.incrementAndGet();
                if (n % 25 == 0) {
                    System.out.printf("  %d/%d (%d s)%n", n, samples.size(), (System.nanoTime() - t0) / 1_000_000_000);
                }
                return r;
            }));
        }
        List<Row> rows = new ArrayList<>();
        for (Future<Row> f : futures) {
            Row r = f.get();
            if (r != null) {
                rows.add(r);
            }
        }
        pool.shutdown();
        clients.forEach(UciClient::close);
        return rows;
    }

    static Row measureOne(UciClient ref, UciClient led, Sample s) throws Exception {
        Score terminal = terminal(s.fenAfter);
        ref.newGame().get(10, TimeUnit.SECONDS);
        Map<Integer, Point> rb = profile(ref, s.fenBefore, REF_D);
        Point refBest = last(rb);
        if (refBest == null) {
            return null;
        }
        Score refPlayed = terminal;
        if (terminal == null) {
            ref.newGame().get(10, TimeUnit.SECONDS);
            Point ra = last(profile(ref, s.fenAfter, REF_D));
            if (ra == null) {
                return null;
            }
            refPlayed = ra.score.negate();
        }
        led.newGame().get(10, TimeUnit.SECONDS);
        Map<Integer, Point> before = profile(led, s.fenBefore, MAX_D);
        Map<Integer, Point> after = terminal == null ? profile(led, s.fenAfter, MAX_D) : Map.of();
        return new Row(s, refBest.score, refBest.best, refPlayed, before, after, terminal);
    }

    /** Mover POV score of a position without legal moves (we mated / stalemated), else null. */
    static Score terminal(String fenAfter) {
        Board b = new Board();
        b.loadFromFen(fenAfter);
        if (MoveGenerator.generateLegalMoves(b).isEmpty()) {
            return b.isKingAttacked() ? Score.mateDelivered() : Score.cp(0);
        }
        return null;
    }

    /** One search to {@code maxDepth}: score/best/cumulative nodes at every completed depth (exact multipv 1). */
    static Map<Integer, Point> profile(UciClient c, String fen, int maxDepth) throws Exception {
        Map<Integer, Point> byDepth = new TreeMap<>();
        SearchResult r = c.search(fen, List.of(), SearchLimits.depth(maxDepth), info -> {
            if (info.bound() == InfoLine.Bound.EXACT && info.multiPv() == 1) {
                synchronized (byDepth) {
                    byDepth.put(info.depth(), new Point(info.score(), info.move(), info.nodes()));
                }
            }
        }).result().get(300, TimeUnit.SECONDS);
        synchronized (byDepth) {
            if (r.best() != null) {
                byDepth.putIfAbsent(r.depth(), new Point(r.best().score(), r.bestMove(), r.nodes()));
            }
            return new TreeMap<>(byDepth);
        }
    }

    static Point last(Map<Integer, Point> m) {
        Point p = null;
        for (Point v : m.values()) {
            p = v;
        }
        return p;
    }

    static Point at(Map<Integer, Point> m, int d) {
        Point p = null;
        for (Map.Entry<Integer, Point> e : m.entrySet()) {
            if (e.getKey() <= d) {
                p = e.getValue();
            }
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------ statistics

    void stats(String name, Classifier c, List<Row> rows) {
        int[] refCounts = new int[MoveQuality.values().length];
        for (Row r : rows) {
            refCounts[ref(c, r).ordinal()]++;
        }
        out("\n=== classifier '" + name + "' ===");
        out("reference distribution: " + distribution(refCounts));
        for (int bd : BEFORE_DEPTHS) {
            out(String.format(Locale.ROOT, "\nposition before searched to depth %d (live analysis while the player thinks)", bd));
            out(String.format(Locale.ROOT, "%6s %7s %7s %7s %8s %8s %8s %8s", "after", "exact", "ok/err", "severe",
                    "missErr", "falseErr", "mateBad", "n"));
            for (int d : AFTER_DEPTHS) {
                int[] t = new int[7];
                for (Row r : rows) {
                    MoveQuality q = led(c, r, bd, d);
                    if (q == null) {
                        continue;
                    }
                    tally(t, q, ref(c, r), r);
                }
                out(String.format(Locale.ROOT, "%6d %6.1f%% %6.1f%% %6.1f%% %8d %8d %8d %8d", d, pct(t[0], t[6]),
                        pct(t[1], t[6]), pct(t[2], t[6]), t[3], t[4], t[5], t[6]));
            }
        }
        out("exact = same class as the reference; ok/err = same BEST|GOOD vs error verdict; severe = 2+ steps apart; "
                + "missErr = reference mistake/blunder shown as BEST/GOOD; falseErr = reference BEST/GOOD shown as "
                + "mistake/blunder; mateBad = conceded forced mate not shown as an error, or a mating/still-mating move "
                + "shown as an error.");
        List<String> bad = new ArrayList<>();
        for (Row r : rows) {
            MoveQuality q = led(c, r, 16, 12);
            if (q != null && mateBad(q, r)) {
                bad.add(String.format(Locale.ROOT, "  %s ply %d %s (%s): led(16/12)=%s ref=%s refBest=%s refPlayed=%s",
                        r.s.game, r.s.ply, r.s.move, r.s.tag, q, ref(c, r), r.refBest.format(), r.refPlayed.format()));
            }
        }
        out("mate problems at before 16 / after 12: " + bad.size());
        bad.stream().limit(20).forEach(this::out);
    }

    static MoveQuality ref(Classifier c, Row r) {
        return c.classify(r.refBest, r.refPlayed, r.s.move.equals(r.refBestMove));
    }

    static MoveQuality led(Classifier c, Row r, int beforeDepth, int afterDepth) {
        Point b = at(r.before, beforeDepth);
        if (b == null) {
            return null;
        }
        Score played;
        if (r.terminalPlayed != null) {
            played = r.terminalPlayed;
        } else {
            Point a = at(r.after, afterDepth);
            if (a == null) {
                return null;
            }
            played = a.score.negate();
        }
        return c.classify(b.score, played, r.s.move.equals(b.best));
    }

    static boolean mateBad(MoveQuality q, Row r) {
        boolean concedes = r.refPlayed.isLosingMate() && !r.refBest.isLosingMate();
        boolean mates = r.refPlayed.isWinningMate();
        return (concedes && !q.isError()) || (mates && q.isError());
    }

    static void tally(int[] t, MoveQuality q, MoveQuality ref, Row r) {
        t[6]++;
        if (q == ref) {
            t[0]++;
        }
        if (q.isError() == ref.isError()) {
            t[1]++;
        }
        if (Math.abs(rank(q) - rank(ref)) >= 2) {
            t[2]++;
        }
        if (ref.ordinal() >= MoveQuality.MISTAKE.ordinal() && !q.isError()) {
            t[3]++;
        }
        if (q.ordinal() >= MoveQuality.MISTAKE.ordinal() && !ref.isError()) {
            t[4]++;
        }
        if (mateBad(q, r)) {
            t[5]++;
        }
    }

    void cost(List<Row> rows) {
        out("\n=== cost of the search of the position after the move (warm hash from the search before) ===");
        out("Pi 5 ms = nodes / (Mac 1-thread nps x " + PI5 + "); 2 threads on the Pi ~ x0.6");
        long nps = macNps(rows);
        out("Mac 1-thread nps measured during the study: " + nps);
        out(String.format(Locale.ROOT, "%5s %9s %9s %9s %9s | %9s %9s %9s %9s", "depth", "nodes p50", "p90", "p95",
                "max", "Pi5 p50", "Pi5 p90", "Pi5 p95", "Pi5 max"));
        double piNps = nps * PI5;
        for (int bd : new int[] { 16 }) {
            for (int d : AFTER_DEPTHS) {
                List<Long> n = new ArrayList<>();
                for (Row r : rows) {
                    Point a = at(r.after, d);
                    if (a != null) {
                        n.add(a.nodes);
                    }
                }
                if (n.isEmpty()) {
                    continue;
                }
                long p50 = pct(n, 50);
                long p90 = pct(n, 90);
                long p95 = pct(n, 95);
                long max = pct(n, 100);
                out(String.format(Locale.ROOT, "%5d %9d %9d %9d %9d | %7.0fms %7.0fms %7.0fms %7.0fms", d, p50, p90,
                        p95, max, p50 * 1000 / piNps, p90 * 1000 / piNps, p95 * 1000 / piNps, max * 1000 / piNps));
            }
        }
        List<Long> n = new ArrayList<>();
        for (Row r : rows) {
            Point b = at(r.before, 16);
            if (b != null) {
                n.add(b.nodes);
            }
        }
        out(String.format(Locale.ROOT, "position before to depth 16 (cold): nodes p50 %d p95 %d -> Pi5 1T %.1f s / %.1f s",
                pct(n, 50), pct(n, 95), pct(n, 50) / piNps, pct(n, 95) / piNps));
    }

    long macNps(List<Row> rows) {
        String p = System.getProperty("led.macNps");
        if (p != null) {
            return Long.parseLong(p);
        }
        try (UciClient c = StockfishTestSupport.client("nps", 1, 64)) {
            SearchResult r = c.search("r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R w KQ - 0 8",
                    SearchLimits.movetime(3000)).result().get(20, TimeUnit.SECONDS);
            return r.nps();
        } catch (Exception e) {
            return 1_200_000;
        }
    }

    // ------------------------------------------------------------------------------------------ cache

    static void writeCache(Path file, List<Row> rows) throws Exception {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            JSONObject o = new JSONObject();
            o.put("game", r.s.game).put("ply", r.s.ply).put("before", r.s.fenBefore).put("move", r.s.move)
                    .put("after", r.s.fenAfter).put("tag", r.s.tag).put("refBest", enc(r.refBest))
                    .put("refBestMove", r.refBestMove).put("refPlayed", enc(r.refPlayed))
                    .put("terminal", r.terminalPlayed == null ? JSONObject.NULL : enc(r.terminalPlayed))
                    .put("b", encPoints(r.before)).put("a", encPoints(r.after));
            sb.append(o).append('\n');
        }
        Files.writeString(file, sb);
    }

    static List<Row> readCache(Path file) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            JSONObject o = new JSONObject(line);
            Sample s = new Sample(o.getString("game"), o.getInt("ply"), o.getString("before"), o.getString("move"),
                    o.getString("after"), o.getString("tag"));
            rows.add(new Row(s, dec(o.getString("refBest")), o.optString("refBestMove", null),
                    dec(o.getString("refPlayed")), decPoints(o.getJSONObject("b")), decPoints(o.getJSONObject("a")),
                    o.isNull("terminal") ? null : dec(o.getString("terminal"))));
        }
        return rows;
    }

    static String enc(Score s) {
        return (s.mate() ? (s.delivered() ? "won" : "mate") : "cp") + ":" + s.value();
    }

    static Score dec(String s) {
        String[] p = s.split(":");
        return switch (p[0]) {
            case "won" -> Score.mateDelivered();
            case "mate" -> Score.mate(Integer.parseInt(p[1]));
            default -> Score.cp(Integer.parseInt(p[1]));
        };
    }

    static JSONObject encPoints(Map<Integer, Point> m) {
        JSONObject o = new JSONObject();
        m.forEach((d, p) -> o.put(String.valueOf(d), new JSONArray().put(enc(p.score)).put(p.best).put(p.nodes)));
        return o;
    }

    static Map<Integer, Point> decPoints(JSONObject o) {
        Map<Integer, Point> m = new TreeMap<>();
        for (String k : o.keySet()) {
            JSONArray a = o.getJSONArray(k);
            m.put(Integer.parseInt(k), new Point(dec(a.getString(0)), a.getString(1), a.getLong(2)));
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------ helpers

    static int rank(MoveQuality q) {
        return q == MoveQuality.BEST ? 0 : q.ordinal() - 1;
    }

    static double pct(int a, int n) {
        return n == 0 ? 0 : 100.0 * a / n;
    }

    static String distribution(int[] c) {
        StringBuilder sb = new StringBuilder();
        for (MoveQuality q : MoveQuality.values()) {
            sb.append(q).append('=').append(c[q.ordinal()]).append(' ');
        }
        return sb.toString().trim();
    }

    static long pct(List<Long> v, int p) {
        long[] a = v.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        if (a.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * a.length) - 1;
        return a[Math.max(0, Math.min(a.length - 1, idx))];
    }

    void out(String s) {
        report.append(s).append('\n');
    }
}
