package io.github.hardin22.javachess.Services;

import io.github.hardin22.javachess.Utils.AppPaths;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.PieceClassifier;
import io.github.hardin22.javachess.Vision.TemplateReader;
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
 * <p>Two readers:</p>
 * <ul>
 *   <li>the ONNX model ({@link PieceClassifier}, loaded on first use: ~12 MB and a few hundred ms), which knows
 *       many board themes and piece sets but not all of them;</li>
 *   <li>a reader calibrated on the board being watched ({@link TemplateReader}): it learns the site's own pieces
 *       from pictures whose position is known for sure ({@link #learn}: the page's markup, or the start position
 *       recognised by the model) and then reads any theme and piece set, cheaply (a few ms on the Pi). When it is
 *       unsure the model's reading is combined with it.</li>
 * </ul>
 * <p>A {@link VisionTracker} locates the board, skips moving frames and reports each stable position once.
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
    private final TemplateReader templates = new TemplateReader();
    private int calibratedReads;
    private int modelReads;

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
    /** Below this best-match quality the calibrated reading is trusted alone (no model inference). */
    private static final double TRUSTED_QUALITY = 0.45;

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
                return readBoardPicture(board, flipped, () -> pc.read(board, flipped, debug ? debugFile() : null));
            }
        };
    }

    /** Reads with the calibrated reader when it can, with the model otherwise or when the former is unsure. */
    private BoardReading readBoardPicture(BufferedImage board, boolean flipped, ModelRead model) throws Exception {
        if (templates.knownSymbols() >= 10 && templates.fits(board)) {
            BoardReading t = templates.read(board, flipped);
            if (templates.lastQuality() <= TRUSTED_QUALITY && minBest(t) >= 0.7f) {
                calibratedReads++;
                return t;
            }
            modelReads++;
            return TemplateReader.fuse(t, model.read());
        }
        modelReads++;
        BoardReading m = model.read();
        if (autoCalibrate && START.equals(m.withPlacementRules().placement()) && m.minConfidence() >= 0.4f) {
            // the start position recognised by the model: a known position to learn this board's pieces from
            log.info("Vision calibrated on the start position");
            templates.learn(board, START, flipped);
        }
        return m;
    }

    private interface ModelRead {
        BoardReading read() throws Exception;
    }

    /** Lowest, over the squares, of the probability of the most likely symbol. */
    private static float minBest(BoardReading r) {
        float min = 1f;
        for (int f = 0; f < 8; f++) {
            for (int k = 0; k < 8; k++) {
                min = Math.min(min, r.confidence(f, k));
            }
        }
        return min;
    }

    private volatile boolean autoCalibrate = true;

    /** Learns from the start position recognised by the model (on by default). */
    public void setAutoCalibrate(boolean enabled) {
        this.autoCalibrate = enabled;
    }

    /**
     * Teaches the calibrated reader the look of this board's pieces from a picture whose position is known for sure
     * (the page's markup at the same moment, or a position confirmed by the game).
     */
    public void learn(BufferedImage board, String placement, boolean flipped) {
        if (templates.boardWidth() > 0 && !templates.fits(board)) {
            templates.clear(); // the board changed size (zoom, window): learn it again
        }
        templates.learn(board, placement, flipped);
    }

    /** True once every piece of the watched board has been learned. */
    public boolean isCalibrated() {
        return templates.isComplete();
    }

    /** Readings made by the calibrated reader alone and with the model, for the logs and tests. */
    public int[] readerStats() {
        return new int[]{calibratedReads, modelReads};
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
