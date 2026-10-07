package io.github.hardin22.javachess.Engine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Live analysis of "the current position" on the shared analysis engine: eval bar, analysis panel,
 * arrows, and the data the LED coach needs. One position at a time; a new request replaces the old one
 * (the engine is stopped, never restarted, and its hash is kept).
 *
 * <p>Updates are coalesced (one per completed depth, at most every {@value #MIN_INTERVAL_MS} ms) and
 * delivered on the engine event thread, so listeners must hop to the FX thread themselves.</p>
 */
public final class PositionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PositionAnalyzer.class);
    static final long MIN_INTERVAL_MS = 60;

    /** Receives analysis updates (engine event thread). */
    @FunctionalInterface
    public interface Listener {
        void onUpdate(AnalysisUpdate update);
    }


    private final Supplier<UciClient> clientSupplier;
    private final Supplier<EngineManager.Budget> budgetSupplier;
    private final CopyOnWriteArrayList<Listener> observers = new CopyOnWriteArrayList<>();

    // guarded by this
    private Request current;
    private int generation;
    private UciClient.SearchHandle mainHandle;
    private boolean mainFinished;
    private AnalysisUpdate lastUpdate;

    // delivery coalescing
    private volatile AnalysisUpdate pending;
    private volatile Request pendingRequest;
    private final AtomicBoolean deliveryScheduled = new AtomicBoolean();
    private volatile long lastDeliveryNanos;
    private volatile int lastDeliveredGeneration = -1;
    private volatile int lastDeliveredDepth;
    private AnalysisUpdate lastDelivered; // engine event thread only

    private record Request(String fen, int depth, int multiPv, Listener listener, int legalMoves, boolean inCheck,
                           int generation) {
    }

    /** The analyzer of the application's engine manager. */
    public static PositionAnalyzer get() {
        return EngineManager.get().analyzer();
    }

    /** For tests: an analyzer on a given client. */
    PositionAnalyzer(Supplier<UciClient> clientSupplier, Supplier<EngineManager.Budget> budgetSupplier) {
        this.clientSupplier = clientSupplier;
        this.budgetSupplier = budgetSupplier;
    }

    /**
     * Analyses {@code fen} up to {@code depth} with {@code multiPv} lines, replacing any previous request.
     * Returns immediately. Calling it again with the same arguments is a no-op (only the listener is replaced).
     *
     * @param listener receives the updates for this position (may be null: only observers are notified)
     */
    public void analyze(String fen, int depth, int multiPv, Listener listener) {
        Objects.requireNonNull(fen, "fen");
        EngineManager.Budget b = budgetSupplier.get();
        int effDepth = Math.max(1, Math.min(Math.max(depth, b.coachConfirmDepth()), b.liveMaxDepth()));
        int k = Math.max(1, multiPv);
        AnalysisUpdate replay = null;
        synchronized (this) {
            if (current != null && current.fen.equals(fen) && current.depth == effDepth && current.multiPv == k) {
                current = new Request(fen, effDepth, k, listener, current.legalMoves, current.inCheck, current.generation);
                replay = lastUpdate;
            } else {
                Board board = new Board();
                board.loadFromFen(fen);
                int legal;
                try {
                    legal = MoveGenerator.generateLegalMoves(board).size();
                } catch (Exception e) {
                    log.warn("invalid position for analysis: {}", fen);
                    return;
                }
                generation++;
                current = new Request(fen, effDepth, k, listener, legal, board.isKingAttacked(), generation);
                lastUpdate = null;
                mainFinished = false;
                startMain(current);
            }
        }
        if (replay != null && listener != null) {
            AnalysisUpdate r = replay;
            EngineEvents.EXECUTOR.execute(() -> safeNotify(listener, r));
        }
    }

    /** Analyses a new position keeping the current listener and parameters (used after a move). */
    public void follow(String fen) {
        Request r;
        synchronized (this) {
            r = current;
        }
        if (r != null) {
            analyze(fen, r.depth, r.multiPv, r.listener);
        } else {
            analyze(fen, 0, 1, null);
        }
    }

    /** Stops the analysis and forgets the listener. */
    public void stop() {
        synchronized (this) {
            generation++;
            current = null;
            lastUpdate = null;
            if (mainHandle != null) {
                mainHandle.cancel();
                mainHandle = null;
            }
        }
    }

    /** Restarts the current request (e.g. after engine options changed). */
    public void refresh() {
        synchronized (this) {
            if (current == null) {
                return;
            }
            generation++;
            current = new Request(current.fen, current.depth, current.multiPv, current.listener, current.legalMoves,
                    current.inCheck, generation);
            mainFinished = false;
            startMain(current);
        }
    }

    /** FEN currently analysed, or null. */
    public synchronized String currentFen() {
        return current == null ? null : current.fen;
    }

    /** Latest update for the current position, or null. */
    public synchronized AnalysisUpdate lastUpdate() {
        return lastUpdate;
    }

    public void addObserver(Listener l) {
        observers.addIfAbsent(l);
    }

    public void removeObserver(Listener l) {
        observers.remove(l);
    }

    /** Priority of a foreground search; higher runs first. Foreground searches pause the live analysis. */
    public enum Priority {
        /** Full game review, one position at a time. */
        REVIEW,
        /** Lift hints (cancelled when the piece is put down). */
        HINT,
        /** Move verdict for the LEDs. */
        VERDICT,
        /** Bot move when the bot shares this engine (low-memory boards). */
        BOT
    }

    private final class Job {
        final Priority priority;
        final long seq;
        final String fen;
        final SearchLimits limits;
        final Map<String, String> overrides;
        final CompletableFuture<SearchResult> future = new CompletableFuture<>();
        UciClient.SearchHandle handle;

        Job(Priority priority, long seq, String fen, SearchLimits limits, Map<String, String> overrides) {
            this.priority = priority;
            this.seq = seq;
            this.fen = fen;
            this.limits = limits;
            this.overrides = overrides;
        }
    }

    // guarded by this
    private final PriorityQueue<Job> queue = new PriorityQueue<>(
            Comparator.comparing((Job j) -> j.priority).reversed().thenComparingLong(j -> j.seq));
    private Job running;
    private Job lastHint;
    private long jobSeq;
    private int liveHolds;

    /**
     * Runs a search on the analysis engine ahead of the live analysis, which pauses and then resumes on the same
     * position with a warm hash. Foreground searches never preempt each other: they run one at a time, highest
     * priority first. {@code overrides} are UCI options valid for this search only.
     */
    public CompletableFuture<SearchResult> submit(Priority priority, String fen, SearchLimits limits,
                                                  Map<String, String> overrides) {
        Job job;
        synchronized (this) {
            job = new Job(priority, jobSeq++, fen, limits, overrides == null ? Map.of() : overrides);
            if (priority == Priority.HINT) {
                lastHint = job;
            }
            queue.add(job);
            pump();
        }
        return job.future;
    }

    /**
     * Pauses the live analysis until the returned handle is closed (e.g. during a full game review, so it does
     * not restart between the review searches). Foreground searches still run.
     */
    public AutoCloseable holdLive() {
        synchronized (this) {
            liveHolds++;
        }
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                synchronized (this) {
                    liveHolds--;
                    pump();
                }
            }
        };
    }

    /**
     * Scores a set of moves of {@code fen} in one search ({@code go searchmoves ...} with MultiPV = number of
     * moves) as a {@link Priority#HINT} foreground search.
     */
    public CompletableFuture<SearchResult> scoreMoves(String fen, List<String> moves, long nodes, long capMs) {
        return scoreMoves(fen, moves, nodes, capMs, Priority.HINT);
    }

    public CompletableFuture<SearchResult> scoreMoves(String fen, List<String> moves, long nodes, long capMs,
                                                      Priority priority) {
        if (moves.isEmpty()) {
            return CompletableFuture.completedFuture(new SearchResult(null, null, List.of(), 0, 0, 0, false));
        }
        return submit(priority, fen, SearchLimits.nodes(nodes).withMultiPv(moves.size()).withSearchMoves(moves)
                .withTimeout(capMs), Map.of());
    }

    /** Best move/score of {@code fen} with a node budget, as a {@link Priority#VERDICT} foreground search. */
    public CompletableFuture<SearchResult> searchBest(String fen, long nodes, long capMs) {
        return submit(Priority.VERDICT, fen, SearchLimits.nodes(nodes).withTimeout(capMs), Map.of());
    }

    /** Stops the latest hint search early (its future completes with partial lines) or drops it if queued. */
    public synchronized void cancelScoring() {
        Job j = lastHint;
        if (j == null) {
            return;
        }
        if (queue.remove(j)) {
            j.future.completeExceptionally(new java.util.concurrent.CancellationException("hint cancelled"));
        } else if (j == running && j.handle != null) {
            j.handle.cancel();
        }
    }

    /** True when no foreground search is running or queued (for tests and diagnostics). */
    public synchronized boolean isForegroundIdle() {
        return running == null && queue.isEmpty();
    }

    /** Caller holds the lock: starts the next foreground job, or resumes the live analysis. */
    private void pump() {
        if (running != null) {
            return;
        }
        Job next = queue.poll();
        if (next == null) {
            if (liveHolds == 0 && current != null && !mainFinished
                    && (mainHandle == null || mainHandle.result().isDone())) {
                startMain(current);
            }
            return;
        }
        UciClient client;
        try {
            client = clientSupplier.get();
        } catch (RuntimeException e) {
            next.future.completeExceptionally(e);
            pump();
            return;
        }
        running = next;
        next.handle = client.search(next.fen, List.of(), next.limits, null, next.overrides);
        next.handle.result().whenComplete((r, err) -> {
            synchronized (PositionAnalyzer.this) {
                if (running == next) {
                    running = null;
                }
                pump();
            }
            if (err != null) {
                next.future.completeExceptionally(err);
            } else {
                next.future.complete(r);
            }
        });
    }

    private boolean liveBlocked() {
        return running != null || !queue.isEmpty() || liveHolds > 0;
    }

    // ------------------------------------------------------------------------------------------

    /** Caller holds the lock. */
    private void startMain(Request req) {
        if (req.legalMoves == 0) {
            mainFinished = true;
            Score terminal = req.inCheck ? Score.mate(0) : Score.cp(0);
            publish(req, new AnalysisUpdate(req.fen, 0, List.of(), true, terminal, 0, 0));
            return;
        }
        if (liveBlocked()) {
            return; // resumed by pump() when the foreground searches are done
        }
        UciClient client;
        try {
            client = clientSupplier.get();
        } catch (RuntimeException e) {
            log.warn("analysis unavailable: {}", e.getMessage());
            return;
        }
        int k = Math.min(req.multiPv, req.legalMoves);
        // A resumed search (after a candidate search) restarts from depth 1 on a warm hash: do not publish
        // shallower results than those already shown for this position.
        int floor = lastUpdate != null && lastUpdate.fen().equals(req.fen) && lastUpdate.lines().size() >= k
                ? lastUpdate.depth() : 0;
        Collector col = new Collector(req, k, floor);
        UciClient.SearchHandle h = client.search(req.fen, List.of(), SearchLimits.depth(req.depth).withMultiPv(k),
                col::onInfo);
        mainHandle = h;
        h.result().whenComplete((r, err) -> onMainDone(req, r, err, col));
    }

    private void onMainDone(Request req, SearchResult r, Throwable err, Collector col) {
        synchronized (this) {
            if (req.generation != generation) {
                return;
            }
            if (err != null) {
                if (!(err instanceof java.util.concurrent.CancellationException)) {
                    log.warn("analysis of {} failed: {}", req.fen, EngineManager.rootMessage(err));
                }
                return;
            }
            if (r.stopped()) {
                return; // interrupted by a candidate search or a newer request
            }
            mainFinished = true;
        }
        List<InfoLine> exact = new ArrayList<>();
        for (InfoLine l : r.lines()) {
            if (l.bound() == InfoLine.Bound.EXACT) {
                exact.add(l);
            }
        }
        if (exact.isEmpty()) {
            exact.addAll(r.lines());
        }
        exact.sort((a, b) -> Integer.compare(a.multiPv(), b.multiPv()));
        publish(req, new AnalysisUpdate(req.fen, r.depth(), exact, true, null, r.nodes(), r.elapsedMs()));
    }

    private final class Collector {
        final Request req;
        final int k;
        final int floor;
        final TreeMap<Integer, InfoLine> lines = new TreeMap<>();
        final long t0 = System.nanoTime();

        Collector(Request req, int k, int floor) {
            this.req = req;
            this.k = k;
            this.floor = floor;
        }

        void onInfo(InfoLine info) {
            if (info.bound() != InfoLine.Bound.EXACT || info.multiPv() > k || info.depth() < floor) {
                return;
            }
            AnalysisUpdate u;
            synchronized (this) {
                lines.put(info.multiPv(), info);
                if (info.multiPv() != k) {
                    return; // wait for the whole set of lines of this iteration
                }
                List<InfoLine> snapshot = new ArrayList<>(lines.values());
                u = new AnalysisUpdate(req.fen, info.depth(), snapshot, false, null, info.nodes(),
                        (System.nanoTime() - t0) / 1_000_000);
            }
            publish(req, u);
        }
    }

    private void publish(Request req, AnalysisUpdate u) {
        synchronized (this) {
            if (req.generation != generation) {
                return;
            }
            lastUpdate = u;
        }
        pending = u;
        pendingRequest = req;
        // No throttling for the first update of a new position, the first one at the verdict depth and the
        // final one: they matter for the LED latency. The others are coalesced (at most one per interval).
        int minDepth = budgetSupplier.get().coachMinDepth();
        boolean urgent = u.finished() || req.generation != lastDeliveredGeneration
                || (u.depth() >= minDepth && lastDeliveredDepth < minDepth);
        if (urgent) {
            EngineEvents.EXECUTOR.execute(this::deliver);
        } else if (deliveryScheduled.compareAndSet(false, true)) {
            long sinceLast = (System.nanoTime() - lastDeliveryNanos) / 1_000_000;
            EngineEvents.EXECUTOR.schedule(() -> {
                deliveryScheduled.set(false);
                deliver();
            }, Math.max(0, MIN_INTERVAL_MS - sinceLast), TimeUnit.MILLISECONDS);
        }
    }

    /** Engine event thread only. */
    private void deliver() {
        AnalysisUpdate u = pending;
        Request req = pendingRequest;
        if (u == null || req == null || u == lastDelivered) {
            return;
        }
        Listener l;
        synchronized (this) {
            if (req.generation != generation || current == null) {
                return; // stale
            }
            l = current.listener;
        }
        lastDelivered = u;
        lastDeliveryNanos = System.nanoTime();
        lastDeliveredGeneration = req.generation;
        lastDeliveredDepth = u.depth();
        if (l != null) {
            safeNotify(l, u);
        }
        for (Listener o : observers) {
            safeNotify(o, u);
        }
    }

    private static void safeNotify(Listener l, AnalysisUpdate u) {
        try {
            l.onUpdate(u);
        } catch (RuntimeException e) {
            log.warn("analysis listener failed: {}", e.toString());
        }
    }
}
