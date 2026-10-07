package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Utils.PgnCodec;
import io.github.hardin22.javachess.Vision.BotMover;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A chess site in memory: answers the probe like chess.com / lichess would, and plays the moves it is clicked like
 * their boards do (click on a piece of the side to move, then on a destination; promotions open a menu whose first
 * entry, the queen, sits on the promotion square). Tests drive the opponent with {@link #opponentPlays}.
 */
public final class FakeSite implements PageDriver {

    final Board board = new Board();
    volatile String url = "https://lichess.org/abcd1234";
    volatile String siteId = "lichess";
    volatile String pageHint = "game";
    volatile boolean flipped;
    volatile BoardSnapshot.Rect rect = new BoardSnapshot.Rect(0, 120, 720, 720);
    volatile double viewportWidth = 720;
    volatile double viewportHeight = 1000;
    volatile boolean boardShown = true;
    volatile boolean placementReadable = true;
    volatile boolean animating;
    volatile boolean challenge;
    volatile boolean loginForm;
    volatile boolean unreachable;
    volatile String result;
    volatile boolean acceptsClicks = true;
    volatile String turnFromClock;
    volatile List<String> lastMove = List.of();
    volatile List<String> movesList;
    volatile Function<BoardSnapshot.Rect, BufferedImage> pictures = r -> new BufferedImage(8, 8,
            BufferedImage.TYPE_INT_RGB);
    final List<double[]> clicks = new CopyOnWriteArrayList<>();
    final List<String> played = new CopyOnWriteArrayList<>();
    volatile int scrolls;
    volatile boolean scrollable = true;
    volatile int probes;

    private String selected;
    private String promotionFrom;
    private String promotionTo;

    FakeSite position(String fen) {
        synchronized (board) {
            board.loadFromFen(fen);
        }
        return this;
    }

    /** The other player moves on the site. */
    void opponentPlays(String uci) {
        synchronized (board) {
            Move m = PgnCodec.fromUci(board, uci);
            if (m == null) {
                throw new IllegalArgumentException("Illegal on the site: " + uci + " in " + board.getFen());
            }
            board.doMove(m);
            lastMove = List.of(uci.substring(0, 2), uci.substring(2, 4));
            played.add(uci);
        }
    }

    String placement() {
        synchronized (board) {
            return SetupPosition.placement(board);
        }
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public CompletableFuture<String> evaluate(String expression) {
        if (unreachable) {
            return CompletableFuture.failedFuture(new IllegalStateException("page not answering"));
        }
        if (BoardProbe.SCRIPT.equals(expression)) {
            probes++;
            return CompletableFuture.completedFuture(json());
        }
        if (BotMover.SCROLL_BOARD_INTO_VIEW.equals(expression)) {
            scrolls++;
            if (!scrollable) {
                return CompletableFuture.completedFuture("false");
            }
            rect = new BoardSnapshot.Rect(rect.x(), Math.max(0, (viewportHeight - rect.h()) / 2), rect.w(), rect.h());
            return CompletableFuture.completedFuture("true");
        }
        return CompletableFuture.completedFuture("null");
    }

    @Override
    public CompletableFuture<BufferedImage> screenshot(BoardSnapshot.Rect clip) {
        if (unreachable) {
            return CompletableFuture.failedFuture(new IllegalStateException("page not answering"));
        }
        return CompletableFuture.completedFuture(pictures.apply(clip));
    }

    @Override
    public CompletableFuture<Void> click(double x, double y) {
        clicks.add(new double[]{x, y});
        if (acceptsClicks) {
            onClick(squareAt(x, y));
        }
        return CompletableFuture.completedFuture(null);
    }

    private String squareAt(double x, double y) {
        BoardSnapshot.Rect r = rect;
        int col = (int) Math.floor((x - r.x()) / (r.w() / 8));
        int row = (int) Math.floor((y - r.y()) / (r.h() / 8));
        if (col < 0 || col > 7 || row < 0 || row > 7) {
            return null;
        }
        int file = flipped ? 7 - col : col;
        int rank = flipped ? row : 7 - row;
        return "" + (char) ('a' + file) + (rank + 1);
    }

    private void onClick(String square) {
        synchronized (board) {
            if (square == null) {
                selected = null;
                return;
            }
            if (promotionTo != null) {
                // menu: queen on the promotion square, then knight, rook, bishop towards the centre
                int index = Math.abs(square.charAt(1) - promotionTo.charAt(1));
                char piece = index < 4 && square.charAt(0) == promotionTo.charAt(0) ? "qnrb".charAt(index) : 0;
                if (piece != 0) {
                    opponentPlaysUnsynchronized(promotionFrom + promotionTo + piece);
                }
                promotionFrom = null;
                promotionTo = null;
                return;
            }
            Piece p = board.getPiece(Square.valueOf(square.toUpperCase()));
            if (selected == null) {
                if (p != Piece.NONE && p.getPieceSide() == board.getSideToMove()) {
                    selected = square;
                }
                return;
            }
            String from = selected;
            selected = null;
            if (p != Piece.NONE && p.getPieceSide() == board.getSideToMove()) {
                selected = square; // another own piece: new selection
                return;
            }
            Piece moving = board.getPiece(Square.valueOf(from.toUpperCase()));
            boolean promotion = moving.getPieceType() == com.github.bhlangonijr.chesslib.PieceType.PAWN
                    && (square.charAt(1) == '8' || square.charAt(1) == '1');
            if (promotion) {
                if (PgnCodec.fromUci(board, from + square + "q") != null) {
                    promotionFrom = from;
                    promotionTo = square;
                }
                return;
            }
            if (PgnCodec.fromUci(board, from + square) != null) {
                opponentPlaysUnsynchronized(from + square);
            }
        }
    }

    private void opponentPlaysUnsynchronized(String uci) {
        Move m = PgnCodec.fromUci(board, uci);
        board.doMove(m);
        lastMove = List.of(uci.substring(0, 2), uci.substring(2, 4));
        played.add(uci);
    }

    /** The probe's answer for the current state. */
    String json() {
        JSONObject o = new JSONObject();
        o.put("v", 1).put("url", url).put("title", "test").put("site", siteId).put("page", pageHint)
                .put("viewport", new JSONObject().put("w", viewportWidth).put("h", viewportHeight)).put("dpr", 1)
                .put("challenge", challenge).put("login", loginForm).put("loggedIn", JSONObject.NULL);
        if (!boardShown) {
            o.put("board", JSONObject.NULL);
            return o.toString();
        }
        JSONObject b = new JSONObject();
        BoardSnapshot.Rect r = rect;
        b.put("x", r.x()).put("y", r.y()).put("w", r.w()).put("h", r.h()).put("flipped", flipped)
                .put("animating", animating);
        synchronized (board) {
            if (placementReadable) {
                JSONArray grid = new JSONArray();
                for (int rank = 0; rank < 8; rank++) {
                    JSONArray row = new JSONArray();
                    for (int file = 0; file < 8; file++) {
                        Piece p = board.getPiece(Square.squareAt(rank * 8 + file));
                        row.put(p == Piece.NONE ? "" : p.getFenSymbol());
                    }
                    grid.put(row);
                }
                b.put("placement", grid);
            } else {
                b.put("placement", JSONObject.NULL);
            }
            b.put("pieces", board.boardToArray().length);
        }
        b.put("lastMove", new JSONArray(lastMove));
        b.put("turn", turnFromClock == null ? JSONObject.NULL : turnFromClock);
        b.put("moves", movesList == null ? JSONObject.NULL : new JSONArray(movesList));
        b.put("result", result == null ? JSONObject.NULL : result);
        b.put("status", JSONObject.NULL);
        o.put("board", b);
        return o.toString();
    }

    List<String> clickedSquares() {
        List<String> out = new ArrayList<>();
        for (double[] c : clicks) {
            out.add(squareAt(c[0], c[1]));
        }
        return out;
    }
}
