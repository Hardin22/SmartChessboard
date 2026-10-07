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
        ConfigManager.setProperty("game.suggestions", String.valueOf(suggestionsToggle.isSelected()));
        ConfigManager.setProperty("game.evaluation", String.valueOf(evaluationToggle.isSelected()));
        ConfigManager.setProperty("ui.mate.animation", String.valueOf(mateAnimationToggle.isSelected()));
        ConfigManager.setProperty("game.bot.level", String.valueOf((int) botLevelSlider.getValue()));
        ConfigManager.setProperty("game.bot.movetime", String.valueOf((int) botThinkingTimeSlider.getValue()));
        ConfigManager.setProperty("game.default.duration", String.valueOf((int) pvpDefaultDurationSlider.getValue()));
        ConfigManager.setProperty("game.default.increment", String.valueOf((int) pvpDefaultIncrementSlider.getValue()));
        ConfigManager.setProperty("game.depth", String.valueOf((int) gameDepthSlider.getValue()));
        ConfigManager.setProperty("analysis.depth", String.valueOf((int) analysisDepthSlider.getValue()));
        ConfigManager.setProperty("move.eval.depth", String.valueOf((int) moveEvalDepthSlider.getValue()));
        if (!isRedacted()) {
            ConfigManager.setProperty("lichess.username", lichessUsernameField.getText());
            ConfigManager.setProperty("chess.com.username", chessComEmailField.getText());
            ConfigManager.setProperty("lichess.token", lichessApiKeyField.getText());
        }
        ConfigManager.setProperty("hardware.led.brightness", String.valueOf((int) ledBrightnessSlider.getValue()));
        applyLedBrightness((int) ledBrightnessSlider.getValue());
        mainController.showToast(I18n.t("settings.saved"));
        backToHome();
    }

    /**
     * Pushes the brightness to the LEDs right away through the runtime layer's
     * {@code Hardware.leds().setBrightnessPercent(int)} when available (looked up reflectively, non-blocking).
     */
    private static void applyLedBrightness(int percent) {
        try {
            Object leds = Class.forName("org.example.javachess.Hardware.Hardware").getMethod("leds").invoke(null);
            leds.getClass().getMethod("setBrightnessPercent", int.class).invoke(leds, percent);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Older runtime layer: the value is read from config at the next start.
        }
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
