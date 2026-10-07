package org.example.javachess.Controllers;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.example.javachess.Components.I18n;
import org.example.javachess.Components.Icons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Root controller: owns the view container, loads views lazily (and preloads them on a background thread so the
 * FX thread never blocks on FXML parsing), and hosts the global overlays (bottom sheet and toast).
 *
 * <p>View names are stable identifiers also used by {@code DevOptions} ({@code -Djavachess.view=NAME}).</p>
 */
public class MainController {

    private static final Logger LOG = LoggerFactory.getLogger(MainController.class);

    /** View name -> FXML path. Names are public API (DevOptions, other controllers). */
    private static final Map<String, String> VIEWS = new LinkedHashMap<>();
    static {
        VIEWS.put("HOME", "/UI/HomeView.fxml");
        VIEWS.put("PVC_SETUP", "/UI/PvCSetupView.fxml");
        VIEWS.put("PVP_SETUP", "/UI/PvPSetupView.fxml");
        VIEWS.put("LICHESS_SETUP", "/UI/LichessSetupView.fxml");
        VIEWS.put("GAME", "/UI/GameView.fxml");
        VIEWS.put("ARCHIVE", "/UI/ArchiveView.fxml");
        VIEWS.put("REVIEW", "/UI/ReviewView.fxml");
        VIEWS.put("PUZZLE_DASHBOARD", "/UI/PuzzleDashboardView.fxml");
        VIEWS.put("PUZZLE_GAME", "/UI/PuzzleView.fxml");
        VIEWS.put("THEME", "/UI/ThemeView.fxml");
        VIEWS.put("SETTINGS", "/UI/SettingsView.fxml");
        VIEWS.put("BROWSER", "/UI/BrowserView.fxml");
    }

    /** Views that must be created on the FX thread (JCEF/Swing bridge), never preloaded. */
    private static final java.util.Set<String> FX_ONLY = java.util.Set.of("BROWSER");

    private record Loaded(Parent view, Object controller) {
    }

    @FXML
    private StackPane rootPane;
    @FXML
    private StackPane mainContainer;
    @FXML
    private StackPane sheetLayer;
    @FXML
    private VBox toastLayer;

