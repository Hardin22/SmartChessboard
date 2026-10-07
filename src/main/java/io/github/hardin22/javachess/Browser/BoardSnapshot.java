package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Side;

import java.util.List;

/**
 * What the page shows at one moment, as read by {@link BoardProbe}: the site and kind of page, verification or login
 * forms, and the main chess board (null when there is none). Coordinates are CSS pixels relative to the viewport.
 *
 * @param challenge true while the site shows a bot verification ("Verify you are human")
 * @param loginForm true when a password field is visible
 * @param loggedIn  true/false when the page tells, null when unknown
 */
public record BoardSnapshot(String url, ChessSite site, String pageHint, String title, boolean challenge,
                            boolean loginForm, Boolean loggedIn, double viewportWidth, double viewportHeight,
                            double devicePixelRatio, BoardView board) {

    /** Rectangle in CSS pixels. */
    public record Rect(double x, double y, double w, double h) {

        public double centerX() {
            return x + w / 2;
        }

        public double centerY() {
            return y + h / 2;
        }

        /** True when the whole rectangle is inside a viewport of the given size (1 px tolerance). */
        public boolean inside(double viewportWidth, double viewportHeight) {
            return x >= -1 && y >= -1 && x + w <= viewportWidth + 1 && y + h <= viewportHeight + 1;
        }

        /** The part of the rectangle inside the viewport (possibly empty: zero width or height). */
        public Rect visiblePart(double viewportWidth, double viewportHeight) {
            double x0 = Math.max(0, x);
            double y0 = Math.max(0, y);
            double x1 = Math.min(viewportWidth, x + w);
            double y1 = Math.min(viewportHeight, y + h);
            return new Rect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        }

        /**
         * True when the board can be pictured: at least 97% of its width and height visible (the hidden strip
         * falls in the margin the readers ignore).
         */
        public boolean mostlyInside(double viewportWidth, double viewportHeight) {
            Rect v = visiblePart(viewportWidth, viewportHeight);
            return w > 0 && h > 0 && v.w() >= 0.97 * w && v.h() >= 0.97 * h;
        }
    }

    /**
     * The main board.
     *
     * @param placement FEN piece placement read from the page (rank 8 first), null when the page does not expose it
     *                  (unknown site, unexpected markup): the board must then be read from a screenshot
     * @param animating a piece is moving or being dragged: the placement may be transient
     * @param lastMove  the squares highlighted as the last move (order not guaranteed), possibly empty
     * @param turn      side to move when the page tells (running clock), else null
     * @param moves     the moves listed by the page (SAN, main line), null when not available
     * @param result    "1-0", "0-1", "1/2-1/2" when the page shows the game is over, else null
     */
    public record BoardView(Rect rect, boolean flipped, boolean animating, int pieces, String placement,
                            List<String> lastMove, Side turn, List<String> moves, String result, String status) {

        public BoardView {
            lastMove = lastMove == null ? List.of() : List.copyOf(lastMove);
            moves = moves == null ? null : List.copyOf(moves);
        }

        /** Side at the bottom of the screen: the user's side in a game. */
        public Side bottomSide() {
            return flipped ? Side.BLACK : Side.WHITE;
        }
    }

    public PageInfo page() {
        return PageInfo.of(url).withHint(pageHint);
    }

    public boolean hasBoard() {
        return board != null;
    }
}
