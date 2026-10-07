package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.PositionEvaluator;
import io.github.hardin22.javachess.Engine.review.ReviewSettings;
import io.github.hardin22.javachess.Engine.review.StockfishPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Engine cost of the product review ({@link GameReviewer}, Lite settings, opening book) on labelled games, split by
 * kind of search, projected to a Raspberry Pi 5 like {@code cv.py}: {@code nodes / (pi5.nps x processes) x 80 /
 * plies} seconds per 40 moves. Opt-in (engine minutes):
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=ReviewCostTest -Dreview.cost=true -Dstockfish.path=... \
 *     -Dreview.dump.only=id1,id2 [-Dreview.cost.processes=3] [-Dreview.cost.secondNodes=N] [-Djavachess.review.*=...]
 * </pre>
 * Kinds: {@code main} = {@link PositionEvaluator#evaluate} at the main budget, {@code main+} = evaluate at any other
 * budget (a re-search), {@code second@N} = {@link PositionEvaluator#addSecondLine} with second-line budget N.
 * Report: {@code target/review-cost.tsv} (one row per game and kind) and a summary on stdout.
 */
@EnabledIfSystemProperty(named = "review.cost", matches = "true")
class ReviewCostTest {

    static final long PI5_NPS = Long.getLong("pi5.nps", 350_000);

    @Test
    void cost() throws Exception {
        ReviewSettings lite = ReviewSettings.lite();
        int processes = Integer.getInteger("review.cost.processes", lite.processes());
        ReviewSettings s = new ReviewSettings(lite.nodes(), Long.getLong("review.cost.secondNodes",
                lite.secondLineNodes()), processes, lite.hashMb());
        List<String> only = List.of(System.getProperty("review.dump.only", "").split(","));
        List<LabelledGame> games = LabelledGame.loadAll(EvalDumpTest.labelsDir(), EvalDumpTest.gamesDir()).stream()
                .filter(g -> only.contains(g.id())).toList();
        StringBuilder tsv = new StringBuilder("id\tplies\tkind\tcalls\tnodes\tpi5_s_per_40\n");
        List<double[]> perGame = new ArrayList<>();
        Map<String, double[]> byKind = new TreeMap<>();
        for (LabelledGame g : games) {
            Counting c = new Counting(new StockfishPool(ReviewBenchmarkTest.stockfish(), s.processes(), s.hashMb()),
                    s.nodes());
            long t0 = System.nanoTime();
            try (GameReviewer r = new GameReviewer(c, s, OpeningBook.standard())) {
                r.review(null, g.uci(), null);
            }
            double wall = (System.nanoTime() - t0) / 1e9;
            int plies = g.uci().size();
            double total = 0;
            for (var e : c.nodes.entrySet()) {
                double pi = e.getValue().get() / (double) (PI5_NPS * s.processes()) * 80 / Math.max(1, plies);
                total += pi;
                tsv.append(String.format(Locale.ROOT, "%s\t%d\t%s\t%d\t%d\t%.2f%n", g.id(), plies, e.getKey(),
                        c.calls.get(e.getKey()).get(), e.getValue().get(), pi));
                double[] k = byKind.computeIfAbsent(e.getKey(), x -> new double[3]);
                k[0] += c.calls.get(e.getKey()).get() * 80.0 / Math.max(1, plies);
                k[1] += pi;
                k[2] = Math.max(k[2], pi);
            }
            perGame.add(new double[] { total, plies });
            System.out.printf(Locale.ROOT, "  %s (%d plies) Pi5 %.1f s/40 %s, Mac %.1f s%n", g.id(), plies, total,
                    c.calls, wall);
        }
        Files.writeString(Path.of("target", "review-cost.tsv"), tsv);
        int n = perGame.size();
        System.out.printf(Locale.ROOT, "review cost on %d games (%d processes, Pi 5 %dk nps): mean %.1f s/40, max %.1f%n",
                n, s.processes(), PI5_NPS / 1000, perGame.stream().mapToDouble(x -> x[0]).average().orElse(0),
                perGame.stream().mapToDouble(x -> x[0]).max().orElse(0));
        byKind.forEach((k, v) -> System.out.printf(Locale.ROOT,
                "  %-14s calls/40 moves %.1f, Pi5 mean %.1f s/40, max %.1f%n", k, v[0] / n, v[1] / n, v[2]));
    }

    /** Counts calls and nodes by kind of search. */
    private static final class Counting implements PositionEvaluator {
        final PositionEvaluator delegate;
        final long mainNodes;
        final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> nodes = new ConcurrentHashMap<>();

        Counting(PositionEvaluator delegate, long mainNodes) {
            this.delegate = delegate;
            this.mainNodes = mainNodes;
        }

        private void count(String kind, long n) {
            calls.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
            nodes.computeIfAbsent(kind, k -> new AtomicLong()).addAndGet(n);
        }

        @Override
        public PositionEval evaluate(String fen, int multiPv, long nodes) throws Exception {
            PositionEval p = delegate.evaluate(fen, multiPv, nodes);
            if (!p.terminal()) {
                count(nodes == mainNodes && multiPv == 1 ? "main" : "main+", p.nodes());
            }
            return p;
        }

        @Override
        public PositionEval addSecondLine(PositionEval p, long mainNodes, long secondNodes) throws Exception {
            PositionEval two = delegate.addSecondLine(p, mainNodes, secondNodes);
            if (two != p) {
                count("second@" + secondNodes, two.nodes() - p.nodes());
            }
            return two;
        }

        @Override
        public void startBlock() throws Exception {
            delegate.startBlock();
        }

        @Override
        public void endBlock() {
            delegate.endBlock();
        }

        @Override
        public void newGame() {
            delegate.newGame();
        }

        @Override
        public int parallelism() {
            return delegate.parallelism();
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
