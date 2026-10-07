package org.example.javachess.Controllers;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.example.javachess.Utils.AppExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root of the UI: hosts one view at a time and loads views on demand.
 *
 * <p>Only HOME is built before the first frame. The views the user is most likely to open next are then
 * built in the background of the UI, one per idle slot ({@link #startIdlePreload()}), so start-up is fast and
 * a tap is never delayed by more than one view load. Any other view is loaded the first time it is shown.</p>
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    /** FXML of every view, by name. */
    private static final Map<String, String> VIEW_PATHS = new LinkedHashMap<>();

    static {
        VIEW_PATHS.put("HOME", "/UI/HomeView.fxml");
        VIEW_PATHS.put("PVC_SETUP", "/UI/PvCSetupView.fxml");
        VIEW_PATHS.put("GAME", "/UI/GameView.fxml");
        VIEW_PATHS.put("PVP_SETUP", "/UI/PvPSetupView.fxml");
        VIEW_PATHS.put("PUZZLE_DASHBOARD", "/UI/PuzzleDashboardView.fxml");
        VIEW_PATHS.put("PUZZLE_GAME", "/UI/PuzzleView.fxml");
        VIEW_PATHS.put("ARCHIVE", "/UI/ArchiveView.fxml");
        VIEW_PATHS.put("REVIEW", "/UI/ReviewView.fxml");
        VIEW_PATHS.put("THEME", "/UI/ThemeView.fxml");
        VIEW_PATHS.put("SETTINGS", "/UI/SettingsView.fxml");
        VIEW_PATHS.put("LICHESS_SETUP", "/UI/LichessSetupView.fxml");
        VIEW_PATHS.put("BROWSER", "/UI/BrowserView.fxml");
    }

    /** Views built ahead of time, most likely first. The browser (JCEF) is never preloaded. */
    private static final List<String> PRELOAD_ORDER = List.of("PVC_SETUP", "GAME", "PVP_SETUP", "PUZZLE_DASHBOARD",
            "ARCHIVE", "THEME", "SETTINGS", "REVIEW", "LICHESS_SETUP");
    /** Pause between two preloaded views, leaving the FX thread free for input and animations. */
    private static final Duration PRELOAD_GAP = Duration.millis(120);

    @FXML
    private StackPane mainContainer;

    private final Map<String, Parent> views = new HashMap<>();
    private final Map<String, Object> controllers = new HashMap<>();
    private final Deque<String> preloadQueue = new ArrayDeque<>();
    private String currentViewName;

    public StackPane getMainContainer() {
        return mainContainer;
    }

    @FXML
    public void initialize() {
        navigateTo("HOME");
    }

    /**
     * Builds the other likely views one at a time while the UI is idle. Call once after the first frame.
     * Disabled with {@code -Djavachess.preload=false}.
     */
    public void startIdlePreload() {
        if (!Boolean.parseBoolean(System.getProperty("javachess.preload", "true"))) {
            return;
        }
        preloadQueue.addAll(PRELOAD_ORDER);
        scheduleNextPreload();
    }

    private void scheduleNextPreload() {
        PauseTransition gap = new PauseTransition(PRELOAD_GAP);
        gap.setOnFinished(e -> {
            String next = preloadQueue.poll();
            if (next == null) {
                log.info("Views preloaded at {} ms", org.example.javachess.Application.StartupMetrics.uptimeMs());
                return;
            }
            if (!views.containsKey(next)) {
                loadView(next, VIEW_PATHS.get(next));
            }
            scheduleNextPreload();
        });
        gap.play();
    }

    public void navigateTo(String viewName) {
        Parent view = views.get(viewName);
        if (view == null && VIEW_PATHS.containsKey(viewName)) {
            loadView(viewName, VIEW_PATHS.get(viewName));
            view = views.get(viewName);
        }
        if (view == null) {
            log.error("View not found: {}", viewName);
            return;
        }
        preloadQueue.remove(viewName);

        // Notify current controller that we are leaving
        if (currentViewName != null) {
            Object currentController = controllers.get(currentViewName);
            if (currentController instanceof NavigationAware aware) {
                aware.onNavigatedFrom();
            }
        }

        mainContainer.getChildren().setAll(view);
        currentViewName = viewName;

        // Notify new controller that we have arrived
        Object newController = controllers.get(viewName);
        if (newController instanceof NavigationAware aware) {
            aware.onNavigatedTo();
        }
    }

    public void loadView(String name, String fxmlPath) {
        if (views.containsKey(name)) {
            return; // Already loaded
        }
        long start = System.nanoTime();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent view = loader.load();
            views.put(name, view);

            Object controller = loader.getController();
            if (controller instanceof NavigationAware aware) {
                aware.setMainController(this);
            }
            controllers.put(name, controller);
            log.debug("View {} loaded in {} ms", name, (System.nanoTime() - start) / 1_000_000);
        } catch (IOException e) {
            log.error("Failed to load view {} from {}", name, fxmlPath, e);
        }
    }

    public Object getController(String name) {
        return controllers.get(name);
    }

    public void openLichess() {
        // Native integration: look for an active game without blocking the UI
        AppExecutors.io().execute(() -> {
            String gameId = org.example.javachess.Utils.LichessAPIHelper.getGameId();
            Platform.runLater(() -> {
                if (gameId != null) {
                    navigateTo("GAME");
                    ActiveGameController controller = (ActiveGameController) getController("GAME");
                    if (controller != null) {
                        controller.startOnlineGame(gameId);
                    }
                } else {
                    log.info("No active Lichess game, opening the setup");
                    navigateTo("LICHESS_SETUP");
                }
            });
        });
    }

    @FXML
    private void rotateScreen() {
        double currentRotate = mainContainer.getRotate();
        mainContainer.setRotate(currentRotate == 0 ? 180 : 0);
    }
}
