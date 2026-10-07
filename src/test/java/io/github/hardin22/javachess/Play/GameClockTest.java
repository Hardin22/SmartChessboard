package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameClockTest {

    final AtomicLong now = new AtomicLong();
    final GameClock clock = new GameClock(TimeControl.minutes(3, 2), now::get, Runnable::run, null);

    void advance(long ms) {
        now.addAndGet(ms * 1_000_000L);
    }

    @Test
    void runsDownAndAddsIncrements() {
        assertEquals("03:00", clock.whiteTextProperty().get());
        clock.start(Side.WHITE);
        assertEquals(Side.WHITE, clock.runningProperty().get());
        advance(10_000);
        clock.moveMade(Side.WHITE);
        assertEquals("02:52", clock.whiteTextProperty().get(), "180 - 10 + 2");
        assertNull(clock.runningProperty().get());
        clock.start(Side.BLACK);
        advance(5_500);
        clock.tick();
        assertEquals("02:54", clock.blackTextProperty().get());
        clock.pause();
        advance(60_000);
        assertEquals(174_500, clock.remainingMillis(Side.BLACK));
    }

    @Test
    void flagsOnceAndStops() {
        AtomicReference<Side> flagged = new AtomicReference<>();
        clock.setOnFlag(flagged::set);
        clock.start(Side.BLACK);
        advance(170_000);
        clock.tick();
        assertTrue(clock.blackLowProperty().get());
        assertFalse(clock.whiteLowProperty().get());
        assertEquals("00:10", clock.blackTextProperty().get());
        advance(11_000);
        clock.tick();
        assertEquals(Side.BLACK, flagged.get());
        assertEquals(Side.BLACK, clock.flaggedProperty().get());
        assertNull(clock.runningProperty().get());
        clock.start(Side.WHITE); // ignored after the flag
        assertNull(clock.runningProperty().get());
    }

    @Test
    void restoreSavedTimes() {
        clock.restore(65_000, 9_000);
        assertEquals("01:05", clock.whiteTextProperty().get());
        assertEquals("00:09.0", clock.blackTextProperty().get());
        assertTrue(clock.blackLowProperty().get());
        assertNull(clock.runningProperty().get());
    }

    @Test
    void flagResultFollowsFide69() {
        Board kingsAndKnight = new Board();
        kingsAndKnight.loadFromFen("8/8/8/4k3/8/8/3NK3/8 w - - 0 1");
        assertEquals("Patta: tempo scaduto e materiale insufficiente",
                GameClock.flagResult(kingsAndKnight, Side.BLACK), "a lone knight cannot mate a bare king");
        assertEquals("Patta: tempo scaduto e materiale insufficiente",
                GameClock.flagResult(kingsAndKnight, Side.WHITE), "a bare king cannot mate");
        Board withPawn = new Board();
        withPawn.loadFromFen("8/8/8/4k3/4p3/8/3NK3/8 w - - 0 1");
        assertEquals("Il Bianco vince per tempo", GameClock.flagResult(withPawn, Side.BLACK),
                "knight against pawn can mate (self-block)");
        assertEquals("Il Nero vince per tempo", GameClock.flagResult(withPawn, Side.WHITE));
    }

    @Test
    void noClockForUnlimitedGames() {
        assertThrows(IllegalArgumentException.class, () -> new GameClock(TimeControl.UNLIMITED));
    }
}
