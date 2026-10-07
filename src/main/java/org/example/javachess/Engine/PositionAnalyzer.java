package org.example.javachess.Engine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    private static volatile PositionAnalyzer instance;

    private final Supplier<UciClient> clientSupplier;
    private final Supplier<EngineManager.Budget> budgetSupplier;
    private final CopyOnWriteArrayList<Listener> observers = new CopyOnWriteArrayList<>();

    // guarded by this
    private Request current;
    private int generation;
    private UciClient.SearchHandle mainHandle;
    private UciClient.SearchHandle candidateHandle;
    private boolean mainFinished;
    private AnalysisUpdate lastUpdate;

    // delivery coalescing
    private volatile AnalysisUpdate pending;
    private volatile Request pendingRequest;
    private final AtomicBoolean deliveryScheduled = new AtomicBoolean();
    private volatile long lastDeliveryNanos;

    private record Request(String fen, int depth, int multiPv, Listener listener, int legalMoves, boolean inCheck,
                           int generation) {
    }

    public static PositionAnalyzer get() {
        PositionAnalyzer a = instance;
        if (a == null) {
            synchronized (PositionAnalyzer.class) {
                a = instance;
                if (a == null) {
                    EngineManager m = EngineManager.get();
                    a = new PositionAnalyzer(m::analysisClient, m::budget);
                    m.onAnalysisReconfigured(a::refresh);
                    instance = a;
                }
            }
        }
        return a;
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

    /**
     * Scores a set of moves of {@code fen} in one search ({@code go searchmoves ...} with MultiPV = number of
     * moves), interrupting the live analysis, which resumes afterwards on the same position (warm hash).
     */
    public CompletableFuture<SearchResult> scoreMoves(String fen, List<String> moves, long nodes, long capMs) {
        if (moves.isEmpty()) {
            return CompletableFuture.completedFuture(new SearchResult(null, null, List.of(), 0, 0, 0, false));
        }
        UciClient.SearchHandle h;
        int g;
        synchronized (this) {
            g = generation;
            UciClient client;
            try {
                client = clientSupplier.get();
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            SearchLimits limits = SearchLimits.nodes(nodes).withMultiPv(moves.size()).withSearchMoves(moves)
                    .withTimeout(capMs);
            h = client.search(fen, List.of(), limits, null);
            candidateHandle = h;
        }
        UciClient.SearchHandle handle = h;
        return h.result().whenComplete((r, e) -> resumeAfterCandidates(g, handle));
    }

    /** Stops a running {@link #scoreMoves} search early (its future completes with partial lines). */
    public synchronized void cancelScoring() {
        if (candidateHandle != null) {
            candidateHandle.cancel();
        }
    }

    private synchronized void resumeAfterCandidates(int g, UciClient.SearchHandle handle) {
        if (candidateHandle == handle) {
            candidateHandle = null;
        }
        if (g == generation && current != null && !mainFinished && candidateHandle == null) {
            startMain(current);
        }
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
        if (deliveryScheduled.compareAndSet(false, true)) {
            long sinceLast = (System.nanoTime() - lastDeliveryNanos) / 1_000_000;
            long delay = u.finished() ? 0 : Math.max(0, MIN_INTERVAL_MS - sinceLast);
            EngineEvents.EXECUTOR.schedule(this::deliver, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void deliver() {
        deliveryScheduled.set(false);
        AnalysisUpdate u = pending;
        Request req = pendingRequest;
        if (u == null || req == null) {
            return;
        }
        Listener l;
        synchronized (this) {
            if (req.generation != generation || current == null) {
                return; // stale
            }
            l = current.listener;
        }
        lastDeliveryNanos = System.nanoTime();
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
