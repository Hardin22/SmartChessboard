package org.example.javachess.Engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Robust asynchronous client for one UCI engine process.
 *
 * <ul>
 *   <li>All commands are serialised on a private daemon "control" thread, so callers never block
 *       (every public method returns immediately, results come back as {@link CompletableFuture}).</li>
 *   <li>A dedicated daemon reader thread parses engine output; no polling, no sleeps.</li>
 *   <li>Starting a search while another one runs sends {@code stop} and waits for its {@code bestmove}
 *       first, so output of an old search can never be attributed to a new one.</li>
 *   <li>Handshakes ({@code uciok}, {@code readyok}) and {@code stop} have timeouts; a hung or dead engine
 *       is killed and transparently restarted on the next request (bounded restart budget).</li>
 *   <li>The process is started lazily on first use (or eagerly with {@link #start()}).</li>
 * </ul>
 * Info callbacks run on the reader thread: they must be quick and must not call back into the client
 * synchronously (hand work to another thread).
 */
public final class UciClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UciClient.class);

    /** How long we wait for "bestmove" after sending "stop". */
    static final long STOP_TIMEOUT_MS = 3_000;
    private static final int MAX_RESTARTS = 3;
    private static final long RESTART_WINDOW_MS = 60_000;

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("uci-timer"));

    private final EngineSpec spec;
    private final ExecutorService control;

    // State below is written by the control thread; volatile because the reader thread reads it.
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean handshakeDone;
    private volatile boolean closed;
    private volatile String engineName = "";
    private volatile ActiveSearch current;
    private volatile CompletableFuture<Void> uciOk = new CompletableFuture<>();
    private volatile CompletableFuture<Void> readyOk = new CompletableFuture<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final Set<String> optionNames = ConcurrentHashMap.newKeySet();

    // Control-thread only.
    private final Map<String, String> desiredOptions = new LinkedHashMap<>();
    private final Map<String, String> appliedOptions = new LinkedHashMap<>();
    private final Deque<Long> restarts = new ArrayDeque<>();
    private int processesStarted;

    public UciClient(EngineSpec spec) {
        this.spec = spec;
        this.desiredOptions.putAll(spec.options());
        this.control = Executors.newSingleThreadExecutor(daemonFactory("uci-" + spec.name()));
    }

    public EngineSpec spec() {
        return spec;
    }

    /** Engine name from "id name", empty before the handshake. */
    public String engineName() {
        return engineName;
    }

    public boolean isAlive() {
        Process p = process;
        return !closed && p != null && p.isAlive() && handshakeDone;
    }

    public boolean isClosed() {
        return closed;
    }

    /** True if the engine declared this option during the handshake (case-insensitive). */
    public boolean hasOption(String name) {
        return optionNames.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Number of processes launched so far (1 = never restarted). Mostly for tests and diagnostics. */
    public int processesStarted() {
        return submit(() -> processesStarted).join();
    }

    /** Starts the process and completes the handshake (uci, options, isready). */
    public CompletableFuture<Void> start() {
        return submit(() -> {
            ensureStarted();
            return null;
        });
    }

    /**
     * Sets a UCI option. Applied immediately when idle, otherwise right before the next search.
     * Remembered and re-applied after a restart.
     */
    public CompletableFuture<Void> setOption(String name, String value) {
        return submit(() -> {
            desiredOptions.put(name, value);
            if (current == null && isAlive()) {
                applyOptions();
            }
            return null;
        });
    }

    /** Sends {@code ucinewgame} (clears the hash) followed by {@code isready}. */
    public CompletableFuture<Void> newGame() {
        return submit(() -> {
            ensureStarted();
            finishCurrent();
            send("ucinewgame");
            waitReady();
            return null;
        });
    }

    /** Stops the running search, if any; its future completes with the best result so far. */
    public CompletableFuture<Void> stop() {
        return submit(() -> {
            finishCurrent();
            return null;
        });
    }

    /**
     * Starts a search. A running search is stopped first.
     *
     * @param fen    position
     * @param moves  moves to play from the FEN (may be empty)
     * @param limits search limits
     * @param onInfo optional listener for every parsed info line (reader thread)
     */
    public SearchHandle search(String fen, List<String> moves, SearchLimits limits, Consumer<InfoLine> onInfo) {
        if (fen == null || fen.isBlank() || fen.indexOf('\n') >= 0 || fen.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid FEN: " + fen);
        }
        ActiveSearch s = new ActiveSearch(fen, moves == null ? List.of() : List.copyOf(moves), limits, onInfo);
        if (closed) {
            s.future.completeExceptionally(new EngineException("engine " + spec.name() + " is closed"));
            return s;
        }
        try {
            control.execute(() -> runSearch(s));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            s.future.completeExceptionally(new EngineException("engine " + spec.name() + " is closed"));
        }
        return s;
    }

    public SearchHandle search(String fen, SearchLimits limits) {
        return search(fen, List.of(), limits, null);
    }

    /** Handle of a running or queued search. */
    public interface SearchHandle {
        CompletableFuture<SearchResult> result();

        /** Stops the search (result completes with what was found) or cancels it if not started yet. */
        void cancel();
    }

    /** Quits the engine and releases threads without blocking the caller. */
    public CompletableFuture<Void> closeAsync() {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        closed = true;
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            control.execute(() -> {
                try {
                    ActiveSearch s = current;
                    if (s != null) {
                        trySend("stop");
                        failSearch(s, new EngineException("engine " + spec.name() + " closed"));
                    }
                    destroyProcess(true);
                } finally {
                    done.complete(null);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            done.complete(null);
        }
        control.shutdown();
        return done;
    }

    /** Quits the engine, waiting at most ~3 s. */
    @Override
    public void close() {
        try {
            closeAsync().get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("[{}] close did not complete cleanly: {}", spec.name(), e.toString());
            Process p = process;
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Control thread

    private <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (closed) {
            f.completeExceptionally(new EngineException("engine " + spec.name() + " is closed"));
            return f;
        }
        try {
            control.execute(() -> {
                try {
                    f.complete(task.call());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            f.completeExceptionally(new EngineException("engine " + spec.name() + " is closed"));
        }
        return f;
    }

    private void runSearch(ActiveSearch s) {
        if (s.cancelled) {
            s.future.completeExceptionally(new CancellationException("search cancelled before start"));
            return;
        }
        try {
            ensureStarted();
            finishCurrent();
            if (s.cancelled) {
                s.future.completeExceptionally(new CancellationException("search cancelled before start"));
                return;
            }
            if (hasOption("MultiPV")) {
                desiredOptions.put("MultiPV", String.valueOf(s.limits.multiPv()));
            }
            applyOptions();
            StringBuilder pos = new StringBuilder("position fen ").append(s.fen);
            if (!s.moves.isEmpty()) {
                pos.append(" moves");
                for (String m : s.moves) {
                    pos.append(' ').append(m);
                }
            }
            send(pos.toString());
            s.startNanos = System.nanoTime();
            s.generation = generation.get();
            current = s;
            send(s.limits.toGoCommand());
            if (s.limits.timeoutMs() > 0) {
                s.timeoutTask = TIMER.schedule(() -> onSearchTimeout(s), s.limits.timeoutMs(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            if (current == s) {
                current = null;
            }
            s.future.completeExceptionally(e instanceof EngineException ? e
                    : new EngineException("search failed on " + spec.name() + ": " + e.getMessage(), e));
        }
    }

    private void onSearchTimeout(ActiveSearch s) {
        try {
            control.execute(() -> {
                if (current == s && !s.stopSent) {
                    log.debug("[{}] search cap {} ms reached, stopping", spec.name(), s.limits.timeoutMs());
                    s.stopSent = true;
                    trySend("stop");
                    TIMER.schedule(() -> control.execute(() -> {
                        if (current == s) {
                            log.warn("[{}] engine ignored stop for {} ms, restarting it", spec.name(), STOP_TIMEOUT_MS);
                            completePartial(s);
                            destroyProcess(false);
                        }
                    }), STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed meanwhile
        }
    }

    /** Stops the running search (if any) and waits for its bestmove; kills a hung engine. */
    private void finishCurrent() {
        ActiveSearch prev = current;
        if (prev == null) {
            return;
        }
        if (!prev.stopSent) {
            prev.stopSent = true;
            trySend("stop");
        }
        try {
            prev.future.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[{}] no bestmove {} ms after stop, restarting engine", spec.name(), STOP_TIMEOUT_MS);
            completePartial(prev);
            destroyProcess(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException e) {
            // the previous search failed (crash): nothing to wait for
        }
        if (current == prev) {
            current = null;
        }
    }

    private void ensureStarted() {
        if (closed) {
            throw new EngineException("engine " + spec.name() + " is closed");
        }
        Process p = process;
        if (p != null && p.isAlive() && handshakeDone) {
            return;
        }
        if (p != null) {
            destroyProcess(false);
        }
        if (processesStarted > 0) {
            long now = System.currentTimeMillis();
            while (!restarts.isEmpty() && now - restarts.peekFirst() > RESTART_WINDOW_MS) {
                restarts.pollFirst();
            }
            if (restarts.size() >= MAX_RESTARTS) {
                throw new EngineException("engine " + spec.name() + " crashed " + MAX_RESTARTS
                        + " times in a minute, giving up for now");
            }
            restarts.addLast(now);
            log.warn("[{}] restarting engine process", spec.name());
        }
        launch();
    }

    private void launch() {
        ProcessBuilder pb = new ProcessBuilder(spec.command());
        pb.redirectErrorStream(true);
        if (spec.workingDir() != null) {
            pb.directory(spec.workingDir().toFile());
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new EngineException("cannot start " + spec.command().get(0) + ": " + e.getMessage(), e);
        }
        processesStarted++;
        int gen = generation.incrementAndGet();
        process = p;
        handshakeDone = false;
        appliedOptions.clear();
        writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.US_ASCII));
        uciOk = new CompletableFuture<>();
        Thread reader = daemonFactory("uci-" + spec.name() + "-reader").newThread(() -> readLoop(p, gen));
        reader.start();
        try {
            send("uci");
            await(uciOk, spec.readyTimeoutMs(), "uciok");
            applyOptions();
            waitReady();
            handshakeDone = true;
            log.info("[{}] engine ready: {} (pid {})", spec.name(), engineName, p.pid());
        } catch (RuntimeException e) {
            destroyProcess(false);
            throw e;
        }
    }

    private void applyOptions() {
        boolean changed = false;
        for (Map.Entry<String, String> e : desiredOptions.entrySet()) {
            if (e.getValue().equals(appliedOptions.get(e.getKey()))) {
                continue;
            }
            if (!optionNames.isEmpty() && !hasOption(e.getKey())) {
                log.debug("[{}] engine has no option '{}', skipped", spec.name(), e.getKey());
                appliedOptions.put(e.getKey(), e.getValue());
                continue;
            }
            send("setoption name " + e.getKey() + " value " + e.getValue());
            appliedOptions.put(e.getKey(), e.getValue());
            changed = true;
        }
        if (changed && handshakeDone) {
            waitReady();
        }
    }

    private void waitReady() {
        readyOk = new CompletableFuture<>();
        send("isready");
        await(readyOk, spec.readyTimeoutMs(), "readyok");
    }

    private void await(CompletableFuture<Void> f, long timeoutMs, String what) {
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new EngineException("engine " + spec.name() + " did not answer " + what + " within " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted waiting for " + what);
        } catch (ExecutionException e) {
            throw new EngineException("engine " + spec.name() + " died before " + what, e.getCause());
        }
    }

    private void send(String cmd) {
        BufferedWriter w = writer;
        if (w == null) {
            throw new EngineException("engine " + spec.name() + " not running");
        }
        try {
            if (log.isTraceEnabled()) {
                log.trace("[{}] > {}", spec.name(), cmd);
            }
            w.write(cmd);
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            throw new EngineException("engine " + spec.name() + " pipe closed: " + e.getMessage(), e);
        }
    }

    private void trySend(String cmd) {
        try {
            send(cmd);
        } catch (EngineException e) {
            log.debug("[{}] could not send '{}': {}", spec.name(), cmd, e.getMessage());
        }
    }

    private void destroyProcess(boolean graceful) {
        Process p = process;
        process = null;
        handshakeDone = false;
        generation.incrementAndGet(); // output of the old process is ignored from now on
        if (p == null) {
            writer = null;
            return;
        }
        if (graceful) {
            trySend("quit");
        }
        writer = null;
        try {
            if (!graceful || !p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(1, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        ActiveSearch s = current;
        if (s != null) {
            failSearch(s, new EngineException("engine " + spec.name() + " stopped"));
        }
    }

    private void completePartial(ActiveSearch s) {
        if (current == s) {
            current = null;
        }
        cancelTimer(s);
        InfoLine best;
        List<InfoLine> lines;
        List<InfoLine> perMove;
        synchronized (s) {
            lines = new ArrayList<>(s.lines.values());
            perMove = new ArrayList<>(s.byMove.values());
        }
        best = lines.isEmpty() ? null : lines.get(0);
        s.future.complete(new SearchResult(best == null ? null : best.move(), null, lines, s.maxDepth, s.nodes,
                elapsedMs(s), true, perMove));
    }

    private void failSearch(ActiveSearch s, Throwable t) {
        if (current == s) {
            current = null;
        }
        cancelTimer(s);
        s.future.completeExceptionally(t);
    }

    private static void cancelTimer(ActiveSearch s) {
        ScheduledFuture<?> t = s.timeoutTask;
        if (t != null) {
            t.cancel(false);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reader thread

    private void readLoop(Process p, int gen) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (gen != generation.get()) {
                    continue; // stale process being torn down
                }
                try {
                    onLine(line);
                } catch (RuntimeException e) {
                    log.warn("[{}] error handling engine line '{}': {}", spec.name(), line, e.toString());
                }
            }
        } catch (IOException e) {
            // stream closed: process killed or crashed
        }
        if (gen == generation.get() && !closed) {
            Integer exit = null;
            try {
                if (p.waitFor(200, TimeUnit.MILLISECONDS)) {
                    exit = p.exitValue();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.warn("[{}] engine process ended unexpectedly (exit {})", spec.name(), exit);
            handshakeDone = false;
            EngineException crash = new EngineException("engine " + spec.name() + " crashed (exit " + exit + ")");
            uciOk.completeExceptionally(crash);
            readyOk.completeExceptionally(crash);
            ActiveSearch s = current;
            if (s != null) {
                failSearch(s, crash);
            }
        }
    }

    private void onLine(String raw) {
        String line = raw.strip();
        if (log.isTraceEnabled()) {
            log.trace("[{}] < {}", spec.name(), line);
        }
        if (line.startsWith("info ")) {
            onInfo(line);
        } else if (line.startsWith("bestmove")) {
            onBestMove(line);
        } else if (line.equals("readyok")) {
            readyOk.complete(null);
        } else if (line.equals("uciok")) {
            uciOk.complete(null);
        } else if (line.startsWith("option name ")) {
            int typeIdx = line.indexOf(" type ");
            String name = typeIdx > 0 ? line.substring(12, typeIdx) : line.substring(12);
            optionNames.add(name.trim().toLowerCase(Locale.ROOT));
        } else if (line.startsWith("id name ")) {
            engineName = line.substring(8).trim();
        } else if (!line.isEmpty() && !line.startsWith("id ")) {
            if (line.toLowerCase(Locale.ROOT).contains("error")) {
                log.warn("[{}] {}", spec.name(), line);
            } else {
                log.debug("[{}] {}", spec.name(), line);
            }
        }
    }

    private void onInfo(String line) {
        ActiveSearch s = current;
        if (s == null) {
            return;
        }
        InfoLine info = InfoLine.parse(line).orElse(null);
        if (info == null) {
            return;
        }
        synchronized (s) {
            if (info.bound() == InfoLine.Bound.EXACT || !s.lines.containsKey(info.multiPv())) {
                s.lines.put(info.multiPv(), info);
            }
            if (info.bound() == InfoLine.Bound.EXACT || !s.byMove.containsKey(info.move())) {
                s.byMove.put(info.move(), info);
            }
            if (info.bound() == InfoLine.Bound.EXACT && info.multiPv() == 1) {
                s.maxDepth = Math.max(s.maxDepth, info.depth());
            }
            s.nodes = Math.max(s.nodes, info.nodes());
        }
        Consumer<InfoLine> cb = s.onInfo;
        if (cb != null) {
            try {
                cb.accept(info);
            } catch (RuntimeException e) {
                log.warn("[{}] info listener failed: {}", spec.name(), e.toString());
            }
        }
    }

    private void onBestMove(String line) {
        ActiveSearch s = current;
        if (s == null) {
            log.debug("[{}] stray '{}' ignored", spec.name(), line);
            return;
        }
        String[] t = line.split("\\s+");
        String best = t.length > 1 ? t[1] : null;
        String ponder = null;
        for (int i = 2; i + 1 < t.length; i++) {
            if (t[i].equals("ponder")) {
                ponder = t[i + 1];
            }
        }
        if (best != null && !InfoLine.isUciMove(best)) {
            best = null; // "(none)", "0000", "NULL"
        }
        if (ponder != null && !InfoLine.isUciMove(ponder)) {
            ponder = null;
        }
        current = null;
        cancelTimer(s);
        List<InfoLine> lines;
        List<InfoLine> perMove;
        synchronized (s) {
            lines = new ArrayList<>(s.lines.values());
            perMove = new ArrayList<>(s.byMove.values());
        }
        s.future.complete(new SearchResult(best, ponder, lines, s.maxDepth, s.nodes, elapsedMs(s), s.stopSent, perMove));
    }

    private static long elapsedMs(ActiveSearch s) {
        return s.startNanos == 0 ? 0 : (System.nanoTime() - s.startNanos) / 1_000_000;
    }

    // ------------------------------------------------------------------------------------------

    private final class ActiveSearch implements SearchHandle {
        final String fen;
        final List<String> moves;
        final SearchLimits limits;
        final Consumer<InfoLine> onInfo;
        final CompletableFuture<SearchResult> future = new CompletableFuture<>();
        final TreeMap<Integer, InfoLine> lines = new TreeMap<>();
        final java.util.LinkedHashMap<String, InfoLine> byMove = new java.util.LinkedHashMap<>();
        volatile boolean cancelled;
        volatile boolean stopSent;
        volatile ScheduledFuture<?> timeoutTask;
        long startNanos;
        int generation;
        int maxDepth;
        long nodes;

        ActiveSearch(String fen, List<String> moves, SearchLimits limits, Consumer<InfoLine> onInfo) {
            this.fen = fen;
            this.moves = moves;
            this.limits = limits;
            this.onInfo = onInfo;
        }

        @Override
        public CompletableFuture<SearchResult> result() {
            return future;
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (future.isDone()) {
                return;
            }
            try {
                control.execute(() -> {
                    if (current == this && !stopSent) {
                        stopSent = true;
                        trySend("stop");
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // closed
            }
        }
    }

    static ThreadFactory daemonFactory(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, n.getAndIncrement() == 0 ? name : name + "-" + n.get());
            t.setDaemon(true);
            return t;
        };
    }
}
