package io.github.hardin22.javachess.review;

import java.util.List;

/** Our side of the comparison: reviews one game. Adapters wrap the production review API. */
public interface Reviewer extends AutoCloseable {

    String name();

    /** Blocking review of the whole game. */
    Result review(ChessComDataset.Game game) throws Exception;

    @Override
    default void close() {
    }

    /**
     * @param labels      one label per ply (never null entries)
     * @param whiteCp     evaluation after each ply, White POV centipawns (mates as +/-100000), may be empty
     * @param elapsedMs   wall time of the review
     * @param nodes       engine nodes searched (0 when unknown)
     * @param cacheHits   positions served by a cache (their time is not engine time)
     */
    record Result(List<ReviewLabel> labels, double whiteAccuracy, double blackAccuracy, List<Double> whiteCp,
                  long elapsedMs, long nodes, int cacheHits) {

        public Result(List<ReviewLabel> labels, double whiteAccuracy, double blackAccuracy, List<Double> whiteCp,
                      long elapsedMs) {
            this(labels, whiteAccuracy, blackAccuracy, whiteCp, elapsedMs, 0, 0);
        }
    }
}
