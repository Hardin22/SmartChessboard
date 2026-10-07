package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Analysis.ReviewFixtures.Spec;
import io.github.hardin22.javachess.Engine.InfoLine;
import io.github.hardin22.javachess.Engine.Score;
import io.github.hardin22.javachess.Engine.SearchResult;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MistakeTrainerTest {

    static final List<String> GAME = List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "f3g5", "d7d5");

    static GameReview review() {
        return ReviewFixtures.review(null, GAME, List.of(Spec.book(), Spec.book(),
                Spec.bad(MoveClassification.INACCURACY, "d2d4", 0.55, 0.5, 80), Spec.good(),
                Spec.bad(MoveClassification.MISTAKE, "f1b5", 0.55, 0.4, 50),
                Spec.bad(MoveClassification.BLUNDER, "f8c5", 0.5, 0.1, 10),
                Spec.bad(MoveClassification.MISS, "e1g1", 0.8, 0.5, 30)));
    }

    final Map<String, CompletableFuture<Double>> judged = new HashMap<>();
    final MistakeTrainer.Judge judge = (fen, tried, best) -> judged.computeIfAbsent(tried,
            t -> new CompletableFuture<>());

    @Test
    void exercisesOfOneSide() {
        List<MistakeTrainer.Exercise> white = MistakeTrainer.exercises(review(), true, false);
        assertEquals(2, white.size(), "mistake and missed win, not the inaccuracy");
        assertEquals(4, white.get(0).ply());
        assertEquals("3. Ac4", white.get(0).moveText());
        assertEquals("3. Ab5", white.get(0).bestText());
        assertEquals("Trova la mossa migliore per il Bianco", white.get(0).task());
        assertEquals(3, MistakeTrainer.exercises(review(), true, true).size());
        List<MistakeTrainer.Exercise> black = MistakeTrainer.exercises(review(), false, false);
        assertEquals(1, black.size());
        assertEquals("3… Ac5", black.get(0).bestText());
        assertEquals(0, MistakeTrainer.exercises(null, true, false).size());
    }

    @Test
    void bestMoveIsRightAtOnce() {
        MistakeTrainer t = new MistakeTrainer(MistakeTrainer.exercises(review(), true, false), judge, Runnable::run,
                null);
        assertEquals(MistakeTrainer.State.YOUR_MOVE, t.stateProperty().get());
        assertEquals(t.currentProperty().get().fen(), t.fenProperty().get());
        t.attempt("f1b5");
        assertEquals(MistakeTrainer.State.CORRECT, t.stateProperty().get());
        assertEquals("Giusto! È la mossa migliore: 3. Ab5", t.messageProperty().get());
        assertEquals("f1b5", t.shownMoveProperty().get());
        assertEquals(1, t.solvedProperty().get());
        t.attempt("d2d4"); // ignored after the answer
        assertEquals(1, t.triesProperty().get());
        t.next();
        assertEquals(1, t.indexProperty().get());
        assertEquals(0, t.triesProperty().get());
    }

    @Test
    void theGameMoveAndBadMovesAreWrongAnAlmostEqualMoveIsAccepted() {
        MistakeTrainer t = new MistakeTrainer(MistakeTrainer.exercises(review(), true, false), judge, Runnable::run,
                null);
        t.attempt("f1c4");
        assertEquals(MistakeTrainer.State.WRONG, t.stateProperty().get());
        assertEquals("È la mossa giocata in partita: cercane una migliore", t.messageProperty().get());
        t.attempt("d2d4"); // must retry first
        assertEquals(1, t.triesProperty().get());
        t.retry();
        assertEquals(MistakeTrainer.State.YOUR_MOVE, t.stateProperty().get());
        assertEquals(t.currentProperty().get().fen(), t.fenProperty().get());

        t.attempt("d2d4");
        assertEquals(MistakeTrainer.State.CHECKING, t.stateProperty().get());
        judged.get("d2d4").complete(0.20);
        assertEquals(MistakeTrainer.State.WRONG, t.stateProperty().get());
        assertEquals("Non è la mossa giusta: riprova", t.messageProperty().get());

        t.retry();
        t.attempt("b1c3");
        judged.get("b1c3").complete(0.02);
        assertEquals(MistakeTrainer.State.ALSO_GOOD, t.stateProperty().get());
        assertEquals("Anche questa va bene! La migliore era 3. Ab5", t.messageProperty().get());
        assertEquals(1, t.solvedProperty().get());
        assertEquals(3, t.triesProperty().get());
    }

    @Test
    void solutionAndEnd() {
        MistakeTrainer t = new MistakeTrainer(MistakeTrainer.exercises(review(), false, false), judge,
                Runnable::run, null);
        t.showSolution();
        assertEquals(MistakeTrainer.State.SOLUTION, t.stateProperty().get());
        assertEquals("f8c5", t.shownMoveProperty().get());
        assertEquals("La mossa migliore era 3… Ac5 · linea: 3… Ac5", t.messageProperty().get());
        t.next();
        assertEquals(MistakeTrainer.State.FINISHED, t.stateProperty().get());
        assertNull(t.currentProperty().get());
        assertEquals("Finito: 0 su 1 risolte", t.messageProperty().get());

        MistakeTrainer none = new MistakeTrainer(List.of(), judge, Runnable::run, null);
        assertEquals(MistakeTrainer.State.FINISHED, none.stateProperty().get());
        assertEquals("Nessun errore da rigiocare: bella partita!", none.messageProperty().get());
    }

    @Test
    void lateJudgementOfAnOldTryIsIgnored() {
        MistakeTrainer t = new MistakeTrainer(MistakeTrainer.exercises(review(), true, false), judge, Runnable::run,
                null);
        t.attempt("d2d4");
        t.showSolution();
        judged.get("d2d4").complete(0.0);
        assertEquals(MistakeTrainer.State.SOLUTION, t.stateProperty().get());
    }

    @Test
    void engineLossFromScores() {
        SearchResult r = new SearchResult("f1b5", null, List.of(), 10, 1, 1, false, List.of(
                new InfoLine(10, 10, 1, Score.cp(60), InfoLine.Bound.EXACT, 1, 1, 1, List.of("f1b5")),
                new InfoLine(10, 10, 2, Score.cp(-140), InfoLine.Bound.EXACT, 1, 1, 1, List.of("d2d4"))));
        double loss = MistakeTrainer.lossFrom(r, "d2d4", "f1b5");
        assertEquals(Score.cp(60).winProbability() - Score.cp(-140).winProbability(), loss, 1e-9);
        assertThrows(IllegalStateException.class, () -> MistakeTrainer.lossFrom(r, "a2a3", "f1b5"));
    }
}
