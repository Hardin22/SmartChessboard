package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Analysis.ReviewFixtures.Spec;
import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.Score;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisSessionTest {

    static final List<String> GAME = List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "f3g5", "d7d5");

    EngineLinesTest.FakeSource source;
    EngineLines lines;
    AnalysisSession session;

    @BeforeEach
    void setUp() {
        TestConfig.isolate();
        source = new EngineLinesTest.FakeSource();
        lines = new EngineLines(source, Runnable::run, null, 0);
        session = new AnalysisSession(null, GAME, lines, null, OpeningBook.standard());
    }

    @Test
    void startsAtTheBeginningAndFollowsTheLines() {
        assertEquals("Posizione iniziale", session.titleProperty().get());
        assertFalse(session.canGoBackProperty().get());
        assertTrue(session.canGoForwardProperty().get());
        assertNull(session.lastMoveProperty().get());
        assertEquals(AnalysisTree.START_FEN, session.fenProperty().get());
        assertEquals(AnalysisTree.START_FEN, lines.fenProperty().get(), "lines follow the position");

        session.next();
        assertEquals("1. e4", session.titleProperty().get());
        assertEquals("e2e4", session.lastMoveProperty().get());
        assertEquals(1, session.mainPlyProperty().get());
        assertTrue(session.bookMoveProperty().get());
        assertEquals(session.fenProperty().get(), lines.fenProperty().get());

        session.goToPly(6);
        assertEquals("3… Cf6", session.titleProperty().get());
        assertTrue(session.openingProperty().get().contains("Two Knights"), session.openingProperty().get());
        session.last();
        assertFalse(session.canGoForwardProperty().get());
        session.first();
        assertEquals(0, session.plyProperty().get());
    }

    @Test
    void variationFromTheScreenAndBackToTheGame() {
        session.goToPly(6); // after 3... Nf6
        int rev = session.revisionProperty().get();
        assertTrue(session.play("d2d4"));
        assertTrue(session.inVariationProperty().get());
        assertEquals("4. d4", session.titleProperty().get());
        assertEquals("4. d4", session.variationTextProperty().get());
        assertEquals(6, session.mainPlyProperty().get(), "the move list keeps the branch point highlighted");
        assertEquals(7, session.plyProperty().get());
        assertEquals(rev + 1, session.revisionProperty().get());
        assertTrue(session.hasVariationsProperty().get());
        assertNull(session.insightProperty().get());

        assertTrue(session.play("e5d4"));
        assertEquals("4. d4 exd4", session.variationTextProperty().get());
        assertFalse(session.play("a1a8"), "illegal move refused");

        session.backToGame();
        assertFalse(session.inVariationProperty().get());
        assertEquals(6, session.plyProperty().get());
        assertTrue(session.movetext().contains("(4. d4 exd4)"), session.movetext());

        // the game move itself is not a variation
        assertTrue(session.play("f3g5"));
        assertFalse(session.inVariationProperty().get());
    }

    @Test
    void playAComputerLine() {
        session.goToPly(2);
        String fen = session.fenProperty().get();
        source.listener.onUpdate(new AnalysisUpdate(fen, 20, List.of(
                EngineLinesTest.line(1, 20, Score.cp(30), "g1f3", "b8c6", "f1b5"),
                EngineLinesTest.line(2, 20, Score.cp(20), "b1c3", "g8f6")), true, null, 1, 1));
        assertTrue(session.playLine(1));
        assertEquals("2. Cc3", session.titleProperty().get());
        assertTrue(session.inVariationProperty().get());
        assertTrue(session.next());
        assertEquals("2… Cf6", session.titleProperty().get());
        assertFalse(session.canGoForwardProperty().get());
        assertFalse(session.playLine(2), "no third line");
    }

    @Test
    void reviewDetailsAndShowBest() {
        List<Spec> specs = new ArrayList<>(List.of(Spec.book(), Spec.book(), Spec.book(), Spec.book(), Spec.good(),
                Spec.good(), Spec.good(), Spec.bad(MoveClassification.MISTAKE, "d7d6", 0.5, 0.2, 35)));
        GameReview review = ReviewFixtures.review(null, GAME, specs);
        session.goToPly(8);
        assertNull(session.insightProperty().get(), "no review yet");
        session.attachReview(review);
        ReviewInsights.MoveInsight m = session.insightProperty().get();
        assertNotNull(m);
        assertEquals(MoveClassification.MISTAKE, m.label());
        assertTrue(m.showBest());
        assertEquals("4… d6", m.bestText());

        assertTrue(session.showBestLine());
        assertTrue(session.inVariationProperty().get());
        assertEquals("4… d6", session.titleProperty().get());
        assertEquals("d7d6", session.lastMoveProperty().get());
        session.backToGame();
        assertEquals(7, session.plyProperty().get(), "back to the position where the best move was possible");

        session.goToPly(1);
        assertFalse(session.insightProperty().get().showBest());
        assertFalse(session.showBestLine());
    }

    @Test
    void deleteVariationAndTapTargets() {
        session.goToPly(2);
        session.play("d2d4");
        assertTrue(session.deleteVariation());
        assertFalse(session.hasVariationsProperty().get());
        assertEquals(2, session.plyProperty().get());
        assertFalse(session.deleteVariation());
        assertEquals(List.of("e2", "f3", "g4", "h5"), session.legalTargets("d1"));
        assertEquals(List.of(), session.legalTargets("e5"), "not the side to move");
        assertEquals(List.of(), session.legalTargets("zz"));
    }

    @Test
    void oneNotificationPerMove() {
        List<AnalysisSession.Position> seen = new ArrayList<>();
        session.positionProperty().addListener((obs, o, n) -> seen.add(n));
        session.next();
        session.next();
        session.attachReview(null); // no position change: no notification
        session.play("d2d4");
        assertEquals(3, seen.size());
        assertEquals("e2e4", seen.get(0).lastMove());
        assertEquals(2, seen.get(1).mainPly());
        assertTrue(seen.get(2).inVariation());
        assertEquals(3, seen.get(2).ply());
        assertEquals(session.fenProperty().get(), seen.get(2).fen());
    }

    @Test
    void freeAnalysisFromAPosition() {
        String fen = "8/8/8/4k3/8/8/4P3/4K3 w - - 0 1";
        AnalysisSession free = new AnalysisSession(fen, List.of(), lines, null, null);
        assertEquals(fen, free.fenProperty().get());
        assertFalse(free.canGoForwardProperty().get());
        assertTrue(free.play("e2e4"));
        assertTrue(free.inVariationProperty().get(), "moves of a free analysis are variations of the start");
        assertEquals("1. e4", free.titleProperty().get());
        assertEquals("", free.openingProperty().get());
        free.close();
        assertEquals(EngineLines.Status.IDLE, lines.statusProperty().get());
    }
}
