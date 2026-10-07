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
            // after the first frame: connect the board and build the other views in idle time
            Platform.runLater(() -> {
                AppExecutors.io().execute(Hardware::get);
                mainController.startIdlePreload();
            });
            DevOptions.afterShow(primaryStage, mainController);
        } catch (IOException e) {
            log.error("Cannot start the user interface", e);
            Platform.exit();
        }
    }

    @Override
    public void stop() {
        log.info("Stopping application...");
        if (Hardware.isInitialized()) {
            Hardware.shutdown(); // LEDs off, serial port closed
        }
        AppExecutors.shutdown(); // pending archive writes are completed first
        org.example.javachess.Controllers.BrowserController.disposeIfStarted();
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
                .filter(n -> !n.equals("DestroyJavaVM") && !n.startsWith("JavaFX") && !n.startsWith("QuantumRenderer")
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
            org.example.javachess.Controllers.BrowserController.disposeIfStarted();
        }, "shutdown-hook"));

        launch(args);
    }
}
