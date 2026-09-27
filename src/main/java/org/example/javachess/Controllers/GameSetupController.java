package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.VBox;
import org.example.javachess.Services.EngineService;
import org.example.javachess.Services.EngineService.EngineType;

public class GameSetupController implements NavigationAware {

    private MainController mainController;

    // PvP Controls
    @FXML
    private Label durataLabel;
    @FXML
    private Slider durataSlider;
    @FXML
    private Label incrementoLabel;
    @FXML
    private Slider incrementoSlider;

    // PvC Controls
    @FXML
    private Label difficoltàLabel;
    @FXML
    private Slider difficoltàSlider;

    // New PvC Selection Controls
    @FXML
    private VBox whiteVbox;
    @FXML
    private VBox blackVbox;
    @FXML
    private VBox randomVbox;

    @FXML
    private org.kordamp.ikonli.javafx.FontIcon whiteIcon;
    @FXML
    private org.kordamp.ikonli.javafx.FontIcon blackIcon;
    @FXML
    private org.kordamp.ikonli.javafx.FontIcon randomIcon;

    @FXML
    private VBox botStockfishBox;
    @FXML
    private VBox botMaia1100Box;
    @FXML
    private VBox botMaia1500Box;
    @FXML
    private VBox botMaia1900Box;

    @FXML
    private org.kordamp.ikonli.javafx.FontIcon botStockfishIcon;
    @FXML
    private org.kordamp.ikonli.javafx.FontIcon botMaia1100Icon;
    @FXML
    private org.kordamp.ikonli.javafx.FontIcon botMaia1500Icon;
    @FXML
    private org.kordamp.ikonli.javafx.FontIcon botMaia1900Icon;

    private String selectedColor = "random";
    private EngineType selectedBotType = EngineType.STOCKFISH;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public void onNavigatedTo() {
        refreshDefaults();
    }

    @FXML
    public void initialize() {
        // Initialize PvP listeners
        if (durataSlider != null) {
            durataSlider.valueProperty()
                    .addListener((obs, oldVal, newVal) -> durataLabel.setText("Durata: " + newVal.intValue() + "min"));
        }
        if (incrementoSlider != null) {
            incrementoSlider.valueProperty().addListener(
                    (obs, oldVal, newVal) -> incrementoLabel.setText("Incremento: " + newVal.intValue() + "s"));
        }

        // Initialize PvC listeners
        if (difficoltàSlider != null) {
            difficoltàSlider.valueProperty()
                    .addListener((obs, oldVal, newVal) -> {
                        if (selectedBotType == EngineType.STOCKFISH) {
                            difficoltàLabel.setText("Difficoltà: " + newVal.intValue());
                        }
                    });
        }

        refreshDefaults();
        updateSelectionVisuals();
        updateBotSelectionVisuals();
    }

    private void refreshDefaults() {
        if (durataSlider != null) {
            int defaultDuration = org.example.javachess.Utils.ConfigManager.getIntProperty("game.default.duration", 10);
            durataSlider.setValue(defaultDuration);
            durataLabel.setText("Durata: " + defaultDuration + "min");
        }
        if (incrementoSlider != null) {
            int defaultIncrement = org.example.javachess.Utils.ConfigManager.getIntProperty("game.default.increment",
                    0);
            incrementoSlider.setValue(defaultIncrement);
            incrementoLabel.setText("Incremento: " + defaultIncrement + "s");
        }
        if (difficoltàSlider != null) {
            int defaultLevel = org.example.javachess.Utils.ConfigManager.getIntProperty("game.bot.level", 10);
            difficoltàSlider.setValue(defaultLevel);
            difficoltàLabel.setText("Difficoltà: " + defaultLevel);
        }
    }

    @FXML
    private void selectWhite() {
        selectedColor = "white";
        updateSelectionVisuals();
    }

    @FXML
    private void selectBlack() {
        selectedColor = "black";
        updateSelectionVisuals();
    }

    @FXML
    private void selectRandom() {
        selectedColor = "random";
        updateSelectionVisuals();
    }

    @FXML
    private void selectBotStockfish() {
        selectedBotType = EngineType.STOCKFISH;
        updateBotSelectionVisuals();
    }

    @FXML
    private void selectBotMaia1100() {
        selectedBotType = EngineType.MAIA_1100;
        updateBotSelectionVisuals();
    }

    @FXML
    private void selectBotMaia1500() {
        selectedBotType = EngineType.MAIA_1500;
        updateBotSelectionVisuals();
    }

    @FXML
    private void selectBotMaia1900() {
        selectedBotType = EngineType.MAIA_1900;
        updateBotSelectionVisuals();
    }

