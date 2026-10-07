package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.List;
import java.util.Map;
import java.util.EnumMap;

/**
 * Result of a game review.
 *
 * @param initialFen    starting position
 * @param moves         one entry per reviewed move, in order (stops at the first illegal/unreadable move)
 * @param positions     engine evaluation of every position: {@code positions.size() == moves.size() + 1}
 * @param whiteAccuracy White's accuracy (0..100), NaN when White made no counted move
 * @param blackAccuracy Black's accuracy (0..100), NaN when Black made no counted move
 * @param opening       name of the last book position reached ("C50 Italian Game"), or null
 * @param stats         timings and cache hits
 */
public record GameReview(String initialFen, List<MoveReview> moves, List<PositionEval> positions,
                         double whiteAccuracy, double blackAccuracy, String opening, Stats stats) {

    public GameReview {
        moves = List.copyOf(moves);
        positions = List.copyOf(positions);
    }

    /**
     * @param elapsedMs     wall clock time of the whole review
     * @param searches      engine searches run (cache misses)
     * @param cacheHits     positions taken from the disk cache
     * @param multiPvSearches extra MultiPV searches for Great/Brilliant/Miss
     * @param nodes         total nodes searched
     */
    public record Stats(long elapsedMs, int searches, int cacheHits, int multiPvSearches, long nodes) {
        public static final Stats NONE = new Stats(0, 0, 0, 0, 0);
    }

    /** Number of moves of each label for one side. */
    public Map<MoveClassification, Integer> counts(boolean white) {
        Map<MoveClassification, Integer> m = new EnumMap<>(MoveClassification.class);
        for (MoveClassification c : MoveClassification.values()) {
            m.put(c, 0);
        }
        for (MoveReview r : moves) {
            if (r.whiteMoved() == white) {
                m.merge(r.label(), 1, Integer::sum);
            }
        }
        return m;
    }
}
