package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.EnginePicker;
import io.github.hardin22.javachess.Components.HardwareStatus;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.StatusChip;
import io.github.hardin22.javachess.Components.Stepper;
import io.github.hardin22.javachess.Components.ThemeManager;
import io.github.hardin22.javachess.Components.TouchKeyboard;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Utils.ConfigManager;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Settings. Every change applies and is saved at once (no "Salva" button to remember). The "Avanzate" page holds
 * the analysis depth and the accounts, edited with the on-screen keyboard.
 */
public class SettingsController implements Screen {

    private static final int[] MOVETIMES = { 500, 1000, 1500, 2000, 3000, 5000, 8000, 10000 };
    private static final int[] MINUTES = { 1, 2, 3, 4, 5, 7, 10, 12, 15, 20, 25, 30, 45, 60, 90 };
    private static final int[] INCREMENTS = { 0, 1, 2, 3, 5, 10, 15, 20, 30 };

    private MainController mainController;
    private final StackPane root = new StackPane();
    private final VBox mainPage = new VBox();
    private final VBox advancedPage = new VBox();
    private final ToggleGroup themeGroup = new ToggleGroup();
    private final StatusChip boardStatus = new StatusChip();
    private final Label lichessStatus = Ui.wrap("", "row-sub");
    private final Button lichessButton = Ui.button("", "fth-link", "btn-outline", "btn-md");
    private final VBox accountFields = new VBox(0);
    private ToggleButton flippedSwitch;
    private ToggleButton autoRotateSwitch;
    private boolean lichessBusy;
    private boolean updating;

