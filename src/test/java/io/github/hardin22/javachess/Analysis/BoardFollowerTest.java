package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.LedMapping;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.MoveLeds;
import io.github.hardin22.javachess.Hardware.SimulatedBoard;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Services.BoardStateManager;
import com.github.bhlangonijr.chesslib.Square;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The analysis and the (simulated) physical board in step: real board manager, sensors and LEDs. */
class BoardFollowerTest {

    static final List<String> GAME = List.of("e2e4", "e7e5", "g1f3", "b8c6");

    SimulatedBoard sim;
    LedRenderer leds;
    BoardStateManager manager;
    AnalysisSession session;
    BoardFollower follower;

    @BeforeEach
    void setUp() {
        TestConfig.isolate();
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
        EngineLines lines = new EngineLines(new EngineLinesTest.FakeSource(), Runnable::run, null, 0);
        session = new AnalysisSession(null, GAME, lines, follower, OpeningBook.NONE);
    }

    @AfterEach
    void tearDown() {
        session.close();
        manager.shutdown();
        leds.shutdown();
    }

    void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.awaitIdle();
            Thread.sleep(10);
        }
        assertTrue(cond.getAsBoolean(), "condition not met; follower " + follower.stateProperty().get() + " / "
                + follower.messageProperty().get());
    }

    int ledAt(String square) {
        return leds.composeNow()[Squares.parse(square)];
    }

    void setUpPieces(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        sim.setOccupancy(BoardStateManager.occupancy(b));
    }

    @Test
    void guidesThePiecesThenPlaysTheBoardMovesAsAVariation() throws InterruptedException {
        session.goToPly(2); // after 1... e5
        session.setBoardFollowing(true);
        assertEquals(BoardFollower.State.PLACING, follower.stateProperty().get());
        await(() -> ledAt("E4") != 0); // starting position on the sensors: e4/e5 must be filled
        assertEquals(LedColors.MISSING, ledAt("E4"));

        setUpPieces(session.fenProperty().get());
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);
        assertEquals("Muovi i pezzi per provare una variante", follower.messageProperty().get());

        // 2. d4 on the board: not the game's move, so it becomes a variation
        sim.lift("D2");
        sim.place("D4");
        await(() -> session.inVariationProperty().get());
        assertEquals("2. d4", session.titleProperty().get());
        assertEquals(BoardFollower.State.FOLLOWING, follower.stateProperty().get(), "no re-sync after a board move");
    }

    @Test
    void oneMoveForwardOnTheScreenIsShownAsAMoveToMake() throws InterruptedException {
        setUpPieces(AnalysisTree.START_FEN);
        session.setBoardFollowing(true);
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        session.next(); // 1. e4 on the screen
        assertEquals(BoardFollower.State.REPLICATING, follower.stateProperty().get());
        assertEquals("Esegui sulla scacchiera: 1. e4", follower.messageProperty().get());
        await(() -> ledAt("E2") == LedColors.REPLICATE && ledAt("E4") == LedColors.REPLICATE);

        sim.lift("E2");
        sim.place("E4");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);
        assertEquals(1, session.plyProperty().get());
    }

    @Test
    void goingBackIsAPositionToSetUp() throws InterruptedException {
        session.goToPly(1);
        setUpPieces(session.fenProperty().get());
        session.setBoardFollowing(true);
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);

        session.previous(); // back to the start: the e-pawn must go back
        assertEquals(BoardFollower.State.PLACING, follower.stateProperty().get());
        await(() -> ledAt("E2") == LedColors.MISSING && ledAt("E4") == LedColors.WRONG);
        sim.lift("E4");
        sim.place("E2");
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);
    }

    @Test
    void stopFreesTheBoard() throws InterruptedException {
        setUpPieces(AnalysisTree.START_FEN);
        session.setBoardFollowing(true);
        await(() -> follower.stateProperty().get() == BoardFollower.State.FOLLOWING);
        session.setBoardFollowing(false);
        assertEquals(BoardFollower.State.OFF, follower.stateProperty().get());
        await(() -> manager.mode() == BoardStateManager.Mode.IDLE);
        // moves on a free board do nothing
        sim.lift("E2");
        sim.place("E4");
        manager.awaitIdle();
        Thread.sleep(50);
        assertEquals(0, session.plyProperty().get());
        assertFalse(follower.isOn());
    }

    @Test
    void samePositionIgnoresCounters() {
        assertTrue(BoardFollower.samePosition("8/8/8/8/8/8/8/K6k w - - 0 1", "8/8/8/8/8/8/8/K6k w - - 5 40"));
        assertFalse(BoardFollower.samePosition("8/8/8/8/8/8/8/K6k w - - 0 1", "8/8/8/8/8/8/8/K6k b - - 0 1"));
        assertFalse(BoardFollower.samePosition(null, "x"));
    }
}
