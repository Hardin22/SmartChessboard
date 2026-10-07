package io.github.hardin22.javachess.Services;

import io.github.hardin22.javachess.Utils.AppPaths;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.PieceClassifier;
import io.github.hardin22.javachess.Vision.VisionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Reads chess positions from pictures of a 2D board: the integrated browser feeds it screenshots of the page
 * (taken by Chromium itself, see {@code Browser/CdpPageDriver}), so it works whatever covers the window and needs
 * no screen-recording permission.
 *
 * <p>It owns the ONNX model ({@link PieceClassifier}, loaded on first use: ~12 MB and a few hundred ms) and a
 * {@link VisionTracker} that locates the board, skips moving frames and reports each stable position once.
 * Not thread-safe: use it from one worker thread, never from the JavaFX thread.</p>
 *
 * <p>Debug pictures with the detections are written with {@code -Djavachess.vision.debug=true} to
 * {@code ~/.javachess/vision-debug/} (a ring of 50 files).</p>
 */
public class VisionService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    private final boolean debug = Boolean.getBoolean("javachess.vision.debug");
    private final VisionTracker.Detector injected;
    private PieceClassifier classifier;
    private String unavailable;
    private VisionTracker tracker;
    private long frame;

    /** Uses the bundled model, loaded on first use. */
    public VisionService() {
        this.injected = null;
    }

    /** Uses the given detector (tests). */
    public VisionService(VisionTracker.Detector detector) {
        this.injected = detector;
    }

    /** True when the model can be used; false (with {@link #unavailableReason()}) when it failed to load. */
    public boolean isAvailable() {
        return tracker() != null;
    }

    /** Why vision cannot work (for the log and the user), or null. */
    public String unavailableReason() {
        tracker();
        return unavailable;
    }

    /** Feeds one picture of the page; see {@link VisionTracker#accept}. */
    public VisionTracker.Result accept(BufferedImage picture) {
        VisionTracker t = tracker();
        if (t == null) {
            return new VisionTracker.Result(VisionTracker.Status.ERROR, null, null);
        }
        return t.accept(picture);
    }

    /** Reads one picture of a board, outside the tracking (used to cross-check other readings). */
    public BoardReading readBoard(BufferedImage board, boolean flipped) throws Exception {
        VisionTracker.Detector d = detector();
        if (d == null) {
            throw new IllegalStateException(unavailable);
        }
        return d.read(board, flipped).withPlacementRules();
    }

    public void setFlipped(boolean flipped) {
        VisionTracker t = tracker();
        if (t != null && t.isFlipped() != flipped) {
            t.setFlipped(flipped);
            log.info("Vision orientation: {}", flipped ? "black at the bottom" : "white at the bottom");
        }
    }

    /** Board rectangle in picture pixels when it is known (null: search the whole picture). */
    public void setBoardHint(Rectangle board) {
        VisionTracker t = tracker();
        if (t != null) {
            t.setBoardHint(board);
        }
    }

    /** Forgets the board and the last position: the next stable position is reported as new. */
    public void resetState() {
        VisionTracker t = tracker();
        if (t != null) {
            t.reset();
        }
    }

    /** The current position will be reported again once stable (e.g. when a synchronisation restarts). */
    public void forgetReported() {
        VisionTracker t = tracker();
        if (t != null) {
            t.forgetReported();
        }
    }

    private VisionTracker tracker() {
        if (tracker == null) {
            VisionTracker.Detector d = detector();
            if (d != null) {
                tracker = new VisionTracker(d);
            }
        }
        return tracker;
    }

    private VisionTracker.Detector detector() {
        if (injected != null) {
            return injected;
        }
        if (classifier == null && unavailable == null) {
            try {
                classifier = new PieceClassifier(PieceClassifier.DEFAULT_MODEL);
            } catch (Throwable e) {
                log.error("Vision model cannot be loaded", e);
                unavailable = "modello di riconoscimento non disponibile";
            }
        }
        if (classifier == null) {
            return null;
        }
        PieceClassifier pc = classifier;
        return new VisionTracker.Detector() {
            @Override
            public Rectangle findBoard(BufferedImage picture) {
                return pc.findBoard(picture);
            }

            @Override
            public BoardReading read(BufferedImage board, boolean flipped) throws Exception {
                return pc.read(board, flipped, debug ? debugFile() : null);
            }
        };
    }

    private String debugFile() {
        Path dir = AppPaths.resolve("vision-debug");
        dir.toFile().mkdirs();
        return dir.resolve("frame-" + (frame++ % 50) + ".png").toString();
    }

    @Override
    public void close() {
        if (classifier != null) {
            classifier.close();
            classifier = null;
        }
    }
}
