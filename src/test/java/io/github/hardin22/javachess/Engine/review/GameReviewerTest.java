package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The reviewer with a fake engine: progressive labels, terminal positions, every position searched once. */
class GameReviewerTest {

    /** Every position is equal, best move = first legal move. */
    private static final class FakeEngine implements PositionEvaluator {
        int searches;

        @Override
        public synchronized PositionEval evaluate(String fen, int multiPv, long nodes) throws InterruptedException {
            searches++;
            Thread.sleep(2);
            Board b = new Board();
            b.loadFromFen(fen);
            String move = b.legalMoves().get(0).toString();
            return new PositionEval(fen, Eval.cp(0), List.of(new EngineLine(move, Eval.cp(0), List.of(move), 10)),
                    10, nodes, false);
        }

        @Override
        public int parallelism() {
            return 3;
        }

        @Override
        public String id() {
            return "fake";
        }
    }

    @Test
    void publishesGrowingProvisionalReviews() throws Exception {
        FakeEngine engine = new FakeEngine();
        List<Integer> sizes = new ArrayList<>();
        String moves = "e4 e5 Nf3 Nc6 Bc4 Nf6 d3 d6 c3 a6 a4 h6 h3 g6 O-O Bg7 Re1 O-O";
        try (GameReviewer r = new GameReviewer(engine, new ReviewSettings(1_000, 1_000, 3, 1), OpeningBook.NONE)) {
            GameReview review = r.review(null, List.of(moves.split(" ")), new ReviewListener() {
                @Override
                public void onPartial(GameReview partial) {
                    synchronized (sizes) {
                        sizes.add(partial.moves().size());
                    }
                }
            });
            assertEquals(18, review.moves().size());
            assertEquals(19, engine.searches, "one search per position (no second lines: nothing is critical)");
        }
        assertFalse(sizes.isEmpty());
        for (int i = 1; i < sizes.size(); i++) {
            assertTrue(sizes.get(i) > sizes.get(i - 1), "prefixes only grow: " + sizes);
        }
        assertEquals(18, sizes.get(sizes.size() - 1), "the last provisional review covers the whole game");
    }

    @Test
    void checkmateIsNotSearched() throws Exception {
        FakeEngine engine = new FakeEngine();
        try (GameReviewer r = new GameReviewer(engine, new ReviewSettings(1_000, 1_000, 2, 1), OpeningBook.NONE)) {
            GameReview review = r.review(null, List.of("f3", "e5", "g4", "Qh4#"), null);
            assertEquals(Eval.blackMates(0), review.positions().get(4).eval());
            assertEquals(4, engine.searches);
        }
    }
}
