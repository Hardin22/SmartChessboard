package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Engine.review.CachingEvaluator;
import io.github.hardin22.javachess.Engine.review.EvalCache;
import io.github.hardin22.javachess.Engine.review.GameReplay;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewSettings;
import io.github.hardin22.javachess.Engine.review.StockfishPool;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Developer tool, skipped unless {@code -Dreview.dataset=<dir of chess.com game JSON>} is set: reviews the games
 * and writes one JSON line per game (our labels, per-position evaluations, our and chess.com accuracies) to
 * {@code -Dreview.out} for offline calibration. Optional: {@code -Dreview.cache}, {@code -Dreview.nodes},
 * {@code -Dreview.processes}, {@code -Dreview.limit}, {@code -Dreview.tag}.
 */
class ReviewCalibrationTest {

    @Test
    void reviewDataset() throws Exception {
        String dataset = System.getProperty("review.dataset");
        assumeTrue(dataset != null && !dataset.isBlank(), "calibration dataset not given");
        Path sf = StockfishTestSupport.requireStockfish();
        Path out = Path.of(System.getProperty("review.out", "target/review-calibration.jsonl"));
        Path cache = Path.of(System.getProperty("review.cache", "target/review-cache"));
        long nodes = Long.getLong("review.nodes", 300_000);
        int processes = Integer.getInteger("review.processes", 4);
        int limit = Integer.getInteger("review.limit", Integer.MAX_VALUE);

        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(dataset))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        ReviewSettings settings = new ReviewSettings(nodes, nodes * 3 / 2, processes, 64);
        StockfishPool pool = new StockfishPool(sf, processes, 64);
        List<String> lines = new ArrayList<>();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.deleteIfExists(out);
        double errSum = 0;
        int errN = 0;
        try (GameReviewer reviewer = new GameReviewer(
                new CachingEvaluator(pool, EvalCache.in(cache, pool.id() + "-" + nodes)), settings,
                OpeningBook.standard())) {
            String tag = System.getProperty("review.tag", "");
            for (Path f : files) {
                JSONObject g = new JSONObject(Files.readString(f));
                if (!tag.isEmpty() && !g.optJSONArray("tags", new JSONArray()).toList().contains(tag)) {
                    continue;
                }
                List<String> uci = new ArrayList<>();
                JSONArray mv = g.optJSONArray("moves_uci");
                if (mv == null) {
                    String movetext = g.getString("pgn").replaceAll("(?m)^\\[.*$", " ");
                    uci.addAll(GameReplay.of(GameReplay.START_FEN, movetext).uci());
                    mv = new JSONArray(uci);
                } else {
                    for (int i = 0; i < mv.length(); i++) {
                        uci.add(mv.getString(i));
                    }
                }
                if (lines.size() >= limit) {
                    break;
                }
                GameReview r = reviewer.review(GameReplay.START_FEN, uci, null);
                JSONObject acc = g.getJSONObject("accuracy");
                JSONObject o = new JSONObject();
                o.put("id", g.getString("id"));
                o.put("white_rating", g.opt("white_rating"));
                o.put("black_rating", g.opt("black_rating"));
                o.put("time_class", g.opt("time_class"));
                o.put("cc_white", acc.optDouble("white"));
                o.put("cc_black", acc.optDouble("black"));
                o.put("our_white", r.whiteAccuracy());
                o.put("our_black", r.blackAccuracy());
                o.put("ms", r.stats().elapsedMs());
                o.put("searches", r.stats().searches());
                o.put("multipv", r.stats().multiPvSearches());
                o.put("nodes", r.stats().nodes());
                o.put("hits", r.stats().cacheHits());
                JSONArray labels = new JSONArray();
                for (MoveReview m : r.moves()) {
                    labels.put(m.label().name());
                }
                o.put("labels", labels);
                JSONArray evals = new JSONArray();
                for (PositionEval p : r.positions()) {
                    JSONObject e = new JSONObject();
                    e.put("k", p.eval().kind().name());
                    e.put("v", p.eval().value());
                    if (p.bestMove() != null) {
                        e.put("best", p.bestMove());
                    }
                    if (p.secondBest() != null) {
                        e.put("k2", p.secondBest().eval().kind().name());
                        e.put("v2", p.secondBest().eval().value());
                    }
                    evals.put(e);
                }
                o.put("evals", evals);
                o.put("uci", mv);
                lines.add(o.toString());
                Files.writeString(out, o + "\n", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                if (!Double.isNaN(r.whiteAccuracy()) && acc.has("white")) {
                    errSum += Math.abs(r.whiteAccuracy() - acc.getDouble("white"));
                    errN++;
                }
                if (!Double.isNaN(r.blackAccuracy()) && acc.has("black")) {
                    errSum += Math.abs(r.blackAccuracy() - acc.getDouble("black"));
                    errN++;
                }
                System.out.printf(Locale.ROOT, "%s ours %.1f/%.1f chess.com %.1f/%.1f %d ms%n", g.getString("id"),
                        r.whiteAccuracy(), r.blackAccuracy(), acc.optDouble("white"), acc.optDouble("black"),
                        r.stats().elapsedMs());
            }
        }
        System.out.printf(Locale.ROOT, "games %d, accuracy MAE %.2f%n", lines.size(), errSum / Math.max(1, errN));
    }
}
