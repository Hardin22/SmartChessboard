package io.github.hardin22.javachess.Hardware;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardDiagnosticsTest {

    SimulatedBoard sim;
    LedRenderer leds;
    BoardStateManager manager;
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    BoardDiagnostics diagnostics;

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
        sim.start(manager);
        manager.awaitIdle();
        diagnostics = new BoardDiagnostics(manager, leds, timer, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        diagnostics.stop();
        manager.shutdown();
        leds.shutdown();
        timer.shutdownNow();
    }

    void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.awaitIdle();
            Thread.sleep(5);
        }
        assertTrue(cond.getAsBoolean(), diagnostics.messageProperty().get());
    }

    int ledAt(String square) {
        return leds.composeNow()[Squares.parse(square)];
    }

    @Test
    void everySensorHasToSeeAPiecePlacedAndLifted() throws InterruptedException {
        // starting position on the board: 32 squares read as occupied
        diagnostics.startSensorTest();
        assertEquals(BoardDiagnostics.Phase.SENSORS, diagnostics.phaseProperty().get());
        assertEquals(BoardDiagnostics.DETECTED, ledAt("E2"), "occupied, not checked yet");
        assertEquals(0, ledAt("E4"));

        sim.lift("E2");
        sim.place("E2");
        await(() -> diagnostics.checkedCountProperty().get() == 1);
        assertEquals(BoardDiagnostics.CHECKED, ledAt("E2"));
        assertTrue(diagnostics.messageProperty().get().equals("1 case su 64 · mancano: a1, b1, c1, d1, e1, f1…"),
                diagnostics.messageProperty().get());

        for (int sq = 0; sq < 64; sq++) {
            if ((sim.occupancy() & Squares.bit(sq)) != 0) {
                sim.lift(sq);
                sim.place(sq);
            } else {
                sim.place(sq);
                sim.lift(sq);
            }
        }
        await(() -> diagnostics.phaseProperty().get() == BoardDiagnostics.Phase.DONE);
        assertEquals(64, diagnostics.checkedCountProperty().get());
        assertEquals("Tutti i 64 sensori funzionano", diagnostics.messageProperty().get());
        assertEquals("", diagnostics.uncheckedText(5));
    }

    @Test
    void theLedTestShowsTheColoursThenEverySquareInOrder() throws InterruptedException {
        List<String> steps = new ArrayList<>();
        diagnostics.ledStepProperty().addListener((o, a, b) -> {
            if (!b.isEmpty()) {
                steps.add(b);
            }
        });
        diagnostics.setTimings(5, 1);
        diagnostics.startLedTest();
        assertEquals(0xFF0000, ledAt("A1"));
        assertEquals(0xFF0000, ledAt("H8"));
        await(() -> diagnostics.phaseProperty().get() == BoardDiagnostics.Phase.IDLE);
        assertEquals(68, steps.size());
        assertEquals(List.of("Rosso", "Verde", "Blu", "Bianco", "a1", "b1"), steps.subList(0, 6));
        assertEquals("h8", steps.get(67));
        assertEquals(0, ledAt("H8"), "LEDs off at the end");
    }

    @Test
    void stopGivesTheBoardBack() throws InterruptedException {
        diagnostics.startSensorTest();
        diagnostics.stop();
        sim.lift("E2");
        manager.awaitIdle();
        assertEquals(0, diagnostics.checkedCountProperty().get());
        assertEquals(BoardDiagnostics.Phase.IDLE, diagnostics.phaseProperty().get());
        assertEquals(0, ledAt("E2"));
    }
}
