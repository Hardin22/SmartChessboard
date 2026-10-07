package org.example.javachess.Oggetti;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChessClockTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong now = new AtomicLong(1_000 * MS);
    private final ChessClock clock = new ChessClock(300, 2, now::get);

    private void advance(long millis) {
        now.addAndGet(millis * MS);
    }

    @Test
    void onlyTheRunningSideLosesTime() {
        assertEquals("05:00", clock.display(ChessClock.Side.WHITE));
        clock.start(ChessClock.Side.WHITE);
        advance(1500);
        assertEquals(298_500, clock.remainingMillis(ChessClock.Side.WHITE));
        assertEquals("04:58", clock.display(ChessClock.Side.WHITE));
        assertEquals(300_000, clock.remainingMillis(ChessClock.Side.BLACK));
        clock.start(ChessClock.Side.BLACK);
        advance(10_000);
        assertEquals(298_500, clock.remainingMillis(ChessClock.Side.WHITE));
        assertEquals(290_000, clock.remainingMillis(ChessClock.Side.BLACK));
    }

    @Test
    void noDriftOverManyMoves() {
        // 200 moves of 1.337 s each: the total must be exact (old Timer-based clock lost time every tick)
        for (int i = 0; i < 100; i++) {
            clock.start(ChessClock.Side.WHITE);
            advance(1337);
            clock.start(ChessClock.Side.BLACK);
            advance(1337);
        }
        clock.stop(null);
        assertEquals(300_000 - 100 * 1337, clock.remainingMillis(ChessClock.Side.WHITE));
        assertEquals(300_000 - 100 * 1337, clock.remainingMillis(ChessClock.Side.BLACK));
    }

    @Test
    void incrementAndStop() {
        clock.start(ChessClock.Side.WHITE);
        advance(5_000);
        clock.addIncrement(ChessClock.Side.WHITE);
        clock.stop(ChessClock.Side.WHITE);
        assertNull(clock.running());
        advance(60_000);
        assertEquals(297_000, clock.remainingMillis(ChessClock.Side.WHITE));
        clock.stop(ChessClock.Side.BLACK); // not running: no effect
        assertEquals(300_000, clock.remainingMillis(ChessClock.Side.BLACK));
    }

    @Test
    void flagAndTenths() {
        ChessClock blitz = new ChessClock(12, 0, now::get);
        blitz.start(ChessClock.Side.BLACK);
        advance(2_500);
        assertEquals("00:09.5", blitz.display(ChessClock.Side.BLACK));
        assertFalse(blitz.isFlagged(ChessClock.Side.BLACK));
        advance(9_500);
        assertTrue(blitz.isFlagged(ChessClock.Side.BLACK));
        assertEquals("00:00.0", blitz.display(ChessClock.Side.BLACK));
        advance(5_000);
        assertEquals(0, blitz.remainingMillis(ChessClock.Side.BLACK));
    }

    @Test
    void repaintOnlyWhenTheTextCanChange() {
        clock.start(ChessClock.Side.WHITE);
        advance(250);
        // 299.75 s left: next change in 750 ms (+1 ms margin)
        assertEquals(751 * MS, clock.nanosUntilDisplayChange());
        ChessClock blitz = new ChessClock(10, 0, now::get);
        blitz.start(ChessClock.Side.WHITE);
        advance(30);
        // 9.97 s left: tenths, next change in 70 ms
        assertEquals(71 * MS, blitz.nanosUntilDisplayChange());
        blitz.stop(null);
        assertEquals(-1, blitz.nanosUntilDisplayChange());
    }

    @Test
    void format() {
        assertEquals("10:00", ChessClock.format(600_000 * MS));
        assertEquals("00:10", ChessClock.format(10_000 * MS));
        assertEquals("00:09.9", ChessClock.format(9_999 * MS));
        assertEquals("00:00.0", ChessClock.format(0));
        assertEquals("90:00", ChessClock.format(5_400_000 * MS));
    }
}
