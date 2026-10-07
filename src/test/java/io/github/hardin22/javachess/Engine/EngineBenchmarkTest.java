package io.github.hardin22.javachess.Engine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Engine benchmark (opt-in: {@code -Dbench=true}, takes several minutes). Produces target/engine-bench.txt with
 * <ol>
 *   <li>nodes per second for thread/hash configurations,</li>
 *   <li>nodes/time needed to reach each depth after a move (cold hash, 1 thread) and the Raspberry Pi estimate,</li>
 *   <li>how often the LED classification at depth d agrees with a deep reference (depth 20), on moves from
 *       weak self-play games (so there are real inaccuracies, mistakes and blunders).</li>
 * </ol>
 * Pi model: a Pi 5 core does ~1/4 of the nodes per second of an Apple M-series core, a Pi 4 core ~1/9
 * (Cortex-A76 2.4 GHz vs A72 1.8 GHz; override with -Dbench.pi5=0.33 -Dbench.pi4=0.14).
 * Use the official binary ({@code -Dstockfish.path=engines/stockfish/stockfish}): the Homebrew bottle measured
 * 2.5-3x slower on the same Mac.
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class EngineBenchmarkTest {

    static final double PI5 = Double.parseDouble(System.getProperty("bench.pi5", "0.25"));
    static final double PI4 = Double.parseDouble(System.getProperty("bench.pi4", "0.11"));
    static final int MAX_D = 16;
    static final int REF_D = Integer.getInteger("bench.refDepth", 20);
    static final int SAMPLES = Integer.getInteger("bench.samples", 240);

    final StringBuilder report = new StringBuilder();

    record Sample(String fenBefore, String move, String fenAfter) {
    }

    record DepthPoint(Score score, String best, long nodes, long timeMs) {
    }

    @Test
    void benchmark() throws Exception {
        StockfishTestSupport.requireStockfish();
        out("Engine benchmark " + java.time.LocalDateTime.now() + ", cores=" + Runtime.getRuntime().availableProcessors());
        try (UciClient probe = StockfishTestSupport.client("probe", 1, 16)) {
            probe.start().get(10, TimeUnit.SECONDS);
            out("Engine: " + probe.engineName());
        }
        npsTable();
        List<Sample> samples = selfPlaySamples();
        out("\nSamples: " + samples.size() + " moves from weak self-play games");
        depthStudy(samples);
        Path file = Path.of("target", "engine-bench.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, report.toString());
        System.out.println(report);
    }

    void npsTable() throws Exception {
        out("\n== Nodes per second (movetime 3000 ms, middlegame position) ==");
        String fen = "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R w KQ - 0 8";
        out(String.format(Locale.ROOT, "%-22s %12s %12s %12s %8s", "config", "M-series nps", "Pi5 est.", "Pi4 est.", "depth"));
        for (int[] cfg : new int[][] { { 1, 16 }, { 1, 64 }, { 2, 64 }, { 3, 64 }, { 4, 64 } }) {
            try (UciClient c = StockfishTestSupport.client("nps", cfg[0], cfg[1])) {
                SearchResult r = c.search(fen, SearchLimits.movetime(3000)).result().get(20, TimeUnit.SECONDS);
                long nps = r.lines().isEmpty() ? r.nps() : Math.max(r.nps(), r.best().nps());
                out(String.format(Locale.ROOT, "Threads=%d Hash=%-4d     %12d %12d %12d %8d", cfg[0], cfg[1], nps,
                        (long) (nps * PI5), (long) (nps * PI4), r.depth()));
            }
        }
    }

    List<Sample> selfPlaySamples() throws Exception {
        List<Sample> samples = new ArrayList<>();
        Random rnd = new Random(42);
        try (UciClient w = StockfishTestSupport.client("selfplay-w", 1, 16);
             UciClient b = StockfishTestSupport.client("selfplay-b", 1, 16)) {
            int game = 0;
            while (samples.size() < SAMPLES && game < 40) {
                game++;
                int skillW = rnd.nextInt(9);
                int skillB = rnd.nextInt(9);
                w.setOption("Skill Level", String.valueOf(skillW));
                b.setOption("Skill Level", String.valueOf(skillB));
                w.newGame().get(10, TimeUnit.SECONDS);
                b.newGame().get(10, TimeUnit.SECONDS);
                Board board = new Board();
                for (int ply = 0; ply < 90 && samples.size() < SAMPLES; ply++) {
                    if (MoveGenerator.generateLegalMoves(board).isEmpty() || board.isDraw()) {
                        break;
                    }
                    UciClient side = ply % 2 == 0 ? w : b;
                    SearchResult r = side.search(board.getFen(), SearchLimits.depth(6 + rnd.nextInt(4)))
                            .result().get(20, TimeUnit.SECONDS);
                    if (r.bestMove() == null) {
                        break;
                    }
                    String before = board.getFen();
                    board.doMove(new Move(r.bestMove(), board.getSideToMove()));
                    if (ply >= 8) { // skip the opening
                        samples.add(new Sample(before, r.bestMove(), board.getFen()));
                    }
                }
            }
        }
        return samples;
    }

    /** Runs one search to MAX_D and records score/best/nodes/time at every depth (exact multipv 1 lines). */
    static Map<Integer, DepthPoint> profile(UciClient c, String fen, int maxDepth) throws Exception {
        Map<Integer, DepthPoint> byDepth = new TreeMap<>();
        c.newGame().get(10, TimeUnit.SECONDS); // cold hash: pessimistic
        SearchResult r = c.search(fen, List.of(), SearchLimits.depth(maxDepth), info -> {
            if (info.bound() == InfoLine.Bound.EXACT && info.multiPv() == 1) {
                synchronized (byDepth) {
                    byDepth.put(info.depth(), new DepthPoint(info.score(), info.move(), info.nodes(), info.timeMs()));
                }
            }
        }).result().get(120, TimeUnit.SECONDS);
        if (r.best() != null) {
            synchronized (byDepth) {
                byDepth.putIfAbsent(r.depth(), new DepthPoint(r.best().score(), r.bestMove(), r.nodes(), r.elapsedMs()));
            }
        }
        return byDepth;
    }

    static DepthPoint at(Map<Integer, DepthPoint> m, int d) {
        DepthPoint p = null;
        for (Map.Entry<Integer, DepthPoint> e : m.entrySet()) {
            if (e.getKey() <= d) {
                p = e.getValue();
            }
        }
        return p;
    }

    /** Score after the move from the mover's POV, handling mate/stalemate positions. */
    static Score playedForMover(String fenAfter, DepthPoint after) {
        Board b = new Board();
        b.loadFromFen(fenAfter);
        if (MoveGenerator.generateLegalMoves(b).isEmpty()) {
            return b.isKingAttacked() ? Score.mate(1) : Score.cp(0);
        }
        return after.score().negate();
    }

    void depthStudy(List<Sample> samples) throws Exception {
        int[] depths = { 6, 8, 10, 11, 12, 13, 14, 16 };
        Map<Integer, List<Long>> nodesAfter = new HashMap<>();
        Map<Integer, List<Long>> timeAfter = new HashMap<>();
        Map<Integer, int[]> agree = new HashMap<>(); // [exact, sameGroup, severe, missedBlunder, falseBlunder, n]
        Map<Integer, int[]> agreeDeepBefore = new HashMap<>();
        int[] refCounts = new int[MoveQuality.values().length];
        long t0 = System.nanoTime();
        try (UciClient ref = StockfishTestSupport.client("reference", 4, 256);
             UciClient one = StockfishTestSupport.client("single", 1, 16)) {
            int i = 0;
            for (Sample s : samples) {
                i++;
                Map<Integer, DepthPoint> refBefore = profile(ref, s.fenBefore, REF_D);
                Map<Integer, DepthPoint> refAfter = profile(ref, s.fenAfter, REF_D);
                Map<Integer, DepthPoint> before = profile(one, s.fenBefore, MAX_D);
                Map<Integer, DepthPoint> after = profile(one, s.fenAfter, MAX_D);
                DepthPoint rb = at(refBefore, REF_D);
                DepthPoint ra = at(refAfter, REF_D);
                if (rb == null || (ra == null && hasMoves(s.fenAfter))) {
                    continue;
                }
                MoveQuality refQ = MoveClassifier.classify(rb.score(), ra == null ? playedForMover(s.fenAfter, null)
                        : playedForMover(s.fenAfter, ra), s.move.equals(rb.best())).quality();
                refCounts[refQ.ordinal()]++;
                for (int d : depths) {
                    DepthPoint b = at(before, d);
                    DepthPoint a = at(after, d);
                    if (b == null || (a == null && hasMoves(s.fenAfter))) {
                        continue;
                    }
                    Score played = a == null ? playedForMover(s.fenAfter, null) : playedForMover(s.fenAfter, a);
                    MoveQuality q = MoveClassifier.classify(b.score(), played, s.move.equals(b.best())).quality();
                    MoveQuality q2 = MoveClassifier.classify(rb.score(), played, s.move.equals(rb.best())).quality();
                    tally(agree.computeIfAbsent(d, x -> new int[6]), q, refQ);
                    tally(agreeDeepBefore.computeIfAbsent(d, x -> new int[6]), q2, refQ);
                    if (a != null) {
                        nodesAfter.computeIfAbsent(d, x -> new ArrayList<>()).add(a.nodes());
                        timeAfter.computeIfAbsent(d, x -> new ArrayList<>()).add(a.timeMs());
                    }
                }
                if (i % 40 == 0) {
                    System.out.printf("  %d/%d samples (%d s)%n", i, samples.size(), (System.nanoTime() - t0) / 1_000_000_000);
                }
            }
        }
        out("\nReference (depth " + REF_D + ") class distribution: " + distribution(refCounts));
        out("\n== Cost of evaluating the position after a move (cold hash, Threads=1) ==");
        out(String.format(Locale.ROOT, "%5s %10s %10s %9s %9s %11s %11s %11s %11s", "depth", "nodes p50", "nodes p95",
                "Mac p50", "Mac p95", "Pi5 1T p50", "Pi5 1T p95", "Pi4 1T p50", "Pi4 1T p95"));
        for (int d : depths) {
            List<Long> n = nodesAfter.getOrDefault(d, List.of());
            List<Long> t = timeAfter.getOrDefault(d, List.of());
            if (n.isEmpty()) {
                continue;
            }
            long t50 = pct(t, 50);
            long t95 = pct(t, 95);
            out(String.format(Locale.ROOT, "%5d %10d %10d %7dms %7dms %9dms %9dms %9dms %9dms", d, pct(n, 50), pct(n, 95),
                    t50, t95, (long) (t50 / PI5), (long) (t95 / PI5), (long) (t50 / PI4), (long) (t95 / PI4)));
        }
        out("\n== Agreement of the LED class at depth d with the depth-" + REF_D + " reference ==");
        out("(both positions searched at depth d / position before at reference depth = user had time to think)");
        out(String.format(Locale.ROOT, "%5s | %7s %7s %7s %8s %8s | %7s %7s %7s %8s %8s", "depth", "exact", "ok/err",
                "severe", "missBlun", "falseBlun", "exact", "ok/err", "severe", "missBlun", "falseBlun"));
        for (int d : depths) {
            int[] a = agree.get(d);
            int[] b = agreeDeepBefore.get(d);
            if (a == null) {
                continue;
            }
            out(String.format(Locale.ROOT, "%5d | %6.1f%% %6.1f%% %6.1f%% %8d %8d | %6.1f%% %6.1f%% %6.1f%% %8d %8d", d,
                    100.0 * a[0] / a[5], 100.0 * a[1] / a[5], 100.0 * a[2] / a[5], a[3], a[4],
                    100.0 * b[0] / b[5], 100.0 * b[1] / b[5], 100.0 * b[2] / b[5], b[3], b[4]));
        }
        out("exact = same class; ok/err = same 'BEST/GOOD vs error' verdict; severe = classes 2+ steps apart "
                + "(BEST and GOOD count as one step); missBlun/falseBlun = blunders missed / invented.");
    }

    static boolean hasMoves(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return !MoveGenerator.generateLegalMoves(b).isEmpty();
    }

    static void tally(int[] t, MoveQuality q, MoveQuality ref) {
        t[5]++;
        if (q == ref) {
            t[0]++;
        }
        if (q.isError() == ref.isError()) {
            t[1]++;
        }
        if (Math.abs(rank(q) - rank(ref)) >= 2) {
            t[2]++;
        }
        if (ref == MoveQuality.BLUNDER && q.ordinal() <= MoveQuality.INACCURACY.ordinal()) {
            t[3]++;
        }
        if (q == MoveQuality.BLUNDER && ref.ordinal() <= MoveQuality.INACCURACY.ordinal()) {
            t[4]++;
        }
    }

    static int rank(MoveQuality q) {
        return q == MoveQuality.BEST ? 0 : q.ordinal() - 1;
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
