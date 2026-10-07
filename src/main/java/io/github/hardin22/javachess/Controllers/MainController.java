package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
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
import javafx.scene.input.RotateEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.RotateButton;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Utils.ErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Root controller: owns the view container, creates screens lazily (and preloads them on a background thread so the
 * FX thread never waits for a screen to be built), the orientation of the whole interface and the global overlays
 * (bottom sheets and toasts).
 *
 * <p>View names are stable identifiers also used by {@code DevOptions} ({@code -Djavachess.view=NAME}) and the tests.
 *
 * <h2>Orientation</h2>
 * The screen sits beside the physical board; its "bottom" end faces one player. {@code ui.screen.flipped} records how
 * the monitor is mounted (bottom end on White's side or not). On top of that the interface can face the other end:
 * games against the computer and puzzles face the human ({@link #face(Side)}), the rotate button and a two-finger
 * twist turn it at any time ({@link #rotateScreen()}).
 */
public class MainController {

    private static final Logger LOG = LoggerFactory.getLogger(MainController.class);
    public static final String FLIPPED_KEY = "ui.screen.flipped";
    public static final String AUTOROTATE_KEY = "ui.autorotate";

    /** How a view is built: an FXML file, or a {@link Screen} created in code. */
    private record ViewSpec(String fxml, Supplier<? extends Screen> factory) {
    }

    /** View name -> how to build it. Names are public API (DevOptions, other controllers, tests). */
    private static final Map<String, ViewSpec> VIEWS = new LinkedHashMap<>();
    static {
        VIEWS.put("HOME", new ViewSpec(null, HomeController::new));
        VIEWS.put("PVC_SETUP", new ViewSpec(null, PvcSetupController::new));
        VIEWS.put("PVP_SETUP", new ViewSpec(null, PvpSetupController::new));
        VIEWS.put("LICHESS_SETUP", new ViewSpec(null, LichessSetupController::new));
        VIEWS.put("GAME", new ViewSpec(null, ActiveGameController::new));
        VIEWS.put("ARCHIVE", new ViewSpec(null, ArchiveController::new));
        VIEWS.put("REVIEW", new ViewSpec(null, ReviewController::new));
        VIEWS.put("TRAINER", new ViewSpec(null, TrainerController::new));
        VIEWS.put("PUZZLE_DASHBOARD", new ViewSpec(null, PuzzleDashboardController::new));
        VIEWS.put("PUZZLE_GAME", new ViewSpec(null, PuzzleController::new));
        VIEWS.put("THEME", new ViewSpec(null, ThemeController::new));
        VIEWS.put("SETTINGS", new ViewSpec(null, SettingsController::new));
        VIEWS.put("BROWSER", new ViewSpec("/UI/BrowserView.fxml", null));
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
    private boolean sheetModal;

    /** Monitor mounted with its bottom end on Black's side (persistent). */
    private boolean mountedFlipped;
    /**
     * The interface currently faces the far end of a normally mounted monitor (manual rotation, or a game facing
     * Black). Boards on screen are drawn from Black's side while this is true, so they match the physical board.
     */
    private final javafx.beans.property.ReadOnlyBooleanWrapper facingFar =
            new javafx.beans.property.ReadOnlyBooleanWrapper(false);
    private RotateTransition rotation;
    private double twist;

    public StackPane getMainContainer() {
        return mainContainer;
    }

    @FXML
    public void initialize() {
        sheetLayer.setVisible(false);
        sheetLayer.setOnMouseClicked(e -> {
            if (e.getTarget() == sheetLayer && !sheetModal) {
                closeSheet();
            }
        });
        rootPane.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && sheetLayer.isVisible()) {
                closeSheet();
            } else if (e.getCode() == KeyCode.R && e.isShortcutDown()) {
                rotateScreen();
            }
        });
        // Two-finger twist on the touch screen turns the interface by 180 degrees.
        rootPane.addEventFilter(RotateEvent.ROTATION_STARTED, e -> twist = 0);
        rootPane.addEventFilter(RotateEvent.ROTATE, e -> twist = e.getTotalAngle());
        rootPane.addEventFilter(RotateEvent.ROTATION_FINISHED, e -> {
            if (Math.abs(twist) >= 70) {
                rotateScreen();
            }
            twist = 0;
        });
        RotateButton.setAction(this::rotateScreen);
        ErrorReporter.setPresenter(this::showError);
        mountedFlipped = Prefs.bool(FLIPPED_KEY, false) ^ Boolean.getBoolean("javachess.rotated");
        // The scene root has the window's size; a screen's minimum size cannot hide a change of proportions.
        rootPane.widthProperty().addListener((obs, o, n) -> updateWide());
        rootPane.heightProperty().addListener((obs, o, n) -> updateWide());
        applyRotation(false);
        navigateTo("HOME");
    }

    /**
     * Builds the other screens off the FX thread, one at a time, so the first tap on any tile is instant.
     * Called by App after the first frame; disabled with {@code -Djavachess.preload=false}.
     */
    public void startIdlePreload() {
        if (!Boolean.parseBoolean(System.getProperty("javachess.preload", "true"))) {
            return;
        }
        VIEWS.keySet().stream()
                .filter(name -> !"HOME".equals(name) && !FX_ONLY.contains(name))
                .forEach(name -> preloader.submit(() -> {
                    try {
                        ensureLoaded(name);
                    } catch (RuntimeException e) {
                        LOG.warn("Preload of {} failed", name, e);
                    }
                }));
        preloader.submit(() -> LOG.info("Views preloaded at {} ms",
                io.github.hardin22.javachess.Application.StartupMetrics.uptimeMs()));
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
        boolean animate = currentViewName != null && Ui.animations();
        mainContainer.getChildren().setAll(loaded.view());
        currentViewName = viewName;
        if (loaded.controller() instanceof Screen screen) {
            screen.setWide(wide);
        }
        notifyNavigatedTo(loaded.controller());
        if (animate) {
            enter(loaded.view());
        } else {
            loaded.view().setOpacity(1);
            loaded.view().setTranslateY(0);
        }
    }

    /** New screen: 160 ms fade and a 24 px rise. */
    private static void enter(Node view) {
        FadeTransition fade = new FadeTransition(Duration.millis(160), view);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(160), view);
        rise.setFromY(24);
        rise.setToY(0);
        rise.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, rise).play();
    }

    private boolean wide;

    /** Tells the current screen when the window becomes wider than tall, or taller than wide. */
    private void updateWide() {
        double w = rootPane.getWidth();
        double h = rootPane.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean now = w > h * 1.05;
        if (now != wide) {
            wide = now;
            rootPane.getStyleClass().remove("wide");
            if (wide) {
                rootPane.getStyleClass().add("wide");
            }
            CompletableFuture<Loaded> current = currentViewName == null ? null : loads.get(currentViewName);
            if (current != null && current.isDone() && !current.isCompletedExceptionally()
                    && current.join().controller() instanceof Screen screen) {
                screen.setWide(wide);
            }
        }
    }

    public boolean isWide() {
        return wide;
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
        ViewSpec spec = VIEWS.get(name);
        if (spec == null && extraPaths.containsKey(name)) {
            spec = new ViewSpec(extraPaths.get(name), null);
        }
        if (spec == null) {
            return null;
        }
        CompletableFuture<Loaded> future = loads.get(name);
        if (future == null) {
            CompletableFuture<Loaded> mine = new CompletableFuture<>();
            future = loads.putIfAbsent(name, mine);
            if (future == null) {
                future = mine;
                try {
                    mine.complete(load(name, spec));
                } catch (RuntimeException e) {
                    loads.remove(name);
                    mine.completeExceptionally(e);
                    throw e;
                }
            }
        }
        return future.join();
    }

    private Loaded load(String name, ViewSpec spec) {
        long start = System.nanoTime();
        Loaded loaded;
        if (spec.factory() != null) {
            Screen screen = spec.factory().get();
            screen.setMainController(this);
            Parent root = screen.getRoot();
            root.getStyleClass().add("screen");
            loaded = new Loaded(root, screen);
        } else {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(spec.fxml()), I18n.bundle());
                Parent view = loader.load();
                Object controller = loader.getController();
                if (controller instanceof NavigationAware aware) {
                    aware.setMainController(this);
                }
                loaded = new Loaded(view, controller);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load view " + name + " from " + spec.fxml(), e);
            }
        }
        LOG.debug("Loaded view {} in {} ms on {}", name, (System.nanoTime() - start) / 1_000_000,
                Thread.currentThread().getName());
        return loaded;
    }

    /** Lichess and Chess.com are played in the integrated browser (the API flow stays available in LICHESS_SETUP). */
    public void openBrowser(String url) {
        Object controller = getController("BROWSER");
        if (controller instanceof BrowserController browser) {
            browser.loadPage(url);
        }
        navigateTo("BROWSER");
    }

    /** Kept for callers of the Lichess Board API flow: resumes a running game or opens its setup screen. */
    public void openLichess() {
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            String gameId = io.github.hardin22.javachess.Utils.LichessAPIHelper.getGameId();
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

    // ------------------------------------------------------------------ orientation

    /** Turns the whole interface by 180 degrees (the person at the other end of the screen wants to read it). */
    public void rotateScreen() {
        facingFar.set(!facingFar.get());
        applyRotation(true);
    }

    /**
     * Makes the interface face the player of {@code side} (White sits at the bottom end of a monitor mounted the
     * normal way). Does nothing when automatic orientation is off in the settings.
     */
    public void face(Side side) {
        if (!Prefs.bool(AUTOROTATE_KEY, true)) {
            return;
        }
        boolean far = side == Side.BLACK;
        if (far != facingFar.get()) {
            facingFar.set(far);
            applyRotation(true);
        }
    }

    /** True when the interface is shown upside down with respect to the monitor. */
    public boolean isRotated() {
        return mountedFlipped ^ facingFar.get();
    }

    /** True while the interface faces Black's end: boards are drawn from Black's side. */
    public boolean isFacingBlack() {
        return facingFar.get();
    }

    public javafx.beans.property.ReadOnlyBooleanProperty facingBlackProperty() {
        return facingFar.getReadOnlyProperty();
    }

    public boolean isMountedFlipped() {
        return mountedFlipped;
    }

    /** "Monitor capovolto": how the monitor is mounted. Persistent. */
    public void setMountedFlipped(boolean flipped) {
        if (flipped != mountedFlipped) {
            mountedFlipped = flipped;
            Prefs.set(FLIPPED_KEY, flipped);
            applyRotation(true);
        }
    }

    private void applyRotation(boolean animate) {
        double target = isRotated() ? 180 : 0;
        Ui.ROTATED.set(isRotated());
        if (rotation != null) {
            rotation.stop();
        }
        double current = ((rootPane.getRotate() % 360) + 360) % 360;
        if (!animate || !Ui.animations() || rootPane.getScene() == null || Math.abs(current - target) < 0.5) {
            rootPane.setRotate(target);
            return;
        }
        rotation = new RotateTransition(Duration.millis(240), rootPane);
        rotation.setFromAngle(current);
        rotation.setToAngle(target == 0 && current > 90 ? 360 : target);
        rotation.setInterpolator(Interpolator.EASE_BOTH);
        rotation.setOnFinished(e -> rootPane.setRotate(target));
        rotation.play();
    }

    // ------------------------------------------------------------------ overlays

    /** Bottom sheet with a title, the given content, rotate and close buttons. Tapping outside closes it. */
    public void showSheet(String title, Node content) {
        showSheet(title, content, null);
    }

    public void showSheet(String title, Node content, Runnable onClosed) {
        openSheet(title, content, onClosed, false, false);
    }

    /**
     * Sheet for the player at the far end of the screen (two-player games): it opens from the top edge, turned
     * towards them.
     */
    public void showSheetFor(boolean farEnd, String title, Node content) {
        openSheet(title, content, null, farEnd, false);
    }

    /** Sheet that only its own buttons can close (confirmations that must be answered). */
    public void showModalSheet(String title, Node content) {
        openSheet(title, content, null, false, true);
    }

    private void openSheet(String title, Node content, Runnable onClosed, boolean farEnd, boolean modal) {
        Region grip = new Region();
        grip.getStyleClass().add("sheet-grip");
        HBox gripRow = new HBox(grip);
        gripRow.setAlignment(Pos.CENTER);

        Label titleLabel = Ui.wrap(title, "sheet-title");
        HBox head = new HBox(16, titleLabel, Ui.hgrow());
        head.setAlignment(Pos.CENTER_LEFT);
        if (!farEnd) {
            head.getChildren().add(RotateButton.create());
        }
        if (!modal) {
            Button close = Ui.iconButton("fth-x", I18n.t("common.close"), this::closeSheet);
            head.getChildren().add(close);
        }
        VBox sheet = new VBox(20, gripRow, head, content);
        sheet.getStyleClass().add("sheet");
        sheet.setMaxHeight(Region.USE_PREF_SIZE);
        boolean wide = rootPane.getWidth() > rootPane.getHeight();
        sheet.setMaxWidth(wide ? 760 : Double.MAX_VALUE);
        StackPane.setAlignment(sheet, wide ? Pos.CENTER : Pos.BOTTOM_CENTER);
        if (wide) {
            sheet.getStyleClass().add("sheet-center");
        }
        sheet.setOnMouseClicked(javafx.event.Event::consume);

        sheetModal = modal;
        onSheetClosed = onClosed;
        sheetLayer.setRotate(farEnd ? 180 : 0);
        sheetLayer.getChildren().setAll(sheet);
        sheetLayer.setVisible(true);
        if (Ui.animations()) {
            TranslateTransition slide = new TranslateTransition(Duration.millis(180), sheet);
            slide.setFromY(wide ? 24 : 160);
            slide.setToY(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            slide.play();
        }
    }

    public boolean isSheetOpen() {
        return sheetLayer != null && sheetLayer.isVisible();
    }

    public void closeSheet() {
        if (sheetLayer == null || !sheetLayer.isVisible()) {
            return;
        }
        sheetLayer.setVisible(false);
        sheetLayer.getChildren().clear();
        sheetModal = false;
        Runnable callback = onSheetClosed;
        onSheetClosed = null;
        if (callback != null) {
            callback.run();
        }
    }

    /** Errors reported through ErrorReporter: a sheet inside the app instead of a separate OS window. */
    private void showError(String title, String message) {
        Label text = Ui.wrap(message, "t-body", "t-muted");
        Button ok = Ui.wide(I18n.t("common.ok"), null, "btn-inverse", "btn-lg");
        ok.setOnAction(e -> closeSheet());
        showSheet(title, new VBox(28, text, ok));
    }

    /** Short message near the bottom of the screen for ~2.5 s. Safe to call from any thread. */
    public void showToast(String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showToast(message));
            return;
        }
        if (toastLayer == null) {
            return;
        }
        Label toast = Ui.wrap(message, "toast");
        toast.setMaxWidth(640);
        toast.setMaxHeight(Region.USE_PREF_SIZE);
        toastLayer.getChildren().setAll(toast);
        FadeTransition in = new FadeTransition(Duration.millis(150), toast);
        in.setFromValue(0);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(Duration.millis(200), toast);
        out.setToValue(0);
        SequentialTransition seq = new SequentialTransition(in, new PauseTransition(Duration.seconds(2.6)), out);
        seq.setOnFinished(e -> toastLayer.getChildren().remove(toast));
        seq.play();
    }
}
