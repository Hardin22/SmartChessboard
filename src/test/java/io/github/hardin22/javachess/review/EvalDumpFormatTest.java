package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The eval dump format round-trips and feeds the pure classifier like the product does. */
class EvalDumpFormatTest {

    @Test
    void evalStringsRoundTrip() {
        for (Eval e : List.of(Eval.cp(0), Eval.cp(-45), Eval.cp(1234), Eval.whiteMates(3), Eval.blackMates(0),
                Eval.whiteMates(0), Eval.blackMates(12))) {
            assertEquals(e, EvalDump.parse(EvalDump.format(e)));
        }
        assertEquals("W#3", EvalDump.format(Eval.whiteMates(3)));
        assertEquals("cp:-45", EvalDump.format(Eval.cp(-45)));
    }

    /** Fool's mate: 1.f3 e5 2.g4 Qh4#, with the second line stored everywhere. */
    @Test
    void foolsMateFromADump(@TempDir Path dir) throws Exception {
        String[] fens = {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "rnbqkbnr/pppppppp/8/8/8/5P2/PPPPP1PP/RNBQKBNR b KQkq - 0 1",
                "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq - 0 2",
                "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2",
                "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"};
        String head = "{\"type\":\"game\",\"id\":\"fool\",\"initial_fen\":\"\",\"uci\":[\"f2f3\",\"e7e5\",\"g2g4\","
                + "\"d8h4\"],\"budget\":\"lite\",\"nodes\":200000,\"second_nodes\":100000}";
        String[] pos = {
                pos(0, fens[0], "cp:30", "e2e4", "d2d4", "cp:25"),
                pos(1, fens[1], "cp:-60", "e7e5", "d7d5", "cp:-50"),
                pos(2, fens[2], "cp:-80", "b1c3", "e2e4", "cp:-90"),
                pos(3, fens[3], "B#1", "d8h4", "b8c6", "cp:-300"),
                "{\"type\":\"pos\",\"i\":4,\"fen\":\"" + fens[4] + "\",\"legal\":0,\"terminal\":true,\"e\":\"B#0\"}"};
        Path budget = Files.createDirectories(dir.resolve("lite"));
        Files.writeString(budget.resolve("fool.jsonl"), head + "\n" + String.join("\n", pos) + "\n");

        List<EvalDump.Game> games = EvalDump.load(dir, "lite");
        assertEquals(1, games.size());
        EvalDump.Game g = games.get(0);
        assertEquals(5, g.positions().size());
        assertTrue(g.positions().get(4).terminal());

        ReviewInput product = g.input(EvalDump.Mode.PRODUCT, OpeningBook.NONE);
        ReviewInput everywhere = g.input(EvalDump.Mode.SECOND_EVERYWHERE, OpeningBook.NONE);
        assertNotNull(everywhere.positions().get(2).secondBest());
        // the product only re-searches where the classifier asks (never a position where the move played was not
        // the engine's choice)
        assertNull(product.positions().get(2).secondBest());

        GameReview r = ReviewClassifier.classifyGame(product);
        assertEquals(MoveClassification.BLUNDER, r.moves().get(2).label(), "2.g4?? allows mate in one");
        assertNotEquals(MoveClassification.BLUNDER, r.moves().get(3).label(), "2...Qh4# is never an error");
        assertEquals("", ReviewCv.mateCheck(r, 3, ReviewLabel.of(r.moves().get(3).label())));
    }

    private static String pos(int i, String fen, String e, String best, String second, String secondEval) {
        return "{\"type\":\"pos\",\"i\":" + i + ",\"fen\":\"" + fen + "\",\"legal\":20,\"terminal\":false,\"e\":\"" + e
                + "\",\"depth\":15,\"nodes\":200000,\"pv\":[\"" + best + "\"],\"second\":{\"move\":\"" + second
                + "\",\"e\":\"" + secondEval + "\",\"depth\":14,\"nodes\":100000,\"pv\":[\"" + second + "\"]}}";
    }
}
