package io.github.hardin22.javachess.Browser;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of page the browser shows, from its URL and, when available, from the page itself (the probe's hint
 * wins: lichess marks its pages with a class on {@code <main>}).
 */
public record PageInfo(ChessSite site, Kind kind, String url) {

    public enum Kind {
        /** Nothing loaded yet (about:blank). */
        BLANK,
        HOME,
        LOGIN,
        /** A game being played (or about to start: chess.com's /play pages show the board while seeking). */
        GAME,
        /** Analysis board: both sides are moved by the user. */
        ANALYSIS,
        PUZZLE,
        /** Watching other players (lichess TV). */
        WATCH,
        /** Board editor: pieces are placed freely, not played. */
        EDITOR,
        OTHER;

        /** Pages where the physical board starts following the screen without being asked. */
        public boolean syncsAutomatically() {
            return this == GAME || this == ANALYSIS;
        }

        /** Pages where a board may be synchronised on request. */
        public boolean maySync() {
            return this == GAME || this == ANALYSIS || this == PUZZLE || this == WATCH || this == OTHER;
        }
    }

    private static final Pattern LICHESS_GAME = Pattern.compile("^/([A-Za-z0-9]{8}|[A-Za-z0-9]{12})(/(white|black))?/?$");
    private static final Pattern LICHESS_ANALYSIS_FEN = Pattern.compile("^/analysis/(?:standard/|fromPosition/)?(.+)$");

    public static PageInfo of(String url) {
        ChessSite site = ChessSite.of(url);
        return new PageInfo(site, kindOf(site, url), url == null ? "" : url);
    }

    /** Same page with the kind reported by the page itself, when it is known. */
    public PageInfo withHint(String pageHint) {
        Kind hinted = switch (pageHint == null ? "" : pageHint) {
            case "game" -> Kind.GAME;
            case "analysis" -> Kind.ANALYSIS;
            case "puzzle" -> Kind.PUZZLE;
            case "home" -> Kind.HOME;
            case "editor" -> Kind.EDITOR;
            case "login" -> Kind.LOGIN;
            case "watch" -> Kind.WATCH;
            default -> null;
        };
        if (hinted == null || hinted == kind || (kind == Kind.WATCH && hinted == Kind.GAME)) {
            return this; // lichess TV is drawn by the game page but nobody here plays it
        }
        return new PageInfo(site, hinted, url);
    }

    static Kind kindOf(ChessSite site, String url) {
        if (url == null || url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) {
            return Kind.BLANK;
        }
        String path = path(url);
        if (path == null) {
            return Kind.OTHER;
        }
        String p = path.toLowerCase(Locale.ROOT);
        return switch (site) {
            case LICHESS -> lichessKind(path, p);
            case CHESS_COM -> chessComKind(p);
            case OTHER -> Kind.OTHER;
        };
    }

    private static Kind lichessKind(String path, String p) {
        if (p.equals("/") || p.isEmpty()) {
            return Kind.HOME;
        }
        if (p.startsWith("/login") || p.startsWith("/signup") || p.startsWith("/auth") || p.startsWith("/password")) {
            return Kind.LOGIN;
        }
        if (p.startsWith("/analysis") || p.startsWith("/study")) {
            return Kind.ANALYSIS;
        }
        if (p.startsWith("/editor")) {
            return Kind.EDITOR;
        }
        if (p.startsWith("/training") || p.startsWith("/streak") || p.startsWith("/storm") || p.startsWith("/racer")) {
            return Kind.PUZZLE;
        }
        if (p.startsWith("/tv") || p.startsWith("/games")) {
            return Kind.WATCH;
        }
        Matcher m = LICHESS_GAME.matcher(path);
        // game ids are random: a word made only of lowercase letters ("training", "streamer") is a page
        if (m.matches() && !m.group(1).matches("[a-z]+")) {
            return Kind.GAME;
        }
        return Kind.OTHER;
    }

    private static Kind chessComKind(String p) {
        if (p.equals("/") || p.isEmpty() || p.equals("/home")) {
            return Kind.HOME;
        }
        if (p.startsWith("/login") || p.startsWith("/register") || p.startsWith("/login_and_go")
                || p.startsWith("/forgot")) {
            return Kind.LOGIN;
        }
        if (p.startsWith("/game/") || p.startsWith("/live") || p.startsWith("/play")) {
            return Kind.GAME;
        }
        if (p.startsWith("/analysis")) {
            return Kind.ANALYSIS;
        }
        if (p.startsWith("/puzzles") || p.startsWith("/puzzle") || p.startsWith("/daily-chess-puzzle")) {
            return Kind.PUZZLE;
        }
        if (p.startsWith("/tv") || p.startsWith("/watch")) {
            return Kind.WATCH;
        }
        return Kind.OTHER;
    }

    /**
     * Position written in the URL of an analysis board, when there is one: lichess
     * {@code /analysis/standard/<fen with _>}, chess.com {@code /analysis?fen=...}. Null otherwise.
     */
    public String fenInUrl() {
        if (kind != Kind.ANALYSIS && kind != Kind.EDITOR) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (site == ChessSite.LICHESS) {
                Matcher m = LICHESS_ANALYSIS_FEN.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
                if (m.matches()) {
                    String fen = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8).replace('_', ' ').trim();
                    return fen.contains("/") ? fen : null;
                }
            } else if (site == ChessSite.CHESS_COM && uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    if (pair.startsWith("fen=")) {
                        String fen = URLDecoder.decode(pair.substring(4), StandardCharsets.UTF_8).trim();
                        return fen.contains("/") ? fen : null;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    /** Same page ignoring the fragment and trailing slash (a new game is a new page, a #hash is not). */
    public boolean samePage(PageInfo other) {
        return other != null && normalise(url).equals(normalise(other.url));
    }

    private static String normalise(String url) {
        String u = url == null ? "" : url;
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String path(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
