package io.github.hardin22.javachess.Browser;

import java.net.URI;
import java.util.Locale;

/** The chess sites the integrated browser knows how to read and play on. */
public enum ChessSite {
    CHESS_COM("Chess.com", "https://www.chess.com"),
    LICHESS("Lichess", "https://lichess.org"),
    OTHER("", "");

    private final String displayName;
    private final String homeUrl;

    ChessSite(String displayName, String homeUrl) {
        this.displayName = displayName;
        this.homeUrl = homeUrl;
    }

    /** Name shown to the user ("Chess.com"); empty for unknown sites. */
    public String displayName() {
        return displayName;
    }

    public String homeUrl() {
        return homeUrl;
    }

    /** Site of a URL by host name ({@code www.chess.com}, {@code lichess.org}...); OTHER for anything else. */
    public static ChessSite of(String url) {
        String host = host(url);
        if (host == null) {
            return OTHER;
        }
        if (host.equals("chess.com") || host.endsWith(".chess.com")) {
            return CHESS_COM;
        }
        if (host.equals("lichess.org") || host.endsWith(".lichess.org")) {
            return LICHESS;
        }
        return OTHER;
    }

    /** Site from the probe's identifier ("chesscom", "lichess"). */
    static ChessSite fromProbe(String id) {
        if ("chesscom".equals(id)) {
            return CHESS_COM;
        }
        if ("lichess".equals(id)) {
            return LICHESS;
        }
        return OTHER;
    }

    static String host(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
