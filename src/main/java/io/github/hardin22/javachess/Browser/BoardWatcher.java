package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Services.VisionService;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.VisionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the page a few times per second and reports what it shows: every {@link BoardSnapshot} (site, login,
 * verification, board rectangle and orientation) and every change of the board's position.
 *
 * <p>Two independent readings of the position are kept:</p>
 * <ul>
 *   <li><b>vision</b>: a picture of the board taken by Chromium is read by the vision model
 *       ({@link VisionService}); the board's rectangle comes from the page when known, otherwise the model finds
 *       the board on the visible page;</li>
 *   <li><b>page</b>: the position written in the page's markup (chess.com and lichess).</li>
 * </ul>
 * Each reading counts once it is the same on two consecutive polls (a piece being dragged or animated is never
 * reported). A {@link PositionUpdate} is sent whenever one of the two stable readings changes; it carries both,
 * according to the {@link ReadMode}, and the synchronisation decides (see {@code OnlineGameSync}).
 *
 * <p>All work and all listener calls happen on the given single-threaded executor.</p>
 */
public final class BoardWatcher {

    private static final Logger log = LoggerFactory.getLogger(BoardWatcher.class);

    /** Where positions are read from (setting {@code browser.reader}). */
    public enum ReadMode {
        /** Vision first; the page's markup, where available, confirms each move and stands in when vision fails. */
        VISION,
        /** Vision only (any site, no use of the markup for positions). */
        VISION_ONLY,
        /** The page's markup first; vision only cross-checks it (disagreements are logged). */
        PAGE;

        public static ReadMode parse(String value) {
            if (value == null) {
                return VISION;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                return VISION;
            }
        }

        public boolean usesPage() {
            return this != VISION_ONLY;
        }
    }

    /**
     * A change of the position on the page.
     *
     * @param vision stable reading of the vision model (null when vision is off or has not read the board yet)
     * @param page   stable position from the page's markup, as a certain reading (null when not available)
     */
    public record PositionUpdate(BoardSnapshot snapshot, BoardReading vision, BoardReading page, ReadMode mode) {

        /** The reading to follow first according to the mode, or the other one when it is missing. */
        public BoardReading primary() {
            return switch (mode) {
                case VISION_ONLY -> vision;
                case VISION -> vision != null ? vision : page;
                case PAGE -> page != null ? page : vision;
            };
        }

        /** The other reading, used to confirm the primary one (null when not available). */
        public BoardReading check() {
            return switch (mode) {
                case VISION_ONLY -> null;
                case VISION -> vision != null ? page : null;
                case PAGE -> page != null ? vision : null;
            };
        }
    }

    /** A reason why positions cannot be read right now. */
    public enum Problem {
        /** The page did not answer (loading, crashed, DevTools unavailable). */
        PAGE_UNREADABLE,
        /** The board is partly outside the visible part of the page (vision needs to see it). */
        BOARD_OUT_OF_VIEW,
        /** The vision model cannot be loaded. */
        VISION_UNAVAILABLE,
        /** The vision model does not see a board where it should be (popup over it...). */
        VISION_NO_BOARD
    }

    public interface Listener {
        /** Every poll that reached the page. */
        void onSnapshot(BoardSnapshot snapshot);

        /** One of the stable readings changed. */
        void onPosition(PositionUpdate update);

        /** Positions cannot be read for this reason; null when reading works again. Sent on changes only. */
        void onProblem(Problem problem);
    }

    private static final int STABLE_POLLS = 2;
    private static final long AUTO_SCROLL_EVERY_MS = 2000;

    private final PageDriver page;
    private final VisionService vision;
    private final ScheduledExecutorService executor;
    private final Listener listener;
    private volatile ReadMode mode;
    private volatile long intervalMs = 300;
    private volatile boolean running;
    private long generation;

    // owned by the executor thread
    private String pageCandidate;
    private int pageStablePolls;
    private String pageStable;
    private BoardReading visionStable;
    private String reportedVision;
    private String reportedPage;
    private Problem problem;
    private long polls;
    private long lastAutoScroll;
    private int autoScrolls;
    private String lastVisionCheck;

