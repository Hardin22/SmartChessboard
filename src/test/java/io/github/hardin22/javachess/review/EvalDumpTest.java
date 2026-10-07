package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.PositionEvaluator;
import io.github.hardin22.javachess.Engine.review.ReviewSettings;
import io.github.hardin22.javachess.Engine.review.StockfishPool;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the evaluation dump of the chess.com-labelled games ({@link EvalDump} format). Opt-in (engine minutes):
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=EvalDumpTest -Dreview.dump=true \
 *     -Dstockfish.path=$HOME/Developer/javaChess/engines/stockfish/stockfish \
 *     [-Dreview.dump.budget=lite|deep|NAME] [-Dreview.dump.nodes=N -Dreview.dump.secondNodes=N]
 *     [-Dreview.dump.processes=3 -Dreview.dump.hash=64] [-Dreview.dump.mpv3=true] [-Dreview.dump.parallelGames=3]
 *     [-Dreview.dump.force=false] [-Dreview.dump.only=id1,id2] [-Dreview.labels=DIR] [-Dreview.gamesDir=DIR] [-Dreview.dump.out=DIR]
 * </pre>
 * The main pass is the product's: a {@link GameReviewer} on a {@link StockfishPool} (N processes x 1 thread, node
 * budget, hash carried over in blocks of consecutive positions). Then every position gets its second line (best move
 * excluded, like the product re-search) and, unless disabled, a MultiPV 3 search at the main budget. Games already
 * dumped are skipped unless {@code force}: re-running after new labelled games arrive only adds those.
 */
@EnabledIfSystemProperty(named = "review.dump", matches = "true")
class EvalDumpTest {

    static final Path TEAM_DATA = Path.of(System.getProperty("user.home"), ".javachess-orchestrator", "review-team",
            "data");

