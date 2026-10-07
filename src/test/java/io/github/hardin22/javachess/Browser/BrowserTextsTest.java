package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Components.I18n;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status texts fit the 720 px wide board monitor with the bar's fonts (title 26 px, sentence 22 px) without
 * being cut: titles in two lines, sentences in three, with realistic values for the placeholders.
 */
class BrowserTextsTest {

    private static final int TEXT_WIDTH = 720 - 2 * 16 - 14 - 12; // bar padding, tone dot, gap

    private static FontMetrics metrics(Font font) {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            return g.getFontMetrics(font);
        } finally {
            g.dispose();
        }
    }

    @Test
    void everyStatusHasItsTextsAndTheyFit() {
        BrowserBar.Fonts fonts = BrowserBar.Fonts.load();
        FontMetrics title = metrics(fonts.title());
        FontMetrics body = metrics(fonts.body());
        Object[] args = {"Chess.com", "a7", "a8", "c3"};
        for (BrowserStatus.State state : BrowserStatus.State.values()) {
            BrowserStatus s = BrowserStatus.of(state, BrowserStatus.NO_PROGRESS, List.of(), args);
            assertFalse(s.title().startsWith("browser."), "missing title for " + state);
            assertFalse(s.detail().startsWith("browser."), "missing detail for " + state);
            int titleLines = BrowserBar.WrappingText.lines(s.title(), title, TEXT_WIDTH).size();
            int detailLines = BrowserBar.WrappingText.lines(s.detail(), body, TEXT_WIDTH).size();
            assertTrue(titleLines <= 2, state + " title in " + titleLines + " lines: " + s.title());
            assertTrue(detailLines <= 3, state + " detail in " + detailLines + " lines: " + s.detail());
        }
        for (String key : List.of("browser.status.loading.slow", "browser.status.board_hidden.out_of_view",
                "browser.status.board_hidden.covered", "browser.status.board_hidden.unreadable",
                "browser.status.your_turn.noboard", "browser.status.your_turn.analysis",
                "browser.status.opponent_turn.played", "browser.status.game_over.short", "browser.status.login.saved",
                "browser.failure.no_network", "browser.failure.unsupported", "browser.failure.no_space",
                "browser.failure.missing_options", "browser.failure.other", "browser.restart.manual")) {
            String text = I18n.t(key, "Dxe5+");
            assertFalse(text.startsWith("browser."), "missing " + key);
            assertTrue(BrowserBar.WrappingText.lines(text, body, TEXT_WIDTH).size() <= 3, key + ": " + text);
        }
    }

    @Test
    void wrappingNeverLosesText() {
        FontMetrics fm = metrics(BrowserBar.Fonts.load().body());
        String text = "Il sito chiede di confermare che sei una persona: tocca la casella nella pagina.";
        for (int width : new int[]{120, 240, 400, 700}) {
            List<String> lines = BrowserBar.WrappingText.lines(text, fm, width);
            assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""), "width " + width);
            for (String line : lines) {
                assertTrue(fm.stringWidth(line) <= width || !line.contains(" "), line);
            }
        }
        List<String> split = BrowserBar.WrappingText.lines("Supercalifragilistichespiralidoso", fm, 80);
        assertTrue(split.size() > 1, "a word longer than the line is split, not cut");
        assertEquals("Supercalifragilistichespiralidoso", String.join("", split));
    }

    @Test
    void geistIsBundled() {
        Font f = BrowserBar.Fonts.load().title();
        assertTrue(f.getFamily().toLowerCase().contains("geist"), f.getFamily());
        assertEquals(26f, f.getSize2D());
    }
}
