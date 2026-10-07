package org.example.javachess.Engine;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.example.javachess.Utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns every engine process of the application and implements {@link EngineSelection}.
 *
 * <p>Processes (each started lazily, then reused; never one per move):</p>
 * <ul>
 *   <li><b>analysis</b>: Stockfish shared by the eval bar, live analysis and the LED coach
 *       ({@link PositionAnalyzer}, {@link MoveCoach}); its hash is reused from move to move;</li>
 *   <li><b>bot</b>: the opponent of the active profile (Stockfish with Skill Level, or lc0 + Maia weights);
 *       swapped in the background when the profile changes, even in the middle of a game;</li>
 *   <li><b>review</b>: Stockfish for the full game review ({@code GameAnalyzer}), closed after 2 idle minutes.</li>
 * </ul>
 * Nothing here blocks the JavaFX thread; observable properties are updated on it.
 */
public final class EngineManager implements EngineSelection {

    private static final Logger log = LoggerFactory.getLogger(EngineManager.class);

    public static final String STOCKFISH = "stockfish";
    public static final String STOCKFISH_LITE = "stockfish-lite";
    public static final String MAIA_1100 = "maia-1100";
    public static final String MAIA_1500 = "maia-1500";
    public static final String MAIA_1900 = "maia-1900";
    public static final String CONFIG_KEY = "engine.profile";

    private static volatile EngineManager instance;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(UciClient.daemonFactory("engine-manager"));
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(UciClient.daemonFactory("engine-manager-timer"));
    private final ReadOnlyObjectWrapper<EngineProfile> active = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyObjectWrapper<EngineStatus> status = new ReadOnlyObjectWrapper<>();

    private volatile List<EngineProfile> profiles;
    private volatile EngineProfile activeProfile;
    private volatile EngineLocator.Lookup stockfishLookup;
    private volatile EngineLocator.Lookup lc0Lookup;

    private UciClient analysis;                         // guarded by this
    private Budget analysisBudget;                       // guarded by this
    private volatile CompletableFuture<UciClient> bot;   // replaced on profile change
    private volatile String botProfileId;
    private UciClient review;                            // guarded by this
    private ScheduledFuture<?> reviewIdleClose;          // guarded by this

    public static EngineManager get() {
        EngineManager m = instance;
        if (m == null) {
            synchronized (EngineManager.class) {
                m = instance;
                if (m == null) {
                    m = new EngineManager(true);
                    instance = m;
                }
            }
        }
        return m;
    }

    private final boolean persist;
    private final List<Runnable> reconfigureHooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Runs {@code hook} after the analysis engine options changed (profile tier switch). */
    void onAnalysisReconfigured(Runnable hook) {
        reconfigureHooks.add(hook);
    }

