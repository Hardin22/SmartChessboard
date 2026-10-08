package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Hardware.LedMapping;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.MoveLeds;
import io.github.hardin22.javachess.Hardware.SimulatedBoard;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Rigioca i tuoi errori" with the real pieces: simulated sensors, real board manager. */
class MistakeTrainerBoardTest {

    SimulatedBoard sim;
    LedRenderer leds;
    BoardStateManager manager;
    BoardFollower follower;
    MistakeTrainer trainer;

    @BeforeEach
    void setUp() {
        sim = new SimulatedBoard();
        leds = new LedRenderer(sim, LedMapping.DEFAULT);
        manager = new BoardStateManager(leds, new MoveLeds(leds), Runnable::run, new BoardStateManager.HintProvider() {
            @Override
            public void pieceLifted(Board position, Square from, double eval, String best, boolean evaluate) {
            }

            @Override
            public void hintsCleared() {
            }
        }, Executors.newSingleThreadScheduledExecutor());
        manager.setTimings(5, 250, 60);
        sim.start(manager);
        manager.awaitIdle();
        follower = new BoardFollower(manager);
        // a wrong try is judged far worse than the best move
        trainer = new MistakeTrainer(MistakeTrainer.exercises(MistakeTrainerTest.review(), true, false),
                (fen, tried, best) -> CompletableFuture.completedFuture(0.5), Runnable::run, follower);
    }

    @AfterEach
    void tearDown() {
        trainer.close();
        manager.shutdown();
        leds.shutdown();
    }

    void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.awaitIdle();
            Thread.sleep(10);
        }
        assertTrue(cond.getAsBoolean(), "trainer " + trainer.stateProperty().get() + " / follower "
                + follower.stateProperty().get() + " / " + follower.messageProperty().get());
    }

    void piecesAs(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        sim.setOccupancy(BoardStateManager.occupancy(b));
    }

    @Test
    void wrongTryOnTheBoardIsTakenBackThenTheRightMoveCounts() throws InterruptedException {
        String start = trainer.fenProperty().get();
        trainer.useBoard(true);
        piecesAs(start);
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        sim.lift("D2"); // 3. d4?
        sim.place("D4");
        await(() -> follower.stateProperty().get() == BoardFollower.State.REPLICATING);
        assertEquals("Riporta indietro sulla scacchiera: 3. d4, da d4 a d2", follower.messageProperty().get());
        assertEquals(MistakeTrainer.State.YOUR_MOVE, trainer.stateProperty().get());
        assertEquals("Non è la mossa giusta: rimetti il pezzo e riprova", trainer.messageProperty().get());
        assertEquals(start, trainer.fenProperty().get());

        sim.lift("D4"); // put it back
        sim.place("D2");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        sim.lift("F1"); // 3. Bb5!
        sim.place("B5");
        await(() -> trainer.stateProperty().get() == MistakeTrainer.State.CORRECT);
        assertEquals(1, trainer.solvedProperty().get());
        assertEquals(2, trainer.triesProperty().get());

        trainer.next(); // the next position is set up with the LEDs
        await(() -> follower.stateProperty().get() == BoardFollower.State.PLACING);
    }
}
