package org.example.javachess.Services;

import org.example.javachess.Utils.AppPaths;
import org.example.javachess.Vision.BoardReading;
import org.example.javachess.Vision.PieceClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Watches the screen for a chess board (chess.com / lichess in the integrated browser) and reports the position.
 *
 * <ol>
 *   <li>Search: full-screen captures until the model finds the same board rectangle on 3 consecutive frames.</li>
 *   <li>Monitor: captures only the board; frames that changed since the previous one are skipped (piece
 *       animations), static frames are classified. A position is reported once it is stable on 2 frames.</li>
 * </ol>
 * Runs on its own daemon thread; callbacks are invoked on that thread (never the JavaFX thread).
 * Debug images are written only with {@code -Djavachess.vision.debug=true}, to {@code ~/.javachess/vision-debug/}.
 */
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);
    private static final int LOCK_FRAMES = 3;
    private static final int STABLE_FRAMES = 2;
    private static final int LOST_FRAMES_BEFORE_SEARCH = 15;

    private final boolean debug = Boolean.getBoolean("javachess.vision.debug");
    private volatile PieceClassifier classifier;
    private volatile boolean running;
    private volatile boolean isFlipped;
    private volatile Rectangle boardRect;
    private Thread scanThread;

    private volatile Consumer<String> onFenChanged;
    private volatile Consumer<BoardReading> onReading;
    private volatile Consumer<Rectangle> onBoardFound;
    private volatile Consumer<String> onError;

    /** Placement + reading of the last reported position. */
    private volatile BoardReading lastReading;

    public VisionService() {
        // The model is loaded lazily on the scan thread (never on the JavaFX thread).
    }

    /** Called with the FEN placement (rank 8 first) when a new stable position is seen. */
    public void setOnFenChanged(Consumer<String> callback) {
        this.onFenChanged = callback;
    }

    /** Called with the full reading (probabilities, confidence) when a new stable position is seen. */
    public void setOnReading(Consumer<BoardReading> callback) {
        this.onReading = callback;
    }

    public void setOnBoardFound(Consumer<Rectangle> callback) {
        this.onBoardFound = callback;
    }

    /** Called with a user message when vision cannot work (model missing, no screen capture permission...). */
    public void setOnError(Consumer<String> callback) {
        this.onError = callback;
    }

    public void setFlipped(boolean flipped) {
        this.isFlipped = flipped;
        log.info("Vision orientation: {}", flipped ? "black at the bottom" : "white at the bottom");
    }

    public synchronized void startScanning() {
        if (running) {
            return;
        }
        running = true;
        scanThread = new Thread(this::scanLoop, "vision-scan");
        scanThread.setDaemon(true);
        scanThread.start();
        log.info("Vision scanning started");
    }

    public void stopScanning() {
        Thread t;
        synchronized (this) {
            running = false;
            t = scanThread;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Vision scanning stopped");
    }

    /** Forgets the locked board so the next scan searches the whole screen again. */
    public void resetState() {
        boardRect = null;
        lastReading = null;
        log.debug("Vision state reset");
    }

    public Rectangle getBoardRect() {
        return boardRect;
    }

    public BoardReading getLastReading() {
        return lastReading;
    }

    private PieceClassifier classifier() {
        PieceClassifier c = classifier;
        if (c == null) {
            synchronized (this) {
                c = classifier;
                if (c == null) {
                    try {
                        c = new PieceClassifier(PieceClassifier.DEFAULT_MODEL);
                        classifier = c;
                    } catch (Throwable e) {
                        log.error("Vision model cannot be loaded", e);
                        report("Riconoscimento della scacchiera non disponibile: " + e.getMessage());
                        return null;
                    }
                }
            }
        }
        return c;
    }

    private void scanLoop() {
        Robot robot;
        try {
            robot = new Robot();
        } catch (Exception e) {
            log.error("Screen capture not available", e);
            report("Cattura dello schermo non disponibile (permessi di registrazione dello schermo?)");
            running = false;
            return;
        }
        PieceClassifier pc = classifier();
        if (pc == null) {
            running = false;
            return;
        }
        Rectangle candidate = null;
        int candidateFrames = 0;
        BufferedImage previous = null;
        String lastSeen = null;
        int stableFrames = 0;
        int lostFrames = 0;
        long frame = 0;

        while (running) {
            try {
                if (boardRect == null) {
                    BufferedImage screen = robot.createScreenCapture(
                            new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
                    Rectangle found = pc.findBoard(screen);
                    if (found != null && candidate != null && similar(candidate, found)) {
                        candidateFrames++;
                    } else {
                        candidate = found;
                        candidateFrames = found == null ? 0 : 1;
                    }
                    if (candidateFrames >= LOCK_FRAMES) {
                        boardRect = candidate;
                        previous = null;
                        stableFrames = 0;
                        lastSeen = null;
                        lostFrames = 0;
                        log.info("Board locked at {}", boardRect);
                        Consumer<Rectangle> cb = onBoardFound;
                        if (cb != null) {
                            cb.accept(boardRect);
                        }
                    } else {
                        sleep(found == null ? 1000 : 150);
                    }
                    continue;
                }

                BufferedImage current = robot.createScreenCapture(boardRect);
                if (previous != null && hasImageChanged(previous, current)) {
                    previous = current; // animation in progress: wait for a still frame
                    stableFrames = 0;
                    sleep(30);
                    continue;
                }
                previous = current;
                String debugPath = debug ? debugFile(frame++) : null;
                BoardReading reading = pc.read(current, isFlipped, debugPath).withPlacementRules();
                if (!reading.hasBoard()) {
                    if (++lostFrames >= LOST_FRAMES_BEFORE_SEARCH) {
                        log.info("Board lost, searching again");
                        boardRect = null;
                        candidate = null;
                        candidateFrames = 0;
                    }
                    sleep(100);
                    continue;
                }
                lostFrames = 0;
                String placement = reading.placement();
                if (placement.equals(lastSeen)) {
                    stableFrames++;
                } else {
                    lastSeen = placement;
                    stableFrames = 1;
                }
                BoardReading reported = lastReading;
                if (stableFrames >= STABLE_FRAMES && (reported == null || !reported.placement().equals(placement))) {
                    lastReading = reading;
                    log.info("Stable position {} (confidence min {}, mean {}, {} ms)", placement,
                            String.format("%.2f", reading.minConfidence()),
                            String.format("%.2f", reading.meanConfidence()), reading.inferenceMs());
                    Consumer<BoardReading> rc = onReading;
                    if (rc != null) {
                        rc.accept(reading);
                    }
                    Consumer<String> fc = onFenChanged;
                    if (fc != null) {
                        fc.accept(placement);
                    }
                }
                sleep(60);
            } catch (Exception e) {
                log.warn("Vision frame failed: {}", e.toString());
                boardRect = null;
                sleep(1000);
            }
        }
    }

    private String debugFile(long frame) {
        Path dir = AppPaths.resolve("vision-debug");
        dir.toFile().mkdirs();
        return dir.resolve("frame-" + (frame % 50) + ".png").toString(); // ring of 50 files
    }

    private void report(String message) {
        Consumer<String> cb = onError;
        if (cb != null) {
            cb.accept(message);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when the two captures differ (sampled every 6 pixels, small tolerance for compression noise). */
    static boolean hasImageChanged(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return true;
        }
        int changed = 0;
        int samples = 0;
        for (int y = 0; y < a.getHeight(); y += 6) {
            for (int x = 0; x < a.getWidth(); x += 6) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                samples++;
                if (Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF)) > 24) {
                    changed++;
                }
            }
        }
        return changed > Math.max(2, samples / 2000);
    }

    /** Intersection over union above 0.9. */
    static boolean similar(Rectangle r1, Rectangle r2) {
        Rectangle i = r1.intersection(r2);
        if (i.isEmpty()) {
            return false;
        }
        double inter = (double) i.width * i.height;
        double union = (double) r1.width * r1.height + (double) r2.width * r2.height - inter;
        return inter / union > 0.90;
    }
}
