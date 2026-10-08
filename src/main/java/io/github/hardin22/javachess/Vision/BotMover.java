package io.github.hardin22.javachess.Vision;

import io.github.hardin22.javachess.Browser.BoardProbe;
import io.github.hardin22.javachess.Browser.BoardSnapshot;
import io.github.hardin22.javachess.Browser.PageDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Plays a move on the page (chess.com, lichess, or any board found by the probe) the way a person does: a click on
 * the piece, a click on the destination and, for a promotion, a click on the piece chosen in the site's menu.
 *
 * <p>Clicks are real input events sent to Chromium ({@link PageDriver#click}): the sites accept them like a tap,
 * the system mouse pointer does not move, and nothing depends on where the window is on the screen. Square
 * centres come from the board's rectangle and orientation read on the page right before the move; a board that is
 * scrolled out of view is scrolled back first.</p>
 */
public class BotMover {

    private static final Logger log = LoggerFactory.getLogger(BotMover.class);

    /** Scrolls the page so that the main board is entirely visible (same board choice as the probe). */
    public static final String SCROLL_BOARD_INTO_VIEW = "(() => {"
            + " let best = null, area = 0;"
            + " for (const el of document.querySelectorAll('cg-board, wc-chess-board, chess-board, [data-javachess-board]')) {"
            + "  const r = el.getBoundingClientRect(); if (r.width * r.height > area) { best = el; area = r.width * r.height; } }"
            + " if (best) best.scrollIntoView({block: 'center', inline: 'center'});"
            + " return !!best; })()";

    /** Order of the pieces in the promotion menus of chess.com and lichess, starting on the promotion square. */
    private static final String PROMOTION_ORDER = "qnrb";

    private final PageDriver page;
    private final long clickGapMs;
    private final long promotionDelayMs;
    private final long scrollSettleMs;

    public BotMover(PageDriver page) {
        this(page, 90, 350, 300);
    }

    BotMover(PageDriver page, long clickGapMs, long promotionDelayMs, long scrollSettleMs) {
        this.page = page;
        this.clickGapMs = clickGapMs;
        this.promotionDelayMs = promotionDelayMs;
        this.scrollSettleMs = scrollSettleMs;
    }

    /**
     * Plays a move given in UCI ("e2e4", "e7e8q"). Completes when the clicks have been sent (whether the site
     * accepted the move is seen on the next reading of the page); fails when there is no board or the move is
     * malformed.
     */
    public CompletableFuture<Void> play(String uci) {
        String move = uci == null ? "" : uci.trim().toLowerCase(Locale.ROOT);
        if (!move.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid move: " + uci));
        }
        return visibleBoard().thenCompose(snapshot -> {
            List<double[]> points = clickPoints(snapshot.board(), move);
            log.info("Playing {} on the page ({} clicks, board {}x{} at {},{}{})", move, points.size(),
                    Math.round(snapshot.board().rect().w()), Math.round(snapshot.board().rect().h()),
                    Math.round(snapshot.board().rect().x()), Math.round(snapshot.board().rect().y()),
                    snapshot.board().flipped() ? ", flipped" : "");
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (int i = 0; i < points.size(); i++) {
                double[] p = points.get(i);
                long gap = i == 0 ? 0 : i == 2 ? promotionDelayMs : clickGapMs;
                chain = chain.thenComposeAsync(v -> page.click(p[0], p[1]), delayed(gap));
            }
            return chain;
        });
    }

    /** The page's board, scrolled into view when needed. */
    private CompletableFuture<BoardSnapshot> visibleBoard() {
        return BoardProbe.read(page).thenCompose(snapshot -> {
            if (snapshot.board() == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No board on the page"));
            }
            if (snapshot.board().rect().inside(snapshot.viewportWidth(), snapshot.viewportHeight())) {
                return CompletableFuture.completedFuture(snapshot);
            }
            log.info("Board partly out of view, scrolling it into view");
            return page.evaluate(SCROLL_BOARD_INTO_VIEW)
                    .thenComposeAsync(v -> BoardProbe.read(page), delayed(scrollSettleMs))
                    .thenCompose(again -> again.board() != null
                            ? CompletableFuture.completedFuture(again)
                            : CompletableFuture.failedFuture(new IllegalStateException("No board on the page")));
        });
    }

    private static Executor delayed(long ms) {
        return ms <= 0 ? Runnable::run : CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS);
    }

    /** Points to click (viewport CSS pixels): origin, destination and, for a promotion, the chosen piece. */
    static List<double[]> clickPoints(BoardSnapshot.BoardView board, String uci) {
        List<double[]> points = new ArrayList<>();
        String from = uci.substring(0, 2);
        String to = uci.substring(2, 4);
        points.add(squareCenter(board.rect(), board.flipped(), from));
        double[] target = squareCenter(board.rect(), board.flipped(), to);
        points.add(target);
        if (uci.length() == 5) {
            // the menu opens on the promotion square and goes towards the centre of the board: queen first
            int index = Math.max(0, PROMOTION_ORDER.indexOf(uci.charAt(4)));
            double cell = board.rect().h() / 8;
            double direction = target[1] < board.rect().centerY() ? 1 : -1;
            points.add(new double[]{target[0], target[1] + direction * index * cell});
        }
        return points;
    }

    /** Centre of a square ("e4") on the screen, for a board drawn with white (or, if flipped, black) at the bottom. */
    static double[] squareCenter(BoardSnapshot.Rect rect, boolean flipped, String square) {
        int file = square.charAt(0) - 'a';
        int rank = square.charAt(1) - '1';
        int col = flipped ? 7 - file : file;
        int row = flipped ? rank : 7 - rank;
        double w = rect.w() / 8;
        double h = rect.h() / 8;
        return new double[]{rect.x() + (col + 0.5) * w, rect.y() + (row + 0.5) * h};
    }
}
