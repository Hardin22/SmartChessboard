package io.github.hardin22.javachess.Application;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import io.github.hardin22.javachess.Controllers.MainController;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Utils.AppExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX application. Start-up does only what the first screen needs (main layout and HOME); the board
 * hardware connects on a background thread and the other views are built while the UI is idle.
 */
public class App extends Application {

    /** Kept to archive the game in progress on exit. */
    private MainController mainController;

    private static final Logger log = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage primaryStage) {
        long startAt = StartupMetrics.uptimeMs();
        // macOS asks the app to quit through Chromium while the browser runs (Cmd+Q, log out): quit the app's way
        io.github.hardin22.javachess.Browser.JcefRuntime.setQuitHandler(
                () -> javafx.application.Platform.runLater(javafx.application.Platform::exit));
        try {
            var resource = App.class.getResource("/UI/MainLayout.fxml");
            if (resource == null) {
                throw new IllegalStateException("Cannot find /UI/MainLayout.fxml");
            }
            FXMLLoader fxmlLoader = new FXMLLoader(resource);
            io.github.hardin22.javachess.Components.ThemeManager.loadFonts();
            Scene scene = new Scene(fxmlLoader.load(), 720, 1280);
            // Fonts, stylesheet, light/dark theme, window title and icons (UI layer).
            io.github.hardin22.javachess.Components.ThemeManager.install(scene, primaryStage);

            applyRenderingProfile(scene);
            primaryStage.setScene(scene);
            boolean fullScreen = DevOptions.placeStage(primaryStage);
            primaryStage.setFullScreenExitHint("");
            if (Boolean.getBoolean("javachess.kiosk")) {
                // kiosk (run_pi.sh): Esc must not leave full screen on the board's monitor
                primaryStage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
            }
            primaryStage.setFullScreen(fullScreen);

            long beforeShow = StartupMetrics.uptimeMs();
            primaryStage.show();
            log.info("Start-up: UI built in {} ms, window shown in {} ms",
                    beforeShow - startAt, StartupMetrics.uptimeMs() - beforeShow);
            StartupMetrics.onStageShown(startAt);

            MainController mainController = fxmlLoader.getController();
            this.mainController = mainController;
            // after the first frame: connect the board and build the other views in idle time
            Platform.runLater(() -> {
                AppExecutors.io().execute(Hardware::get); // also registers the coach -> LED renderer
                mainController.startIdlePreload();
            });
            DevOptions.afterShow(primaryStage, mainController);
        } catch (IOException e) {
            log.error("Cannot start the user interface", e);
            Platform.exit();
        }
    }

    /**
     * Without a GPU (Raspberry Pi with -Dprism.order=sw) drop shadows and blurs are computed by the CPU on every
     * repaint (in a profiled game ~60% of the renderer's CPU samples). The root gets the style class
     * "software-rendering" for the stylesheets; {@code -Djavachess.effects=off} removes every effect
     * ("auto" = off for software rendering on ARM; default "on" until measured on the Pi).
     */
    private static void applyRenderingProfile(Scene scene) {
        boolean software = !Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D);
        if (software) {
            scene.getRoot().getStyleClass().add("software-rendering");
        }
        String effects = System.getProperty("javachess.effects", "on");
        boolean arm = System.getProperty("os.arch", "").matches("(?i)aarch64|arm.*");
        if ("off".equalsIgnoreCase(effects) || ("auto".equalsIgnoreCase(effects) && software && arm)) {
            scene.getStylesheets().add("data:text/css,*%7B-fx-effect:null;%7D");
            log.info("Visual effects disabled (software rendering)");
        }
    }

    @Override
    public void stop() {
        log.info("Stopping application...");
        // A game in progress is archived (as interrupted) like when leaving the game screen.
        try {
            if (mainController != null
                    && mainController.getController("GAME") instanceof io.github.hardin22.javachess.Controllers.NavigationAware game) {
                game.onNavigatedFrom();
            }
        } catch (RuntimeException e) {
            log.warn("Could not save the game in progress: {}", e.toString());
        }
        // ...and so is a game followed in the integrated browser (only if it was opened: no view loaded for this)
        try {
            if (mainController != null && io.github.hardin22.javachess.Browser.JcefRuntime.app() != null
                    && mainController.getController("BROWSER")
                    instanceof io.github.hardin22.javachess.Controllers.BrowserController browser) {
                browser.onAppExit();
            }
        } catch (RuntimeException e) {
            log.warn("Could not save the browser game in progress: {}", e.toString());
        }
        if (Hardware.isInitialized()) {
            Hardware.shutdown(); // LEDs off, serial port closed
        }
        AppExecutors.shutdown(); // pending archive writes are completed first
        io.github.hardin22.javachess.Browser.JcefRuntime.shutdown();
        io.github.hardin22.javachess.Engine.EngineManager.shutdownIfStarted(); // engines get "quit" before the kill below
        stopChildProcesses();
        logLingeringThreads();
        // Last resort for threads started by libraries that do not use daemon threads.
        System.exit(exitCode);
    }

    /** Exit status of the process; {@link #RESTART_EXIT_CODE} asks the service manager to start the app again. */
    private static volatile int exitCode = 0;

    /** systemd restarts the app on a non-zero exit (deploy/javachess.service, Restart=on-failure). */
    public static final int RESTART_EXIT_CODE = 75;

    public static void setExitCode(int code) {
        exitCode = code;
    }

    /** Engines (Stockfish, Lc0) and browser helpers must not outlive the app. */
    private static void stopChildProcesses() {
        List<ProcessHandle> children = ProcessHandle.current().descendants().toList();
        if (children.isEmpty()) {
            return;
        }
        children.forEach(ProcessHandle::destroy);
        for (ProcessHandle child : children) {
            try {
                child.onExit().get(500, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                child.destroyForcibly();
            }
        }
        log.info("Stopped {} child process(es)", children.size());
    }

    private static void logLingeringThreads() {
        List<String> names = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && !t.isDaemon() && t != Thread.currentThread())
                .map(Thread::getName)
                .filter(n -> !n.equals("DestroyJavaVM") && !n.equals("main") && !n.startsWith("JavaFX") && !n.startsWith("QuantumRenderer")
                        && !n.startsWith("InvokeLaterDispatcher") && !n.startsWith("AWT-"))
                .sorted()
                .toList();
        if (!names.isEmpty()) {
            log.info("Non-daemon threads still alive at exit: {}", names);
        }
    }

    public static void main(String[] args) {
        Bootstrap.init(); // logging, ~/.javachess data folder + migration, global exception handler
        // Persistent cookies for the HTTP clients (Lichess); cheap, keeps sessions across restarts
        try {
            java.net.CookieManager cookieManager = new java.net.CookieManager(
                    new io.github.hardin22.javachess.Utils.PersistentCookieStore(),
                    java.net.CookiePolicy.ACCEPT_ALL);
            java.net.CookieHandler.setDefault(cookieManager);
        } catch (Exception e) {
            log.warn("Persistent cookies unavailable: {}", e.getMessage());
        }

        // Ctrl+C / kill: turn the LEDs off and release JCEF
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (Hardware.isInitialized()) {
                Hardware.shutdown();
            }
            AppExecutors.shutdown(); // finish pending archive / puzzle progress writes
            io.github.hardin22.javachess.Browser.JcefRuntime.shutdown();
        }, "shutdown-hook"));

        launch(args);
    }
}
