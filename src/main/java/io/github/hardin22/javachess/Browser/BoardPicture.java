package io.github.hardin22.javachess.Browser;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

/**
 * Takes the picture of the board that vision reads: exactly the board's rectangle, at the device's pixel density.
 * When a thin strip of the board is outside the visible page (a page a few pixels wider than the window), the
 * visible part is pictured and the strip is filled by repeating its edge, so the squares keep their place.
 */
public final class BoardPicture {

    private BoardPicture() {
    }

    /** Fails when the board is not visible enough ({@link BoardSnapshot.Rect#mostlyInside}). */
    public static CompletableFuture<BufferedImage> take(PageDriver page, BoardSnapshot snapshot) {
        BoardSnapshot.BoardView board = snapshot.board();
        if (board == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No board"));
        }
        BoardSnapshot.Rect r = board.rect();
        if (r.inside(snapshot.viewportWidth(), snapshot.viewportHeight())) {
            return page.screenshot(r);
        }
        if (!r.mostlyInside(snapshot.viewportWidth(), snapshot.viewportHeight())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Board out of view"));
        }
        BoardSnapshot.Rect v = r.visiblePart(snapshot.viewportWidth(), snapshot.viewportHeight());
        return page.screenshot(v).thenApply(img -> pad(img, r, v));
    }

    /** Places the visible part in a picture of the whole board, repeating the edge pixels into the missing strip. */
    static BufferedImage pad(BufferedImage visible, BoardSnapshot.Rect board, BoardSnapshot.Rect part) {
        double scale = visible.getWidth() / part.w(); // device pixels per CSS pixel
        int w = (int) Math.round(board.w() * scale);
        int h = (int) Math.round(board.h() * scale);
        int ox = (int) Math.round((part.x() - board.x()) * scale);
        int oy = (int) Math.round((part.y() - board.y()) * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(visible, ox, oy, null);
        g.dispose();
        int vw = visible.getWidth();
        int vh = visible.getHeight();
        for (int y = 0; y < h; y++) {
            int sy = Math.min(vh - 1, Math.max(0, y - oy));
            for (int x = 0; x < w; x++) {
                boolean inX = x >= ox && x < ox + vw;
                boolean inY = y >= oy && y < oy + vh;
                if (!inX || !inY) {
                    int sx = Math.min(vw - 1, Math.max(0, x - ox));
                    out.setRGB(x, y, visible.getRGB(sx, sy));
                }
            }
        }
        return out;
    }
}
