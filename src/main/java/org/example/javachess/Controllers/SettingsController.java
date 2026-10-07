package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.example.javachess.Utils.ConfigManager;

public class SettingsController implements NavigationAware {

    private MainController mainController;

    @FXML
    private ToggleButton suggestionsToggle;
    @FXML
    private ToggleButton evaluationToggle;
    @FXML
    private ToggleButton mateAnimationToggle;
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
    private PasswordField lichessPasswordField;
    @FXML
    private TextField chessComEmailField;
    @FXML
    private PasswordField chessComPasswordField;
    @FXML
    private PasswordField lichessApiKeyField;
    @FXML
    private Slider ledBrightnessSlider;
    @FXML
    private Label ledBrightnessLabel;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        loadSettings();
    }

    @Override
    public void onNavigatedTo() {
        loadSettings(); // the view is cached: discard unsaved edits from a previous visit
    }

    @FXML
    public void initialize() {
        // Bind slider labels
        botLevelSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> botLevelLabel.setText(String.valueOf(newVal.intValue())));

        botThinkingTimeSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> botThinkingTimeLabel
                        .setText(String.format("%.1fs", newVal.doubleValue() / 1000.0)));

        pvpDefaultDurationSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> pvpDefaultDurationLabel.setText(newVal.intValue() + "min"));

        pvpDefaultIncrementSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> pvpDefaultIncrementLabel.setText(newVal.intValue() + "s"));

        gameDepthSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> gameDepthLabel.setText(String.valueOf(newVal.intValue())));

        analysisDepthSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> analysisDepthLabel.setText(String.valueOf(newVal.intValue())));

        moveEvalDepthSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> moveEvalDepthLabel.setText(String.valueOf(newVal.intValue())));

        ledBrightnessSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> ledBrightnessLabel.setText(newVal.intValue() + "%"));

        // Toggle text updates
        setupToggle(suggestionsToggle);
        setupToggle(evaluationToggle);
        setupToggle(mateAnimationToggle);
    }

    private void setupToggle(ToggleButton toggle) {
        toggle.selectedProperty().addListener((obs, oldVal, newVal) -> toggle.setText(newVal ? "ON" : "OFF"));
    }

    private void loadSettings() {
        suggestionsToggle.setSelected(ConfigManager.getBooleanProperty("game.suggestions", true));
        evaluationToggle.setSelected(ConfigManager.getBooleanProperty("game.evaluation", true));
        mateAnimationToggle.setSelected(ConfigManager.getBooleanProperty("ui.mate.animation", true));

        int botLevel = ConfigManager.getIntProperty("game.bot.level", 10);
        botLevelSlider.setValue(botLevel);
        botLevelLabel.setText(String.valueOf(botLevel));

        int botMovetime = ConfigManager.getIntProperty("game.bot.movetime", 2000);
        botThinkingTimeSlider.setValue(botMovetime);
        botThinkingTimeLabel.setText(String.format("%.1fs", botMovetime / 1000.0));

        int pvpDuration = ConfigManager.getIntProperty("game.default.duration", 10);
        pvpDefaultDurationSlider.setValue(pvpDuration);
        pvpDefaultDurationLabel.setText(pvpDuration + "min");

        int pvpIncrement = ConfigManager.getIntProperty("game.default.increment", 0);
        pvpDefaultIncrementSlider.setValue(pvpIncrement);
        pvpDefaultIncrementLabel.setText(pvpIncrement + "s");

        int gameDepth = ConfigManager.getIntProperty("game.depth", 18);
        gameDepthSlider.setValue(gameDepth);
        gameDepthLabel.setText(String.valueOf(gameDepth));

        int analysisDepth = ConfigManager.getIntProperty("analysis.depth", 12);
        analysisDepthSlider.setValue(analysisDepth);
        analysisDepthLabel.setText(String.valueOf(analysisDepth));

        int moveEvalDepth = ConfigManager.getIntProperty("move.eval.depth", 8);
        moveEvalDepthSlider.setValue(moveEvalDepth);
        moveEvalDepthLabel.setText(String.valueOf(moveEvalDepth));

        lichessUsernameField.setText(ConfigManager.getProperty("lichess.username", ""));
        chessComEmailField.setText(ConfigManager.getProperty("chess.com.username", ""));
        lichessApiKeyField.setText(ConfigManager.getStoredProperty("lichess.token", ""));

        int brightness = ConfigManager.getIntProperty("hardware.led.brightness", 100);
        ledBrightnessSlider.setValue(brightness);
        ledBrightnessLabel.setText(brightness + "%");
    }

    @FXML
    private void saveSettings() {
        // One atomic write for all values. Passwords are not stored any more: the integrated browser keeps
        // its own login session (see ConfigManager), so the password fields are ignored.
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
        values.put("lichess.username", lichessUsernameField.getText());
        values.put("chess.com.username", chessComEmailField.getText());
        values.put("lichess.token", lichessApiKeyField.getText());
        values.put("hardware.led.brightness", String.valueOf((int) ledBrightnessSlider.getValue()));
        ConfigManager.setProperties(values);
        if (lichessPasswordField != null) {
            lichessPasswordField.clear();
        }
        if (chessComPasswordField != null) {
            chessComPasswordField.clear();
        }

        backToHome();
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }
}
