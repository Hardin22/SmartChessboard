package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Utils.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuzzleRushTest {

    final List<Integer> asked = new ArrayList<>();
    final AtomicInteger ids = new AtomicInteger();
    final AtomicLong now = new AtomicLong();
    GameClock clock;

    @BeforeEach
    void resetRecords() {
        for (PuzzleRush.Mode m : PuzzleRush.Mode.values()) {
            ConfigManager.setProperty(m.recordKey(), null);
        }
    }

    PuzzleRush rush(PuzzleRush.Mode mode, int rating) {
        return new PuzzleRush(mode, rating, target -> {
            asked.add(target);
            return CompletableFuture.completedFuture(new Puzzle("p" + ids.incrementAndGet(), "8/8/8/8/8/8/8/K6k w - - 0 1",
                    List.of(), target, 0, 0, 0, List.of(), "", ""));
        }, () -> clock = new GameClock(new TimeControl(mode.seconds(), 0), now::get, Runnable::run, null),
                Runnable::run);
    }

    @Test
    void survivalEndsAfterThreeFailuresAndGetsHarder() {
        PuzzleRush r = rush(PuzzleRush.Mode.SURVIVAL, 1500);
        assertEquals("", r.timeTextProperty().get());
        r.start();
        assertEquals(PuzzleRush.State.PLAYING, r.stateProperty().get());
        assertEquals(900, asked.get(0), "starts 600 below the player's rating");
        r.solved();
        r.solved();
        assertEquals(1020, asked.get(2));
        assertEquals(2, r.scoreProperty().get());
        r.failed();
        r.failed();
        assertEquals(PuzzleRush.State.PLAYING, r.stateProperty().get());
        r.failed();
        assertEquals(PuzzleRush.State.FINISHED, r.stateProperty().get());
        assertNull(r.currentProperty().get());
        assertEquals("Tre errori: 2 puzzle risolti · nuovo record!", r.messageProperty().get());
        assertTrue(r.newRecordProperty().get());
        assertEquals(2, r.bestProperty().get());
        r.solved(); // ignored after the end
        assertEquals(2, r.scoreProperty().get());
        assertEquals(2, rush(PuzzleRush.Mode.SURVIVAL, 1500).bestProperty().get(), "record saved");
    }

    @Test
    void timedSeriesEndsWhenTheTimeIsUp() {
        PuzzleRush r = rush(PuzzleRush.Mode.THREE_MINUTES, 800);
        assertEquals("03:00", r.timeTextProperty().get());
        r.start();
        assertEquals(500, asked.get(0), "never below 500");
        now.addAndGet(100_000_000_000L);
        clock.tick();
        assertEquals("01:20", r.timeTextProperty().get());
        r.solved();
        now.addAndGet(81_000_000_000L);
        clock.tick();
        assertEquals(PuzzleRush.State.FINISHED, r.stateProperty().get());
        assertEquals("Tempo scaduto: 1 puzzle risolto · nuovo record!", r.messageProperty().get());
    }

    @Test
    void noPuzzlesAndStop() {
        PuzzleRush empty = new PuzzleRush(PuzzleRush.Mode.SURVIVAL, 1500,
                t -> CompletableFuture.completedFuture(null), () -> null, Runnable::run);
        empty.start();
        assertEquals(PuzzleRush.State.FINISHED, empty.stateProperty().get());
        assertEquals("Nessun puzzle disponibile: 0 puzzle risolti", empty.messageProperty().get());
        assertFalse(empty.newRecordProperty().get());

        PuzzleRush r = rush(PuzzleRush.Mode.FIVE_MINUTES, 2000);
        r.start();
        assertEquals(1200, asked.get(asked.size() - 1), "never above 1200 at the start");
        r.stop();
        assertEquals("Serie interrotta: 0 puzzle risolti", r.messageProperty().get());
    }
}
