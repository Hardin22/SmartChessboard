package io.github.hardin22.javachess.Stats;

import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.GameReplay;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewStoreTest {

    @TempDir
    Path dir;

    static final List<String> MOVES = List.of("e2e4", "e7e5", "g1f3", "b8c6");

    static GameReview review(double whiteAcc, double blackAcc) {
        GameReplay replay = GameReplay.of(null, MOVES);
        List<MoveReview> moves = new ArrayList<>();
        List<PositionEval> positions = new ArrayList<>();
        MoveClassification[] labels = {MoveClassification.BOOK_MOVE, MoveClassification.BOOK_MOVE,
                MoveClassification.BEST, MoveClassification.BLUNDER};
        for (int i = 0; i < MOVES.size(); i++) {
            String fen = replay.fens().get(i);
            moves.add(new MoveReview(i, MOVES.get(i), replay.sans().get(i), fen, i % 2 == 0, labels[i],
                    Eval.cp(30), i == 3 ? Eval.whiteMates(4) : Eval.cp(-25), i == 3 ? "g8f6" : MOVES.get(i),
                    i == 3 ? List.of("g8f6", "d2d4") : List.of(), 0.55, i == 3 ? 0.05 : 0.5, i == 3 ? 5 : 100));
            positions.add(new PositionEval(fen, Eval.cp(20), List.of(), 12, 100, false));
        }
        positions.add(new PositionEval(replay.fens().get(4), Eval.blackMates(0), List.of(), 0, 0, true));
        return new GameReview(replay.initialFen(), moves, positions, whiteAcc, blackAcc, "C44 King's Knight Opening",
                GameReview.Stats.NONE);
    }

    @Test
    void savedReviewComesBackIdentical() {
        ReviewStore store = new ReviewStore(dir, Runnable::run);
        String key = ReviewStore.key(null, MOVES, 1500, 0);
        GameReview r = review(91.5, Double.NaN);
        store.save(key, r);
        GameReview back = new ReviewStore(dir, Runnable::run).find(key).orElseThrow();
        assertEquals(r.moves(), back.moves());
        assertEquals(91.5, back.whiteAccuracy());
        assertTrue(Double.isNaN(back.blackAccuracy()));
        assertEquals("C44 King's Knight Opening", back.opening());
        assertEquals(5, back.positions().size());
        assertEquals(Eval.blackMates(0), back.positions().get(4).eval());
        assertEquals(List.of("g8f6", "d2d4"), back.moves().get(3).bestLine());
    }

    @Test
    void summariesSurviveARestart() {
        ReviewStore store = new ReviewStore(dir, Runnable::run);
        String key = ReviewStore.key(null, MOVES, 0, 0);
        store.save(key, review(88.0, 61.25));
        ReviewStore.Summary s = new ReviewStore(dir, Runnable::run).summary(key).orElseThrow();
        assertEquals(88.0, s.whiteAccuracy());
        assertEquals(61.25, s.blackAccuracy());
        assertEquals(1, s.count(false, MoveClassification.BLUNDER));
        assertEquals(1, s.count(true, MoveClassification.BOOK_MOVE));
        assertEquals("C44 King's Knight Opening", s.opening());
        assertTrue(!s.phases().isEmpty());
        assertTrue(s.analyzedAt() != null);
    }

    @Test
    void keysDependOnGameRatingsAndVersion() {
        String a = ReviewStore.key(null, MOVES, 0, 0);
        assertEquals(a, ReviewStore.key(io.github.hardin22.javachess.Analysis.AnalysisTree.START_FEN, MOVES, 0, 0));
        assertNotEquals(a, ReviewStore.key(null, MOVES.subList(0, 3), 0, 0));
        assertNotEquals(a, ReviewStore.key(null, MOVES, 1500, 0));
        assertEquals(32, a.length());
    }

    @Test
    void damagedFilesAreIgnored() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("abc.json"), "{ broken");
        Files.writeString(dir.resolve("index.json"), "nope");
        ReviewStore store = new ReviewStore(dir, Runnable::run);
        assertTrue(store.find("abc").isEmpty());
        assertTrue(store.find("missing").isEmpty());
        assertTrue(store.summaries().isEmpty());
        store.save("k", review(80, 70)); // rewrites a good index
        assertEquals(1, new ReviewStore(dir, Runnable::run).summaries().size());
    }
}
