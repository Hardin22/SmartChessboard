package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.LedMapping;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.MoveLeds;
import io.github.hardin22.javachess.Hardware.SimulatedBoard;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Play.GameClock;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.BoardStateManager;
import io.github.hardin22.javachess.Utils.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coordinates on the (simulated) board: real board manager, sensors and LEDs; a clock moved by hand. */
class CoordinateTrainerTest {

    final AtomicLong nanos = new AtomicLong();
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    SimulatedBoard sim;
    LedRenderer leds;
    BoardStateManager manager;
    GameClock clock;

    @BeforeEach
    void setUp() {
        if (System.getProperty("javachess.home") == null) {
            System.setProperty("javachess.home", System.getProperty("java.io.tmpdir") + "/javachess-test-home");
            ConfigManager.reload();
        }
        Map<String, String> reset = new HashMap<>();
        for (String k : new String[]{"find.white", "find.black", "name.white", "name.black"}) {
            reset.put("training.coordinates." + k + ".best", null);
        }
        ConfigManager.setProperties(reset);
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
        sim.start(manager);
        sim.setOccupancy(0); // training on an empty board
        manager.awaitIdle();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        leds.shutdown();
        scheduler.shutdownNow();
    }

    CoordinateTrainer trainer(CoordinateTrainer.Mode mode) {
        return new CoordinateTrainer(mode, true, new Random(11), () -> {
            clock = new GameClock(new TimeControl(CoordinateTrainer.SECONDS, 0), nanos::get, Runnable::run, scheduler);
            return clock;
        }, manager, leds);
    }

    void timeUp() {
        nanos.addAndGet(31_000_000_000L);
        clock.tick();
    }

    void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.awaitIdle();
            Thread.sleep(10);
        }
        assertTrue(cond.getAsBoolean());
    }

    @Test
    void findTheSquareWithThePiecesThenTheTimeRunsOut() throws InterruptedException {
        CoordinateTrainer t = trainer(CoordinateTrainer.Mode.FIND);
        t.start();
        assertEquals(CoordinateTrainer.State.PLAYING, t.stateProperty().get());
        assertEquals("00:30", t.timeTextProperty().get());

        String first = t.targetProperty().get();
        sim.place(first.toUpperCase()); // a piece placed on the square asked
        await(() -> t.scoreProperty().get() == 1);
        assertEquals("Giusto!", t.messageProperty().get());
        String second = t.targetProperty().get();
        assertFalse(second.equals(first), "never the same square twice in a row");
        sim.lift(first.toUpperCase()); // taking the piece back is not an answer
        manager.awaitIdle();
        assertEquals(0, t.mistakesProperty().get());

        String wrong = second.equals("a1") ? "h8" : "a1";
        sim.place(wrong.toUpperCase());
        // the flash lasts 0.6 s: read it as soon as it is there
        await(() -> leds.composeNow()[Squares.parse(wrong)] == LedColors.ERROR
                && leds.composeNow()[Squares.parse(second)] == LedColors.GOOD);
        await(() -> t.mistakesProperty().get() == 1);
        assertEquals("No: quella è " + wrong + ", cercavi " + second, t.messageProperty().get());
        assertEquals(wrong, t.wrongSquareProperty().get());

        t.answer(t.targetProperty().get()); // a tap on the screen
        assertEquals(2, t.scoreProperty().get());
        assertNull(t.wrongSquareProperty().get());

        timeUp();
        assertEquals(CoordinateTrainer.State.FINISHED, t.stateProperty().get());
        assertEquals("Tempo scaduto: 2 giuste, 1 errore", t.messageProperty().get());
        assertEquals(2, t.bestProperty().get());
        sim.place("H1"); // the board is free again
        manager.awaitIdle();
        assertEquals(2, t.scoreProperty().get());
    }

    @Test
    void nameTheLitSquareAmongFourAndBeatTheRecord() {
        CoordinateTrainer first = trainer(CoordinateTrainer.Mode.NAME);
        first.start();
        String asked = first.targetProperty().get();
        assertEquals(LedColors.BEST, leds.composeNow()[Squares.parse(asked)]);
        assertEquals(4, first.choicesProperty().get().size());
        assertTrue(first.choicesProperty().get().contains(asked));
        assertEquals(4, first.choicesProperty().get().stream().distinct().count());
        first.answer(asked);
        timeUp();
        assertEquals(1, first.bestProperty().get());
        assertFalse(first.newRecordProperty().get(), "the first score is not a record to celebrate");
        assertEquals(0, leds.composeNow()[Squares.parse(first.targetProperty().get())], "LEDs off at the end");

        CoordinateTrainer second = trainer(CoordinateTrainer.Mode.NAME);
        assertEquals(1, second.bestProperty().get());
        second.start();
        second.answer(second.targetProperty().get());
        String wrong = second.choicesProperty().get().stream()
                .filter(c -> !c.equals(second.targetProperty().get())).findFirst().orElseThrow();
        String target = second.targetProperty().get();
        second.answer(wrong);
        assertEquals("No: era " + target, second.messageProperty().get());
        second.answer(second.targetProperty().get());
        timeUp();
        assertTrue(second.newRecordProperty().get());
        assertEquals("Nuovo record! 2 giuste, 1 errore", second.messageProperty().get());
    }
}
