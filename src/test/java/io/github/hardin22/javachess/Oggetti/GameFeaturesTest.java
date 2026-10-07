package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Analysis.MoveText;
import io.github.hardin22.javachess.Play.GameSnapshot;
import io.github.hardin22.javachess.Play.GameSnapshotStore;
import io.github.hardin22.javachess.Play.TimeControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Move list, start position, take-back and resuming in the base game class (no screen, simulated board). */
class GameFeaturesTest {

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** Applies moves like the real games do (doMove + updatePgn) and saves itself for resuming. */
    static final class Game extends AbstractGame {
        Game() {
            super(null, null);
        }

        @Override
        public void startGame() {
            gameRunning = true;
        }

        @Override
        public void handleMoveInput(String moveInput) {
            Move m = MoveText.legal(board, moveInput);
            board.doMove(m);
            updatePgn(m);
        }

        @Override
        public void endGame(String endMessage, boolean saveGame) {
            gameRunning = false;
            forgetSnapshotIfFinished(endMessage);
        }

        @Override
        protected GameSnapshot snapshot() {
            return new GameSnapshot(GameSnapshot.Mode.PVP, initialFen, movesUci, true, null, null, 0,
                    TimeControl.minutes(5, 0), 300_000, 300_000, 0, 0, null, null);
        }

        String getInitialFenForTest() {
            return initialFen;
        }

        String pgnText() {
            return pgn.toString().trim();
        }

        boolean undo(int plies) {
            return undoPlies(plies);
        }

        void replay(List<String> moves) {
            replayMoves(moves);
        }
    }

    @BeforeAll
    static void simulatedBoard() {
        System.setProperty("javachess.board", "sim");
    }

    @BeforeEach
    @AfterEach
    void noSavedGame() throws Exception {
        GameSnapshotStore.get().clear();
        flushStorage();
    }

    static void flushStorage() throws Exception {
        io.github.hardin22.javachess.Utils.AppExecutors.storage().submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void movesAreRecordedAndSavedAfterEachMove() throws Exception {
        Game g = new Game();
        g.startGame();
        g.handleMoveInput("e2e4");
        g.handleMoveInput("e7e5");
        assertEquals(List.of("e2e4", "e7e5"), g.getMovesUci());
        flushStorage();
        GameSnapshot saved = GameSnapshotStore.get().load().orElseThrow();
        assertEquals(List.of("e2e4", "e7e5"), saved.moves());
    }

    @Test
    void takeBackRestoresPositionMovesAndPgn() {
        Game g = new Game();
        g.startGame();
        g.handleMoveInput("e2e4");
        g.handleMoveInput("e7e5");
        String afterTwo = g.getBoard().getFen();
        g.handleMoveInput("g1f3");
        g.handleMoveInput("b8c6");
        assertEquals("1. e2e4 e7e5 2. g1f3 b8c6", g.pgnText());
        assertTrue(g.undo(2));
        assertEquals(List.of("e2e4", "e7e5"), g.getMovesUci());
        assertEquals("1. e2e4 e7e5", g.pgnText());
        assertEquals(afterTwo, g.getBoard().getFen());
        assertTrue(g.resyncing, "the board is asked to put the pieces back");
        assertFalse(g.undo(3), "not enough moves");
        g.handleMoveInput("f1c4");
        assertEquals("1. e2e4 e7e5 2. f1c4", g.pgnText());
    }

    @Test
    void startPositionAndReplay() {
        Game g = new Game();
        String fen = "4k3/8/8/8/8/8/4P3/4K3 b - - 0 40";
        g.setStartPosition(fen);
        assertEquals(fen, g.getInitialFenForTest());
        g.replay(List.of("e8d7", "e2e4", "zzzz", "d7d6"));
        assertEquals(List.of("e8d7", "e2e4"), g.getMovesUci(), "replay stops at the first illegal move");
        assertEquals("e8d7 41. e2e4", g.pgnText());
        g.startGame();
        assertThrows(IllegalStateException.class, () -> g.setStartPosition(START));
    }

    @Test
    void aResultForgetsTheSavedGameAnInterruptionKeepsIt() throws Exception {
        Game g = new Game();
        g.startGame();
        g.handleMoveInput("e2e4");
        g.endGame("Partita interrotta", true);
        flushStorage();
        assertTrue(GameSnapshotStore.get().load().isPresent());

        Game h = new Game();
        h.startGame();
        h.handleMoveInput("d2d4");
        h.endGame("Il Nero abbandona: vince il Bianco", true);
        flushStorage();
        assertTrue(GameSnapshotStore.get().load().isEmpty());
    }
}
