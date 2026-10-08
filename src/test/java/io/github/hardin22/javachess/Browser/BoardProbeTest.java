package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Side;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe's answers recorded on the real sites (anonymous browser, October 2026, 720x967 window): chess.com and
 * lichess analysis boards, game pages, TV, login and home pages.
 */
class BoardProbeTest {

    static String fixture(String name) {
        try (InputStream in = BoardProbeTest.class.getResourceAsStream("/browser/probe/" + name + ".json")) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void lichessAnalysisFromBlacksSide() {
        BoardSnapshot s = BoardProbe.parse(fixture("lichess-analysis-black"));
        assertEquals(ChessSite.LICHESS, s.site());
        assertEquals(PageInfo.Kind.ANALYSIS, s.page().kind());
        assertFalse(s.challenge());
        assertFalse(s.loginForm());
        assertEquals(Boolean.FALSE, s.loggedIn());
        assertEquals(720, s.viewportWidth());
        BoardSnapshot.BoardView b = s.board();
        assertNotNull(b);
        assertTrue(b.flipped());
        assertEquals(Side.BLACK, b.bottomSide());
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R", b.placement());
        assertEquals(32, b.pieces());
        assertEquals(712, b.rect().w(), 0.5);
        assertTrue(b.rect().inside(s.viewportWidth(), s.viewportHeight()));
        assertNull(b.turn());
        assertNull(b.result());
    }

    @Test
    void lichessTvHasClockAndLastMove() {
        BoardSnapshot s = BoardProbe.parse(fixture("lichess-tv"));
        assertEquals(PageInfo.Kind.WATCH, s.page().kind()); // drawn by the game page, but TV
        BoardSnapshot.BoardView b = s.board();
        assertEquals("6kr/Q4r1p/4p3/1P1pP3/P4q1p/8/5PP1/R5K1", b.placement());
        assertEquals(Side.WHITE, b.turn());
        assertEquals(List.of("f7", "f8"), b.lastMove());
        assertTrue(b.flipped());
    }

    @Test
    void lichessHomeShowsOnlyATvThumbnail() {
        BoardSnapshot s = BoardProbe.parse(fixture("lichess-home"));
        assertEquals(PageInfo.Kind.HOME, s.page().kind());
        assertNotNull(s.board());
        assertTrue(s.board().rect().w() < 0.5 * s.viewportWidth());
    }

    @Test
    void chessComAnalysisFlipped() {
        BoardSnapshot s = BoardProbe.parse(fixture("chesscom-analysis-flip"));
        assertEquals(ChessSite.CHESS_COM, s.site());
        assertEquals(PageInfo.Kind.ANALYSIS, s.page().kind());
        assertTrue(s.board().flipped());
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R", s.board().placement());
    }

    @Test
    void chessComPlayPageScrolledBoard() {
        BoardSnapshot s = BoardProbe.parse(fixture("chesscom-online"));
        assertEquals(PageInfo.Kind.GAME, s.page().kind());
        BoardSnapshot.BoardView b = s.board();
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", b.placement());
        assertFalse(b.flipped());
        assertFalse(b.rect().inside(s.viewportWidth(), s.viewportHeight()), "the page scrolled the board up");
    }

    @Test
    void loginPages() {
        for (String name : List.of("chesscom-login", "lichess-login")) {
            BoardSnapshot s = BoardProbe.parse(fixture(name));
            assertTrue(s.loginForm(), name);
            assertEquals(PageInfo.Kind.LOGIN, s.page().kind(), name);
            assertNull(s.board(), name);
        }
    }

    @Test
    void chessComHomeWithoutBoard() {
        BoardSnapshot s = BoardProbe.parse(fixture("chesscom-home"));
        assertEquals(PageInfo.Kind.HOME, s.page().kind());
        assertFalse(s.hasBoard());
    }

    @Test
    void gridToPlacement() {
        JSONArray grid = new JSONArray();
        for (int rank = 0; rank < 8; rank++) {
            JSONArray row = new JSONArray();
            for (int file = 0; file < 8; file++) {
                row.put(rank == 0 && file == 4 ? "K" : rank == 7 && file == 4 ? "k" : rank == 1 && file == 0 ? "P" : "");
            }
            grid.put(row);
        }
        assertEquals("4k3/8/8/8/8/8/P7/4K3", BoardProbe.placement(grid));
        grid.getJSONArray(3).put(2, "X");
        assertNull(BoardProbe.placement(grid), "unknown piece letter");
        assertNull(BoardProbe.placement(new JSONArray("[[],[]]")), "wrong size");
    }

    @Test
    void unknownSiteBoardHasNoPlacement() {
        BoardSnapshot s = BoardProbe.parse("{\"url\":\"https://example.com\",\"site\":\"other\",\"viewport\":{\"w\":800,"
                + "\"h\":600},\"board\":{\"x\":10,\"y\":20,\"w\":400,\"h\":400,\"flipped\":false,\"placement\":null}}");
        assertEquals(ChessSite.OTHER, s.site());
        assertNull(s.board().placement());
        assertEquals(400, s.board().rect().h());
    }

    @Test
    void cloudflareChallengeAndMalformedAnswers() {
        BoardSnapshot s = BoardProbe.parse("{\"url\":\"https://www.chess.com/\",\"site\":\"chesscom\",\"title\":"
                + "\"Just a moment...\",\"challenge\":true,\"board\":null}");
        assertTrue(s.challenge());
        assertNull(s.board());
        assertThrows(IllegalArgumentException.class, () -> BoardProbe.parse("not json"));
        assertNull(BoardProbe.parse("{\"board\":{\"w\":0,\"h\":0}}").board(), "an empty rectangle is no board");
    }

    @Test
    void theScriptIsBundled() {
        assertTrue(BoardProbe.SCRIPT.contains("cg-board"));
        assertTrue(BoardProbe.SCRIPT.contains("wc-chess-board"));
        assertFalse(BoardProbe.SCRIPT.contains("appendChild"), "the probe must never change the page");
        assertFalse(BoardProbe.SCRIPT.contains("innerHTML ="), "the probe must never change the page");
    }
}
