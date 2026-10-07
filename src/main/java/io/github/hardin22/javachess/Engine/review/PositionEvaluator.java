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
     * @param nodes   node budget (hardware independent, unlike time)
     */
    PositionEval evaluate(String fen, int multiPv, long nodes) throws Exception;

    /**
     * Adds the second best line to an evaluation searched with MultiPV 1 (for Great/Brilliant). The default searches
     * again with MultiPV 2; engines can search only the other moves, which is much cheaper.
     *
     * @param p           evaluation with at least the best line
     * @param mainNodes   node budget {@code p} was searched with (cache key)
     * @param secondNodes node budget of the second line
     */
    default PositionEval addSecondLine(PositionEval p, long mainNodes, long secondNodes) throws Exception {
        return evaluate(p.fen(), 2, Math.max(mainNodes, secondNodes));
    }

    /**
     * The calling thread is about to evaluate a block of consecutive positions of one game: an engine may reserve a
     * process with a cleared hash for it, so the block's evaluations do not depend on what was searched before.
     * Always paired with {@link #endBlock()} in a finally. No-op by default.
     */
    default void startBlock() throws Exception {
    }

    /** End of the calling thread's block (see {@link #startBlock()}). */
    default void endBlock() {
    }

    /** A new game starts: engines may clear their hash (no-op by default). */
    default void newGame() {
    }

    /** How many {@link #evaluate} calls can usefully run at the same time. */
    int parallelism();

    /** Identifies the engine and its settings, part of the cache key ("stockfish-19"). */
    String id();

    @Override
    default void close() {
    }
}
