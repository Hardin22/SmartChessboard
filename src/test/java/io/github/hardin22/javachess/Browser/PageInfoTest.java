package io.github.hardin22.javachess.Browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageInfoTest {

    private static final String[] TABLE = {
            "https://www.chess.com/login, CHESS_COM, LOGIN",
            "https://www.chess.com/login_and_go?returnUrl=x, CHESS_COM, LOGIN",
            "https://www.chess.com/, CHESS_COM, HOME",
            "https://www.chess.com/home, CHESS_COM, HOME",
            "https://www.chess.com/play/online, CHESS_COM, GAME",
            "https://www.chess.com/play/computer, CHESS_COM, GAME",
            "https://www.chess.com/game/live/123456789, CHESS_COM, GAME",
            "https://www.chess.com/game/daily/987, CHESS_COM, GAME",
            "https://www.chess.com/analysis?fen=x, CHESS_COM, ANALYSIS",
            "https://www.chess.com/puzzles/rated, CHESS_COM, PUZZLE",
            "https://www.chess.com/news, CHESS_COM, OTHER",
            "https://lichess.org/, LICHESS, HOME",
            "https://lichess.org/login, LICHESS, LOGIN",
            "https://lichess.org/signup, LICHESS, LOGIN",
            "https://lichess.org/abcd1234, LICHESS, GAME",
            "https://lichess.org/abcd1234/black, LICHESS, GAME",
            "https://lichess.org/AbCdEfGh1234, LICHESS, GAME",
            "https://lichess.org/training, LICHESS, PUZZLE",
            "https://lichess.org/training/daily, LICHESS, PUZZLE",
            "https://lichess.org/streamer, LICHESS, OTHER",
            "https://lichess.org/insights, LICHESS, OTHER",
            "https://lichess.org/analysis, LICHESS, ANALYSIS",
            "https://lichess.org/study/abc, LICHESS, ANALYSIS",
            "https://lichess.org/editor, LICHESS, EDITOR",
            "https://lichess.org/tv, LICHESS, WATCH",
            "https://example.com/board, OTHER, OTHER",
            "about:blank, OTHER, BLANK",
    };

    @Test
    void classifiesUrls() {
        for (String row : TABLE) {
            String[] cells = row.split(",\\s*");
            PageInfo info = PageInfo.of(cells[0]);
            assertEquals(ChessSite.valueOf(cells[1]), info.site(), row);
            assertEquals(PageInfo.Kind.valueOf(cells[2]), info.kind(), row);
        }
    }

    @Test
    void sitesByHostOnly() {
        assertEquals(ChessSite.CHESS_COM, ChessSite.of("https://chess.com/play"));
        assertEquals(ChessSite.LICHESS, ChessSite.of("https://lichess.org"));
        assertEquals(ChessSite.OTHER, ChessSite.of("https://evilchess.com/play"));
        assertEquals(ChessSite.OTHER, ChessSite.of("https://lichess.org.evil.net/"));
        assertEquals(ChessSite.OTHER, ChessSite.of("not a url"));
        assertEquals(ChessSite.OTHER, ChessSite.of(null));
    }

    @Test
    void thePageHintWinsExceptForTv() {
        PageInfo lichessGameWithWordId = PageInfo.of("https://lichess.org/abcdefgh"); // all lowercase: unknown
        assertEquals(PageInfo.Kind.OTHER, lichessGameWithWordId.kind());
        assertEquals(PageInfo.Kind.GAME, lichessGameWithWordId.withHint("game").kind());
        assertEquals(PageInfo.Kind.WATCH, PageInfo.of("https://lichess.org/tv").withHint("game").kind());
        assertEquals(PageInfo.Kind.HOME, PageInfo.of("https://lichess.org/").withHint("").kind());
    }

    @Test
    void readsTheFenOfAnAnalysisUrl() {
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", PageInfo.of(
                "https://lichess.org/analysis/standard/rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R_b_KQkq_-_1_2")
                .fenInUrl());
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", PageInfo.of(
                "https://www.chess.com/analysis?fen=rnbqkbnr%2Fpppp1ppp%2F8%2F4p3%2F4P3%2F5N2%2FPPPP1PPP%2FRNBQKB1R"
                        + "%20b%20KQkq%20-%201%202&flip=true").fenInUrl());
        assertNull(PageInfo.of("https://lichess.org/analysis").fenInUrl());
        assertNull(PageInfo.of("https://lichess.org/abcd1234").fenInUrl());
    }

    @Test
    void samePageIgnoresTheFragment() {
        assertTrue(PageInfo.of("https://lichess.org/abcd1234#12").samePage(PageInfo.of("https://lichess.org/abcd1234")));
        assertTrue(PageInfo.of("https://lichess.org/").samePage(PageInfo.of("https://lichess.org")));
        assertFalse(PageInfo.of("https://lichess.org/abcd1234").samePage(PageInfo.of("https://lichess.org/abcd9999")));
    }

    @Test
    void automaticSynchronisationOnlyOnGamesAndAnalysis() {
        for (PageInfo.Kind kind : PageInfo.Kind.values()) {
            assertEquals(kind == PageInfo.Kind.GAME || kind == PageInfo.Kind.ANALYSIS, kind.syncsAutomatically(),
                    kind.name());
        }
        assertFalse(PageInfo.Kind.EDITOR.maySync());
        assertFalse(PageInfo.Kind.LOGIN.maySync());
        assertTrue(PageInfo.Kind.PUZZLE.maySync());
    }
}
