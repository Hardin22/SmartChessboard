package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrillSessionTest {

    @TempDir
    Path dir;

    /** Plays the scripted moves in order, with the given value for the side to move. */
    static final class Script implements DrillSession.Opponent {
        final Deque<String> moves = new ArrayDeque<>();
        int cp;
        int calls;

        Script(int cp, String... moves) {
            this.cp = cp;
            this.moves.addAll(List.of(moves));
        }

        @Override
        public CompletableFuture<DrillSession.Reply> reply(String fen) {
            calls++;
            return CompletableFuture.completedFuture(new DrillSession.Reply(moves.poll(), cp));
        }
    }

    DrillProgress progress() {
        return new DrillProgress(dir.resolve("endgames.json"), Runnable::run,
                Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC));
    }

    static EndgameDrills.Drill drill(String fen, boolean white, EndgameDrills.Goal goal, int moves) {
        return new EndgameDrills.Drill("test", EndgameDrills.MATES, "Prova", fen, white, goal, moves, 1, "");
    }

    DrillSession session(EndgameDrills.Drill d, DrillSession.Opponent o, DrillProgress p) {
        return new DrillSession(d, o, Runnable::run, null, p);
    }

    @Test
    void mateWithinTheMovesSucceedsAndIsRecorded() {
        DrillProgress progress = progress();
        DrillSession s = session(drill("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", true, EndgameDrills.Goal.MATE, 3),
                new Script(0), progress);
        assertEquals("Dai scacco matto in 3 mosse", s.messageProperty().get());
        assertFalse(s.play("a1a9"), "not a move");
        assertTrue(s.play("a1a8"));
        assertEquals(DrillSession.State.SUCCESS, s.stateProperty().get());
        assertEquals("Scacco matto! Ce l'hai fatta in 1 mossa", s.messageProperty().get());
        assertEquals("Risolto · 1 mossa", progress.entry("test").label());
        assertEquals(2, progress.entry("test").stars());
    }

    @Test
    void stalemateLosesTheWin() {
        DrillSession s = session(drill("7k/8/6Q1/8/8/8/8/K7 w - - 0 1", true, EndgameDrills.Goal.MATE, 10),
                new Script(0), null);
        s.play("g6f7");
        assertEquals(DrillSession.State.FAILED, s.stateProperty().get());
        assertEquals("Stallo: la vittoria è sfumata", s.messageProperty().get());
    }

    @Test
    void theComputerAnswersAndTheMovesRunOut() {
        Script script = new Script(0, "h8h7", "h7h8");
        DrillSession s = session(drill("7k/8/8/8/8/8/8/K5Q1 w - - 0 1", true, EndgameDrills.Goal.MATE, 2),
                script, null);
        assertTrue(s.play("g1g2"));
        assertEquals(DrillSession.State.YOUR_MOVE, s.stateProperty().get());
        assertEquals("Il computer ha giocato 1… Rh7 · ultima mossa", s.messageProperty().get());
        assertEquals(1, s.movesLeftProperty().get());
        assertTrue(s.play("g2g3"));
        assertEquals(DrillSession.State.FAILED, s.stateProperty().get());
        assertEquals("Mosse finite: riprova, puoi farcela in 2 mosse", s.messageProperty().get());

        s.restart();
        assertEquals(DrillSession.State.YOUR_MOVE, s.stateProperty().get());
        assertEquals(2, s.movesLeftProperty().get());
        assertEquals("7k/8/8/8/8/8/8/K5Q1 w - - 0 1", s.fenProperty().get());
    }

    @Test
    void aPromotionCountsOnlyWhenTheNewQueenIsSafe() {
        DrillSession safe = session(drill("8/4P3/8/8/8/8/k7/4K3 w - - 0 1", true, EndgameDrills.Goal.PROMOTE, 5),
                new Script(0), null);
        safe.play("e7e8q");
        assertEquals(DrillSession.State.SUCCESS, safe.stateProperty().get());
        assertEquals("Promosso! 1. e8=D in 1 mossa", safe.messageProperty().get());

        DrillSession taken = session(drill("3k4/4P3/8/8/8/8/8/4K3 w - - 0 1", true, EndgameDrills.Goal.PROMOTE, 5),
                new Script(0, "d8e8"), null);
        taken.play("e7e8q");
        assertEquals(DrillSession.State.FAILED, taken.stateProperty().get(), "the queen is taken: a draw");
        assertEquals("Materiale insufficiente: la vittoria è sfumata", taken.messageProperty().get());
    }

    @Test
    void holdingTheDrawIsCheckedByTheEngineAtTheEnd() {
        String fen = "8/8/8/4k3/8/8/4P3/4K3 b - - 0 1";
        DrillSession held = session(drill(fen, false, EndgameDrills.Goal.DRAW, 1), new Script(0), progress());
        held.play("e5e4");
        assertEquals(DrillSession.State.SUCCESS, held.stateProperty().get());
        assertEquals("Patta tenuta per 1 mossa. Obiettivo raggiunto", held.messageProperty().get());

        DrillSession lost = session(drill(fen, false, EndgameDrills.Goal.DRAW, 1), new Script(900), null);
        lost.play("e5f6");
        assertEquals(DrillSession.State.FAILED, lost.stateProperty().get());
        assertTrue(lost.messageProperty().get().contains("ora la posizione è persa"));
    }

    @Test
    void inADrawDrillAPromotionOfTheOpponentFailsAndTakingThePawnSucceeds() {
        DrillSession promoted = session(drill("8/4P3/8/8/8/k7/8/4K3 b - - 0 1", false, EndgameDrills.Goal.DRAW, 10),
                new Script(0, "e7e8q"), null);
        promoted.play("a3b3");
        assertEquals(DrillSession.State.FAILED, promoted.stateProperty().get());
        assertEquals("Il pedone è arrivato a promozione: riprova", promoted.messageProperty().get());

        DrillSession taken = session(drill("8/8/8/8/8/8/3kP3/7K b - - 0 1", false, EndgameDrills.Goal.DRAW, 10),
                new Script(0), null);
        taken.play("d2e2");
        assertEquals(DrillSession.State.SUCCESS, taken.stateProperty().get());
        assertEquals("Patta: materiale insufficiente. Obiettivo raggiunto", taken.messageProperty().get());
    }

    @Test
    void theComputerMovesFirstWhenItIsItsTurnAndHintsAreCounted() {
        Script script = new Script(0, "e1d2", "e5d5");
        DrillSession s = session(drill("8/8/8/4k3/8/8/4P3/4K3 w - - 0 1", false, EndgameDrills.Goal.DRAW, 5),
                script, null);
        assertEquals(List.of("e1d2"), s.playedMoves());
        assertEquals(DrillSession.State.YOUR_MOVE, s.stateProperty().get());
        s.hint();
        assertEquals("e5d5", s.shownMoveProperty().get());
        assertEquals("Prova 1… Rd5", s.messageProperty().get());
        assertEquals(1, s.hintsProperty().get());
    }

    @Test
    void aMissingEngineDoesNotBlockTheScreen() {
        DrillSession s = session(drill("8/8/8/4k3/8/8/4P3/4K3 w - - 0 1", true, EndgameDrills.Goal.PROMOTE, 5),
                fen -> CompletableFuture.failedFuture(new IllegalStateException("no engine")), null);
        s.play("e2e4");
        assertEquals(DrillSession.State.YOUR_MOVE, s.stateProperty().get());
        assertEquals("Il motore non risponde: tocca \"Ricomincia\"", s.messageProperty().get());
    }

    @Test
    void theCatalogPositionsAreLegalAndStillToPlay() {
        assertEquals(10, EndgameDrills.all().size());
        for (EndgameDrills.Drill d : EndgameDrills.all()) {
            Board b = new Board();
            b.loadFromFen(d.fen());
            assertFalse(b.isMated() || b.isDraw(), d.id());
            assertFalse(b.legalMoves().isEmpty(), d.id());
            assertTrue(EndgameDrills.categories().contains(d.category()), d.id());
            assertFalse(d.tip().isBlank(), d.id());
            assertEquals(d.id(), EndgameDrills.byId(d.id()).orElseThrow().id());
        }
        assertEquals(5, EndgameDrills.inCategory(EndgameDrills.MATES).size());
    }
}
