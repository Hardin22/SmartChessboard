package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.ProcessPlan;
import io.github.hardin22.javachess.Engine.review.CachingEvaluator;
import io.github.hardin22.javachess.Engine.review.EvalCache;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEvaluator;
import io.github.hardin22.javachess.Engine.review.ReviewSettings;
import io.github.hardin22.javachess.Engine.review.StockfishPool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The review core of {@code Engine.review} ({@link GameReviewer}) with explicit, reproducible settings:
 * {@code -Dreview.nodes} (default: the Stockfish Lite budget), {@code -Dreview.processes}, {@code -Dreview.hash}.
 * Evaluations are cached in {@code target/review-cache} (keyed by position, MultiPV and nodes), so re-running the
 * harness after a classifier change costs no engine time; {@code -Dreview.cache=false} measures cold runs.
 */
public final class CoreReviewer implements Reviewer {

    private final GameReviewer reviewer;
    private final ReviewSettings settings;
    private final String name;

    public CoreReviewer(Path stockfish) {
        int cores = Runtime.getRuntime().availableProcessors();
        ReviewSettings base = ReviewSettings.lite(ProcessPlan.forRam(8_192, 4), cores);
        long nodes = Long.getLong("review.nodes", base.nodes());
        settings = new ReviewSettings(nodes, Long.getLong("review.secondLineNodes",
                nodes * base.secondLineNodes() / base.nodes()),
                Integer.getInteger("review.processes", base.processes()), Integer.getInteger("review.hash",
                base.hashMb()));
        PositionEvaluator pool = new StockfishPool(stockfish, settings.processes(), settings.hashMb());
        boolean cache = !"false".equals(System.getProperty("review.cache"));
        PositionEvaluator evaluator = cache
                ? new CachingEvaluator(pool, EvalCache.in(Path.of(System.getProperty("review.cacheDir",
                "target/review-cache")), pool.id()))
                : pool;
        reviewer = new GameReviewer(evaluator, settings, OpeningBook.standard());
        name = String.format("GameReviewer core, %dk nodes (2nd line %dk), %d processes x 1 thread, hash %d%s",
                settings.nodes() / 1000, settings.secondLineNodes() / 1000, settings.processes(), settings.hashMb(),
                cache ? ", disk cache" : "");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Result review(ChessComDataset.Game game) throws Exception {
        long t0 = System.nanoTime();
        GameReview r = reviewer.review(null, game.uci(), null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (r.moves().size() != game.plies()) {
            throw new IllegalStateException(game.id() + ": reviewed " + r.moves().size() + " of " + game.plies());
        }
        List<ReviewLabel> labels = new ArrayList<>();
        List<Double> cp = new ArrayList<>();
        List<Double> winBefore = new ArrayList<>();
        List<Double> winAfter = new ArrayList<>();
        for (MoveReview m : r.moves()) {
            labels.add(ReviewLabel.of(m.label()));
            cp.add(m.after().legacyPawns() * 100);
            winBefore.add(m.winBefore());
            winAfter.add(m.winAfter());
        }
        // with cache hits the engine work is rebuilt from the stored evaluations (main pass + MultiPV re-searches)
        long nodes = r.stats().cacheHits() == 0 ? r.stats().nodes()
                : r.positions().stream().mapToLong(p -> p.nodes()).sum()
                        + (long) r.stats().multiPvSearches() * settings.secondLineNodes();
        return new Result(labels, r.whiteAccuracy(), r.blackAccuracy(), cp, ms, nodes, r.stats().cacheHits(),
                winBefore, winAfter);
    }

    @Override
    public void close() {
        reviewer.close();
    }
}