    @Test
    void dumpLabelledGames() throws Exception {
        String budget = System.getProperty("review.dump.budget", "lite");
        ReviewSettings base = "deep".equals(budget) ? ReviewSettings.full() : ReviewSettings.lite();
        long nodes = Long.getLong("review.dump.nodes", base.nodes());
        long second = Long.getLong("review.dump.secondNodes", base.secondLineNodes());
        long mpv3Nodes = Long.getLong("review.dump.mpv3Nodes", nodes);
        boolean mpv3 = !"false".equals(System.getProperty("review.dump.mpv3"));
        ReviewSettings s = new ReviewSettings(nodes, second, Integer.getInteger("review.dump.processes", 3),
                Integer.getInteger("review.dump.hash", 64));
        Path root = Path.of(System.getProperty("review.dump.out", TEAM_DATA.resolve("evals_labeled").toString()));
        // games outside the frozen cross-validation set (folds.json) are the hold-out: kept apart, not for tuning
        Map<String, Integer> folds = ReviewCv.folds(Path.of(System.getProperty("review.dump.folds",
                root.resolve("folds.json").toString())));
        Path cvOut = root.resolve(budget);
        Path holdOut = root.resolve("holdout").resolve(budget);
        Files.createDirectories(cvOut);
        boolean force = Boolean.getBoolean("review.dump.force");

        List<String> only = List.of(System.getProperty("review.dump.only", "").split(",")).stream()
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
        List<LabelledGame> games = new ArrayList<>();
        for (LabelledGame g : LabelledGame.loadAll(labelsDir(), gamesDir())) {
            boolean selected = only.isEmpty() || only.contains(g.id());
            Path out = folds.isEmpty() || folds.containsKey(g.id()) ? cvOut : holdOut;
            if (selected && (force || !Files.exists(out.resolve(g.id() + ".jsonl")))) {
                games.add(g);
            }
        }
        System.out.printf(Locale.ROOT, "eval dump %s: %d games to do, %s, mpv3 %s -> %s%n", budget, games.size(), s,
                mpv3 ? mpv3Nodes : "off", root);
        int parallel = Integer.getInteger("review.dump.parallelGames", 3);
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, parallel));
        AtomicInteger done = new AtomicInteger();
        long t0 = System.nanoTime();
        List<Future<?>> jobs = new ArrayList<>();
        for (LabelledGame g : games) {
            jobs.add(ex.submit(() -> {
                long g0 = System.nanoTime();
                Path out = folds.isEmpty() || folds.containsKey(g.id()) ? cvOut : holdOut;
                Files.createDirectories(out);
                dump(g, budget, s, mpv3 ? mpv3Nodes : 0, out);
                System.out.printf(Locale.ROOT, "  %s (%d plies) %.1fs [%d/%d]%n", g.id(), g.uci().size(),
                        (System.nanoTime() - g0) / 1e9, done.incrementAndGet(), games.size());
                return null;
            }));
        }
        for (Future<?> f : jobs) {
            f.get();
        }
        ex.shutdown();
        System.out.printf(Locale.ROOT, "eval dump %s done in %.0fs%n", budget, (System.nanoTime() - t0) / 1e9);
        try (Stream<Path> files = Files.list(cvOut)) {
            assertTrue(files.count() > 0, "nothing dumped in " + cvOut);
        }
    }

    private static void dump(LabelledGame g, String budget, ReviewSettings s, long mpv3Nodes, Path out)
            throws Exception {
        StockfishPool pool = new StockfishPool(ReviewBenchmarkTest.stockfish(), s.processes(), s.hashMb());
        Recording rec = new Recording(pool);
        long t0 = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        try (GameReviewer reviewer = new GameReviewer(rec, s, OpeningBook.NONE)) {
            GameReview r = reviewer.review(null, g.uci(), null);
            long mainMs = (System.nanoTime() - t0) / 1_000_000;
            JSONObject h = new JSONObject();
            h.put("type", "game");
            h.put("id", g.id());
            h.put("initial_fen", "");
            h.put("uci", new JSONArray(g.uci()));
            h.put("budget", budget);
            h.put("nodes", s.nodes());
            h.put("second_nodes", s.secondLineNodes());
            h.put("mpv3_nodes", mpv3Nodes);
            h.put("processes", s.processes());
            h.put("hash_mb", s.hashMb());
            h.put("engine", pool.id());
            h.put("hash_mode", pool.hashMode().name().toLowerCase(Locale.ROOT));
            h.put("cold_hash", pool.hashMode() == StockfishPool.HashMode.COLD);
            h.put("product_ms", mainMs);
            List<String> lines = new ArrayList<>();
            List<PositionEval> ps = r.positions();
            // the product's own second line where it searched one, else a search of the same kind now; then the
            // diagnostic MultiPV 3, all on the pool's processes in parallel
            PositionEval[] twos = new PositionEval[ps.size()];
            PositionEval[] threes = new PositionEval[ps.size()];
            ExecutorService ex = Executors.newFixedThreadPool(pool.parallelism());
            try {
                List<Future<?>> jobs = new ArrayList<>();
                for (int i = 0; i < ps.size(); i++) {
                    final int idx = i;
                    if (ps.get(i).terminal()) {
                        continue;
                    }
                    jobs.add(ex.submit(() -> {
                        PositionEval main = rec.mains.get(ps.get(idx).fen());
                        PositionEval two = rec.seconds.get(main.fen());
                        twos[idx] = two != null ? two : pool.addSecondLine(main, s.nodes(), s.secondLineNodes());
                        if (mpv3Nodes > 0) {
                            threes[idx] = pool.evaluate(main.fen(), 3, mpv3Nodes);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : jobs) {
                    f.get();
                }
            } finally {
                ex.shutdownNow();
            }
            for (int i = 0; i < ps.size(); i++) {
                PositionEval p = ps.get(i);
                JSONObject o = new JSONObject();
                o.put("type", "pos");
                o.put("i", i);
                o.put("fen", p.fen());
                o.put("legal", legalMoves(p.fen()));
                o.put("terminal", p.terminal());
                o.put("e", EvalDump.format(p.eval()));
                if (!p.terminal()) {
                    PositionEval main = rec.mains.get(p.fen());
                    o.put("depth", main.depth());
                    o.put("nodes", main.nodes());
                    o.put("pv", new JSONArray(main.best().pv().subList(0, Math.min(main.best().pv().size(),
                            EvalDump.PV_MAX))));
                    PositionEval two = twos[i];
                    o.put("second", two.secondBest() == null ? JSONObject.NULL
                            : EvalDump.line(two.secondBest(), two.nodes() - main.nodes()));
                    if (threes[i] != null) {
                        PositionEval m = threes[i];
                        JSONObject mo = new JSONObject();
                        mo.put("depth", m.depth());
                        mo.put("nodes", m.nodes());
                        JSONArray la = new JSONArray();
                        // UciClient keeps the last EXACT line per multipv index: when the search stops on a
                        // bound line, an older line can repeat a move of a newer one (reported to realtime)
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        m.lines().stream().filter(l -> seen.add(l.move())).forEach(l -> la.put(EvalDump.line(l, -1)));
                        mo.put("lines", la);
                        o.put("mpv3", mo);
                    }
                }
                lines.add(o.toString());
            }
            h.put("total_ms", (System.nanoTime() - t0) / 1_000_000);
            sb.append(h).append('\n');
            lines.forEach(l -> sb.append(l).append('\n'));
        }
        Path tmp = out.resolve(g.id() + ".jsonl.tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, out.resolve(g.id() + ".jsonl"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Keeps the product's main searches and second lines apart (both are stored separately in the dump). */
    private static final class Recording implements PositionEvaluator {
        final PositionEvaluator delegate;
        final Map<String, PositionEval> mains = new ConcurrentHashMap<>();
        final Map<String, PositionEval> seconds = new ConcurrentHashMap<>();

        Recording(PositionEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public PositionEval evaluate(String fen, int multiPv, long nodes) throws Exception {
            PositionEval p = delegate.evaluate(fen, multiPv, nodes);
            mains.put(fen, p);
            return p;
        }

        @Override
        public PositionEval addSecondLine(PositionEval p, long mainNodes, long secondNodes) throws Exception {
            PositionEval two = delegate.addSecondLine(p, mainNodes, secondNodes);
            seconds.put(p.fen(), two);
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

    private static int legalMoves(String fen) {
        com.github.bhlangonijr.chesslib.Board b = new com.github.bhlangonijr.chesslib.Board();
        b.loadFromFen(fen);
        return b.legalMoves().size();
    }

    static Path labelsDir() {
        return Path.of(System.getProperty("review.labels", TEAM_DATA.resolve("labels_chesscom").toString()));
    }

    static Path gamesDir() {
        return Path.of(System.getProperty("review.gamesDir", TEAM_DATA.resolve("games").toString()));
    }
}