    /** @param persist save the chosen profile in config.properties (false in tests) */
    EngineManager(boolean persist) {
        this.persist = persist;
        refreshProfiles();
        String saved = ConfigManager.getProperty(CONFIG_KEY, STOCKFISH);
        EngineProfile initial = findProfile(saved).filter(EngineProfile::available)
                .or(() -> profiles.stream().filter(EngineProfile::available).findFirst())
                .orElse(profiles.get(0));
        if (!initial.id().equals(saved)) {
            log.warn("engine profile '{}' not available, using '{}'", saved, initial.id());
        }
        activeProfile = initial;
        active.set(initial);
        status.set(initial.available()
                ? new EngineStatus(EngineStatus.State.READY, initial.id(), initial.displayName())
                : EngineStatus.error(initial.id(), stockfishLookup.describeMissing()));
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownNow, "engine-shutdown"));
        log.info("engines: stockfish={}, lc0={}, active profile={}",
                stockfishLookup.path().map(Path::toString).orElse("missing"),
                lc0Lookup.path().map(Path::toString).orElse("missing"), initial.id());
    }

    // ------------------------------------------------------------------------------------------
    // EngineSelection

    @Override
    public List<EngineProfile> profiles() {
        return profiles;
    }

    @Override
    public ReadOnlyObjectProperty<EngineProfile> activeProfileProperty() {
        return active.getReadOnlyProperty();
    }

    /** Engine state for the UI (loading / ready / error with message). Updated on the JavaFX thread. */
    @Override
    public ReadOnlyObjectProperty<EngineStatus> statusProperty() {
        return status.getReadOnlyProperty();
    }

    /** Active profile, safe to read from any thread. */
    public EngineProfile activeProfile() {
        return activeProfile;
    }

    @Override
    public void select(String profileId) {
        Optional<EngineProfile> found = findProfile(profileId);
        if (found.isEmpty()) {
            log.warn("unknown engine profile '{}'", profileId);
            return;
        }
        EngineProfile p = found.get();
        if (!p.available()) {
            log.warn("engine profile '{}' is not available on this machine", profileId);
            setStatus(EngineStatus.error(p.id(), missingMessage(p)));
            return;
        }
        EngineProfile previous = activeProfile;
        activeProfile = p;
        Fx.run(() -> active.set(p));
        exec.execute(() -> {
            if (persist) {
                ConfigManager.setProperty(CONFIG_KEY, p.id());
            }
            boolean tierChanged = isLite(previous) != isLite(p);
            if (tierChanged) {
                reconfigureAnalysis();
            }
            ensureBot(p);
        });
    }

    /** Re-reads which binaries exist (e.g. after running the install script). */
    public void refreshProfiles() {
        stockfishLookup = EngineLocator.stockfish();
        lc0Lookup = EngineLocator.lc0();
        boolean sf = stockfishLookup.path().isPresent();
        boolean lc0 = lc0Lookup.path().isPresent();
        List<EngineProfile> list = new ArrayList<>();
        list.add(new EngineProfile(STOCKFISH, "Stockfish", "Massima forza · analisi profonda", sf));
        list.add(new EngineProfile(STOCKFISH_LITE, "Stockfish Lite",
                "Leggero · 1 thread, ideale su Raspberry Pi 4", sf));
        for (String elo : new String[] { "1100", "1500", "1900" }) {
            boolean weights = Files.isRegularFile(maiaWeights(elo));
            list.add(new EngineProfile("maia-" + elo, "Maia " + elo,
                    "Stile umano · circa " + elo + " Elo (lc0)", lc0 && weights));
        }
        profiles = List.copyOf(list);
    }

    // ------------------------------------------------------------------------------------------
    // Engines

    /** Search budgets of the active tier (full or lite). */
    public Budget budget() {
        return isLite(activeProfile) ? Budget.lite() : Budget.full();
    }

    /** Shared analysis engine (started lazily). Throws {@link EngineException} if Stockfish is missing. */
    public synchronized UciClient analysisClient() {
        if (analysis == null || analysis.isClosed()) {
            Path sf = stockfishLookup.path().orElseThrow(() -> {
                setStatus(EngineStatus.error(activeProfile.id(), stockfishLookup.describeMissing()));
                return new EngineException(stockfishLookup.describeMissing());
            });
            analysisBudget = budget();
            analysis = new UciClient(EngineSpec.of("analysis", sf, stockfishOptions(analysisBudget.threads(),
                    analysisBudget.hashMb())));
            analysis.start().exceptionally(t -> {
                setStatus(EngineStatus.error(activeProfile.id(), "Stockfish non si avvia: " + rootMessage(t)));
                return null;
            });
        }
        return analysis;
    }

    /**
     * Engine for the full-game review. Reused between reviews and closed after two idle minutes;
     * call {@link #releaseReviewClient()} when done.
     */
    public synchronized UciClient acquireReviewClient() {
        if (reviewIdleClose != null) {
            reviewIdleClose.cancel(false);
            reviewIdleClose = null;
        }
        if (review == null || review.isClosed()) {
            Path sf = stockfishLookup.path().orElseThrow(() -> new EngineException(stockfishLookup.describeMissing()));
            Budget b = budget();
            review = new UciClient(EngineSpec.of("review", sf, stockfishOptions(b.threads(), Math.max(32, b.hashMb()))));
        }
        return review;
    }

    public synchronized void releaseReviewClient() {
        if (review != null && reviewIdleClose == null) {
            UciClient r = review;
            reviewIdleClose = timer.schedule(() -> {
                synchronized (EngineManager.this) {
                    if (review == r) {
                        review = null;
                        reviewIdleClose = null;
                    }
                }
                r.closeAsync();
            }, 2, TimeUnit.MINUTES);
        }
    }

    /**
     * Asks the bot of the active profile for a move. Never blocks; the future completes with the UCI move,
     * or exceptionally (engine missing/crashed). A profile switch in progress is waited for, and a request
     * interrupted by a switch is retried once on the new engine.
     *
     * @param fen        current position
     * @param skillLevel Stockfish "Skill Level" 0..20 (ignored by Maia)
     */
    public CompletableFuture<String> botMove(String fen, int skillLevel) {
        return botMoveOnce(fen, skillLevel).handle((move, err) -> {
            if (err == null) {
                return CompletableFuture.completedFuture(move);
            }
            log.warn("bot move failed ({}), retrying once", rootMessage(err));
            return botMoveOnce(fen, skillLevel);
        }).thenCompose(f -> f);
    }

    private CompletableFuture<String> botMoveOnce(String fen, int skillLevel) {
        EngineProfile p = activeProfile;
        return ensureBot(p).thenCompose(client -> {
            SearchLimits limits;
            if (isMaia(p)) {
                limits = SearchLimits.nodes(Math.max(1, ConfigManager.getIntProperty("maia.nodes", 1))).withTimeout(15_000);
            } else {
                client.setOption("Skill Level", String.valueOf(Math.max(0, Math.min(20, skillLevel))));
                int movetime = Math.max(100, ConfigManager.getIntProperty("game.bot.movetime", 2000));
                Budget b = isLite(p) ? Budget.lite() : Budget.full();
                limits = SearchLimits.movetime(Math.min(movetime, b.botMaxMovetimeMs()));
                if (b.botMaxNodes() > 0) {
                    limits = limits.withNodes(b.botMaxNodes());
                }
                limits = limits.withTimeout(limits.movetimeMs() + 3_000L);
            }
            long t0 = System.nanoTime();
            return client.search(fen, limits).result().thenApply(r -> {
                log.info("[bot {}] {} in {} ms (depth {}, {} nodes)", p.id(), r.bestMove(),
                        (System.nanoTime() - t0) / 1_000_000, r.depth(), r.nodes());
                if (r.bestMove() == null) {
                    throw new CompletionException(new EngineException("bot returned no move"));
                }
                return r.bestMove();
            });
        });
    }

    /** Returns (starting if needed) the bot engine for a profile; switching closes the old one. */
    private synchronized CompletableFuture<UciClient> ensureBot(EngineProfile p) {
        CompletableFuture<UciClient> current = bot;
        if (current != null && p.id().equals(botProfileId) && !current.isCompletedExceptionally()) {
            UciClient c = current.getNow(null);
            if (c == null || !c.isClosed()) {
                return current;
            }
        }
        CompletableFuture<UciClient> old = current;
        botProfileId = p.id();
        setStatus(EngineStatus.loading(p.id(), p.displayName()));
        CompletableFuture<UciClient> next = new CompletableFuture<>();
        bot = next;
        exec.execute(() -> {
            try {
                UciClient c = new UciClient(botSpec(p));
                c.start().get(c.spec().readyTimeoutMs() * 2 + 1_000, TimeUnit.MILLISECONDS);
                next.complete(c);
                if (activeProfile.id().equals(p.id())) {
                    setStatus(EngineStatus.ready(p.id(), p.displayName()));
                }
            } catch (Exception e) {
                String msg = p.displayName() + " non disponibile: " + rootMessage(e);
                log.error(msg);
                next.completeExceptionally(new EngineException(msg, e));
                setStatus(EngineStatus.error(p.id(), msg));
            }
            if (old != null) {
                old.thenAccept(UciClient::closeAsync);
            }
        });
        return next;
    }

    private void reconfigureAnalysis() {
        UciClient client;
        Budget b = budget();
        synchronized (this) {
            if (analysis == null || (analysisBudget != null && analysisBudget.threads() == b.threads()
                    && analysisBudget.hashMb() == b.hashMb())) {
                return;
            }
            analysisBudget = b;
            client = analysis;
        }
        client.setOption("Threads", String.valueOf(b.threads()));
        client.setOption("Hash", String.valueOf(b.hashMb()));
        // outside our lock: the analyzer calls analysisClient() under its own
        reconfigureHooks.forEach(Runnable::run);
    }

    /** Closes every engine process (used at shutdown; safe to call more than once). */
    public void shutdown() {
        List<UciClient> all = new ArrayList<>();
        synchronized (this) {
            if (analysis != null) {
                all.add(analysis);
            }
            if (review != null) {
                all.add(review);
            }
        }
        CompletableFuture<UciClient> b = bot;
        if (b != null) {
            UciClient c = b.getNow(null);
            if (c != null) {
                all.add(c);
            }
        }
        CompletableFuture.allOf(all.stream().map(UciClient::closeAsync).toArray(CompletableFuture[]::new))
                .orTimeout(3, TimeUnit.SECONDS).exceptionally(t -> null).join();
    }

    private void shutdownNow() {
        try {
            shutdown();
        } catch (Throwable t) {
            // JVM is exiting anyway
        }
    }

    // ------------------------------------------------------------------------------------------
    // Specs and budgets

    private EngineSpec botSpec(EngineProfile p) {
        if (isMaia(p)) {
            Path lc0 = lc0Lookup.path().orElseThrow(() -> new EngineException(lc0Lookup.describeMissing()));
            Map<String, String> opts = new LinkedHashMap<>();
            opts.put("WeightsFile", maiaWeights(p.id().substring(5)).toAbsolutePath().toString());
            opts.put("Threads", "1");
            return EngineSpec.of("bot-" + p.id(), lc0, opts).withReadyTimeout(30_000);
        }
        Path sf = stockfishLookup.path().orElseThrow(() -> new EngineException(stockfishLookup.describeMissing()));
        Budget b = isLite(p) ? Budget.lite() : Budget.full();
        return EngineSpec.of("bot-" + p.id(), sf, stockfishOptions(b.botThreads(), b.botHashMb()));
    }

    static Map<String, String> stockfishOptions(int threads, int hashMb) {
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("Threads", String.valueOf(threads));
        opts.put("Hash", String.valueOf(hashMb));
        return opts;
    }

    private static Path maiaWeights(String elo) {
        return EngineLocator.enginesDir().resolve("maia").resolve("maia-" + elo + ".pb.gz");
    }

    private Optional<EngineProfile> findProfile(String id) {
        return profiles.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    private String missingMessage(EngineProfile p) {
        if (isMaia(p)) {
            return lc0Lookup.path().isEmpty() ? lc0Lookup.describeMissing()
                    : "Pesi Maia mancanti in " + EngineLocator.enginesDir().resolve("maia");
        }
        return stockfishLookup.describeMissing();
    }

    static boolean isLite(EngineProfile p) {
        return p != null && STOCKFISH_LITE.equals(p.id());
    }

    static boolean isMaia(EngineProfile p) {
        return p != null && p.id().startsWith("maia-");
    }

    private void setStatus(EngineStatus s) {
        Fx.run(() -> status.set(s));
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    /**
     * Search budgets for one resource tier. Numbers come from {@code EngineBenchmarkTest} (Stockfish 19, 240 moves
     * of weak self-play, reference depth 20; Pi 5 core ~1/3 and Pi 4 core ~1/7 of an Apple M-series core):
     * <ul>
     *   <li>depth 12 is the shallowest depth with no missed and no invented blunder vs the reference (depth 10
     *       missed 3/240, depth 8 missed 2); cold-hash cost after a move: p50 10k / p95 33k nodes, i.e.
     *       ~80 / 310 ms on one Pi 5 core and ~190 / 730 ms on one Pi 4 core;</li>
     *   <li>depth 16 raises the ok/error agreement to 96% (when the position before was searched deeper) but costs
     *       ~1.1 / 2.8 s on one Pi 5 core: used only as a capped confirmation.</li>
     * </ul>
     *
     * @param threads            analysis engine threads
     * @param hashMb             analysis engine hash
     * @param liveMaxDepth       cap for the eval bar / live analysis depth
     * @param coachMinDepth      minimum depth before a move verdict is emitted
     * @param coachConfirmDepth  depth of the confirmation verdict
     * @param coachCapMs         time cap of the confirmation
     * @param candidateNodes     node budget of the lift-hint search (all moves of one piece, MultiPV)
     * @param candidateCapMs     time cap of the lift-hint search
     * @param botThreads         bot engine threads
     * @param botHashMb          bot engine hash
     * @param botMaxMovetimeMs   cap for the bot thinking time
     * @param botMaxNodes        node cap for the bot (0 = none)
     * @param reviewMovetimeCapMs per-position time cap of the full game review (0 = none)
     */
    public record Budget(int threads, int hashMb, int liveMaxDepth, int coachMinDepth, int coachConfirmDepth,
                         long coachCapMs, long candidateNodes, long candidateCapMs, int botThreads, int botHashMb,
                         int botMaxMovetimeMs, long botMaxNodes, int reviewMovetimeCapMs) {

        /** Pi 5 / desktop: 2 analysis threads by default (config stockfish.threads), deep live analysis. */
        public static Budget full() {
            int cores = Runtime.getRuntime().availableProcessors();
            int threads = ConfigManager.getIntProperty("stockfish.threads", Math.max(1, Math.min(cores / 2, 4)));
            int hash = ConfigManager.getIntProperty("stockfish.hash", 64);
            return new Budget(threads, hash, 30, 12, 16, 2_500, 150_000, 600, 1, 32, 10_000, 0, 4_000);
        }

        /** Pi 4 / weak hardware: 1 thread everywhere, same verdict depth (12), cheaper confirmation and hints. */
        public static Budget lite() {
            return new Budget(1, 16, 16, 12, 14, 1_500, 40_000, 500, 1, 16, 1_000, 300_000, 1_500);
        }
    }
}
