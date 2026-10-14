package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Components.I18n;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static io.github.hardin22.javachess.Browser.BrowserStatus.Action.RESYNC;
import static io.github.hardin22.javachess.Browser.BrowserStatus.Action.SHOW_BOARD;
import static io.github.hardin22.javachess.Browser.BrowserStatus.Action.SYNC_STOP;
import static io.github.hardin22.javachess.Browser.BrowserStatus.NO_PROGRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bar keeps its height through a whole game on the 720 px board monitor: a change of height resizes the page
 * below it, which Chromium lays out again (a flash at every move).
 */
class BrowserBarHeightTest {

    private static void layOut(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component child : k.getComponents()) {
                layOut(child);
            }
        }
    }

    private static int heightShowing(BrowserBar bar, BrowserStatus s) {
        bar.show(s);
        for (int i = 0; i < 3; i++) { // the sentence's height depends on its width
            bar.setSize(720, bar.getPreferredSize().height);
            layOut(bar);
        }
        return bar.getPreferredSize().height;
    }

    @Test
    void theHeightDoesNotChangeFromTheStartToTheEndOfAGame() {
        List<BrowserStatus> game = List.of(
                BrowserStatus.of(BrowserStatus.State.STARTING, -1, List.of()),
                BrowserStatus.of(BrowserStatus.State.LOADING, -1, List.of(), "Chess.com"),
                BrowserStatus.of(BrowserStatus.State.NO_GAME, NO_PROGRESS, List.of()),
                BrowserStatus.of(BrowserStatus.State.BOARD_FOUND, NO_PROGRESS, List.of(BrowserStatus.Action.SYNC_START)),
                BrowserStatus.of(BrowserStatus.State.READING, -1, List.of(SYNC_STOP)),
                BrowserStatus.of(BrowserStatus.State.SETUP, NO_PROGRESS, List.of(SYNC_STOP)),
                BrowserStatus.of(BrowserStatus.State.YOUR_TURN, NO_PROGRESS, List.of(SYNC_STOP)),
                BrowserStatus.of(BrowserStatus.State.SENDING, -1, List.of(SYNC_STOP), "Cxe5+"),
                BrowserStatus.of(BrowserStatus.State.OPPONENT_TURN, NO_PROGRESS, List.of(SYNC_STOP))
                        .withDetail(I18n.t("browser.status.opponent_turn.played", "Cxe5+")),
                BrowserStatus.of(BrowserStatus.State.REPLICATE, NO_PROGRESS, List.of(SYNC_STOP), "Dxf7+", "d1", "f7"),
                BrowserStatus.of(BrowserStatus.State.YOUR_TURN, NO_PROGRESS, List.of(SYNC_STOP))
                        .withDetail(I18n.t("browser.status.your_turn.noboard")),
                BrowserStatus.of(BrowserStatus.State.BOARD_HIDDEN, NO_PROGRESS, List.of(SHOW_BOARD, SYNC_STOP),
                        I18n.t("browser.status.board_hidden.out_of_view")),
                BrowserStatus.of(BrowserStatus.State.NOT_ACCEPTED, NO_PROGRESS, List.of(RESYNC, SYNC_STOP), "e2"),
                BrowserStatus.of(BrowserStatus.State.UNCERTAIN, NO_PROGRESS, List.of(RESYNC, SYNC_STOP), "e4, d5, f3"),
                BrowserStatus.of(BrowserStatus.State.READING, -1, List.of(RESYNC, BrowserStatus.Action.RELOAD))
                        .withDetail(I18n.t("browser.status.reading.stuck",
                                I18n.t("browser.status.reading.why.not_stable"))),
                BrowserStatus.of(BrowserStatus.State.GAME_OVER, NO_PROGRESS, List.of(), "1-0"));
        BrowserBar bar = new BrowserBar(nav -> { }, action -> { });
        int first = heightShowing(bar, game.get(0));
        for (BrowserStatus s : game) {
            assertEquals(first, heightShowing(bar, s), s.state() + ": " + s.title() + " | " + s.detail());
        }
    }

    @Test
    void aLongerTextMakesItTallerButItDoesNotShrinkBack() {
        BrowserBar bar = new BrowserBar(nav -> { }, action -> { });
        int normal = heightShowing(bar, BrowserStatus.of(BrowserStatus.State.YOUR_TURN, NO_PROGRESS, List.of()));
        String longText = "Una frase molto lunga ".repeat(12);
        int tall = heightShowing(bar, BrowserStatus.of(BrowserStatus.State.UNCERTAIN, NO_PROGRESS, List.of())
                .withDetail(longText));
        assertEquals(true, tall > normal);
        assertEquals(tall, heightShowing(bar, BrowserStatus.of(BrowserStatus.State.YOUR_TURN, NO_PROGRESS,
                List.of())));
        bar.resetHeight();
        assertEquals(normal, heightShowing(bar, BrowserStatus.of(BrowserStatus.State.YOUR_TURN, NO_PROGRESS,
                List.of())));
    }
}
