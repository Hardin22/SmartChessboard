package io.github.hardin22.javachess.Controllers;

import io.github.hardin22.javachess.Application.App;
import io.github.hardin22.javachess.Browser.BoardStateManagerBoard;
import io.github.hardin22.javachess.Browser.BoardWatcher;
import io.github.hardin22.javachess.Browser.BrowserBar;
import io.github.hardin22.javachess.Browser.BrowserSession;
import io.github.hardin22.javachess.Browser.BrowserStatus;
import io.github.hardin22.javachess.Browser.BrowserWindow;
import io.github.hardin22.javachess.Browser.ChessSite;
import io.github.hardin22.javachess.Browser.JcefRuntime;
import io.github.hardin22.javachess.Browser.PageInfo;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.ThemeManager;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Services.VisionService;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;
import io.github.hardin22.javachess.Vision.BotMover;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.cef.CefApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The integrated browser (chess.com, lichess) as a screen of the app.
 *
 * <p>The page itself is shown by {@link BrowserWindow}, a Swing window laid over the app; this controller's
 * JavaFX view is visible only while the browser starts, downloads or fails, and shows the same
 * {@link BrowserStatus} as the bar above the page. Everything that can fail is reported through the
 * {@link BrowserSession} with a message and recovery actions; nothing is lost in a background thread.</p>
 */
public class BrowserController implements NavigationAware {

    private static final Logger log = LoggerFactory.getLogger(BrowserController.class);

    /**
     * Default of the {@code browser.reader} setting (page, vision, vision-only): the page's markup, cross-checked by
     * vision, which takes over when the markup cannot be read (see docs/browser.md for the field trials).
     */
    public static final String DEFAULT_READER = "page";

    @FXML
    private Label statusTitle;
    @FXML
    private Label statusDetail;
    @FXML
    private ProgressBar statusProgress;
    @FXML
    private Pane actionBox;

