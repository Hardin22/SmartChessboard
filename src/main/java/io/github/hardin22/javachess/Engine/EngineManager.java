package io.github.hardin22.javachess.Engine;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import io.github.hardin22.javachess.Utils.ConfigManager;
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
import java.util.concurrent.TimeUnit;

/**
 * Owns every engine process of the application and implements {@link EngineSelection}.
 *
 * <p>Processes (each started lazily, reused, closed when idle; never one per move), sized by {@link ProcessPlan}:</p>
 * <ul>
 *   <li><b>analysis</b>: Stockfish shared by the eval bar, live analysis, LED coach and full game review
 *       ({@link PositionAnalyzer} schedules them: foreground searches by priority, live analysis in the
 *       background); on 1 GB boards the Stockfish bot runs here too;</li>
 *   <li><b>bot</b>: the opponent of the active profile (Stockfish with Skill Level, or lc0 + Maia weights);
 *       swapped in the background when the profile changes, even in the middle of a game.</li>
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

    private final ProcessPlan plan;
    private UciClient analysis;                         // guarded by this
    private Budget analysisBudget;                       // guarded by this
    private PositionAnalyzer analyzer;                   // guarded by this
    private volatile CompletableFuture<UciClient> bot;   // replaced on profile change
    private volatile String botProfileId;

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
        this(persist, ProcessPlan.detect());
    }

    EngineManager(boolean persist, ProcessPlan plan) {
        this.persist = persist;
        this.plan = plan;
        refreshProfiles();
        String saved = ConfigManager.getProperty(CONFIG_KEY, "").trim();
        if (saved.isEmpty()) {
            saved = defaultProfileForThisMachine();
        }
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
        long reapEvery = Math.max(1_000, Math.min(30_000, plan.idleCloseMs() / 4));
        timer.scheduleWithFixedDelay(this::closeIdleEngines, reapEvery, reapEvery, TimeUnit.MILLISECONDS);
        log.info("engines: stockfish={}, lc0={}, active profile={}",
                stockfishLookup.path().map(Path::toString).orElse("missing"),
                lc0Lookup.path().map(Path::toString).orElse("missing"), initial.id());
        log.info("engine memory plan: {}", plan.describe());
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
                "Leggero · pensato per Raspberry Pi 5", sf));
        for (String elo : new String[] { "1100", "1500", "1900" }) {
            boolean weights = Files.isRegularFile(maiaWeights(elo));
            list.add(new EngineProfile("maia-" + elo, "Maia " + elo,
                    "Stile umano · circa " + elo + " Elo (lc0)", lc0 && weights));
        }
        profiles = List.copyOf(list);
    }

    // ------------------------------------------------------------------------------------------
    // Engines

    /** Search budgets of the active tier (full or lite) on this machine's {@link ProcessPlan}. */
    public Budget budget() {
        return isLite(activeProfile) ? Budget.lite(plan) : Budget.full(plan);
    }

    public ProcessPlan plan() {
        return plan;
    }

    /** The scheduler of the analysis process (live analysis, LED coach, review and, on 1 GB, the bot). */
    public synchronized PositionAnalyzer analyzer() {
        if (analyzer == null) {
            analyzer = new PositionAnalyzer(this::analysisClient, this::budget);
            onAnalysisReconfigured(analyzer::refresh);
        }
        return analyzer;
    }

    /** Shared analysis engine (started lazily). Throws {@link EngineException} if Stockfish is missing. */
    public synchronized UciClient analysisClient() {
        if (analysis == null || analysis.isClosed()) {
            Path sf = stockfishLookup.path().orElseThrow(() -> {
                setStatus(EngineStatus.error(activeProfile.id(), stockfishLookup.describeMissing()));
                return new EngineException(stockfishLookup.describeMissing());
            });
            analysisBudget = budget();
            Map<String, String> opts = stockfishOptions(analysisBudget.threads(), analysisBudget.hashMb());
            opts.putAll(strengthOptions(BotStrength.full())); // base values for the per-search override of a shared bot
            analysis = new UciClient(EngineSpec.of("analysis", sf, opts));
            analysis.start().exceptionally(t -> {
                setStatus(EngineStatus.error(activeProfile.id(), "Stockfish non si avvia: " + rootMessage(t)));
                return null;
            });
        }
        return analysis;
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
        BotStrength strength = botStrength;
        if (strength != null) {
            return botMove(fen, strength);
        }
        return botMove(fen, new BotStrength(skillLevel, 0, 0, 0));
    }

    /**
     * Strength used by {@link #botMove(String, int)} instead of its skill level (null = the skill level). Set by a
     * game that chose a level in Elo or plays with a clock (the bot then thinks within its remaining time).
     */
    public void setBotStrength(BotStrength strength) {
        this.botStrength = strength;
    }

    public BotStrength botStrength() {
        return botStrength;
    }

    private volatile BotStrength botStrength;

    /** Like {@link #botMove(String, int)} with an explicit strength and thinking time. */
    public CompletableFuture<String> botMove(String fen, BotStrength strength) {
        return botMoveOnce(fen, strength).handle((move, err) -> {
            if (err == null) {
                return CompletableFuture.completedFuture(move);
            }
            // typical cause: the profile was switched while the bot was thinking (old engine closed)
            log.info("bot move not completed ({}), retrying once on the current engine", rootMessage(err));
            return botMoveOnce(fen, strength);
        }).thenCompose(f -> f);
    }

    /** UCI options of a Stockfish bot for {@code strength}. */
    static Map<String, String> strengthOptions(BotStrength strength) {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("Skill Level", String.valueOf(strength.uciElo() > 0 ? 20 : strength.skillLevel()));
        o.put("UCI_LimitStrength", strength.uciElo() > 0 ? "true" : "false");
        o.put("UCI_Elo", String.valueOf(strength.uciElo() > 0 ? strength.uciElo() : BotStrength.MAX_ELO));
        return o;
    }

    private CompletableFuture<String> botMoveOnce(String fen, BotStrength strength) {
        EngineProfile p = activeProfile;
        Map<String, String> options = strengthOptions(strength);
        if (!isMaia(p) && plan.botSharesAnalysis()) {
            // Low-memory board: the bot is a high-priority search on the analysis process.
            long t0 = System.nanoTime();
            return analyzer().submit(PositionAnalyzer.Priority.BOT, fen, stockfishBotLimits(p, strength), options)
                    .thenApply(r -> logBotMove(p, r, t0));
        }
        return ensureBot(p).thenCompose(client -> {
            SearchLimits limits;
            if (isMaia(p)) {
                limits = SearchLimits.nodes(Math.max(1, ConfigManager.getIntProperty("maia.nodes", 1))).withTimeout(15_000);
            } else {
                options.forEach(client::setOption);
                limits = stockfishBotLimits(p, strength);
            }
            long t0 = System.nanoTime();
            return client.search(fen, limits).result().thenApply(r -> logBotMove(p, r, t0));
        });
    }

    private SearchLimits stockfishBotLimits(EngineProfile p, BotStrength strength) {
        int movetime = strength.movetimeMs() > 0 ? Math.max(50, strength.movetimeMs())
                : Math.max(100, ConfigManager.getIntProperty("game.bot.movetime", 2000));
        Budget b = isLite(p) ? Budget.lite(plan) : Budget.full(plan);
        SearchLimits limits = SearchLimits.movetime(Math.min(movetime, b.botMaxMovetimeMs()));
        if (b.botMaxNodes() > 0) {
            limits = limits.withNodes(b.botMaxNodes());
        }
        if (strength.depth() > 0) {
            limits = limits.withDepth(strength.depth());
        }
        return limits.withTimeout(limits.movetimeMs() + 3_000L);
    }

    private static String logBotMove(EngineProfile p, SearchResult r, long t0) {
        log.info("[bot {}] {} in {} ms (depth {}, {} nodes)", p.id(), r.bestMove(),
                (System.nanoTime() - t0) / 1_000_000, r.depth(), r.nodes());
        if (r.bestMove() == null) {
            throw new CompletionException(new EngineException("bot returned no move"));
        }
        return r.bestMove();
    }

    /** Returns (starting if needed) the bot engine for a profile; switching closes the old one. */
    private synchronized CompletableFuture<UciClient> ensureBot(EngineProfile p) {
        CompletableFuture<UciClient> current = bot;
        if (!isMaia(p) && plan.botSharesAnalysis()) {
            // no bot process on this board: close a Maia engine left over from a previous profile
            bot = null;
            botProfileId = null;
            if (current != null) {
                current.thenAccept(UciClient::closeAsync);
            }
            setStatus(EngineStatus.ready(p.id(), p.displayName()));
            return CompletableFuture.failedFuture(new IllegalStateException("bot shares the analysis engine"));
        }
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

    /**
     * Stockfish Lite on any Raspberry Pi (it is sized for the Pi 5; a Pi 4 core is ~1/9 of an M-series core) or with
     * less than ~1.4 GB of RAM, Stockfish elsewhere.
     */
    static String defaultProfileForThisMachine() {
        if (ProcessPlan.detect().ramMb() < 1_400) {
            log.info("{} MB of RAM: defaulting to Stockfish Lite", ProcessPlan.detect().ramMb());
            return STOCKFISH_LITE;
        }
        try {
            Path model = Path.of("/proc/device-tree/model");
            if (Files.isReadable(model)) {
                String m = Files.readString(model).replace("\0", "");
                if (m.contains("Raspberry Pi")) {
                    log.info("{}: defaulting to Stockfish Lite", m.trim());
                    return STOCKFISH_LITE;
                }
            }
        } catch (Exception ignored) {
            // not a Pi / unreadable
        }
        return STOCKFISH;
    }

    /** Closes the engines if the manager was ever created (application exit); never starts anything. */
    public static void shutdownIfStarted() {
        EngineManager m = instance;
        if (m != null) {
            m.shutdown();
        }
    }

    /** Closes every engine process (used at shutdown; safe to call more than once). */
    public void shutdown() {
        List<UciClient> all = new ArrayList<>();
        synchronized (this) {
            if (analysis != null) {
                all.add(analysis);
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

    /** Closes engine processes idle for longer than the plan allows; they restart lazily on the next request. */
    void closeIdleEngines() {
        try {
            long limit = plan.idleCloseMs();
            CompletableFuture<UciClient> b = bot;
            UciClient botClient = b == null ? null : b.getNow(null);
            if (botClient != null && !botClient.isClosed() && botClient.idleMillis() > limit) {
                synchronized (this) {
                    if (bot == b) {
                        bot = null;
                        botProfileId = null;
                    }
                }
                log.info("closing idle bot engine ({} s without searches)", botClient.idleMillis() / 1000);
                botClient.closeAsync();
            }
            UciClient a;
            PositionAnalyzer pa;
            synchronized (this) {
                a = analysis;
                pa = analyzer;
            }
            if (a != null && !a.isClosed() && a.idleMillis() > limit && (pa == null || pa.isForegroundIdle())) {
                synchronized (this) {
                    if (analysis == a) {
                        analysis = null;
                    }
                }
                log.info("closing idle analysis engine ({} s without searches)", a.idleMillis() / 1000);
                a.closeAsync();
            }
        } catch (RuntimeException e) {
            log.warn("idle engine check failed: {}", e.toString());
        }
    }

    /** Live engine processes (for diagnostics and tests). */
    public synchronized List<UciClient> liveClients() {
        List<UciClient> out = new ArrayList<>();
        if (analysis != null && !analysis.isClosed()) {
            out.add(analysis);
        }
        CompletableFuture<UciClient> b = bot;
        UciClient c = b == null ? null : b.getNow(null);
        if (c != null && !c.isClosed()) {
            out.add(c);
        }
        return out;
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
        Budget b = isLite(p) ? Budget.lite(plan) : Budget.full(plan);
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
     * Search budgets for one resource tier. LED numbers from {@code LedDepthStudyTest} (Stockfish 19, 613 moves of
     * real chess.com games of all levels plus their mating / mate-allowing moves, reference = both positions at depth
     * 20, LED classification = the review's fast verdict, SPEC v1.6). The position before is searched by the live analysis
     * while the player thinks, the position after the move with that warm hash:
     * <table>
     *   <caption>LED verdict at depth d of the position after the move, position before at depth 16</caption>
     *   <tr><th>d</th><th>same class</th><th>same ok/error</th><th>real mistakes shown ok</th><th>nodes p50 / p95 / max</th><th>Pi 5, 1 thread p50 / p95</th></tr>
     *   <tr><td>10</td><td>78.3%</td><td>94.3%</td><td>2</td><td>2 k / 14 k / 77 k</td><td>7 / 47 ms</td></tr>
     *   <tr><td>12</td><td>77.8%</td><td>93.5%</td><td>0</td><td>6 k / 44 k / 273 k</td><td>21 / 146 ms</td></tr>
     *   <tr><td>14</td><td>80.4%</td><td>95.1%</td><td>1</td><td>27 k / 109 k / 568 k</td><td>89 / 362 ms</td></tr>
     *   <tr><td>16</td><td>81.2%</td><td>96.4%</td><td>0</td><td>115 k / 300 k / 911 k</td><td>385 ms / 1.0 s</td></tr>
     * </table>
     * Depth 12 is the verdict depth (shallower loses agreement, deeper costs 4x per two plies); 16 the confirmation,
     * which only changes the LEDs if the class changes. A shallow position before costs more than a shallow position
     * after (before at depth 12 instead of 16: -5 points of agreement). The engine's top move (at depth &ge; 10) is
     * BEST at once: 1 inaccuracy (no mistake) in ~250 such moves. Pi 5 = 300 k nodes/s per core (1/4 of an M4 core; 2 threads ~1.7x).
     * The Raspberry Pi 4 (1 thread at ~120 k nodes/s) keeps the depth 14 confirmation.
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
                         int botMaxMovetimeMs, long botMaxNodes, int reviewMovetimeCapMs, ReviewPlan review) {

        public Budget(int threads, int hashMb, int liveMaxDepth, int coachMinDepth, int coachConfirmDepth,
                      long coachCapMs, long candidateNodes, long candidateCapMs, int botThreads, int botHashMb,
                      int botMaxMovetimeMs, long botMaxNodes, int reviewMovetimeCapMs) {
            this(threads, hashMb, liveMaxDepth, coachMinDepth, coachConfirmDepth, coachCapMs, candidateNodes,
                    candidateCapMs, botThreads, botHashMb, botMaxMovetimeMs, botMaxNodes, reviewMovetimeCapMs,
                    new ReviewPlan(1, 1, 16));
        }

        /** Stockfish: threads and hash from the {@link ProcessPlan}, deep live analysis, all spare cores for reviews. */
        public static Budget full(ProcessPlan plan) {
            int reviewWorkers = plan.ramMb() >= 6_000 ? clamp(plan.cores() - 2, 1, 6)
                    : plan.ramMb() >= 2_800 ? clamp(plan.cores() - 1, 1, 3) : 1;
            int reviewHash = plan.ramMb() >= 6_000 ? 128 : plan.ramMb() >= 2_800 ? 32 : 16;
            return new Budget(plan.analysisThreads(), plan.analysisHashMb(), 30, 12, 16, 2_500, 150_000, 600, 1,
                    plan.botHashMb(), 10_000, 0, 1_500, new ReviewPlan(reviewWorkers, 1, reviewHash));
        }

        /**
         * Stockfish Lite, the default on a Raspberry Pi (designed for a Pi 5 8 GB: 4 Cortex-A76 cores, ~300-450 k
         * nodes/s each). In a game: 2 analysis threads (eval bar, LED verdicts and hints), the bot on a third core,
         * the fourth for the UI and the camera. In a review nobody plays: 3 single-thread processes in parallel.
         * Smaller boards (Pi 4, 1-4 GB) get 1 thread and small hashes.
         */
        public static Budget lite(ProcessPlan plan) {
            boolean big = plan.ramMb() >= 6_000;
            boolean mid = plan.ramMb() >= 2_800;
            int threads = mid && plan.cores() >= 4 ? 2 : 1;
            int hash = big ? 64 : mid ? 32 : Math.min(16, plan.analysisHashMb());
            int reviewWorkers = mid ? clamp(plan.cores() - 1, 1, 3) : 1;
            return new Budget(threads, hash, 18, 12, mid ? 16 : 14, 1_500, 60_000, 500, 1,
                    big ? 32 : Math.min(16, plan.botHashMb()), 1_000, 300_000, 1_000,
                    new ReviewPlan(reviewWorkers, 1, hash));
        }

        public static Budget full() {
            return full(ProcessPlan.detect());
        }

        public static Budget lite() {
            return lite(ProcessPlan.detect());
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    /**
     * Engines of the game review (the review's own pool of single-thread processes, {@code Engine.review}). The node
     * budget per position is not here: it is {@code Engine.review.ReviewSettings} (Lite / full), the only place.
     *
     * @param workers parallel Stockfish processes
     * @param threads threads of each process
     * @param hashMb  hash of each process
     */
    public record ReviewPlan(int workers, int threads, int hashMb) {
    }
}
