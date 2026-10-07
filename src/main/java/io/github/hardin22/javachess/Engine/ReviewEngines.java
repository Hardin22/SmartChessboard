package io.github.hardin22.javachess.Engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Stockfish processes dedicated to game reviews (review screen, archive analysis), separate from the live analysis
 * process so that a review can search several positions in parallel. Sized by {@link EngineManager.ReviewPlan}
 * ({@code workers} processes, each with its own threads and hash), started lazily and closed when idle by
 * {@link EngineManager}.
 *
 * <p>Usage: {@code try (ReviewEngines.Session s = EngineManager.get().reviewEngines().open(true)) { ... }}. Searches
 * are queued and dispatched to the first free worker; {@code search(worker, ...)} pins a search to one worker (to
 * reuse its hash on consecutive positions of the same game). A worker runs one search at a time.</p>
 */
public final class ReviewEngines {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngines.class);

    private final Supplier<EngineSpec> specSupplier;
    private final Supplier<EngineManager.ReviewPlan> planSupplier;
    private final Supplier<PositionAnalyzer> liveSupplier;

    // guarded by this
    private final List<Worker> workers = new ArrayList<>();
    private final Deque<Job> queue = new ArrayDeque<>();
    private int openSessions;
    private long lastUseNanos = System.nanoTime();

    private static final class Worker {
        final int index;
        final UciClient client;
        Job running;

        Worker(int index, UciClient client) {
            this.index = index;
            this.client = client;
        }
    }

    private static final class Job {
        final Session session;
        final int worker; // -1 = any
        final String fen;
        final List<String> moves;
        final SearchLimits limits;
        final CompletableFuture<SearchResult> future = new CompletableFuture<>();
        UciClient.SearchHandle handle;

        Job(Session session, int worker, String fen, List<String> moves, SearchLimits limits) {
            this.session = session;
            this.worker = worker;
            this.fen = fen;
            this.moves = moves == null ? List.of() : List.copyOf(moves);
            this.limits = limits;
        }
    }

    /**
     * @param specSupplier  Stockfish launch spec for one worker (threads and hash from the review plan)
     * @param planSupplier  current review plan (workers, threads, hash)
     * @param liveSupplier  the live analysis to pause during a session, or null
     */
    ReviewEngines(Supplier<EngineSpec> specSupplier, Supplier<EngineManager.ReviewPlan> planSupplier,
                  Supplier<PositionAnalyzer> liveSupplier) {
        this.specSupplier = specSupplier;
        this.planSupplier = planSupplier;
        this.liveSupplier = liveSupplier;
    }

    /** Number of parallel workers a session gets (the plan's, at least 1). */
    public int workers() {
        return Math.max(1, planSupplier.get().workers());
    }

    /**
     * Opens a review session: the workers start a new game ({@code ucinewgame}) and, when {@code pauseLive} is true,
     * the live analysis is paused until the session is closed (its CPU goes to the review).
     */
    public Session open(boolean pauseLive) {
        AutoCloseable hold = null;
        if (pauseLive && liveSupplier != null) {
            try {
                hold = liveSupplier.get().holdLive();
            } catch (RuntimeException e) {
                log.debug("live analysis not paused: {}", e.toString());
            }
        }
        synchronized (this) {
            openSessions++;
            lastUseNanos = System.nanoTime();
            ensureWorkers();
            for (Worker w : workers) {
                if (w.running == null) {
                    w.client.newGame();
                }
            }
        }
        return new Session(hold);
    }

    /** A review in progress; closing it cancels its pending searches and resumes the live analysis. */
    public final class Session implements AutoCloseable {
        private final AutoCloseable hold;
        private volatile boolean closed;

        private Session(AutoCloseable hold) {
            this.hold = hold;
        }

        /** Number of workers searching in parallel for this session. */
        public int workers() {
            synchronized (ReviewEngines.this) {
                return Math.max(1, workers.size());
            }
        }

        /** Searches {@code fen} (+ {@code moves}) on the first free worker. */
        public CompletableFuture<SearchResult> search(String fen, List<String> moves, SearchLimits limits) {
            return search(-1, fen, moves, limits);
        }

        /** Searches on worker {@code worker} (0-based, modulo the worker count; -1 = first free worker). */
        public CompletableFuture<SearchResult> search(int worker, String fen, List<String> moves, SearchLimits limits) {
            Job job = new Job(this, worker, fen, moves, limits);
            if (closed) {
                job.future.completeExceptionally(new CancellationException("review session closed"));
                return job.future;
            }
            synchronized (ReviewEngines.this) {
                queue.add(job);
                pump();
            }
            return job.future;
        }

        /** Sends {@code ucinewgame} to every idle worker (e.g. between two games of an archive batch). */
        public void newGame() {
            synchronized (ReviewEngines.this) {
                for (Worker w : workers) {
                    if (w.running == null) {
                        w.client.newGame();
                    }
                }
            }
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            List<Job> dropped = new ArrayList<>();
            synchronized (ReviewEngines.this) {
                queue.removeIf(j -> {
                    if (j.session == this) {
                        dropped.add(j);
                        return true;
                    }
                    return false;
                });
                for (Worker w : workers) {
                    if (w.running != null && w.running.session == this && w.running.handle != null) {
                        w.running.handle.cancel();
                    }
                }
                openSessions--;
                lastUseNanos = System.nanoTime();
            }
            dropped.forEach(j -> j.future.completeExceptionally(new CancellationException("review session closed")));
            if (hold != null) {
                try {
                    hold.close();
                } catch (Exception e) {
                    log.debug("resume live analysis: {}", e.toString());
                }
            }
        }
    }

    /** Caller holds the lock. */
    private void ensureWorkers() {
        workers.removeIf(w -> w.client.isClosed() && w.running == null);
        int n = workers();
        while (workers.size() < n) {
            EngineSpec base = specSupplier.get();
            EngineSpec spec = new EngineSpec("review-" + workers.size(), base.command(), base.options(),
                    base.workingDir(), base.readyTimeoutMs());
            UciClient c = new UciClient(spec);
            c.start().exceptionally(t -> {
                log.warn("review engine {} did not start: {}", spec.name(), EngineManager.rootMessage(t));
                return null;
            });
            workers.add(new Worker(workers.size(), c));
        }
        for (int i = 0; i < workers.size(); i++) {
            if (workers.get(i).index != i) {
                workers.set(i, new Worker(i, workers.get(i).client));
            }
        }
    }

    /** Caller holds the lock: starts queued jobs on free workers. */
    private void pump() {
        if (queue.isEmpty()) {
            return;
        }
        ensureWorkers();
        for (var it = queue.iterator(); it.hasNext(); ) {
            Job job = it.next();
            Worker w = freeWorkerFor(job);
            if (w == null) {
                continue;
            }
            it.remove();
            start(w, job);
        }
    }

    private Worker freeWorkerFor(Job job) {
        if (job.worker >= 0) {
            Worker w = workers.get(job.worker % workers.size());
            return w.running == null ? w : null;
        }
        for (Worker w : workers) {
            if (w.running == null) {
                return w;
            }
        }
        return null;
    }

    private void start(Worker w, Job job) {
        w.running = job;
        lastUseNanos = System.nanoTime();
        job.handle = w.client.search(job.fen, job.moves, job.limits, null);
        job.handle.result().whenComplete((r, err) -> {
            synchronized (ReviewEngines.this) {
                if (w.running == job) {
                    w.running = null;
                }
                lastUseNanos = System.nanoTime();
                pump();
            }
            if (err != null) {
                job.future.completeExceptionally(err);
            } else {
                job.future.complete(r);
            }
        });
    }

    /** Closes the workers when no session is open and none was used for {@code idleMs}. */
    void closeIfIdle(long idleMs) {
        List<UciClient> toClose = new ArrayList<>();
        synchronized (this) {
            if (openSessions > 0 || workers.isEmpty() || !queue.isEmpty()
                    || (System.nanoTime() - lastUseNanos) / 1_000_000 < idleMs) {
                return;
            }
            for (Worker w : workers) {
                if (w.running != null) {
                    return;
                }
            }
            workers.forEach(w -> toClose.add(w.client));
            workers.clear();
        }
        log.info("closing {} idle review engine(s)", toClose.size());
        toClose.forEach(UciClient::closeAsync);
    }

    /** Closes every worker (application exit). */
    void shutdown() {
        List<UciClient> all = new ArrayList<>();
        synchronized (this) {
            workers.forEach(w -> all.add(w.client));
            workers.clear();
        }
        all.forEach(UciClient::closeAsync);
    }

    /** Live worker processes (diagnostics and tests). */
    synchronized List<UciClient> liveClients() {
        List<UciClient> out = new ArrayList<>();
        for (Worker w : workers) {
            if (!w.client.isClosed()) {
                out.add(w.client);
            }
        }
        return out;
    }
}
