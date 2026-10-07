package org.example.javachess.Application;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.example.javachess.Controllers.MainController;
import org.example.javachess.Hardware.Hardware;
import org.example.javachess.Utils.AppExecutors;
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
        try {
            var resource = App.class.getResource("/UI/MainLayout.fxml");
            if (resource == null) {
                throw new IllegalStateException("Cannot find /UI/MainLayout.fxml");
            }
            FXMLLoader fxmlLoader = new FXMLLoader(resource);
            org.example.javachess.Components.ThemeManager.loadFonts();
            Scene scene = new Scene(fxmlLoader.load(), 720, 1280);
            // Fonts, stylesheet, light/dark theme, window title and icons (UI layer).
            org.example.javachess.Components.ThemeManager.install(scene, primaryStage);

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
                AppExecutors.io().execute(() -> {
                    Hardware.get();
                    org.example.javachess.Hardware.CoachLeds.install(); // move-quality LEDs from the engine coach
                });
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
                    && mainController.getController("GAME") instanceof org.example.javachess.Controllers.NavigationAware game) {
                game.onNavigatedFrom();
            }
        } catch (RuntimeException e) {
            log.warn("Could not save the game in progress: {}", e.toString());
        }
        if (Hardware.isInitialized()) {
            Hardware.shutdown(); // LEDs off, serial port closed
        }
        AppExecutors.shutdown(); // pending archive writes are completed first
        org.example.javachess.Controllers.BrowserController.disposeIfStarted();
        org.example.javachess.Engine.EngineManager.shutdownIfStarted(); // engines get "quit" before the kill below
        stopChildProcesses();
        logLingeringThreads();
        // Last resort for threads started by libraries that do not use daemon threads.
        System.exit(0);
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
                    new org.example.javachess.Utils.PersistentCookieStore(),
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
            org.example.javachess.Controllers.BrowserController.disposeIfStarted();
        }, "shutdown-hook"));

        launch(args);
    }
}