    private final Map<String, String> extraPaths = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Loaded>> loads = new ConcurrentHashMap<>();
    private final ExecutorService preloader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "view-preloader");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private String currentViewName;
    private Runnable onSheetClosed;

    public StackPane getMainContainer() {
        return mainContainer;
    }

    @FXML
    public void initialize() {
        sheetLayer.setVisible(false);
        sheetLayer.setOnMouseClicked(e -> {
            if (e.getTarget() == sheetLayer) {
                closeSheet();
            }
        });
        rootPane.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && sheetLayer.isVisible()) {
                closeSheet();
            }
        });
        navigateTo("HOME");
        // Preload the other views off the FX thread, one at a time, so the first tap on any tile is instant.
        Platform.runLater(() -> VIEWS.keySet().stream()
                .filter(name -> !"HOME".equals(name) && !FX_ONLY.contains(name))
                .forEach(name -> preloader.submit(() -> {
                    try {
                        ensureLoaded(name);
                    } catch (RuntimeException e) {
                        LOG.warn("Preload of {} failed", name, e);
                    }
                })));
    }

    // ------------------------------------------------------------------ navigation

    public void navigateTo(String viewName) {
        Loaded loaded;
        try {
            loaded = ensureLoaded(viewName);
        } catch (RuntimeException e) {
            LOG.error("Cannot open view {}", viewName, e);
            showToast(I18n.t("error.view", viewName));
            return;
        }
        if (loaded == null) {
            LOG.error("Unknown view {}", viewName);
            return;
        }
        if (viewName.equals(currentViewName) && mainContainer.getChildren().contains(loaded.view())) {
            notifyNavigatedTo(loaded.controller());
            return;
        }
        if (currentViewName != null) {
            CompletableFuture<Loaded> current = loads.get(currentViewName);
            if (current != null && current.isDone() && !current.isCompletedExceptionally()
                    && current.join().controller() instanceof NavigationAware aware) {
                aware.onNavigatedFrom();
            }
        }
        closeSheet();
        mainContainer.getChildren().setAll(loaded.view());
        currentViewName = viewName;
        notifyNavigatedTo(loaded.controller());
    }

    private static void notifyNavigatedTo(Object controller) {
        if (controller instanceof NavigationAware aware) {
            aware.onNavigatedTo();
        }
    }

    public String getCurrentViewName() {
        return currentViewName;
    }

    /** Registers (if needed) and loads a view. Kept for compatibility: callers may pass custom FXML paths. */
    public void loadView(String name, String fxmlPath) {
        if (!VIEWS.containsKey(name) && fxmlPath != null) {
            extraPaths.put(name, fxmlPath);
        }
        ensureLoaded(name);
    }

    public Object getController(String name) {
        Loaded loaded = ensureLoaded(name);
        return loaded == null ? null : loaded.controller();
    }

    /** Loads the view once; concurrent callers (FX thread and preloader) share the same result. */
    private Loaded ensureLoaded(String name) {
        String path = VIEWS.getOrDefault(name, extraPaths.get(name));
        if (path == null) {
            return null;
        }
        CompletableFuture<Loaded> future = loads.get(name);
        if (future == null) {
            CompletableFuture<Loaded> mine = new CompletableFuture<>();
            future = loads.putIfAbsent(name, mine);
            if (future == null) {
                future = mine;
                try {
                    mine.complete(load(name, path));
                } catch (RuntimeException e) {
                    loads.remove(name);
                    mine.completeExceptionally(e);
                    throw e;
                }
            }
        }
        return future.join();
    }

    private Loaded load(String name, String fxmlPath) {
        long start = System.nanoTime();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath), I18n.bundle());
            Parent view = loader.load();
            Object controller = loader.getController();
            if (controller instanceof NavigationAware aware) {
                aware.setMainController(this);
            }
            LOG.debug("Loaded view {} in {} ms on {}", name, (System.nanoTime() - start) / 1_000_000,
                    Thread.currentThread().getName());
            return new Loaded(view, controller);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load view " + name + " from " + fxmlPath, e);
        }
    }

    public void openLichess() {
        Thread.ofVirtual().name("lichess-check").start(() -> {
            String gameId = org.example.javachess.Utils.LichessAPIHelper.getGameId();
            Platform.runLater(() -> {
                if (gameId != null) {
                    ActiveGameController controller = (ActiveGameController) getController("GAME");
                    navigateTo("GAME");
                    controller.startOnlineGame(gameId);
                } else {
                    navigateTo("LICHESS_SETUP");
                }
            });
        });
    }

    /** Rotates the whole UI by 180 degrees (monitor mounted upside down / facing the other player). */
    public void rotateScreen() {
        rootPane.setRotate(rootPane.getRotate() == 0 ? 180 : 0);
    }

    public boolean isRotated() {
        return rootPane.getRotate() != 0;
    }

    // ------------------------------------------------------------------ overlays

    /**
     * Shows a bottom sheet (centred dialog on wide screens) with a title, the given content and a close button.
     * Tapping outside or pressing Esc closes it.
     */
    public void showSheet(String title, Node content) {
        showSheet(title, content, null);
    }

    public void showSheet(String title, Node content, Runnable onClosed) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("sheet-title");
        Button close = new Button();
        close.getStyleClass().addAll("btn", "btn-ghost", "icon-btn");
        close.setGraphic(Icons.of("fth-x", 22));
        close.setAccessibleText(I18n.t("common.close"));
        close.setOnAction(e -> closeSheet());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(12, titleLabel, spacer, close);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox sheet = new VBox(16, head, content);
        sheet.getStyleClass().add("sheet");
        boolean wide = rootPane.getWidth() > rootPane.getHeight();
        sheet.setMaxWidth(wide ? 640 : Double.MAX_VALUE);
        sheet.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(sheet, wide ? Pos.CENTER : Pos.BOTTOM_CENTER);
        if (!wide) {
            sheet.getStyleClass().add("sheet-bottom");
        }

        onSheetClosed = onClosed;
        sheetLayer.getChildren().setAll(sheet);
        sheetLayer.setVisible(true);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), sheet);
        slide.setFromY(wide ? 16 : 120);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        slide.play();
    }

    public void closeSheet() {
        if (sheetLayer == null || !sheetLayer.isVisible()) {
            return;
        }
        sheetLayer.setVisible(false);
        sheetLayer.getChildren().clear();
        Runnable callback = onSheetClosed;
        onSheetClosed = null;
        if (callback != null) {
            callback.run();
        }
    }

    /** Short message at the bottom of the screen for ~2.5 s. Safe to call from any thread. */
    public void showToast(String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showToast(message));
            return;
        }
        if (toastLayer == null) {
            return;
        }
        Label toast = new Label(message);
        toast.getStyleClass().add("toast");
        toast.setWrapText(true);
        toast.setMaxWidth(560);
        toastLayer.getChildren().setAll(toast);
        FadeTransition in = new FadeTransition(Duration.millis(150), toast);
        in.setFromValue(0);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(Duration.millis(200), toast);
        out.setToValue(0);
        SequentialTransition seq = new SequentialTransition(in, new PauseTransition(Duration.seconds(2.5)), out);
        seq.setOnFinished(e -> toastLayer.getChildren().remove(toast));
        seq.play();
    }
}
