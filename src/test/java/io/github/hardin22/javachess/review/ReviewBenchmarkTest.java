package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.EngineLocator;
import io.github.hardin22.javachess.Engine.EngineSpec;
import io.github.hardin22.javachess.Engine.MoveQuality;
import io.github.hardin22.javachess.Engine.SearchLimits;
import io.github.hardin22.javachess.Engine.SearchResult;
import io.github.hardin22.javachess.Engine.UciClient;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Engine cost of the review and of the LED verdict, measured on this machine and projected to a Raspberry Pi 5.
 * Opt-in: {@code -Dreview.bench=true} (minutes). Output: {@code target/review-bench/bench.md} and {@code bench.json}.
 *
 * <p><b>Pi 5 projection.</b> Stockfish's work is counted in nodes, so the projection only needs the single-core
 * speed of both machines: {@code piTime = nodes / (piNpsPerCore * threads * threadEfficiency)}. This machine's
 * nodes/s per core and its thread efficiency (nps(T) / (T * nps(1))) are measured here on middlegame positions; the
 * Pi 5 (Cortex-A76 @ 2.4 GHz, SF19 NNUE) is assumed at {@code -Dpi5.nps} per core (default 350k, the 300-400k range
 * is reported too) with the same thread efficiency. The explicit Mac/Pi ratio is {@code macNps1 / pi5.nps}.
 * Replace the assumption with a measure ({@code stockfish bench} on the Pi) as soon as one is available.</p>
 *
 * <ul>
 *   <li>Calibration: nps with 1, 2 and 4 threads.</li>
 *   <li>Review workload of the legacy GameAnalyzer (each position at depth D, MultiPV 3) on a sample of fixture
 *       games: nodes and time per ply.</li>
 *   <li>LED depth study: verdict of the played move ({@link ReviewClassifier#fast}) at depth 8..16 versus a depth-20
 *       reference on real game positions, with the cost of each depth (single thread, like the LED path).</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "review.bench", matches = "true")
class ReviewBenchmarkTest {

    static final long PI5_NPS = Long.getLong("pi5.nps", 350_000);
    static final String[] MIDDLEGAMES = {
            "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R w KQ - 0 8",
            "r2q1rk1/pp1bbppp/2nppn2/8/3NP3/2N1B3/PPPQBPPP/R4RK1 w - - 4 10",
            "2rq1rk1/pp1bppbp/3p1np1/4n3/3NP3/1BN1BP2/PPPQ2PP/2KR3R w - - 5 12",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "r1b2rk1/pp1nqppp/2pbpn2/3p4/2PP4/2NBPN2/PPQ2PPP/R1B2RK1 w - - 2 9",
            "8/5pk1/6p1/3R4/5P2/r5P1/5K2/8 b - - 1 45",
    };

    final StringBuilder md = new StringBuilder();
    final JSONObject json = new JSONObject();

    @Test
    void benchmark() throws Exception {
        Path sf = stockfish();
        Path out = Path.of(System.getProperty("review.out", "target/review-bench"));
        Files.createDirectories(out);
        md.append("## Engine benchmark (").append(sf).append(")\n\n");

        Map<Integer, Double> nps = calibrate(sf);
        double nps1 = nps.get(1);
        double ratio = nps1 / PI5_NPS;
        md.append(f("Mac/Pi 5 single-core ratio: %.2fx (this machine %.0fk nps/core, Pi 5 assumed %dk; 300k-400k range "
                + "gives %.2fx-%.2fx).%n%n", ratio, nps1 / 1000, PI5_NPS / 1000, nps1 / 400_000, nps1 / 300_000));
        json.put("macNps1", Math.round(nps1)).put("pi5Nps", PI5_NPS).put("macPiRatio", round(ratio));

        if (!Boolean.getBoolean("review.bench.skipReview")) {
            reviewWorkload(sf, nps);
        }
        if (!Boolean.getBoolean("review.bench.skipLed")) {
            ledDepthStudy(sf);
        }
        Files.writeString(out.resolve("bench.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("bench.json"), json.toString(2), StandardCharsets.UTF_8);
        System.out.println(md);
    }

    // ------------------------------------------------------------------ calibration

    Map<Integer, Double> calibrate(Path sf) throws Exception {
        long nodes = Long.getLong("review.bench.calNodes", 3_000_000);
        Map<Integer, Double> nps = new LinkedHashMap<>();
        md.append("| threads | nps | nps/thread | efficiency |\n|---:|---:|---:|---:|\n");
        for (int t : new int[] { 1, 2, 4 }) {
            long n = 0, ms = 0;
            try (UciClient e = client(sf, t, 64)) {
                for (String fen : MIDDLEGAMES) {
                    e.newGame().get(30, TimeUnit.SECONDS);
                    SearchResult r = e.search(fen, SearchLimits.nodes(nodes)).result().get(10, TimeUnit.MINUTES);
                    n += r.nodes();
                    ms += r.elapsedMs();
                }
            }
            double v = n * 1000.0 / Math.max(1, ms);
            nps.put(t, v);
            md.append(f("| %d | %.0fk | %.0fk | %.2f |%n", t, v / 1000, v / t / 1000, v / t / nps.get(1)));
            json.put("macNps" + t, Math.round(v));
        }
        md.append('\n');
        return nps;
    }

    // ------------------------------------------------------------------ review workload (legacy model)

    void reviewWorkload(Path sf, Map<Integer, Double> nps) throws Exception {
        int depth = Integer.getInteger("review.depth", 12);
        int threads = Integer.getInteger("review.bench.threads", 4);
        List<ChessComDataset.Game> games = sample(Integer.getInteger("review.bench.games", 4));
        long nodes = 0, ms = 0;
        int positions = 0, plies = 0;
        try (UciClient e = client(sf, threads, 128)) {
            for (ChessComDataset.Game g : games) {
                e.newGame().get(30, TimeUnit.SECONDS);
                for (String fen : g.fens()) {
                    if (MateProbe.isMate(fen) || stalemate(fen)) {
                        continue;
                    }
                    SearchResult r = e.search(fen, SearchLimits.depth(depth).withMultiPv(3)).result()
                            .get(10, TimeUnit.MINUTES);
                    nodes += r.nodes();
                    ms += r.elapsedMs();
                    positions++;
                }
                plies += g.plies();
            }
        }
        double nodesPerPly = nodes / (double) plies;
        double macMsPerPly = ms / (double) plies;
        double eff2 = nps.get(2) / 2 / nps.get(1);
        double eff4 = nps.get(4) / 4 / nps.get(1);
        double pi2 = nodesPerPly / (PI5_NPS * 2 * eff2) * 1000;
        double pi4 = nodesPerPly / (PI5_NPS * 4 * eff4) * 1000;
        md.append(f("**Legacy review workload** (depth %d, MultiPV 3, N+1 searches, %d threads, %d games, %d plies)%n%n",
                depth, threads, games.size(), plies));
        md.append("| | per ply | per 40-move game (80 plies) |\n|---|---:|---:|\n");
        md.append(f("| nodes | %.0fk | %.1fM |%n", nodesPerPly / 1000, nodesPerPly * 80 / 1e6));
        md.append(f("| Mac (%d threads) | %.0f ms | %.1f s |%n", threads, macMsPerPly, macMsPerPly * 80 / 1000));
        md.append(f("| Pi 5 est., 2 threads | %.0f ms | %.1f s |%n", pi2, pi2 * 80 / 1000));
        md.append(f("| Pi 5 est., 4 threads | %.0f ms | %.1f s |%n%n", pi4, pi4 * 80 / 1000));
        json.put("legacyReview", new JSONObject().put("depth", depth).put("positions", positions).put("plies", plies)
                .put("nodesPerPly", Math.round(nodesPerPly)).put("macMsPerPly", round(macMsPerPly))
                .put("pi5MsPerPly2T", round(pi2)).put("pi5MsPerPly4T", round(pi4))
                .put("pi5SecPer40Moves4T", round(pi4 * 80 / 1000)));
    }

    // ------------------------------------------------------------------ LED depth study

    void ledDepthStudy(Path sf) throws Exception {
        int[] depths = Arrays.stream(System.getProperty("review.bench.ledDepths", "8,10,12,14,16").split(","))
                .mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        int refDepth = Integer.getInteger("review.bench.refDepth", 20);
        int samples = Integer.getInteger("review.bench.ledPlies", 120);
        List<String[]> plies = samplePlies(samples);

        int n = depths.length;
        int[] agree = new int[n], errorFlip = new int[n], severe = new int[n];
        long[] nodes = new long[n], ms = new long[n];
        List<List<Long>> latency = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            latency.add(new ArrayList<>());
        }
        int counted = 0;
        try (UciClient e = client(sf, 1, 16)) {
            for (String[] p : plies) {
                String before = p[0], move = p[1], after = p[2];
                if (MateProbe.isMate(after) || stalemate(after)) {
                    continue; // exact verdict, no search needed
                }
                e.newGame().get(30, TimeUnit.SECONDS);
                MoveQuality ref = verdict(e, before, move, after, refDepth, null);
                counted++;
                for (int i = 0; i < n; i++) {
                    e.newGame().get(30, TimeUnit.SECONDS);
                    long[] cost = new long[2];
                    MoveQuality q = verdict(e, before, move, after, depths[i], cost);
                    nodes[i] += cost[0];
                    ms[i] += cost[1];
                    latency.get(i).add(cost[0]);
                    if (q == ref) {
                        agree[i]++;
                    }
                    if (q.isError() != ref.isError()) {
                        errorFlip[i]++;
                    }
                    if (Math.abs(q.ordinal() - ref.ordinal()) >= 2) {
                        severe[i]++;
                    }
                }
            }
        }
        md.append(f("**LED verdict vs depth** (%d real positions, played move, 1 thread, two searches: before and after "
                + "the move; reference depth %d)%n%n", counted, refDepth));
        md.append("| depth | same class | error/no-error flips | off by 2+ classes | nodes p50 | nodes p95 | Mac ms mean "
                + "| Pi 5 ms p50 | Pi 5 ms p95 |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        JSONObject led = new JSONObject();
        for (int i = 0; i < n; i++) {
            long p50 = pct(latency.get(i), 50), p95 = pct(latency.get(i), 95);
            md.append(f("| %d | %.1f%% | %.1f%% | %.1f%% | %.0fk | %.0fk | %.0f | %.0f | %.0f |%n", depths[i],
                    100.0 * agree[i] / counted, 100.0 * errorFlip[i] / counted, 100.0 * severe[i] / counted,
                    p50 / 1000.0, p95 / 1000.0, ms[i] / (double) counted, p50 * 1000.0 / PI5_NPS,
                    p95 * 1000.0 / PI5_NPS));
            led.put(String.valueOf(depths[i]), new JSONObject().put("agree", round(agree[i] / (double) counted))
                    .put("errorFlip", round(errorFlip[i] / (double) counted))
                    .put("nodesP50", p50).put("nodesP95", p95)
                    .put("pi5MsP50", Math.round(p50 * 1000.0 / PI5_NPS))
                    .put("pi5MsP95", Math.round(p95 * 1000.0 / PI5_NPS)));
        }
        md.append("\nPi 5 times = nodes of both searches / pi5.nps (one core); the live analysis usually has the "
                + "\"before\" search already done when the move is played, so the real post-move latency is lower.\n\n");
        json.put("ledDepth", led);
    }

    /**
     * LED-style verdict ({@link ReviewClassifier#fast}): best line before vs the position after the move, both at
     * {@code depth}, MultiPV 1.
     */
    static MoveQuality verdict(UciClient e, String before, String move, String after, int depth, long[] cost)
            throws Exception {
        SearchResult b = e.search(before, SearchLimits.depth(depth)).result().get(10, TimeUnit.MINUTES);
        SearchResult a = e.search(after, SearchLimits.depth(depth)).result().get(10, TimeUnit.MINUTES);
        if (cost != null) {
            cost[0] = b.nodes() + a.nodes();
            cost[1] = b.elapsedMs() + a.elapsedMs();
        }
        boolean white = before.split(" ")[1].equals("w");
        Eval best = Eval.fromUci(b.score(), white);
        Eval played = Eval.fromUci(a.score(), !white);
        return ReviewClassifier.fast(best, played, white, move.equals(b.bestMove())).quality();
    }

    // ------------------------------------------------------------------ helpers

    static List<ChessComDataset.Game> sample(int k) {
        List<ChessComDataset.Game> all = new ArrayList<>(ChessComDataset.load());
        Collections.shuffle(all, new Random(7));
        return all.subList(0, Math.min(k, all.size()));
    }

    /** Random (fixed seed) plies of the fixture games, skipping the first 8 (opening). {before, uci, after}. */
    static List<String[]> samplePlies(int k) {
        List<String[]> all = new ArrayList<>();
        for (ChessComDataset.Game g : ChessComDataset.load()) {
            for (int i = 8; i < g.plies(); i++) {
                all.add(new String[] { g.fens().get(i), g.uci().get(i), g.fens().get(i + 1) });
            }
        }
        Collections.shuffle(all, new Random(11));
        return all.subList(0, Math.min(k, all.size()));
    }

    static boolean stalemate(String fen) {
        com.github.bhlangonijr.chesslib.Board b = new com.github.bhlangonijr.chesslib.Board();
        b.loadFromFen(fen);
        return b.isStaleMate();
    }

    static Path stockfish() {
        String prop = System.getProperty("stockfish.path");
        Optional<Path> p = prop != null && !prop.isBlank() ? Optional.of(Path.of(prop)) : EngineLocator.stockfish().path();
        assumeTrue(p.isPresent() && Files.isExecutable(p.get()), "Stockfish not installed: benchmark skipped");
        return p.get();
    }

    static UciClient client(Path sf, int threads, int hash) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("Threads", String.valueOf(threads));
        opts.put("Hash", String.valueOf(hash));
        UciClient c = new UciClient(EngineSpec.of("bench-" + threads, sf, opts));
        c.start().get(60, TimeUnit.SECONDS);
        return c;
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

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String f(String fmt, Object... args) {
        return String.format(Locale.ROOT, fmt, args);
    }
}