    public SettingsController() {
        build();
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    // ================================================================== pages

    private void build() {
        ScreenHeader header = new ScreenHeader(I18n.t("settings.title"), () -> mainController.navigateTo("HOME"));
        header.setSubtitle(I18n.t("settings.subtitle"));

        // appearance
        ToggleButton dark = segment(I18n.t("theme.dark"), "fth-moon", ThemeManager.Mode.DARK);
        ToggleButton light = segment(I18n.t("theme.light"), "fth-sun", ThemeManager.Mode.LIGHT);
        ToggleButton system = segment(I18n.t("theme.system"), "fth-monitor", ThemeManager.Mode.SYSTEM);
        HBox themes = Ui.segmented(themeGroup, List.of(dark, light, system));
        themeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null && !updating) {
                ThemeManager.get().setMode((ThemeManager.Mode) n.getUserData());
            }
        });
        VBox appearance = group(
                padded(new VBox(14, Ui.label(I18n.t("settings.theme"), "row-title"), themes)),
                navRow(I18n.t("settings.boards"), I18n.t("settings.boards.description"),
                        () -> mainController.navigateTo("THEME")));

        // screen
        flippedSwitch = Ui.toggleSwitch(false);
        flippedSwitch.setOnAction(e -> mainController.setMountedFlipped(flippedSwitch.isSelected()));
        autoRotateSwitch = Ui.toggleSwitch(Prefs.bool(MainController.AUTOROTATE_KEY, true));
        autoRotateSwitch.setOnAction(e -> Prefs.set(MainController.AUTOROTATE_KEY, autoRotateSwitch.isSelected()));
        Button rotateNow = Ui.button(I18n.t("settings.rotate.now"), "fth-rotate-cw", "btn-outline", "btn-md");
        rotateNow.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        rotateNow.setOnAction(e -> mainController.rotateScreen());
        VBox screen = group(
                row(I18n.t("settings.flipped"), I18n.t("settings.flipped.description"), flippedSwitch),
                row(I18n.t("settings.autorotate"), I18n.t("settings.autorotate.description"), autoRotateSwitch),
                row(I18n.t("settings.rotate"), I18n.t("settings.rotate.description"), rotateNow));

        // game
        VBox game = group(
                switchRow("game.evaluation", true, I18n.t("settings.evaluation"),
                        I18n.t("settings.evaluation.description")),
                switchRow(PVC_SUGGESTIONS_KEY, false, I18n.t("settings.pvc.suggestions"),
                        I18n.t("settings.pvc.suggestions.description")),
                switchRow(PVP_SUGGESTIONS_KEY, false, I18n.t("settings.pvp.suggestions"),
                        I18n.t("settings.pvp.suggestions.description")),
                switchRow(io.github.hardin22.javachess.Oggetti.PvcGame.REPLICATION_FREE_KEY, true,
                        I18n.t("settings.clock.replication"), I18n.t("settings.clock.replication.description")),
                switchRow("ui.mate.animation", true, I18n.t("settings.mate"), I18n.t("settings.mate.description")));

        // computer
        Stepper movetime = new Stepper(MOVETIMES, Prefs.integer("game.bot.movetime", 2000));
        movetime.format(v -> String.format(Locale.ITALIAN, v % 1000 == 0 ? "%.0f s" : "%.1f s", v / 1000.0), null);
        movetime.valueProperty().addListener((obs, o, n) -> Prefs.set("game.bot.movetime", n.intValue()));
        VBox computer = group(
                stepperBlock(I18n.t("settings.bottime"), I18n.t("settings.bottime.description"), movetime));

        // clock
        Stepper minutes = new Stepper(MINUTES, Prefs.integer("game.default.duration", 10));
        minutes.format(String::valueOf, I18n.t("pvp.minutes.unit"));
        minutes.valueProperty().addListener((obs, o, n) -> Prefs.set("game.default.duration", n.intValue()));
        Stepper increment = new Stepper(INCREMENTS, Prefs.integer("game.default.increment", 0));
        increment.format(v -> "+" + v, I18n.t("pvp.increment.unit"));
        increment.valueProperty().addListener((obs, o, n) -> Prefs.set("game.default.increment", n.intValue()));
        VBox clock = group(
                stepperBlock(I18n.t("settings.pvpduration"), I18n.t("settings.pvpduration.description"), minutes),
                stepperBlock(I18n.t("settings.pvpincrement"), I18n.t("settings.pvpincrement.description"),
                        increment));

        // board and LEDs
        Stepper brightness = new Stepper(10, 100, 10, Prefs.integer("hardware.led.brightness", 100));
        brightness.format(v -> v + "%", null);
        brightness.valueProperty().addListener((obs, o, n) -> {
            Prefs.set("hardware.led.brightness", n.intValue());
            io.github.hardin22.javachess.Hardware.Hardware.leds().setBrightnessPercent(n.intValue());
        });
        VBox hardware = group(
                row(I18n.t("settings.boardstatus"), I18n.t("settings.boardstatus.description"), boardStatus),
                stepperBlock(I18n.t("settings.brightness"), I18n.t("settings.brightness.description"), brightness));

        // engine
        VBox engine = new VBox(12, Ui.wrap(I18n.t("settings.engine.description"), "t-small", "t-muted"),
                new EnginePicker());

        String version = SettingsController.class.getPackage().getImplementationVersion();
        VBox about = group(
                navRow(I18n.t("settings.advanced"), I18n.t("settings.advanced.description"), this::showAdvanced),
                row(I18n.t("settings.version"), "javaChess · GPL-3.0", Ui.label(version == null ? "dev" : version,
                        "row-value")));

        VBox body = new VBox(14,
                Ui.sectionLabel(I18n.t("settings.appearance")), appearance,
                Ui.sectionLabel(I18n.t("settings.screen")), screen,
                Ui.sectionLabel(I18n.t("settings.game")), game,
                Ui.sectionLabel(I18n.t("settings.computer")), computer,
                Ui.sectionLabel(I18n.t("settings.clock")), clock,
                Ui.sectionLabel(I18n.t("settings.hardware")), hardware,
                Ui.sectionLabel(I18n.t("settings.engine")), engine,
                Ui.sectionLabel(I18n.t("settings.system")), about);
        body.getStyleClass().add("screen-body");
        mainPage.getChildren().addAll(header, Ui.scroll(body));
        buildAdvanced();
        root.getChildren().addAll(mainPage, advancedPage);
        advancedPage.setVisible(false);
    }

    private void buildAdvanced() {
        ScreenHeader header = new ScreenHeader(I18n.t("settings.advanced"), this::showMain);
        header.setSubtitle(I18n.t("settings.advanced.description"));
        Stepper depth = new Stepper(8, 30, 1, Prefs.integer("game.depth", 18));
        depth.format(String::valueOf, I18n.t("analysis.depth.unit"));
        depth.valueProperty().addListener((obs, o, n) -> Prefs.set("game.depth", n.intValue()));
        VBox analysis = group(
                stepperBlock(I18n.t("settings.gamedepth"), I18n.t("settings.gamedepth.description"), depth),
                padded(Ui.wrap(I18n.t("settings.leds.note"), "row-sub")));

        lichessButton.setOnAction(e -> toggleLichessAccount());
        VBox lichessTexts = new VBox(4, Ui.label(I18n.t("settings.lichess"), "row-title"), lichessStatus);
        lichessTexts.setMinWidth(0);
        HBox.setHgrow(lichessTexts, javafx.scene.layout.Priority.ALWAYS);
        HBox lichessRow = new HBox(16, lichessTexts, lichessButton);
        lichessRow.getStyleClass().add("row");
        VBox accounts = group(lichessRow, accountFields,
                navRow(I18n.t("settings.lichess.api"), I18n.t("settings.lichess.api.description"),
                        () -> mainController.openLichess())); // a game left on Lichess is resumed, else the setup

        VBox body = new VBox(14,
                Ui.sectionLabel(I18n.t("settings.analysis")), analysis,
                Ui.sectionLabel(I18n.t("settings.accounts")), accounts);
        body.getStyleClass().add("screen-body");
        advancedPage.getChildren().addAll(header, Ui.scroll(body));
        advancedPage.getStyleClass().add("screen");
    }

    private void showAdvanced() {
        refreshAccounts();
        advancedPage.setVisible(true);
        mainPage.setVisible(false);
    }

    private void showMain() {
        advancedPage.setVisible(false);
        mainPage.setVisible(true);
    }

    /** Opens the "Avanzate" page (DevOptions demo). */
    public void openAdvanced() {
        showAdvanced();
    }

    @Override
    public void onNavigatedTo() {
        showMain();
        updating = true;
        themeGroup.getToggles().stream().filter(t -> t.getUserData() == ThemeManager.get().getMode()).findFirst()
                .ifPresent(t -> t.setSelected(true));
        updating = false;
        if (mainController != null) {
            flippedSwitch.setSelected(mainController.isMountedFlipped());
        }
        autoRotateSwitch.setSelected(Prefs.bool(MainController.AUTOROTATE_KEY, true));
        HardwareStatus.bind(boardStatus);
    }

    @Override
    public boolean onBack() {
        if (advancedPage.isVisible()) {
            showMain();
            return true;
        }
        return false;
    }

    // ================================================================== rows

    private static ToggleButton segment(String text, String icon, ThemeManager.Mode mode) {
        ToggleButton b = new ToggleButton(text, Icons.of(icon, 26));
        b.setUserData(mode);
        return b;
    }

    private static VBox group(Node... rows) {
        VBox box = new VBox();
        box.getStyleClass().add("group");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                box.getChildren().add(Ui.hairline());
            }
            box.getChildren().add(rows[i]);
        }
        return box;
    }

    private static Node padded(Node node) {
        VBox box = new VBox(node);
        box.setPadding(new Insets(24, 28, 24, 28));
        return box;
    }

    private static HBox row(String title, String description, Node control) {
        HBox row = new HBox(Ui.texts(title, description, "row-title", "row-sub"), control);
        row.getStyleClass().add("row");
        return row;
    }

    /** Best-move arrows and LED verdicts in a two-player game (off by default: it is a game between people). */
    public static final String PVP_SUGGESTIONS_KEY = "game.pvp.suggestions";
    /** Best-move arrows always on against the computer (off by default: the Hint button gives it on request). */
    public static final String PVC_SUGGESTIONS_KEY = "game.pvc.suggestions";

    private static HBox switchRow(String key, boolean fallback, String title, String description) {
        ToggleButton toggle = Ui.toggleSwitch(Prefs.bool(key, fallback));
        toggle.setOnAction(e -> Prefs.set(key, toggle.isSelected()));
        return row(title, description, toggle);
    }

    private static VBox stepperBlock(String title, String description, Stepper stepper) {
        VBox box = new VBox(14, Ui.texts(title, description, "row-title", "row-sub"), stepper);
        box.setPadding(new Insets(24, 28, 24, 28));
        return box;
    }

    private static HBox navRow(String title, String description, Runnable action) {
        HBox row = row(title, description, Icons.of("fth-chevron-right", 30));
        row.getStyleClass().add("row-press");
        row.setOnMouseClicked(e -> action.run());
        return row;
    }

    // ================================================================== accounts

    /** Screenshot/demo runs hide (and never overwrite) the stored accounts. */
    private static boolean isRedacted() {
        return System.getProperty("javachess.snapshot") != null || Boolean.getBoolean("javachess.redact");
    }

    private void refreshAccounts() {
        boolean redact = isRedacted();
        accountFields.getChildren().setAll(
                Ui.hairline(),
                fieldRow(I18n.t("settings.lichess.username"), "lichess.username", false, redact),
                Ui.hairline(),
                fieldRow(I18n.t("settings.lichess.token"), ConfigManager.LICHESS_TOKEN, true, redact),
                Ui.hairline(),
                fieldRow(I18n.t("settings.chesscom.username"), "chess.com.username", false, redact));
        showLichessAccount();
    }

    /** A settings value edited with the on-screen keyboard; secrets are shown as dots. */
    private HBox fieldRow(String title, String key, boolean secret, boolean redact) {
        String value = redact ? "" : key.equals(ConfigManager.LICHESS_TOKEN)
                ? ConfigManager.getStoredProperty(key, "") : Prefs.string(key, "");
        String shown = value.isBlank() ? I18n.t("settings.field.empty") : secret ? "••••••••" : value;
        Label valueLabel = Ui.label(shown, "row-value");
        HBox row = row(title, null, valueLabel);
        row.getStyleClass().add("row-press");
        row.setOnMouseClicked(e -> {
            if (redact) {
                return;
            }
            editField(title, secret ? "" : value, text -> {
                Prefs.set(key, text);
                valueLabel.setText(text.isBlank() ? I18n.t("settings.field.empty") : secret ? "••••••••" : text);
            });
        });
        return row;
    }

    private void editField(String title, String initial, Consumer<String> onDone) {
        TouchKeyboard keyboard = new TouchKeyboard(title);
        keyboard.textProperty().set(initial);
        keyboard.setOnDone(() -> {
            mainController.closeSheet();
            onDone.accept(keyboard.textProperty().get().trim());
        });
        mainController.showSheet(title, keyboard);
    }

    private void showLichessAccount() {
        boolean connected = ConfigManager.hasLichessToken();
        String user = isRedacted() ? "" : Prefs.string("lichess.username", "");
        lichessStatus.setText(!connected ? I18n.t("settings.lichess.disconnected")
                : user.isBlank() ? I18n.t("settings.lichess.connected.anon") : I18n.t("settings.lichess.connected", user));
        lichessButton.setText(I18n.t(connected ? "settings.lichess.disconnect" : "settings.lichess.connect"));
        lichessButton.setDisable(lichessBusy || isRedacted());
    }

    /** "Collega account" runs the Lichess OAuth (PKCE) login in a browser; "Scollega" revokes the token. */
    private void toggleLichessAccount() {
        if (lichessBusy) {
            return;
        }
        lichessBusy = true;
        if (ConfigManager.hasLichessToken()) {
            io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
                new io.github.hardin22.javachess.Services.LichessOAuth().logout(); // network: off the FX thread
                javafx.application.Platform.runLater(() -> {
                    lichessBusy = false;
                    refreshAccounts();
                });
            });
            return;
        }
        lichessStatus.setText(I18n.t("settings.lichess.waiting"));
        lichessButton.setDisable(true);
        new io.github.hardin22.javachess.Services.LichessOAuth()
                .login(this::openLoginPage, java.time.Duration.ofMinutes(5))
                .whenComplete((username, err) -> javafx.application.Platform.runLater(() -> {
                    lichessBusy = false;
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        io.github.hardin22.javachess.Utils.ErrorReporter.showError("Lichess", cause.getMessage());
                    } else if (username != null) {
                        Prefs.set("lichess.username", username);
                    }
                    refreshAccounts();
                }));
    }

    /** Desktop: system browser. Board (kiosk, no desktop browser): the integrated browser. */
    private void openLoginPage(java.net.URI uri) {
        boolean desktop = !Boolean.getBoolean("javachess.kiosk") && java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE);
        if (desktop) {
            io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
                try {
                    java.awt.Desktop.getDesktop().browse(uri);
                } catch (Exception e) {
                    io.github.hardin22.javachess.Utils.ErrorReporter.showError("Lichess",
                            io.github.hardin22.javachess.Utils.ErrorReporter.userMessage(e));
                }
            });
        } else {
            javafx.application.Platform.runLater(() -> mainController.openBrowser(uri.toString()));
        }
    }
}
