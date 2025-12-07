package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import org.example.javachess.Utils.ImageCache;

public class GameSetupController implements NavigationAware {

    private MainController mainController;

    // PvP Controls
    @FXML private Label durataLabel;
    @FXML private Slider durataSlider;
    @FXML private Label incrementoLabel;
    @FXML private Slider incrementoSlider;

    // PvC Controls
    @FXML private Label difficoltàLabel;
    @FXML private Slider difficoltàSlider;
    @FXML private ToggleButton whiteButton;
    @FXML private ToggleButton blackButton;
    @FXML private ToggleButton randomButton;
    @FXML private ToggleGroup colorGroup;
    @FXML private VBox whiteVbox;
    @FXML private VBox blackVbox;
    @FXML private VBox randomVbox;
    @FXML private ImageView white;
    @FXML private ImageView black;
    @FXML private ImageView random;

    private String selectedColor = "random";

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Initialize PvP listeners
        if (durataSlider != null) {
            durataSlider.valueProperty().addListener((obs, oldVal, newVal) -> 
                durataLabel.setText("durata: " + newVal.intValue() + "min"));
        }
        if (incrementoSlider != null) {
            incrementoSlider.valueProperty().addListener((obs, oldVal, newVal) -> 
                incrementoLabel.setText("incremento: " + newVal.intValue() + "s"));
        }

        // Initialize PvC listeners
        if (difficoltàSlider != null) {
            difficoltàSlider.valueProperty().addListener((obs, oldVal, newVal) -> 
                difficoltàLabel.setText("Difficoltà: " + newVal.intValue()));
        }

        if (colorGroup != null) {
            colorGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
                whiteVbox.getStyleClass().remove("vbox-border-purple");
                blackVbox.getStyleClass().remove("vbox-border-purple");
                randomVbox.getStyleClass().remove("vbox-border-purple");

                if (newVal == whiteButton) {
                    selectedColor = "white";
                    whiteVbox.getStyleClass().add("vbox-border-purple");
                } else if (newVal == blackButton) {
                    selectedColor = "black";
                    blackVbox.getStyleClass().add("vbox-border-purple");
                } else if (newVal == randomButton) {
                    selectedColor = "random";
                    randomVbox.getStyleClass().add("vbox-border-purple");
                }
            });
            
            // Load images for color selection
            try {
                ImageCache cache = ImageCache.getInstance();
                // Assuming images are available, if not, handle gracefully
                // white.setImage(cache.getImage("/images/white_king.png")); // Placeholder
                // black.setImage(cache.getImage("/images/black_king.png")); // Placeholder
                // random.setImage(cache.getImage("/images/random.png")); // Placeholder
            } catch (Exception e) {
                // Ignore if images missing for now
            }
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
        int difficulty = (int) difficoltàSlider.getValue();
        boolean isWhite = true;
        if ("black".equals(selectedColor)) isWhite = false;
        else if ("random".equals(selectedColor)) isWhite = Math.random() < 0.5;

        mainController.loadView("GAME", "/UI/GameView.fxml");
        ActiveGameController gameController = (ActiveGameController) mainController.getController("GAME");
        gameController.startPvC(difficulty, isWhite);
        mainController.navigateTo("GAME");
    }
}
