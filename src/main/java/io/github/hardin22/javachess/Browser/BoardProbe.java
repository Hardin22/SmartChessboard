package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Side;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reads the page with a small read-only script ({@code /browser/board-probe.js}) and turns its JSON answer into a
 * {@link BoardSnapshot}. The script knows the markup of chess.com ({@code wc-chess-board}, pieces
 * {@code .piece.wp.square-52}) and lichess ({@code cg-board}, pieces {@code piece.white.pawn} placed with CSS
 * transforms); for other sites it only locates a board, which is then read from a screenshot.
 */
public final class BoardProbe {

    /** The probe script: an expression whose value is the JSON object parsed by {@link #parse}. */
    public static final String SCRIPT = load("/browser/board-probe.js");

    private BoardProbe() {
    }

    /** Runs the probe on the page. */
    public static CompletableFuture<BoardSnapshot> read(PageDriver page) {
        return page.evaluate(SCRIPT).thenApply(BoardProbe::parse);
    }

    /** Parses the probe's answer (the JSON value of the script). */
    public static BoardSnapshot parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject vp = o.optJSONObject("viewport");
            BoardSnapshot.BoardView board = o.isNull("board") ? null : parseBoard(o.optJSONObject("board"));
            return new BoardSnapshot(o.optString("url", ""), ChessSite.fromProbe(o.optString("site")),
                    o.optString("page", ""), o.optString("title", ""), o.optBoolean("challenge"),
                    o.optBoolean("login"), o.isNull("loggedIn") || !o.has("loggedIn") ? null : o.optBoolean("loggedIn"),
                    vp == null ? 0 : vp.optDouble("w", 0), vp == null ? 0 : vp.optDouble("h", 0),
                    o.optDouble("dpr", 1), board, o.optBoolean("ready", true));
        } catch (JSONException e) {
            throw new IllegalArgumentException("Unexpected probe answer: " + abbreviate(json), e);
        }
    }

    private static BoardSnapshot.BoardView parseBoard(JSONObject b) {
        if (b == null) {
            return null;
        }
        BoardSnapshot.Rect rect = new BoardSnapshot.Rect(b.optDouble("x", 0), b.optDouble("y", 0),
                b.optDouble("w", 0), b.optDouble("h", 0));
        if (!(rect.w() > 0) || !(rect.h() > 0)) {
            return null;
        }
        String placement = b.isNull("placement") ? null : placement(b.optJSONArray("placement"));
        String turn = b.optString("turn", "");
        return new BoardSnapshot.BoardView(rect, b.optBoolean("flipped"), b.optBoolean("animating"),
                b.optInt("pieces", 0), placement, strings(b.optJSONArray("lastMove")),
                "w".equals(turn) ? Side.WHITE : "b".equals(turn) ? Side.BLACK : null,
                b.isNull("moves") ? null : strings(b.optJSONArray("moves")),
                b.isNull("result") ? null : emptyToNull(b.optString("result")),
                b.isNull("status") ? null : emptyToNull(b.optString("status")));
    }

    /**
     * FEN placement from the probe's 8x8 grid ({@code grid[rank][file]}, rank 0 = rank 1, "" = empty square).
     * Null when the grid is malformed.
     */
    static String placement(JSONArray grid) {
        if (grid == null || grid.length() != 8) {
            return null;
        }
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            JSONArray row = grid.optJSONArray(rank);
            if (row == null || row.length() != 8) {
                return null;
            }
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                String cell = row.optString(file, "");
                if (cell.isEmpty()) {
                    empty++;
                    continue;
                }
                if (cell.length() != 1 || "PNBRQKpnbrqk".indexOf(cell.charAt(0)) < 0) {
                    return null;
                }
                if (empty > 0) {
                    fen.append(empty);
                    empty = 0;
                }
                fen.append(cell);
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (rank > 0) {
                fen.append('/');
            }
        }
        return fen.toString();
    }

    private static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String s = array.optString(i, "").trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String load(String resource) {
        try (InputStream in = BoardProbe.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
