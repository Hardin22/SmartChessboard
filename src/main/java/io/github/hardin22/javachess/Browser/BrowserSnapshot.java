package io.github.hardin22.javachess.Browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Developer screenshots of the browser window as the user sees it: the bar (painted by Swing) above the page
 * (pictured by Chromium). The JavaFX snapshot of {@code DevOptions} cannot see this window, and the system
 * screenshot tools may lack permissions.
 *
 * <ul>
 *   <li>{@code -Djavachess.browser.snapshot=out.png} write the picture {@code javachess.browser.snapshot.delayMs}
 *       (default 8000) after the window is shown; several comma-separated files are written at that interval</li>
 *   <li>{@code -Djavachess.snapshot.exit=true} quit afterwards</li>
 * </ul>
 */
public final class BrowserSnapshot {

    private static final Logger log = LoggerFactory.getLogger(BrowserSnapshot.class);

    private BrowserSnapshot() {
    }

    /** Schedules the snapshots requested on the command line, if any. */
    public static void scheduleIfRequested(BrowserWindow window, Runnable exit) {
        String files = System.getProperty("javachess.browser.snapshot");
        if (files == null || files.isBlank()) {
            return;
        }
        long delay = Long.getLong("javachess.browser.snapshot.delayMs", 8000L);
        String[] outs = files.split(",");
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String out : outs) {
            chain = chain.thenCompose(v -> CompletableFuture.supplyAsync(() -> null,
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)))
                    .thenCompose(v -> write(window, new File(out.trim())));
        }
        chain.whenComplete((v, e) -> {
            if (e != null) {
                log.warn("Browser snapshot failed", e);
            }
            if (Boolean.getBoolean("javachess.snapshot.exit")) {
                exit.run();
            }
        });
    }

    /** Writes the window (bar + page) to a PNG file. */
    public static CompletableFuture<Void> write(BrowserWindow window, File out) {
        CompletableFuture<BufferedImage> bar = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                BrowserBar b = window.bar();
                BufferedImage img = new BufferedImage(Math.max(1, b.getWidth()), Math.max(1, b.getHeight()),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                b.printAll(g);
                g.dispose();
                bar.complete(img);
            } catch (RuntimeException e) {
                bar.completeExceptionally(e);
            }
        });
        return bar.thenCombine(window.page().screenshot(null), (top, page) -> {
            int w = top.getWidth();
            int pageH = (int) Math.round(page.getHeight() * (w / (double) page.getWidth()));
            BufferedImage all = new BufferedImage(w, top.getHeight() + pageH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = all.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(top, 0, 0, null);
            g.drawImage(page, 0, top.getHeight(), w, pageH, null);
            g.dispose();
            try {
                File parent = out.getAbsoluteFile().getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                ImageIO.write(all, "png", out);
                log.info("Browser snapshot written: {}", out.getAbsolutePath());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        });
    }
}
