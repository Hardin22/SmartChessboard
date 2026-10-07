package io.github.hardin22.javachess.Engine.review;

/**
 * Something that evaluates positions for the review: a pool of Stockfish processes ({@link StockfishPool}), a
 * cache in front of it, or a fake in tests. Implementations are thread safe: the reviewer calls
 * {@link #evaluate} from up to {@link #parallelism()} threads at once.
 */
public interface PositionEvaluator extends AutoCloseable {

    /**
     * Evaluates a non-terminal position. Blocking.
     *
     * @param fen     the position (must have legal moves)
     * @param multiPv number of lines (1, or 2 for the second best move)
     * @param nodes   node budget (deterministic and hardware independent, unlike time)
     */
    PositionEval evaluate(String fen, int multiPv, long nodes) throws Exception;

    /** How many {@link #evaluate} calls can usefully run at the same time. */
    int parallelism();

    /** Identifies the engine and its settings, part of the cache key ("stockfish-19"). */
    String id();

    @Override
    default void close() {
    }
}