    /**
     * @param vision vision model, or null when vision cannot be used at all
     */
    public BoardWatcher(PageDriver page, VisionService vision, ScheduledExecutorService executor, Listener listener,
                        ReadMode mode) {
        this.page = page;
        this.vision = vision;
        this.executor = executor;
        this.listener = listener;
        this.mode = mode;
    }

    public void setReadMode(ReadMode mode) {
        executor.execute(() -> {
            if (this.mode != mode) {
                log.info("Board reading mode: {}", mode);
                this.mode = mode;
                forgetAll();
            }
        });
    }

    public ReadMode readMode() {
        return mode;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = Math.max(20, intervalMs);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        long gen = ++generation;
        executor.execute(() -> loop(gen));
    }

    public synchronized void stop() {
        running = false;
        generation++;
    }

    public boolean isRunning() {
        return running;
    }

    /** The current position will be reported again on the next stable poll (e.g. when a synchronisation starts). */
    public void forgetReported() {
        executor.execute(() -> {
            reportedVision = null;
            reportedPage = null;
        });
    }

    public long polls() {
        return polls;
    }

    /** Times the board was scrolled back into view for vision. */
    public int autoScrolls() {
        return autoScrolls;
    }

    private void forgetAll() {
        pageCandidate = null;
        pageStablePolls = 0;
        pageStable = null;
        visionStable = null;
        reportedVision = null;
        reportedPage = null;
        if (vision != null && vision.isAvailable()) {
            vision.resetState();
        }
    }

    private void loop(long gen) {
        if (!running || gen != generation) {
            return;
        }
        pollOnce().whenComplete((v, e) -> {
            if (e != null && running && gen == generation) { // after stop() a late answer does not matter
                log.warn("Board watch failed", e);
            }
            if (running && gen == generation) {
                long delay = problem == Problem.PAGE_UNREADABLE ? Math.max(1000, intervalMs) : intervalMs;
                executor.schedule(() -> loop(gen), delay, TimeUnit.MILLISECONDS);
            }
        });
    }

    /** One poll; completes on the executor thread. */
    CompletableFuture<Void> pollOnce() {
        return BoardProbe.read(page).handleAsync((snapshot, error) -> {
            polls++;
            if (error != null) {
                log.debug("Page probe failed: {}", error.toString());
                pageCandidate = null;
                pageStablePolls = 0;
                report(Problem.PAGE_UNREADABLE);
                return CompletableFuture.<Void>completedFuture(null);
            }
            listener.onSnapshot(snapshot);
            return readPosition(snapshot);
        }, executor).thenCompose(f -> f);
    }

    private CompletableFuture<Void> readPosition(BoardSnapshot snapshot) {
        BoardSnapshot.BoardView board = snapshot.board();
        ReadMode m = mode;
        if (m.usesPage()) {
            readMarkup(board);
        }
        boolean visionUsable = vision != null && vision.isAvailable();
        if (!visionUsable) {
            report(m == ReadMode.VISION_ONLY || board == null || board.placement() == null
                    ? Problem.VISION_UNAVAILABLE : null);
            publish(snapshot);
            return CompletableFuture.completedFuture(null);
        }
        if (m == ReadMode.PAGE && board != null && board.placement() != null && vision.isCalibrated()
                && Objects.equals(pageStable, lastVisionCheck)) {
            // page mode: vision only cross-checks new positions (and stands in when the markup is unreadable);
            // no picture while nothing changed, to spare the Raspberry Pi's CPU
            publish(snapshot);
            return CompletableFuture.completedFuture(null);
        }
        lastVisionCheck = pageStable;
        if (board == null && m != ReadMode.VISION_ONLY && snapshot.site() != ChessSite.OTHER) {
            // a known site without a board on this page: nothing to read
            report(null);
            publish(snapshot);
            return CompletableFuture.completedFuture(null);
        }
        if (board != null && !board.rect().mostlyInside(snapshot.viewportWidth(), snapshot.viewportHeight())) {
            long now = System.currentTimeMillis();
            if (now - lastAutoScroll > AUTO_SCROLL_EVERY_MS) {
                // vision needs to see the board, and so does the user: some pages scroll it away (chess.com
                // follows its move list). Bring it back; tell the user only if that does not work.
                lastAutoScroll = now;
                autoScrolls++;
                page.evaluate(io.github.hardin22.javachess.Vision.BotMover.SCROLL_BOARD_INTO_VIEW);
            } else {
                report(Problem.BOARD_OUT_OF_VIEW);
            }
            publish(snapshot);
            return CompletableFuture.completedFuture(null);
        }
        // board known from the page: a picture of the board only; otherwise the visible page, searched
        CompletableFuture<BufferedImage> picture = board != null ? BoardPicture.take(page, snapshot)
                : page.screenshot(null);
        return picture.handleAsync((image, error) -> {
            if (error != null) {
                log.debug("Screenshot failed: {}", error.toString());
                report(Problem.PAGE_UNREADABLE);
            } else {
                readPicture(board, image);
            }
            publish(snapshot);
            return null;
        }, executor);
    }

