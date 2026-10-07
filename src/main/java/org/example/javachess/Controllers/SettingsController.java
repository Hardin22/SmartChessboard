package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import org.example.javachess.Components.HardwareStatus;
import org.example.javachess.Components.I18n;
import org.example.javachess.Components.StatusChip;
import org.example.javachess.Components.ThemeManager;
import org.example.javachess.Utils.ConfigManager;

import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Settings. Theme, engine and screen rotation apply immediately; the other values are written when the user taps
 * "Salva impostazioni" (same config keys as before).
 */
public class SettingsController implements NavigationAware {

    private MainController mainController;

    @FXML
    private ToggleButton themeSystem;
    @FXML
    private ToggleButton themeDark;
    @FXML
    private ToggleButton themeLight;
    @FXML
    private ToggleButton suggestionsToggle;
    @FXML
    private ToggleButton evaluationToggle;
    @FXML
    private ToggleButton mateAnimationToggle;
    @FXML
    private ToggleButton rotateToggle;
    @FXML
    private Slider botLevelSlider;
    @FXML
    private Label botLevelLabel;
    @FXML
    private Slider botThinkingTimeSlider;
    @FXML
    private Label botThinkingTimeLabel;
    @FXML
    private Slider pvpDefaultDurationSlider;
    @FXML
    private Label pvpDefaultDurationLabel;
    @FXML
    private Slider pvpDefaultIncrementSlider;
    @FXML
    private Label pvpDefaultIncrementLabel;
    @FXML
    private Slider gameDepthSlider;
    @FXML
    private Label gameDepthLabel;
    @FXML
    private Slider analysisDepthSlider;
    @FXML
    private Label analysisDepthLabel;
    @FXML
    private Slider moveEvalDepthSlider;
    @FXML
    private Label moveEvalDepthLabel;
    @FXML
    private TextField lichessUsernameField;
    @FXML
    private TextField chessComEmailField;
    @FXML
    private PasswordField lichessApiKeyField;
    @FXML
    private Slider ledBrightnessSlider;
    @FXML
    private Label ledBrightnessLabel;
    @FXML
    private StatusChip boardStatus;
    @FXML
    private Label versionLabel;
    @FXML
    private Label lichessStatusLabel;
    @FXML
    private javafx.scene.control.Button lichessConnectButton;
    private boolean lichessBusy;