    private MainController mainController;
    private final ScheduledExecutorService sessionThread =
            Executors.newSingleThreadScheduledExecutor(AppExecutors.daemonFactory("browser-session"));
    private final ScheduledExecutorService watchThread =
            Executors.newSingleThreadScheduledExecutor(AppExecutors.daemonFactory("browser-watch"));
    private BrowserSession session;
    private volatile BrowserWindow window;
    private volatile BoardWatcher watcher;
    private volatile BotMover mover;
    private VisionService vision;
    private volatile String pendingUrl;
    /** The user is on the browser screen (or opening it): a window that becomes ready may be shown. */
    private volatile boolean wanted;
    private boolean stageWasFullScreen;
    private ScheduledFuture<?> ticker;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        session = new BrowserSession(sessionThread,
                new BoardStateManagerBoard(Hardware.boardState(),
                        () -> ConfigManager.getBooleanProperty("game.suggestions", true)),
                uci -> {
                    BotMover m = mover;
                    return m != null ? m.play(uci)
                            : CompletableFuture.failedFuture(new IllegalStateException("No browser"));
                },
                game -> AppExecutors.storage().execute(() -> GameArchiveService.getInstance().add(game)),
                new Commands(), System::currentTimeMillis);
        session.addListener(status -> Platform.runLater(() -> showOnView(status)));
        session.addListener(status -> SwingUtilities.invokeLater(() -> {
            BrowserWindow w = window;
            if (w != null) {
                w.bar().show(status);
            }
        }));
    }

    /** Opens the browser on {@code url} (chess.com, lichess...). Call on the JavaFX thread. */
    public void loadPage(String url) {
        log.info("Browser requested: {}", url);
        pendingUrl = url;
        wanted = true;
        if (window != null) {
            showWindow(url);
            return;
        }
        startEngine();
    }

    // ------------------------------------------------------------------ start-up

    private void startEngine() {
        session.engineStarting();
        JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
                session.engineDownloading(fraction);
            }

            @Override
            public void installing() {
                session.engineInstalling();
            }
        }).whenComplete((app, error) -> {
            if (error != null) {
                if (JcefRuntime.isRestartRequired(error)) {
                    log.info("Browser installed, restart required: {}", error.getMessage());
                    session.engineRestartRequired();
                } else {
                    BrowserSession.Failure failure = JcefRuntime.classify(error);
                    if (failure == BrowserSession.Failure.NO_NETWORK) {
                        // expected (first start without Internet): the user sees it, no stack trace needed
                        log.warn("Cannot start the integrated browser: no network ({})", error.toString());
                    } else {
                        log.error("Cannot start the integrated browser", error);
                    }
                    session.engineFailed(failure);
                }
                return;
            }
            SwingUtilities.invokeLater(() -> createWindow(app));
        });
    }

    /** Swing thread: the window is built once; any failure is shown to the user (never an empty window). */
    private void createWindow(CefApp app) {
        if (window != null) {
            return;
        }
        String url = pendingUrl != null ? pendingUrl : ChessSite.CHESS_COM.homeUrl();
        BrowserWindow w;
        try {
            w = new BrowserWindow(app, url, session, this::onNav);
        } catch (Throwable e) {
            log.error("Cannot create the browser window", e);
            session.engineFailed(JcefRuntime.classify(e));
            return;
        }
        window = w;
        mover = new BotMover(w.page());
        vision = new VisionService();
        BoardWatcher.ReadMode mode = BoardWatcher.ReadMode.parse(ConfigManager.getProperty("browser.reader",
                DEFAULT_READER));
        watcher = new BoardWatcher(w.page(), vision, watchThread, new BoardWatcher.Listener() {
            @Override
            public void onSnapshot(io.github.hardin22.javachess.Browser.BoardSnapshot snapshot) {
                session.onSnapshot(snapshot);
            }

            @Override
            public void onPosition(BoardWatcher.PositionUpdate update) {
                session.onPosition(update);
            }

            @Override
            public void onProblem(BoardWatcher.Problem problem) {
                session.onProblem(problem);
            }
        }, mode);
        log.info("Board reading: {}", mode);
        session.setLoginAssistant(new io.github.hardin22.javachess.Browser.LoginAssistant(w.page(),
                io.github.hardin22.javachess.Browser.CredentialStore.system(), session::onSessionThread,
                AppExecutors.io(), () -> { }, System::currentTimeMillis));
        w.bar().show(session.status());
        session.engineReady();
        Platform.runLater(() -> {
            // the page is already loading; if the user left during the start-up (it can take minutes the first
            // time) the window waits for the next loadPage instead of covering whatever they are doing now
            if (wanted) {
                showWindow(null);
            } else {
                log.info("Browser ready in the background (the user left the browser screen)");
            }
        });
        io.github.hardin22.javachess.Browser.BrowserSnapshot.scheduleIfRequested(w, Platform::exit);
    }

    // ------------------------------------------------------------------ window

    /** JavaFX thread: lays the browser window over the app's window and loads {@code url} when needed. */
    private void showWindow(String url) {
        BrowserWindow w = window;
        if (w == null) {
            return;
        }
        Stage stage = stage();
        Rectangle bounds = stage == null ? new Rectangle(0, 0, 720, 1280)
                : new Rectangle((int) Math.round(stage.getX()), (int) Math.round(stage.getY()),
                (int) Math.round(stage.getWidth()), (int) Math.round(stage.getHeight()));
        boolean fullScreen = stage != null && stage.isFullScreen();
        stageWasFullScreen = fullScreen;
        BrowserBar.Theme theme = barTheme();
        SwingUtilities.invokeLater(() -> {
            w.bar().applyTheme(theme);
            w.show(bounds, fullScreen);
            if (url != null && shouldLoad(w, url)) {
                w.load(url);
            }
        });
        session.setVisible(true);
        watcher.start();
        startTicker();
    }

    /** Opening the same site again keeps the page (a game in progress is not interrupted). */
    private static boolean shouldLoad(BrowserWindow w, String url) {
        String current = w.page().url();
        PageInfo now = PageInfo.of(current);
        return now.kind() == PageInfo.Kind.BLANK || now.site() != ChessSite.of(url) || now.site() == ChessSite.OTHER;
    }

    /** Back to the app: the synchronisation stops (a game in progress is archived), the window is hidden. */
    private void hideWindow() {
        wanted = false;
        session.setVisible(false);
        BoardWatcher wt = watcher;
        if (wt != null) {
            wt.stop();
        }
        stopTicker();
        BrowserWindow w = window;
        if (w != null) {
            SwingUtilities.invokeLater(w::hide);
            JcefRuntime.flushCookies(); // the login is on disk even if the app is closed right after
        }
        Platform.runLater(() -> {
            if (mainController != null) {
                mainController.navigateTo("HOME");
            }
            Stage stage = stage();
            if (stage != null) {
                if (stageWasFullScreen && !stage.isFullScreen()) {
                    stage.setFullScreen(true);
                }
                stage.toFront();
                stage.requestFocus();
            }
        });
    }

    private synchronized void startTicker() {
        if (ticker == null) {
            ticker = sessionThread.scheduleAtFixedRate(session::tick, 500, 500, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void stopTicker() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }

    private void onNav(BrowserBar.Nav nav) {
        switch (nav) {
            case HOME -> session.perform(BrowserStatus.Action.BACK_HOME);
            case PAGE_BACK -> session.perform(BrowserStatus.Action.PAGE_BACK);
            case RELOAD -> session.perform(BrowserStatus.Action.RELOAD);
        }
    }

    private Stage stage() {
        if (mainController == null || mainController.getMainContainer().getScene() == null) {
            return null;
        }
        Window w = mainController.getMainContainer().getScene().getWindow();
        return w instanceof Stage s ? s : null;
    }

    private static BrowserBar.Theme barTheme() {
        try {
            ThemeManager.Palette p = ThemeManager.get().palette();
            boolean dark = ThemeManager.get().isDark();
            return new BrowserBar.Theme(awt(p.bg()), awt(p.surface()), awt(p.surface2()), awt(p.border()),
                    awt(p.fg()), awt(p.muted()), awt(p.accent()),
                    dark ? java.awt.Color.decode("#06121F") : java.awt.Color.WHITE,
                    awt(p.success()), awt(p.warning()), awt(p.danger()));
        } catch (RuntimeException e) {
            return BrowserBar.Theme.DARK;
        }
    }

    private static java.awt.Color awt(javafx.scene.paint.Color c) {
        return new java.awt.Color((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue(), (float) c.getOpacity());
    }

    // ------------------------------------------------------------------ JavaFX view (start-up and failures)

    private void showOnView(BrowserStatus status) {
        if (statusTitle == null) {
            return;
        }
        statusTitle.setText(status.title());
        statusDetail.setText(status.detail());
        boolean progress = status.hasProgress();
        statusProgress.setVisible(progress);
        statusProgress.setManaged(progress);
        statusProgress.setProgress(status.progress() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : status.progress());
        actionBox.getChildren().clear();
        boolean first = true;
        for (BrowserStatus.Action action : status.actions()) {
            Button b = new Button(action.label());
            b.getStyleClass().addAll("btn", "btn-lg", first && action != BrowserStatus.Action.BACK_HOME
                    ? "btn-primary" : "btn-outline");
            b.setOnAction(e -> session.perform(action));
            actionBox.getChildren().add(b);
            first = false;
        }
    }

    @FXML
    private void onBack() {
        session.perform(BrowserStatus.Action.BACK_HOME);
    }

    @Override
    public void onNavigatedFrom() {
        // leaving the start-up screen without the page: nothing runs in the background
        BrowserWindow w = window;
        if (w == null || !w.isShowing()) {
            wanted = false;
            session.setVisible(false);
        }
    }

    /**
     * The app is closing: a game followed in the browser is archived (as interrupted), and the board released, before
     * the storage threads stop. Waits for the session thread (its events run in order), at most 2 s.
     */
    public void onAppExit() {
        session.setVisible(false);
        try {
            sessionThread.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("The browser game could not be saved on exit: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ session commands

    private final class Commands implements BrowserSession.Commands {
        @Override
        public void reload() {
            BrowserWindow w = window;
            if (w != null) {
                SwingUtilities.invokeLater(w::reload);
            }
        }

        @Override
        public void pageBack() {
            BrowserWindow w = window;
            if (w != null) {
                SwingUtilities.invokeLater(w::goBack);
            }
        }

        @Override
        public void retryEngine() {
            Platform.runLater(() -> {
                if (window == null) {
                    startEngine();
                }
            });
        }

        @Override
        public void restartApp() {
            Platform.runLater(BrowserController::restartApplication);
        }

        @Override
        public void backHome() {
            hideWindow();
        }

        @Override
        public void showBoard() {
            BrowserWindow w = window;
            if (w != null) {
                w.page().evaluate(BotMover.SCROLL_BOARD_INTO_VIEW);
            }
        }
    }

    /**
     * Restarts the app so that the browser bundle is preloaded (Raspberry Pi): under systemd a non-zero exit makes
     * the service start it again; started from run_pi.sh the launcher is run again; otherwise the user is asked
     * to restart it.
     */
    static void restartApplication() {
        String launcher = System.getenv("JAVACHESS_LAUNCHER");
        try {
            if (System.getenv("INVOCATION_ID") != null) {
                log.info("Restarting through systemd");
                App.setExitCode(App.RESTART_EXIT_CODE);
                Platform.exit();
                return;
            }
            if (launcher != null && Files.isExecutable(Path.of(launcher))) {
                log.info("Restarting with {}", launcher);
                // detached grandchild: it survives the app and is not stopped with the app's child processes
                new ProcessBuilder("bash", "-c", "(sleep 3; exec \"$0\") >/dev/null 2>&1 &", launcher).start();
                Platform.exit();
                return;
            }
        } catch (Exception e) {
            log.warn("Automatic restart failed: {}", e.toString());
        }
        io.github.hardin22.javachess.Utils.ErrorReporter.showError(I18n.t("browser.title"),
                I18n.t("browser.restart.manual"));
    }
}