    private void readMarkup(BoardSnapshot.BoardView board) {
        String placement = board == null ? null : board.placement();
        if (placement == null || board.animating()) {
            pageStablePolls = 0;
            if (placement == null) {
                pageCandidate = null;
                pageStable = null;
            }
            return;
        }
        if (placement.equals(pageCandidate)) {
            pageStablePolls++;
        } else {
            pageCandidate = placement;
            pageStablePolls = 1;
        }
        if (pageStablePolls >= STABLE_POLLS) {
            pageStable = placement;
        }
    }

    private void readPicture(BoardSnapshot.BoardView board, BufferedImage image) {
        vision.setFlipped(board != null && board.flipped());
        vision.setBoardHint(board != null ? new Rectangle(0, 0, image.getWidth(), image.getHeight()) : null);
        VisionTracker.Result result = vision.accept(image);
        calibrate(board, image, result);
        switch (result.status()) {
            case NEW_POSITION, STABLE -> {
                report(null);
                visionStable = result.reading();
            }
            case NO_BOARD -> report(Problem.VISION_NO_BOARD);
            case LOST -> {
                visionStable = null;
                report(Problem.VISION_NO_BOARD);
            }
            case ERROR -> report(vision.isAvailable() ? Problem.VISION_NO_BOARD : Problem.VISION_UNAVAILABLE);
            default -> report(null);
        }
    }

    /**
     * Teaches vision this board's pieces from the page's markup: a picture taken while the page shows a stable,
     * known position is a labelled example. Done until vision knows every piece, and again whenever it disagrees
     * with the page (theme changed, zoom...). Never in {@link ReadMode#VISION_ONLY}.
     */
    private void calibrate(BoardSnapshot.BoardView board, BufferedImage image, VisionTracker.Result result) {
        if (!mode.usesPage() || board == null || board.animating() || board.placement() == null
                || !board.placement().equals(pageStable)) {
            return;
        }
        boolean disagrees = result.reading() != null && !result.reading().placement().equals(board.placement());
        if (!vision.isCalibrated() || disagrees) {
            vision.learn(image, board.placement(), board.flipped());
            learned++;
            if (learned == 1 || disagrees) {
                log.info("Vision learned this board's pieces from the page ({}{})", vision.isCalibrated()
                        ? "complete" : "partial", disagrees ? ", after a disagreement" : "");
            }
        }
    }

    private int learned;

    /** Sends an update when one of the stable readings changed since the last update. */
    private void publish(BoardSnapshot snapshot) {
        ReadMode m = mode;
        String v = visionStable == null ? null : visionStable.placement();
        String p = m.usesPage() ? pageStable : null;
        if (v == null && p == null) {
            return;
        }
        if (Objects.equals(v, reportedVision) && Objects.equals(p, reportedPage)) {
            return;
        }
        reportedVision = v;
        reportedPage = p;
        if (v != null && p != null && !v.equals(p)) {
            log.info("Vision and page differ: vision {}, page {}", v, p);
        }
        listener.onPosition(new PositionUpdate(snapshot, visionStable, p == null ? null : BoardReading.certain(p), m));
    }

    private void report(Problem p) {
        if (p != problem) {
            problem = p;
            listener.onProblem(p);
        }
    }
}