    private boolean updatingTheme;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        loadSettings();
    }

    @FXML
    public void initialize() {
        bind(botLevelSlider, botLevelLabel, String::valueOf);
        bind(botThinkingTimeSlider, botThinkingTimeLabel,
                v -> String.format(Locale.ITALIAN, "%.1f s", v / 1000.0));
        bind(pvpDefaultDurationSlider, pvpDefaultDurationLabel, v -> I18n.t("pvp.value.minutes", v));
        bind(pvpDefaultIncrementSlider, pvpDefaultIncrementLabel, v -> I18n.t("pvp.value.seconds", v));
        bind(gameDepthSlider, gameDepthLabel, String::valueOf);
        bind(analysisDepthSlider, analysisDepthLabel, String::valueOf);
        bind(moveEvalDepthSlider, moveEvalDepthLabel, String::valueOf);
        bind(ledBrightnessSlider, ledBrightnessLabel, v -> v + "%");

        ToggleGroup themeGroup = new ToggleGroup();
        themeSystem.setToggleGroup(themeGroup);
        themeDark.setToggleGroup(themeGroup);
        themeLight.setToggleGroup(themeGroup);
        selectThemeToggle(ThemeManager.get().getMode());
        themeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) {
                if (o != null) {
                    o.setSelected(true);
                }
                return;
            }
            if (!updatingTheme) {
                ThemeManager.get().setMode(n == themeSystem ? ThemeManager.Mode.SYSTEM
                        : n == themeLight ? ThemeManager.Mode.LIGHT : ThemeManager.Mode.DARK);
            }
        });

        rotateToggle.setOnAction(e -> {
            if (mainController != null && mainController.isRotated() != rotateToggle.isSelected()) {
                mainController.rotateScreen();
            }
        });

        String version = SettingsController.class.getPackage().getImplementationVersion();
        versionLabel.setText(version == null ? "dev" : version);
    }

    private void selectThemeToggle(ThemeManager.Mode mode) {
        updatingTheme = true;
        switch (mode) {
            case SYSTEM -> themeSystem.setSelected(true);
            case LIGHT -> themeLight.setSelected(true);
            default -> themeDark.setSelected(true);
        }
        updatingTheme = false;
    }

    private static void bind(Slider slider, Label label, IntFunction<String> format) {
        slider.valueProperty().addListener((obs, o, n) -> label.setText(format.apply(n.intValue())));
        label.setText(format.apply((int) slider.getValue()));
    }

    @Override
    public void onNavigatedTo() {
        loadSettings();
        selectThemeToggle(ThemeManager.get().getMode());
        if (mainController != null) {
            rotateToggle.setSelected(mainController.isRotated());
        }
        HardwareStatus.bind(boardStatus);
        showLichessAccount();
    }

    private void showLichessAccount() {
        boolean connected = ConfigManager.hasLichessToken();
        String user = isRedacted() ? "" : ConfigManager.getProperty("lichess.username", "");
        lichessStatusLabel.setText(!connected ? I18n.t("settings.lichess.disconnected")
                : user.isBlank() ? I18n.t("settings.lichess.connected.anon") : I18n.t("settings.lichess.connected", user));
        lichessConnectButton.setText(I18n.t(connected ? "settings.lichess.disconnect" : "settings.lichess.connect"));
        lichessConnectButton.setDisable(lichessBusy);
    }

    /** "Collega account" runs the Lichess OAuth (PKCE) login in a browser; "Scollega" revokes the token. */
    @FXML
    private void toggleLichessAccount() {
        if (lichessBusy) {
            return;
        }
        lichessBusy = true;
        if (ConfigManager.hasLichessToken()) {
            org.example.javachess.Utils.AppExecutors.io().execute(() -> {
                new org.example.javachess.Services.LichessOAuth().logout(); // network: off the FX thread
                javafx.application.Platform.runLater(() -> {
                    lichessBusy = false;
                    lichessApiKeyField.clear();
                    showLichessAccount();
                });
            });
            return;
        }
        lichessStatusLabel.setText(I18n.t("settings.lichess.waiting"));
        lichessConnectButton.setDisable(true);
        new org.example.javachess.Services.LichessOAuth()
                .login(this::openLoginPage, java.time.Duration.ofMinutes(5))
                .whenComplete((username, err) -> javafx.application.Platform.runLater(() -> {
                    lichessBusy = false;
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        org.example.javachess.Utils.ErrorReporter.showError("Lichess", cause.getMessage());
                    } else {
                        lichessUsernameField.setText(username);
                        lichessApiKeyField.setText(ConfigManager.getProperty("lichess.token", ""));
                    }
                    showLichessAccount();
                }));
    }

    /** Desktop: system browser. Board (kiosk, no desktop browser): the integrated browser. */
    private void openLoginPage(java.net.URI uri) {
        boolean desktop = !Boolean.getBoolean("javachess.kiosk") && java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE);
        if (desktop) {
            org.example.javachess.Utils.AppExecutors.io().execute(() -> {
                try {
                    java.awt.Desktop.getDesktop().browse(uri);
                } catch (Exception e) {
                    org.example.javachess.Utils.ErrorReporter.showError("Lichess",
                            org.example.javachess.Utils.ErrorReporter.userMessage(e));
                }
            });
        } else {
            javafx.application.Platform.runLater(() -> {
                if (mainController.getController("BROWSER") instanceof BrowserController browser) {
                    browser.loadPage(uri.toString());
                }
                mainController.navigateTo("BROWSER");
            });
        }
    }

    private void loadSettings() {
        suggestionsToggle.setSelected(ConfigManager.getBooleanProperty("game.suggestions", true));
        evaluationToggle.setSelected(ConfigManager.getBooleanProperty("game.evaluation", true));
        mateAnimationToggle.setSelected(ConfigManager.getBooleanProperty("ui.mate.animation", true));
        botLevelSlider.setValue(ConfigManager.getIntProperty("game.bot.level", 10));
        botThinkingTimeSlider.setValue(ConfigManager.getIntProperty("game.bot.movetime", 2000));
        pvpDefaultDurationSlider.setValue(ConfigManager.getIntProperty("game.default.duration", 10));
        pvpDefaultIncrementSlider.setValue(ConfigManager.getIntProperty("game.default.increment", 0));
        gameDepthSlider.setValue(ConfigManager.getIntProperty("game.depth", 18));
        analysisDepthSlider.setValue(ConfigManager.getIntProperty("analysis.depth", 12));
        moveEvalDepthSlider.setValue(ConfigManager.getIntProperty("move.eval.depth", 8));
        ledBrightnessSlider.setValue(ConfigManager.getIntProperty("hardware.led.brightness", 100));

        // Screenshots for the docs must never show real accounts.
        boolean redact = isRedacted();
        lichessUsernameField.setText(redact ? "" : ConfigManager.getProperty("lichess.username", ""));
        chessComEmailField.setText(redact ? "" : ConfigManager.getProperty("chess.com.username", ""));
        lichessApiKeyField.setText(redact ? "" : ConfigManager.getProperty("lichess.token", ""));
        lichessUsernameField.setPromptText(redact ? "nome-utente" : "");
        chessComEmailField.setPromptText(redact ? "nome@esempio.it" : "");
    }

    @FXML
    private void saveSettings() {
        // One atomic write for all values. Passwords are not stored: the integrated browser keeps its own session.
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("game.suggestions", String.valueOf(suggestionsToggle.isSelected()));
        values.put("game.evaluation", String.valueOf(evaluationToggle.isSelected()));
        values.put("ui.mate.animation", String.valueOf(mateAnimationToggle.isSelected()));
        values.put("game.bot.level", String.valueOf((int) botLevelSlider.getValue()));
        values.put("game.bot.movetime", String.valueOf((int) botThinkingTimeSlider.getValue()));
        values.put("game.default.duration", String.valueOf((int) pvpDefaultDurationSlider.getValue()));
        values.put("game.default.increment", String.valueOf((int) pvpDefaultIncrementSlider.getValue()));
        values.put("game.depth", String.valueOf((int) gameDepthSlider.getValue()));
        values.put("analysis.depth", String.valueOf((int) analysisDepthSlider.getValue()));
        values.put("move.eval.depth", String.valueOf((int) moveEvalDepthSlider.getValue()));
        values.put("hardware.led.brightness", String.valueOf((int) ledBrightnessSlider.getValue()));
        if (!isRedacted()) {
            values.put("lichess.username", lichessUsernameField.getText());
            values.put("chess.com.username", chessComEmailField.getText());
            values.put("lichess.token", lichessApiKeyField.getText());
        }
        ConfigManager.setProperties(values);
        // Apply the brightness right away (non-blocking).
        org.example.javachess.Hardware.Hardware.leds().setBrightnessPercent((int) ledBrightnessSlider.getValue());
        mainController.showToast(I18n.t("settings.saved"));
        backToHome();
    }

    /** Screenshot/demo runs hide (and never overwrite) the stored accounts. */
    private static boolean isRedacted() {
        return System.getProperty("javachess.snapshot") != null || Boolean.getBoolean("javachess.redact");
    }

    @FXML
    private void openThemes() {
        mainController.navigateTo("THEME");
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }
}
