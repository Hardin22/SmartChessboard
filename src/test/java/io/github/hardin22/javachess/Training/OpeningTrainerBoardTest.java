package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Analysis.AnalysisTree;
import io.github.hardin22.javachess.Analysis.BoardFollower;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.LedMapping;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.MoveLeds;
import io.github.hardin22.javachess.Hardware.SimulatedBoard;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The opening trainer played with the real pieces: simulated sensors, real board manager and LEDs. */
class OpeningTrainerBoardTest {

    SimulatedBoard sim;
    LedRenderer leds;
    BoardStateManager manager;
    BoardFollower follower;
    OpeningTrainer trainer;

    @BeforeEach
    void setUp() {
        sim = new SimulatedBoard();
        leds = new LedRenderer(sim, LedMapping.DEFAULT);
        BoardStateManager.HintProvider noHints = new BoardStateManager.HintProvider() {
            @Override
            public void pieceLifted(Board position, Square from, double eval, String best, boolean evaluate) {
            }

            @Override
            public void hintsCleared() {
            }
        };
        manager = new BoardStateManager(leds, new MoveLeds(leds), Runnable::run, noHints,
                Executors.newSingleThreadScheduledExecutor());
        manager.setTimings(5, 250, 60);
        sim.start(manager);
        manager.awaitIdle();
        follower = new BoardFollower(manager);
        trainer = new OpeningTrainer(OpeningCatalog.byId("italiana").orElseThrow(), OpeningExplorer.standard(),
                OpeningExplorer.Rating.CLUB, OpeningBook.standard(), 12, new Random(5), follower, null);
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

    int ledAt(String square) {
        return leds.composeNow()[Squares.parse(square)];
    }

    void move(String from, String to) {
        sim.lift(from);
        sim.place(to);
    }

    @Test
    void theLineIsPlayedOnTheBoardAndTheAppsRepliesAreShownOnTheLeds() throws InterruptedException {
        Board start = new Board();
        sim.setOccupancy(BoardStateManager.occupancy(start));
        trainer.useBoard(true);
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        move("E2", "E4");
        await(() -> follower.stateProperty().get() == BoardFollower.State.REPLICATING);
        assertEquals(List.of("e2e4", "e7e5"), trainer.playedMoves());
        assertEquals("Esegui sulla scacchiera: 1… e5", follower.messageProperty().get());
        await(() -> ledAt("E7") == LedColors.REPLICATE && ledAt("E5") == LedColors.REPLICATE);
        move("E7", "E5");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        move("D2", "D4"); // not the Italian: the LEDs take it back
        await(() -> follower.stateProperty().get() == BoardFollower.State.REPLICATING);
        assertEquals(OpeningTrainer.State.WRONG, trainer.stateProperty().get());
        assertEquals("Riporta indietro sulla scacchiera: 2. d4, da d4 a d2", follower.messageProperty().get());
        move("D4", "D2");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        move("G1", "F3");
        await(() -> trainer.playedMoves().size() == 4);
        assertEquals("b8c6", trainer.playedMoves().get(3));
        await(() -> follower.stateProperty().get() == BoardFollower.State.REPLICATING);
        move("B8", "C6");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);
        assertEquals(1, trainer.mistakesProperty().get());
    }

    @Test
    void restartingSetsTheStartingPositionUpAgain() throws InterruptedException {
        sim.setOccupancy(BoardStateManager.occupancy(new Board()));
        trainer.useBoard(true);
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);
        move("E2", "E4");
        await(() -> follower.stateProperty().get() == BoardFollower.State.REPLICATING);
        move("E7", "E5");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        trainer.restart();
        assertEquals(AnalysisTree.START_FEN, trainer.fenProperty().get());
        await(() -> ledAt("E2") == LedColors.MISSING && ledAt("E4") == LedColors.WRONG);
        move("E4", "E2");
        move("E5", "E7");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);
    }
}
