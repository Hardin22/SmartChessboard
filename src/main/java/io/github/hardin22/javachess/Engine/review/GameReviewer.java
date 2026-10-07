package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Engine.EngineException;
import io.github.hardin22.javachess.Engine.EngineLocator;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Utils.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a game review: evaluates every position once (MultiPV 1, node budget, in parallel on
 * {@link PositionEvaluator#parallelism()} engines), re-searches with MultiPV 2 only the positions the classifier
 * needs for Great/Brilliant/Miss, then classifies with {@link ReviewClassifier#classifyGame}. The score after a move
 * is the evaluation of the next position (one pass, no per-move search).
 *
 * <p>Blocking: call {@link #review} from a background thread; interrupting it cancels the review.</p>
 */
public final class GameReviewer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameReviewer.class);

    private final PositionEvaluator evaluator;
    private final ReviewSettings settings;
    private final OpeningBook book;

    public GameReviewer(PositionEvaluator evaluator, ReviewSettings settings, OpeningBook book) {
        this.evaluator = evaluator;
        this.settings = settings;
        this.book = book == null ? OpeningBook.NONE : book;
    }

    /**
     * Reviewer for the active engine profile (Stockfish Lite or full): Stockfish processes from
     * {@link ReviewSettings}, the disk cache in the data folder and the bundled opening book. Close it when done
     * (it owns the engine processes).
     */
    public static GameReviewer createDefault() {
        Path sf = EngineLocator.stockfish().path()
                .orElseThrow(() -> new EngineException(EngineLocator.stockfish().describeMissing()));
        EngineManager m = EngineManager.get();
        int cores = Runtime.getRuntime().availableProcessors();
        boolean lite = EngineManager.STOCKFISH_LITE.equals(m.activeProfile().id());
        ReviewSettings s = lite ? ReviewSettings.lite(m.plan(), cores) : ReviewSettings.full(m.plan(), cores);
        StockfishPool pool = new StockfishPool(sf, s.processes(), s.hashMb());
        Path dir = AppPaths.resolve("review-cache");
        return new GameReviewer(new CachingEvaluator(pool, EvalCache.in(dir, pool.id())), s, OpeningBook.standard());
    }

    public ReviewSettings settings() {
        return settings;
    }

    /** Reviews a game given as UCI or SAN tokens (see {@link GameReplay}). Blocking. */
    public GameReview review(String initialFen, List<String> moves, ReviewListener listener)
            throws InterruptedException, ExecutionException {
        ReviewListener l = listener == null ? ReviewListener.NONE : listener;
        long t0 = System.nanoTime();
        GameReplay replay = GameReplay.of(initialFen, moves);
        int n = replay.uci().size();
        PositionEval[] positions = new PositionEval[n + 1];
        int hits0 = evaluator instanceof CachingEvaluator c ? c.hits() : 0;
        int miss0 = evaluator instanceof CachingEvaluator c ? c.misses() : 0;
        AtomicLong nodes = new AtomicLong();

        // pass 1: every position, MultiPV 1
        int threads = Math.max(1, Math.min(evaluator.parallelism(), n + 1));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "review-search");
            t.setDaemon(true);
            return t;
        });
        try {
            AtomicInteger done = new AtomicInteger();
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i <= n; i++) {
                final int idx = i;
                String fen = replay.fens().get(i);
                jobs.add(pool.submit(() -> {
                    PositionEval p = evaluator.evaluate(fen, 1, settings.nodes());
                    positions[idx] = p;
                    nodes.addAndGet(p.nodes());
                    l.onPosition(idx, p);
                    l.onProgress(0.9 * done.incrementAndGet() / (n + 1));
                    return null;
                }));
            }
            await(jobs);

            // pass 2: MultiPV 2 where the classifier needs the second best move
            BitSet need = ReviewClassifier.needsSecondLine(
                    new ReviewInput(replay.initialFen(), replay.uci(), Arrays.asList(positions), book));
            List<Future<?>> second = new ArrayList<>();
            AtomicInteger done2 = new AtomicInteger();
            int total2 = Math.max(1, need.cardinality());
            for (int i = need.nextSetBit(0); i >= 0; i = need.nextSetBit(i + 1)) {
                final int idx = i;
                String fen = replay.fens().get(i);
                second.add(pool.submit(() -> {
                    PositionEval p = evaluator.evaluate(fen, 2, settings.secondLineNodes());
                    positions[idx] = p;
                    nodes.addAndGet(p.nodes());
                    l.onProgress(0.9 + 0.1 * done2.incrementAndGet() / total2);
                    return null;
                }));
            }
            await(second);

            GameReview r = ReviewClassifier.classifyGame(
                    new ReviewInput(replay.initialFen(), replay.uci(), Arrays.asList(positions), book));
            int hits = evaluator instanceof CachingEvaluator c ? c.hits() - hits0 : 0;
            int misses = evaluator instanceof CachingEvaluator c ? c.misses() - miss0 : n + 1 + need.cardinality();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("review: {} moves, {} searches ({} MultiPV 2), {} cache hits, {} nodes in {} ms", n, misses,
                    need.cardinality(), hits, nodes.get(), ms);
            l.onProgress(1.0);
            return new GameReview(r.initialFen(), r.moves(), r.positions(), r.whiteAccuracy(), r.blackAccuracy(),
                    r.opening(), new GameReview.Stats(ms, misses, hits, need.cardinality(), nodes.get()));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(List<Future<?>> jobs) throws InterruptedException, ExecutionException {
        try {
            for (Future<?> f : jobs) {
                f.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            for (Future<?> f : jobs) {
                f.cancel(true);
            }
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            evaluator.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
