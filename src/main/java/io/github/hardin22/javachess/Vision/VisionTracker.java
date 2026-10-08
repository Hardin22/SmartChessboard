package io.github.hardin22.javachess.Vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Turns a stream of pictures of a page (or of the screen) into stable chess positions. Synchronous and free of
 * threads, so it can be fed with recorded screenshots in tests.
 *
 * <ol>
 *   <li>Locate: with a known board rectangle ({@link #setBoardHint}, e.g. from the page's markup) the board is used
 *       directly; otherwise the detector must find the same rectangle on {@value #LOCK_FRAMES} consecutive frames.</li>
 *   <li>Watch: only the board is examined. A board that changed since the previous frame is moving (piece animation,
 *       drag): it is not read. A still board is read (once: an unchanged picture keeps its reading, so the model
 *       runs about once per move); a position is accepted once it is read the same on
 *       {@value #STABLE_FRAMES} frames in a row, and reported ({@link Status#NEW_POSITION}) only when it differs
 *       from the last reported one, so a move is reported once.</li>
 *   <li>Lose: after {@value #LOST_FRAMES} frames without a board the search starts again.</li>
 * </ol>
 */
public final class VisionTracker {

    /** The model: finds the board on a whole frame and reads the pieces of a board picture. */
    public interface Detector {
        /** Board rectangle in frame pixels, or null. */
        Rectangle findBoard(BufferedImage frame);

        /** Reads a picture of the board (roughly cropped to it); {@code flipped} = black at the bottom. */
        BoardReading read(BufferedImage board, boolean flipped) throws Exception;
    }

    public enum Status {
        /** Looking for the board (no rectangle yet). */
        SEARCHING,
        /** The board moved or a piece is animating: frame skipped. */
        MOVING,
        /** A position was read but is not stable yet. */
        READING,
        /** Same position as the last reported one. */
        STABLE,
        /** A new stable position: {@link Result#reading()} is to be used. */
        NEW_POSITION,
        /** The rectangle shows no board (popup, page changed...). */
        NO_BOARD,
        /** The board was lost: searching again from the next frame. */
        LOST,
        /** The detector failed on this frame. */
        ERROR
    }

    /** Outcome of one frame. {@code reading} is the frame's reading (null when the frame was not read). */
    public record Result(Status status, Rectangle board, BoardReading reading) {
    }

    static final int LOCK_FRAMES = 3;
    static final int STABLE_FRAMES = 2;
    static final int LOST_FRAMES = 8;

    private final Detector detector;
    private boolean flipped;
    private Rectangle hint;
    private Rectangle locked;
    private Rectangle candidate;
    private int candidateFrames;
    private BufferedImage previous;
    private BoardReading previousReading;
    private long reads;
    private String lastSeen;
    private int stableFrames;
    private int lostFrames;
    private BoardReading lastReported;

    public VisionTracker(Detector detector) {
        this.detector = detector;
    }

    /** Black at the bottom of the board picture. */
    public void setFlipped(boolean flipped) {
        if (this.flipped != flipped) {
            this.flipped = flipped;
            restartStability();
        }
    }

    public boolean isFlipped() {
        return flipped;
    }

    /** Known board rectangle in frame pixels (null: find it with the detector). */
    public void setBoardHint(Rectangle hint) {
        Rectangle h = hint == null ? null : new Rectangle(hint);
        if (h == null ? this.hint != null : !h.equals(this.hint)) {
            this.hint = h;
            previous = null; // different crop: do not compare with the old one
            previousReading = null;
        }
    }

    /** Forgets everything: board rectangle and reported position. */
    public void reset() {
        locked = null;
        candidate = null;
        candidateFrames = 0;
        lostFrames = 0;
        lastReported = null;
        restartStability();
    }

    /** The current position will be reported again (as new) once it is stable. */
    public void forgetReported() {
        lastReported = null;
    }

    public BoardReading lastReported() {
        return lastReported;
    }

    /** Number of pictures actually read by the detector (unchanged pictures are not read again). */
    public long reads() {
        return reads;
    }

    public Rectangle board() {
        return hint != null ? hint : locked;
    }

    private void restartStability() {
        previous = null;
        previousReading = null;
        lastSeen = null;
        stableFrames = 0;
    }

    public Result accept(BufferedImage frame) {
        Rectangle target = hint != null ? clamp(hint, frame) : locked;
        if (target == null) {
            return search(frame);
        }
        BufferedImage crop = copy(frame, target);
        if (previous != null && hasImageChanged(previous, crop)) {
            previous = crop;
            previousReading = null;
            stableFrames = 0;
            return new Result(Status.MOVING, target, null);
        }
        BoardReading reading;
        if (previous != null && previousReading != null) {
            reading = previousReading; // same picture as the last frame read: same reading, no inference
        } else {
            try {
                reading = detector.read(crop, flipped).withPlacementRules();
                reads++;
            } catch (Exception e) {
                previous = crop;
                previousReading = null;
                stableFrames = 0;
                return new Result(Status.ERROR, target, null);
            }
        }
        previous = crop;
        previousReading = reading;
        if (!reading.hasBoard()) {
            stableFrames = 0;
            if (++lostFrames >= LOST_FRAMES && hint == null) {
                locked = null;
                candidate = null;
                candidateFrames = 0;
                lostFrames = 0;
                restartStability();
                return new Result(Status.LOST, target, reading);
            }
            return new Result(Status.NO_BOARD, target, reading);
        }
        lostFrames = 0;
        String placement = reading.placement();
        if (placement.equals(lastSeen)) {
            stableFrames++;
        } else {
            lastSeen = placement;
            stableFrames = 1;
        }
        if (stableFrames < STABLE_FRAMES) {
            return new Result(Status.READING, target, reading);
        }
        if (lastReported == null || !lastReported.placement().equals(placement)) {
            lastReported = reading;
            return new Result(Status.NEW_POSITION, target, reading);
        }
        return new Result(Status.STABLE, target, reading);
    }

    private Result search(BufferedImage frame) {
        Rectangle found;
        try {
            found = detector.findBoard(frame);
        } catch (RuntimeException e) {
            found = null;
        }
        if (found != null && candidate != null && similar(candidate, found)) {
            candidateFrames++;
        } else {
            candidate = found;
            candidateFrames = found == null ? 0 : 1;
        }
        if (candidateFrames >= LOCK_FRAMES) {
            locked = candidate;
            lostFrames = 0;
            restartStability();
        }
        return new Result(Status.SEARCHING, found, null);
    }

    private static Rectangle clamp(Rectangle r, BufferedImage frame) {
        Rectangle c = r.intersection(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
        return c.isEmpty() ? null : c;
    }

    private static BufferedImage copy(BufferedImage frame, Rectangle r) {
        BufferedImage out = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(frame, 0, 0, r.width, r.height, r.x, r.y, r.x + r.width, r.y + r.height, null);
        g.dispose();
        return out;
    }

    /** True when the two pictures differ (sampled every 6 pixels, small tolerance for compression noise). */
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
