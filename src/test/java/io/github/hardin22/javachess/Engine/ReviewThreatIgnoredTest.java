package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.ReviewSettings;
import io.github.hardin22.javachess.Engine.review.StockfishPool;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The "threat ignored" Brilliant needs a search the reviewer runs only for it (the position after the opponent takes
 * the piece left en prise): the real app, not only the gate with stored searches, must find it.
 */
class ReviewThreatIgnoredTest {

    /** Bai Jinshi - Ding Liren 2017 (no ratings: the product's default), up to 33.Nxf7. */
    private static final List<String> BAI_DING = List.of(("d2d4 g8f6 c2c4 e7e6 b1c3 f8b4 g1f3 e8g8 c1g5 c7c5 e2e3 c5d4 "
            + "d1d4 b8c6 d4d3 h7h6 g5h4 d7d5 a1d1 g7g5 h4g3 f6e4 f3d2 e4c5 d3c2 d5d4 d2f3 e6e5 f3e5 d4c3 d1d8 c3b2 e1e2 "
            + "f8d8 c2b2 c5a4 b2c2 a4c3 e2f3 d8d4 h2h3 h6h5 g3h2 g5g4 f3g3 d4d2 c2b3 c3e4 g3h4 b4e7 h4h5 g8g7 h2f4 c8f5 "
            + "f4h6 g7h7 b3b7 d2f2 h6g5 a8h8 e5f7").split(" "));

    @Test
    void ruxf2ByBaiDingIsBrilliantInTheProductReview() throws Exception {
        ReviewSettings s = ReviewSettings.lite();
        try (GameReviewer r = new GameReviewer(new StockfishPool(StockfishTestSupport.requireStockfish(), s.processes(),
                s.hashMb(), StockfishPool.HashMode.BLOCK), s, OpeningBook.standard())) {
            GameReview review = r.review(null, BAI_DING, null);
            // 29...Rxf2!! (ply 58) leaves the rook d2... the queen b7 takes on f2 into mate: chess.com Brilliant
            assertEquals("d2f2", review.moves().get(57).uci());
            assertEquals(MoveClassification.BRILLIANT, review.moves().get(57).label());
        }
    }
}
