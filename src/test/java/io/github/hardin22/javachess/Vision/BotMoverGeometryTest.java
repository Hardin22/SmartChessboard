package io.github.hardin22.javachess.Vision;

import io.github.hardin22.javachess.Browser.BoardSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Square centres on the screen for both orientations, and the promotion menu. */
class BotMoverGeometryTest {

    private static final BoardSnapshot.Rect RECT = new BoardSnapshot.Rect(100, 200, 800, 800);

    @Test
    void whiteAtTheBottom() {
        assertArrayEquals(new double[]{150, 950}, BotMover.squareCenter(RECT, false, "a1"), 1e-9);
        assertArrayEquals(new double[]{850, 250}, BotMover.squareCenter(RECT, false, "h8"), 1e-9);
        assertArrayEquals(new double[]{550, 650}, BotMover.squareCenter(RECT, false, "e4"), 1e-9);
    }

    @Test
    void blackAtTheBottom() {
        assertArrayEquals(new double[]{850, 250}, BotMover.squareCenter(RECT, true, "a1"), 1e-9);
        assertArrayEquals(new double[]{150, 950}, BotMover.squareCenter(RECT, true, "h8"), 1e-9);
        assertArrayEquals(new double[]{450, 550}, BotMover.squareCenter(RECT, true, "e4"), 1e-9);
    }

    private static BoardSnapshot.BoardView view(boolean flipped) {
        return new BoardSnapshot.BoardView(RECT, flipped, false, 3, null, List.of(), null, null, null, null);
    }

    @Test
    void promotionMenuGoesTowardsTheCentre() {
        // white promotes at the top of the screen: the menu goes down
        List<double[]> q = BotMover.clickPoints(view(false), "e7e8q");
        assertEquals(3, q.size());
        assertArrayEquals(q.get(1), q.get(2), 1e-9, "queen on the promotion square");
        List<double[]> n = BotMover.clickPoints(view(false), "e7e8n");
        assertEquals(q.get(1)[1] + 100, n.get(2)[1], 1e-9);
        List<double[]> b = BotMover.clickPoints(view(false), "e7e8b");
        assertEquals(q.get(1)[1] + 300, b.get(2)[1], 1e-9);
        // black promoting with black at the bottom: also at the top of the screen
        List<double[]> r = BotMover.clickPoints(view(true), "e2e1r");
        assertEquals(r.get(1)[1] + 200, r.get(2)[1], 1e-9);
        // black promoting with white at the bottom: bottom of the screen, the menu goes up
        List<double[]> up = BotMover.clickPoints(view(false), "e2e1n");
        assertEquals(up.get(1)[1] - 100, up.get(2)[1], 1e-9);
    }

    @Test
    void ordinaryMovesAreTwoClicks() {
        assertEquals(2, BotMover.clickPoints(view(false), "g1f3").size());
    }
}