    private void updateBotSelectionVisuals() {
        if (botStockfishBox == null)
            return; // Safety check

        // Reset
        botStockfishBox.setStyle("-fx-border-color: transparent;");
        botMaia1100Box.setStyle("-fx-border-color: transparent;");
        botMaia1500Box.setStyle("-fx-border-color: transparent;");
        botMaia1900Box.setStyle("-fx-border-color: transparent;");

        botStockfishIcon.setIconColor(javafx.scene.paint.Color.web("#E0E0E0"));
        botMaia1100Icon.setIconColor(javafx.scene.paint.Color.web("#E0E0E0"));
        botMaia1500Icon.setIconColor(javafx.scene.paint.Color.web("#E0E0E0"));
        botMaia1900Icon.setIconColor(javafx.scene.paint.Color.web("#E0E0E0"));

        // Highlight
        String highlightColor = "#03DAC6"; // Teal accent for Bot
        String borderStyle = "-fx-border-color: " + highlightColor + "; -fx-border-width: 2; -fx-border-radius: 10;";

        switch (selectedBotType) {
            case STOCKFISH:
                botStockfishBox.setStyle(borderStyle);
                botStockfishIcon.setIconColor(javafx.scene.paint.Color.web(highlightColor));
                difficoltàSlider.setDisable(false);
                difficoltàLabel.setText("Difficoltà: " + (int) difficoltàSlider.getValue());
                break;
            case MAIA_1100:
                botMaia1100Box.setStyle(borderStyle);
                botMaia1100Icon.setIconColor(javafx.scene.paint.Color.web(highlightColor));
                difficoltàSlider.setDisable(true);
                difficoltàLabel.setText("Rating: 1100");
                break;
            case MAIA_1500:
                botMaia1500Box.setStyle(borderStyle);
                botMaia1500Icon.setIconColor(javafx.scene.paint.Color.web(highlightColor));
                difficoltàSlider.setDisable(true);
                difficoltàLabel.setText("Rating: 1500");
                break;
            case MAIA_1900:
                botMaia1900Box.setStyle(borderStyle);
                botMaia1900Icon.setIconColor(javafx.scene.paint.Color.web(highlightColor));
                difficoltàSlider.setDisable(true);
                difficoltàLabel.setText("Rating: 1900");
                break;
        }
    }

    private void updateSelectionVisuals() {
        if (whiteVbox == null || blackVbox == null || randomVbox == null)
            return;

        // Reset all
        whiteVbox.setStyle("-fx-border-color: transparent;");
        blackVbox.setStyle("-fx-border-color: transparent;");
        randomVbox.setStyle("-fx-border-color: transparent;");

        whiteIcon.setIconColor(javafx.scene.paint.Color.web("#E0E0E0"));
        blackIcon.setIconColor(javafx.scene.paint.Color.web("#121212")); // Keep black icon black/dark
        randomIcon.setIconColor(javafx.scene.paint.Color.web("#D4AF37"));

        // Highlight selected
        String highlightColor = "#BB86FC"; // Purple accent
        String borderStyle = "-fx-border-color: " + highlightColor + "; -fx-border-width: 2; -fx-border-radius: 10;";

        switch (selectedColor) {
            case "white":
                whiteVbox.setStyle(borderStyle);
                whiteIcon.setIconColor(javafx.scene.paint.Color.web(highlightColor));
                break;
            case "black":
                blackVbox.setStyle(borderStyle);
                blackIcon.setIconColor(javafx.scene.paint.Color.web(highlightColor));
                break;
            case "random":
                randomVbox.setStyle(borderStyle);
                randomIcon.setIconColor(javafx.scene.paint.Color.web(highlightColor));
                break;
        }
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }

    @FXML
    private void startGamePvP() {
        int duration = (int) durataSlider.getValue();
        int increment = (int) incrementoSlider.getValue();

        mainController.loadView("GAME", "/UI/GameView.fxml");
        ActiveGameController gameController = (ActiveGameController) mainController.getController("GAME");
        gameController.startPvP(duration, increment);
        mainController.navigateTo("GAME");
    }

    @FXML
    private void startGamePvC() {
        // Set Engine Type Global
        // REMOVED: EngineService.getInstance().setEngineType(selectedBotType);

        int difficulty = (int) difficoltàSlider.getValue();
        boolean isWhite = true;
        if ("black".equals(selectedColor))
            isWhite = false;
        else if ("random".equals(selectedColor))
            isWhite = Math.random() < 0.5;

        mainController.loadView("GAME", "/UI/GameView.fxml");
        ActiveGameController gameController = (ActiveGameController) mainController.getController("GAME");
        gameController.startPvC(difficulty, isWhite, selectedBotType);
        mainController.navigateTo("GAME");
    }
}
