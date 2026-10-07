package io.github.hardin22.javachess.Controllers;

import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Engine.EngineSelection;
import io.github.hardin22.javachess.Services.EngineService.EngineType;
import io.github.hardin22.javachess.Utils.ConfigManager;

/** Presentation for the two local setup screens: PvCSetupView (computer) and PvPSetupView (two players). */
public class GameSetupController implements NavigationAware {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final int[][] PRESETS = { { 1, 0 }, { 3, 0 }, { 3, 2 }, { 5, 0 }, { 5, 3 }, { 10, 0 }, { 10, 5 },
            { 15, 10 }, { 30, 0 } };

    private MainController mainController;

    // PvP
    @FXML
    private Label durataLabel;
    @FXML
    private Slider durataSlider;
    @FXML
    private Label incrementoLabel;
    @FXML
    private Slider incrementoSlider;
    @FXML
    private FlowPane presetPane;
    @FXML
    private Label timeSummary;

    // PvC
    @FXML
    private Label difficoltàLabel;
    @FXML
    private Slider difficoltàSlider;
    @FXML
    private Label levelHint;
    @FXML
    private ToggleButton whiteButton;
    @FXML
    private ToggleButton randomButton;
    @FXML
    private ToggleButton blackButton;
    @FXML
    private Button botStockfishBox;
    @FXML
    private Button botMaia1100Box;
    @FXML
    private Button botMaia1500Box;
    @FXML
    private Button botMaia1900Box;

    private final ToggleGroup presetGroup = new ToggleGroup();
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
        if (durataSlider != null) {
            durataSlider.valueProperty().addListener((obs, o, n) -> updateTimeLabels());
            incrementoSlider.valueProperty().addListener((obs, o, n) -> updateTimeLabels());
            buildPresets();
        }
        if (difficoltàSlider != null) {
            difficoltàSlider.valueProperty().addListener((obs, o, n) -> updateBotSelectionVisuals());
            ToggleGroup colorGroup = new ToggleGroup();
            whiteButton.setToggleGroup(colorGroup);
            randomButton.setToggleGroup(colorGroup);
            blackButton.setToggleGroup(colorGroup);
            // Keep one segment always selected.
            colorGroup.selectedToggleProperty().addListener((obs, o, n) -> {
                if (n == null && o != null) {
                    o.setSelected(true);
                }
            });
        }
        refreshDefaults();
        updateSelectionVisuals();
        updateBotSelectionVisuals();
        markUnavailableBots();
    }

    /** Maia needs lc0 and its weights: rows of engines missing on this device are disabled, with the reason. */
    private void markUnavailableBots() {
        if (botStockfishBox == null) {
            return;
        }
        Object[][] rows = { { botMaia1100Box, EngineType.MAIA_1100 }, { botMaia1500Box, EngineType.MAIA_1500 },
                { botMaia1900Box, EngineType.MAIA_1900 } };
        for (Object[] row : rows) {
            Button button = (Button) row[0];
            String id = ((EngineType) row[1]).profileId();
            EngineSelection.get().profiles().stream().filter(p -> p.id().equals(id) && !p.available()).findFirst()
                    .ifPresent(p -> {
                        button.setDisable(true);
                        if (button.getGraphic() != null
                                && button.getGraphic().lookup(".option-description") instanceof Label desc) {
                            desc.setText(I18n.t("engine.unavailable.short"));
                        }
                    });
        }
    }

    private void buildPresets() {
        for (int[] preset : PRESETS) {
            ToggleButton chip = new ToggleButton(preset[0] + " + " + preset[1]);
            chip.getStyleClass().setAll("chip", "mono");
            chip.setMinWidth(96);
            chip.setToggleGroup(presetGroup);
            chip.setUserData(preset);
            chip.setOnAction(e -> {
                durataSlider.setValue(preset[0]);
                incrementoSlider.setValue(preset[1]);
            });
            presetPane.getChildren().add(chip);
        }
    }

    private void updateTimeLabels() {
        int minutes = (int) durataSlider.getValue();
        int increment = (int) incrementoSlider.getValue();
        durataLabel.setText(I18n.t("pvp.value.minutes", minutes));
        incrementoLabel.setText(I18n.t("pvp.value.seconds", increment));
        timeSummary.setText(minutes + " + " + increment);
        presetGroup.getToggles().forEach(t -> {
            int[] p = (int[]) t.getUserData();
            t.setSelected(p[0] == minutes && p[1] == increment);
        });
    }

    private void refreshDefaults() {
        if (durataSlider != null) {
            durataSlider.setValue(ConfigManager.getIntProperty("game.default.duration", 10));
            incrementoSlider.setValue(ConfigManager.getIntProperty("game.default.increment", 0));
            updateTimeLabels();
        }
        if (difficoltàSlider != null) {
            difficoltàSlider.setValue(ConfigManager.getIntProperty("game.bot.level", 10));
            updateBotSelectionVisuals();
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
        if (botStockfishBox == null) {
            return;
        }
        botStockfishBox.pseudoClassStateChanged(SELECTED, selectedBotType == EngineType.STOCKFISH);
        botMaia1100Box.pseudoClassStateChanged(SELECTED, selectedBotType == EngineType.MAIA_1100);
        botMaia1500Box.pseudoClassStateChanged(SELECTED, selectedBotType == EngineType.MAIA_1500);
        botMaia1900Box.pseudoClassStateChanged(SELECTED, selectedBotType == EngineType.MAIA_1900);

        boolean stockfish = selectedBotType == EngineType.STOCKFISH;
        difficoltàSlider.setDisable(!stockfish);
        levelHint.setVisible(!stockfish);
        levelHint.setManaged(!stockfish);
        difficoltàLabel.setText(stockfish ? I18n.t("pvc.level.value", (int) difficoltàSlider.getValue())
                : switch (selectedBotType) {
                    case MAIA_1100 -> "Elo 1100";
                    case MAIA_1500 -> "Elo 1500";
                    default -> "Elo 1900";
                });
    }

    private void updateSelectionVisuals() {
        if (whiteButton == null) {
            return;
        }
        whiteButton.setSelected("white".equals(selectedColor));
        blackButton.setSelected("black".equals(selectedColor));
        randomButton.setSelected("random".equals(selectedColor));
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }

    @FXML
    private void startGamePvP() {
        int duration = (int) durataSlider.getValue();
        int increment = (int) incrementoSlider.getValue();
        ActiveGameController gameController = (ActiveGameController) mainController.getController("GAME");
        mainController.navigateTo("GAME");
        gameController.startPvP(duration, increment);
    }

    @FXML
    private void startGamePvC() {
        int difficulty = (int) difficoltàSlider.getValue();
        boolean isWhite = true;
        if ("black".equals(selectedColor)) {
            isWhite = false;
        } else if ("random".equals(selectedColor)) {
            isWhite = Math.random() < 0.5;
        }
        ActiveGameController gameController = (ActiveGameController) mainController.getController("GAME");
        mainController.navigateTo("GAME");
        gameController.startPvC(difficulty, isWhite, selectedBotType);
    }
}
