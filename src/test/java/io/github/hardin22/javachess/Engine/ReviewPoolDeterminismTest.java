package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.StockfishPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With a cleared hash before every search a review evaluation does not depend on what the process searched before. */
class ReviewPoolDeterminismTest {

    private static final String A = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4";
    /** A after 4...g6: the search of A visits it, a warm hash could change the next search of A. */
    private static final String B = "r1bqkb1r/pppp1p1p/2n2np1/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 5";

    @Test
    void sameEvaluationWhateverWasSearchedBefore() throws Exception {
        try (StockfishPool pool = new StockfishPool(StockfishTestSupport.requireStockfish(), 1, 16, true)) {
            PositionEval first = pool.evaluate(A, 1, 60_000);
            pool.evaluate(B, 1, 300_000);
            PositionEval again = pool.evaluate(A, 1, 60_000);
            assertEquals(first.eval(), again.eval());
            assertEquals(first.bestMove(), again.bestMove());
            assertEquals(first.depth(), again.depth());
            assertEquals(first.best().pv(), again.best().pv());
        }
    }
}
