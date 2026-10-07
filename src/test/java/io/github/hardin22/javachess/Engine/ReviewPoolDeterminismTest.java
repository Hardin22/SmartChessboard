package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewSettings;
import io.github.hardin22.javachess.Engine.review.StockfishPool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With a cleared hash before every search a review evaluation does not depend on what the process searched before. */
class ReviewPoolDeterminismTest {

    private static final String A = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4";
    /** A after 4...g6: the search of A visits it, a warm hash could change the next search of A. */
    private static final String B = "r1bqkb1r/pppp1p1p/2n2np1/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 5";

    @Test
    void sameEvaluationWhateverWasSearchedBefore() throws Exception {
        try (StockfishPool pool = new StockfishPool(StockfishTestSupport.requireStockfish(), 1, 16, StockfishPool.HashMode.COLD)) {
            PositionEval first = pool.evaluate(A, 1, 60_000);
            pool.evaluate(B, 1, 300_000);
            PositionEval again = pool.evaluate(A, 1, 60_000);
            assertEquals(first.eval(), again.eval());
            assertEquals(first.bestMove(), again.bestMove());
            assertEquals(first.depth(), again.depth());
            assertEquals(first.best().pv(), again.best().pv());
        }
    }

    @Test
    void blockHashGivesTheSameReviewWithAnyNumberOfProcesses() throws Exception {
        List<String> moves = List.of("e4 e5 Nf3 Nc6 Bc4 Nf6 Ng5 d5 exd5 Na5 Bb5+ c6 dxc6 bxc6 Be2 h6 Nf3 e4 Ne5 Bd6"
                .split(" "));
        GameReview one = review(1, moves);
        GameReview three = review(3, moves);
        for (int i = 0; i < one.positions().size(); i++) {
            assertEquals(one.positions().get(i).eval(), three.positions().get(i).eval(), "position " + i);
            assertEquals(one.positions().get(i).bestMove(), three.positions().get(i).bestMove(), "position " + i);
        }
        for (int i = 0; i < one.moves().size(); i++) {
            assertEquals(one.moves().get(i).label(), three.moves().get(i).label(), "move " + i);
        }
    }

    private static GameReview review(int processes, List<String> moves) throws Exception {
        ReviewSettings s = new ReviewSettings(40_000, 20_000, processes, 16);
        try (GameReviewer r = new GameReviewer(new StockfishPool(StockfishTestSupport.requireStockfish(), processes, 16,
                StockfishPool.HashMode.BLOCK), s, OpeningBook.NONE)) {
            return r.review(null, moves, null);
        }
    }
}
