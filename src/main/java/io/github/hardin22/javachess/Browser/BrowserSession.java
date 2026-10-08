package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The integrated browser's state machine. It receives everything that happens (browser engine start-up and
 * download, page loads and errors, what the page shows, reading problems, the synchronisation's progress, the
 * user's actions), starts and stops the synchronisation with the physical board, and turns it all into one
 * {@link BrowserStatus} for the user.
 *
 * <p>Event methods may be called from any thread: they run on the session's single-threaded executor, which
 * also owns the {@link OnlineGameSync}. Listeners are called on that thread.</p>
 */
public final class BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    /** Start-up of the browser engine (JCEF). */
    public enum Engine {
        NOT_STARTED, STARTING, DOWNLOADING, INSTALLING, READY, RESTART_REQUIRED, FAILED
    }

    /** Why the browser engine could not start. */
    public enum Failure {
        NO_NETWORK, UNSUPPORTED, NO_SPACE, MISSING_OPTIONS, OTHER
    }

    /** What the session asks the browser window to do. */
    public interface Commands {
        void reload();

        void pageBack();

        void retryEngine();

        void restartApp();

        void backHome();

        void showBoard();

        /**
         * Whether the app may read the page (probe, scripts, DevTools). False on login and verification pages:
         * the app keeps its hands off them, so that the site's bot check sees only the user.
         */
        void pageAccess(boolean allowed);
    }

    /** Why the app keeps its hands off the page; see {@link Commands#pageAccess}. */
    public enum HandsOff {
        /** A login page of a known site (from its address). */
        LOGIN,
        /** A bot verification (Cloudflare "Just a moment...", Turnstile), from the title or one reading. */
        VERIFICATION
    }

    /** Errors meaning there is no connection at all (Chromium net error names). */
    private static final Set<String> OFFLINE_ERRORS = Set.of("ERR_INTERNET_DISCONNECTED", "ERR_NAME_NOT_RESOLVED",
            "ERR_NAME_RESOLUTION_FAILED", "ERR_ADDRESS_UNREACHABLE", "ERR_NETWORK_CHANGED",
            "ERR_NETWORK_ACCESS_DENIED", "ERR_PROXY_CONNECTION_FAILED");
    static final long SLOW_LOAD_MS = 15_000;
    /** On a verification page whose end was not noticed, the page is read once again after this long. */
    static final long VERIFICATION_RECHECK_MS = 30_000;
    static final long CRASH_RELOAD_EVERY_MS = 60_000;
    /** How long after a load started a complete document counts as loaded without Chromium's notice. */
    static final long LOAD_END_GRACE_MS = 2_000;
    /** A board narrower than this share of the page (or than 240 px) is a thumbnail, not the game. */
    private static final double MIN_BOARD_SHARE = 0.45;

    private final Executor executor;
    private final OnlineGameSync sync;
    private final PhysicalBoard board;
    private final Commands commands;
    private final LongSupplier clock;
    private final List<Consumer<BrowserStatus>> listeners = new CopyOnWriteArrayList<>();
    private volatile BrowserStatus status = BrowserStatus.initial();

    // owned by the executor thread
    private Engine engine = Engine.NOT_STARTED;
    private double engineProgress = -1;
    private Failure failure;
    private String url = "";
    private boolean loading;
    private long loadingSince;
    private String loadingUrl = "";
    private long lastCrashReload = Long.MIN_VALUE;
    private String loadError;
    private BoardSnapshot snapshot;
    private BoardWatcher.Problem problem;
    private PageInfo page = PageInfo.of("");
    private boolean pausedOnPage;
    private boolean visible;
    private LoginAssistant login;
    private String title = "";
    private boolean verificationSeen;
    private boolean loginFormSeen;
    private long verificationSince;
    private HandsOff handsOff;

    public BrowserSession(Executor executor, PhysicalBoard board, OnlineGameSync.MoveSender sender,
                          Consumer<ArchivedGame> archive, Commands commands, LongSupplier clock) {
        this.executor = executor;
        this.board = board;
        this.commands = commands;
        this.clock = clock;
        this.sync = new OnlineGameSync(board, sender, archive, s -> recompute(), clock, executor);
    }

    public void addListener(Consumer<BrowserStatus> listener) {
        listeners.add(listener);
        listener.accept(status);
    }

    public BrowserStatus status() {
        return status;
    }

    /** The synchronisation (to be used on the session thread only). */
    OnlineGameSync sync() {
        return sync;
    }

    // ------------------------------------------------------------------ engine

    public void engineStarting() {
        post(() -> {
            engine = Engine.STARTING;
            failure = null;
        });
    }

    /** Download progress 0..1 (or -1 when unknown). */
    public void engineDownloading(double progress) {
        post(() -> {
            engine = Engine.DOWNLOADING;
            engineProgress = progress;
        });
    }

    public void engineInstalling() {
        post(() -> engine = Engine.INSTALLING);
    }

    public void engineReady() {
        post(() -> engine = Engine.READY);
    }

    public void engineRestartRequired() {
        post(() -> engine = Engine.RESTART_REQUIRED);
    }

    public void engineFailed(Failure reason) {
        post(() -> {
            engine = Engine.FAILED;
            failure = reason;
        });
    }

    public Engine engine() {
        return engine;
    }

    // ------------------------------------------------------------------ window and page

    /** The browser window is shown (true) or the user went back to the app (false). */
    public void setVisible(boolean shown) {
        post(() -> {
            visible = shown;
            if (!shown) {
                sync.stop();
                pausedOnPage = false;
            }
        });
    }

    public void pageLoading(String newUrl) {
        post(() -> {
            loading = true;
            loadingSince = clock.getAsLong();
            loadingUrl = newUrl == null ? "" : newUrl;
            loadError = null;
            verificationSeen = false; // the verification passed (the site reloads) or the user reloaded
            loginFormSeen = false;
            onUrl(newUrl);
        });
    }

    public void pageLoaded(String newUrl, int httpStatus) {
        post(() -> {
            loading = false;
            if (httpStatus >= 500 && httpStatus < 600) {
                loadError = "HTTP_" + httpStatus;
            }
            onUrl(newUrl);
        });
    }

    /** Chromium says it is no longer loading {@code currentUrl}: ends a load whose end notice was lost. */
    public void pageLoadedIfLoading(String currentUrl) {
        post(() -> {
            if (loading && clock.getAsLong() - loadingSince >= LOAD_END_GRACE_MS) {
                log.info("Page loaded (Chromium is idle): {}", currentUrl);
                loading = false;
            }
        });
    }

    /** The page changed its address without a load (single-page sites, e.g. a chess.com game starting). */
    public void addressChanged(String newUrl) {
        post(() -> onUrl(newUrl));
    }

    /** A main-frame load failed with a Chromium error ({@code ERR_ABORTED} is a cancelled load, not an error). */
    public void pageLoadFailed(String failedUrl, String errorCode) {
        post(() -> {
            if ("ERR_ABORTED".equals(errorCode)) {
                return;
            }
            log.warn("Page load failed ({}): {}", errorCode, failedUrl);
            loading = false;
            loadError = errorCode;
            onUrl(failedUrl);
            if (isPageCrash(errorCode)) {
                // Chromium closed the page's process (out of memory, crash): open it again once by itself;
                // if it happens again within a minute the user decides (Ricarica)
                long now = clock.getAsLong();
                if (lastCrashReload == Long.MIN_VALUE || now - lastCrashReload >= CRASH_RELOAD_EVERY_MS) {
                    lastCrashReload = now;
                    log.info("Reloading the page after its process ended");
                    commands.reload();
                }
            }
        });
    }

    /** The page's process ended (see BrowserWindow: "RENDERER_" + Chromium's termination status). */
    static boolean isPageCrash(String errorCode) {
        return errorCode != null && errorCode.startsWith("RENDERER_");
    }

    private void onUrl(String newUrl) {
        if (newUrl == null || newUrl.isBlank() || newUrl.startsWith("data:") || newUrl.startsWith("chrome-error:")) {
            return;
        }
        PageInfo next = PageInfo.of(newUrl);
        if (!next.samePage(page)) {
            if (sync.isActive()) {
                log.info("Page changed: {} -> {}", page.url(), newUrl);
                sync.stop();
            }
            pausedOnPage = false;
            snapshot = null;
            problem = null;
        }
        if (!newUrl.equals(url)) {
            verificationSeen = false;
            loginFormSeen = false;
        }
        url = newUrl;
        page = next;
        updateHandsOff();
    }

    /** The page's title changed (Chromium's notice). Cloudflare's verification has a recognisable title. */
    public void titleChanged(String newTitle) {
        post(() -> {
            String t = newTitle == null ? "" : newTitle;
            if (t.equals(title)) {
                return;
            }
            title = t;
            if (isVerificationTitle(t)) {
                seeVerification();
            } else {
                verificationSeen = false;
            }
            updateHandsOff();
        });
    }

    /** True when the app keeps its hands off this address from the start (login pages of the known sites). */
    public static boolean handsOffAddress(String address) {
        PageInfo p = PageInfo.of(address == null ? "" : address);
        return p.kind() == PageInfo.Kind.LOGIN && p.site() != ChessSite.OTHER;
    }

    /** Titles of Cloudflare's verification page in the languages a board's owner may use. */
    static boolean isVerificationTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return t.contains("just a moment") || t.contains("un momento") || t.contains("attention required")
                || t.contains("one more step") || t.contains("un instant") || t.contains("nur einen moment")
                || t.contains("cloudflare");
    }

    private void seeVerification() {
        if (!verificationSeen) {
            verificationSeen = true;
            verificationSince = clock.getAsLong();
        }
    }

    /** What the app may do with the page now; tells the controller when it changes. */
    private void updateHandsOff() {
        HandsOff next = verificationSeen ? HandsOff.VERIFICATION
                : loginFormSeen || handsOffAddress(page.url()) ? HandsOff.LOGIN : null;
        if (next == handsOff) {
            return;
        }
        log.info(next == null ? "Reading the page again" : "Hands off the page: {}", next);
        handsOff = next;
        if (next == HandsOff.LOGIN && login != null) {
            login.onLoginPage(page.site());
        }
        commands.pageAccess(next == null);
    }

    /** Why the app does not read the page now, or null. */
    public HandsOff handsOff() {
        return handsOff;
    }

    // ------------------------------------------------------------------ what the page shows

    public void onSnapshot(BoardSnapshot s) {
        post(() -> {
            if (!s.url().isBlank()) {
                onUrl(s.url());
            }
            if (loading && s.ready() && clock.getAsLong() - loadingSince >= LOAD_END_GRACE_MS
                    && s.url().equals(loadingUrl)) {
                // Chromium's "load finished" never came (on macOS some of its notices to Java are lost): the page
                // says it is complete
                log.info("Page loaded (seen by the probe): {}", s.url());
                loading = false;
            }
            snapshot = s;
            PageInfo info = s.page();
            page = info;
            if (s.challenge()) {
                seeVerification(); // read once: from now on the app keeps its hands off this page
            } else if (s.loginForm() && s.site() != ChessSite.OTHER && !loginFormSeen) {
                loginFormSeen = true; // a login form inside another page (e.g. a pop-up)
                verificationSince = clock.getAsLong();
            }
            updateHandsOff();
            if (handsOff != null) {
                return;
            }
            if (login != null) {
                login.onSnapshot(s);
            }
            if (!sync.isActive() && !pausedOnPage && visible && isMainBoard(s) && info.kind().syncsAutomatically()) {
                startSync(info);
            }
            sync.tick();
        });
    }

    public void onProblem(BoardWatcher.Problem p) {
        post(() -> problem = p);
    }

    public void onPosition(BoardWatcher.PositionUpdate update) {
        post(() -> sync.onPosition(update));
    }

    /** Periodic: pending moves, slow pages. */
    public void tick() {
        post(() -> {
            sync.tick();
            if ((handsOff == HandsOff.VERIFICATION || loginFormSeen)
                    && clock.getAsLong() - verificationSince >= VERIFICATION_RECHECK_MS
                    && (login == null || login.state() != LoginAssistant.State.FILLING)) {
                // no sign of the verification (or of the pop-up login) ending, whose notices can be lost: read the
                // page once more
                verificationSeen = false;
                loginFormSeen = false;
                snapshot = null;
                updateHandsOff();
            }
            if (login != null) {
                login.tick(url);
            }
        });
    }

    /** The helper that types saved logins and offers to save typed ones (set once the page exists). */
    public void setLoginAssistant(LoginAssistant assistant) {
        post(() -> login = assistant);
    }

    /** Runs a task on the session's thread and refreshes the status (for components owned by the session). */
    public void onSessionThread(Runnable task) {
        post(task);
    }

    private static boolean isMainBoard(BoardSnapshot s) {
        BoardSnapshot.BoardView b = s.board();
        if (b == null) {
            return false;
        }
        double min = Math.min(240, MIN_BOARD_SHARE * Math.max(1, s.viewportWidth()));
        return b.rect().w() >= min;
    }

    private void startSync(PageInfo info) {
        OnlineGameSync.Mode mode = info.kind() == PageInfo.Kind.ANALYSIS ? OnlineGameSync.Mode.ANALYSIS
                : OnlineGameSync.Mode.PLAY;
        sync.start(mode, info);
    }

    // ------------------------------------------------------------------ actions

    /** A button of the status was pressed. */
    public void perform(BrowserStatus.Action action) {
        log.info("Browser action: {}", action);
        post(() -> {
            switch (action) {
                case RETRY -> commands.retryEngine();
                case RELOAD -> commands.reload();
                case PAGE_BACK -> commands.pageBack();
                case RESTART_APP -> commands.restartApp();
                case BACK_HOME -> commands.backHome();
                case SHOW_BOARD -> commands.showBoard();
                case SAVE_LOGIN -> {
                    if (login != null) {
                        login.save();
                    }
                }
                case DISMISS -> {
                    if (login != null) {
                        login.dismiss();
                    }
                }
                case USE_SAVED_LOGIN -> {
                    if (login != null && handsOff == HandsOff.LOGIN) {
                        // the user asked for it: the page is touched only to type the saved login
                        login.fillSaved(page.site(), url, () -> commands.pageAccess(handsOff == null));
                    }
                }
                case FORGET_LOGIN -> {
                    if (login != null) {
                        login.forget();
                    }
                }
                case SYNC_START -> {
                    pausedOnPage = false;
                    if (snapshot != null && snapshot.board() != null) {
                        startSync(snapshot.page());
                    }
                }
                case SYNC_STOP -> {
                    pausedOnPage = true;
                    sync.stop();
                }
                case RESYNC -> {
                    if (snapshot != null && snapshot.board() != null) {
                        sync.stop();
                        startSync(snapshot.page());
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ status

    private void post(Runnable event) {
        executor.execute(() -> {
            try {
                event.run();
            } catch (RuntimeException e) {
                log.error("Browser session event failed", e);
            }
            recompute();
        });
    }

    private void recompute() {
        BrowserStatus next = compute();
        if (!next.equals(status)) {
            status = next;
            log.info("Browser status: {} - {} {}", next.state(), next.title(), next.detail());
            for (Consumer<BrowserStatus> l : listeners) {
                l.accept(next);
            }
        }
    }

    private String siteName() {
        ChessSite site = page.site();
        if (site != ChessSite.OTHER) {
            return site.displayName();
        }
        String host = ChessSite.host(url);
        return host != null ? host.replaceFirst("^www\\.", "") : I18n.t("browser.site.other");
    }

    BrowserStatus compute() {
        List<BrowserStatus.Action> home = List.of(BrowserStatus.Action.BACK_HOME);
        switch (engine) {
            case NOT_STARTED, STARTING -> {
                return BrowserStatus.of(BrowserStatus.State.STARTING, -1, home);
            }
            case DOWNLOADING -> {
                String percent = engineProgress >= 0 ? Math.round(engineProgress * 100) + "%" : "";
                return BrowserStatus.of(BrowserStatus.State.DOWNLOADING, engineProgress, home, percent);
            }
            case INSTALLING -> {
                return BrowserStatus.of(BrowserStatus.State.INSTALLING, -1, home);
            }
            case RESTART_REQUIRED -> {
                return BrowserStatus.of(BrowserStatus.State.RESTART_REQUIRED, BrowserStatus.NO_PROGRESS,
                        List.of(BrowserStatus.Action.RESTART_APP, BrowserStatus.Action.BACK_HOME));
            }
            case FAILED -> {
                String reason = I18n.t("browser.failure." + (failure == null ? Failure.OTHER : failure).name()
                        .toLowerCase(Locale.ROOT));
                return BrowserStatus.of(BrowserStatus.State.UNAVAILABLE, BrowserStatus.NO_PROGRESS,
                        List.of(BrowserStatus.Action.RETRY, BrowserStatus.Action.BACK_HOME), reason);
            }
            case READY -> {
                // below
            }
        }
        List<BrowserStatus.Action> reload = List.of(BrowserStatus.Action.RELOAD);
        if (loadError != null && isPageCrash(loadError)) {
            return BrowserStatus.of(BrowserStatus.State.PAGE_CRASHED, BrowserStatus.NO_PROGRESS, reload);
        }
        if (loadError != null) {
            boolean offline = OFFLINE_ERRORS.contains(loadError);
            return BrowserStatus.of(offline ? BrowserStatus.State.OFFLINE : BrowserStatus.State.SITE_UNREACHABLE,
                    BrowserStatus.NO_PROGRESS, reload, siteName());
        }
        LoginAssistant.State loginNow = login == null ? LoginAssistant.State.IDLE : login.state();
        if (handsOff == HandsOff.VERIFICATION) {
            return BrowserStatus.of(BrowserStatus.State.VERIFY, BrowserStatus.NO_PROGRESS, reload);
        }
        if (loading && (handsOff != null || snapshot == null || snapshot.board() == null)) {
            boolean slow = clock.getAsLong() - loadingSince >= SLOW_LOAD_MS;
            BrowserStatus s = BrowserStatus.of(BrowserStatus.State.LOADING, -1, slow ? reload : List.of(), siteName());
            return slow ? s.withDetail(I18n.t("browser.status.loading.slow")) : s;
        }
        if (handsOff == HandsOff.LOGIN) {
            if (loginNow == LoginAssistant.State.FAILED) {
                return BrowserStatus.of(BrowserStatus.State.LOGIN_FAILED, BrowserStatus.NO_PROGRESS,
                        List.of(BrowserStatus.Action.FORGET_LOGIN, BrowserStatus.Action.DISMISS));
            }
            boolean saved = login != null && login.hasSaved(page.site()).orElse(false);
            BrowserStatus s = BrowserStatus.of(BrowserStatus.State.LOGIN, BrowserStatus.NO_PROGRESS,
                    saved && loginNow != LoginAssistant.State.FILLING
                            ? List.of(BrowserStatus.Action.USE_SAVED_LOGIN) : List.of(), siteName());
            return loginNow == LoginAssistant.State.FILLING ? s.withDetail(I18n.t("browser.status.login.saved")) : s;
        }
        if (snapshot == null) {
            return BrowserStatus.of(BrowserStatus.State.LOADING, -1, List.of(), siteName());
        }
        PageInfo info = snapshot.page();
        LoginAssistant.State loginState = login == null ? LoginAssistant.State.IDLE : login.state();
        if (!sync.isActive() && loginState == LoginAssistant.State.FAILED) {
            return BrowserStatus.of(BrowserStatus.State.LOGIN_FAILED, BrowserStatus.NO_PROGRESS,
                    List.of(BrowserStatus.Action.FORGET_LOGIN, BrowserStatus.Action.DISMISS));
        }
        if (!sync.isActive() && (snapshot.loginForm() || info.kind() == PageInfo.Kind.LOGIN)) {
            BrowserStatus s = BrowserStatus.of(BrowserStatus.State.LOGIN, BrowserStatus.NO_PROGRESS, List.of(),
                    siteName());
            return loginState == LoginAssistant.State.FILLING ? s.withDetail(I18n.t("browser.status.login.saved")) : s;
        }
        if (!sync.isActive() && loginState == LoginAssistant.State.OFFER_SAVE) {
            return BrowserStatus.of(BrowserStatus.State.SAVE_LOGIN, BrowserStatus.NO_PROGRESS,
                    List.of(BrowserStatus.Action.SAVE_LOGIN, BrowserStatus.Action.DISMISS));
        }
        if (sync.isActive()) {
            return syncStatus(sync.state());
        }
        List<BrowserStatus.Action> connect = List.of(BrowserStatus.Action.SYNC_START);
        if (pausedOnPage && snapshot.board() != null) {
            return BrowserStatus.of(BrowserStatus.State.PAUSED, BrowserStatus.NO_PROGRESS, connect);
        }
        if (info.kind() == PageInfo.Kind.EDITOR) {
            return BrowserStatus.of(BrowserStatus.State.EDITOR, BrowserStatus.NO_PROGRESS, List.of());
        }
        if (isMainBoard(snapshot) && info.kind().maySync()) {
            return BrowserStatus.of(BrowserStatus.State.BOARD_FOUND, BrowserStatus.NO_PROGRESS, connect);
        }
        return BrowserStatus.of(BrowserStatus.State.NO_GAME, BrowserStatus.NO_PROGRESS, List.of());
    }

    private BrowserStatus syncStatus(OnlineGameSync.State s) {
        List<BrowserStatus.Action> stop = List.of(BrowserStatus.Action.SYNC_STOP);
        BrowserStatus hidden = hiddenBoard(stop);
        switch (s.phase()) {
            case WAITING_BOARD -> {
                return hidden != null ? hidden : BrowserStatus.of(BrowserStatus.State.READING, -1, stop);
            }
            case SETUP -> {
                BrowserStatus st = BrowserStatus.of(BrowserStatus.State.SETUP, BrowserStatus.NO_PROGRESS, stop);
                return s.detail() != null ? st.withDetail(s.detail()) : st;
            }
            case MY_TURN -> {
                if (hidden != null) {
                    return hidden;
                }
                BrowserStatus st = BrowserStatus.of(BrowserStatus.State.YOUR_TURN, BrowserStatus.NO_PROGRESS, stop);
                if (!s.boardConnected()) {
                    return st.withDetail(I18n.t("browser.status.your_turn.noboard"));
                }
                return s.mode() == OnlineGameSync.Mode.ANALYSIS
                        ? st.withDetail(I18n.t("browser.status.your_turn.analysis")) : st;
            }
            case SENDING -> {
                return BrowserStatus.of(BrowserStatus.State.SENDING, -1, stop, nz(s.lastMove()));
            }
            case OPPONENT_TURN -> {
                if (hidden != null) {
                    return hidden;
                }
                BrowserStatus st = BrowserStatus.of(BrowserStatus.State.OPPONENT_TURN, BrowserStatus.NO_PROGRESS,
                        stop);
                return s.lastMove() != null && s.toMove() != s.mySide() && s.plies() > 0
                        ? st.withDetail(I18n.t("browser.status.opponent_turn.played", s.lastMove())) : st;
            }
            case REPLICATE -> {
                return BrowserStatus.of(BrowserStatus.State.REPLICATE, BrowserStatus.NO_PROGRESS, stop,
                        nz(s.lastMove()), nz(s.from()), nz(s.to()));
            }
            case NOT_ACCEPTED -> {
                return BrowserStatus.of(BrowserStatus.State.NOT_ACCEPTED, BrowserStatus.NO_PROGRESS,
                        List.of(BrowserStatus.Action.RESYNC, BrowserStatus.Action.SYNC_STOP), nz(s.detail()));
            }
            case UNCERTAIN -> {
                if (hidden != null) {
                    return hidden;
                }
                return BrowserStatus.of(BrowserStatus.State.UNCERTAIN, BrowserStatus.NO_PROGRESS,
                        List.of(BrowserStatus.Action.RESYNC, BrowserStatus.Action.SYNC_STOP), nz(s.detail()));
            }
            case GAME_OVER -> {
                BrowserStatus st = BrowserStatus.of(BrowserStatus.State.GAME_OVER, BrowserStatus.NO_PROGRESS,
                        List.of(), nz(s.result()));
                return s.saved() ? st : st.withDetail(I18n.t("browser.status.game_over.short"));
            }
            default -> {
                return BrowserStatus.of(BrowserStatus.State.READING, -1, stop);
            }
        }
    }

    /** A status explaining why the board cannot be read, or null when it can. */
    private BrowserStatus hiddenBoard(List<BrowserStatus.Action> stop) {
        if (problem == null) {
            return null;
        }
        String key = switch (problem) {
            case BOARD_OUT_OF_VIEW -> "browser.status.board_hidden.out_of_view";
            case VISION_NO_BOARD -> "browser.status.board_hidden.covered";
            case PAGE_UNREADABLE -> "browser.status.board_hidden.unreadable";
            case VISION_UNAVAILABLE -> null; // the page's markup stands in, nothing to tell
        };
        if (key == null) {
            return null;
        }
        List<BrowserStatus.Action> actions = problem == BoardWatcher.Problem.BOARD_OUT_OF_VIEW
                ? List.of(BrowserStatus.Action.SHOW_BOARD, BrowserStatus.Action.SYNC_STOP)
                : problem == BoardWatcher.Problem.PAGE_UNREADABLE
                ? List.of(BrowserStatus.Action.RELOAD, BrowserStatus.Action.SYNC_STOP) : stop;
        return BrowserStatus.of(BrowserStatus.State.BOARD_HIDDEN, BrowserStatus.NO_PROGRESS, actions, I18n.t(key));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
