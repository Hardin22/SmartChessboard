package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.PuzzleThemes;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Services.PuzzleService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Puzzle dashboard: target rating, mode and theme filters. Puzzle lookup runs off the FX thread. */
public class PuzzleDashboardController implements NavigationAware {

    private MainController mainController;

    @FXML
    private Slider ratingSlider;
    @FXML
    private Label ratingValueLabel;
    @FXML
    private VBox themeSections;
    @FXML
    private Button startButton;
    @FXML
    private Label playerRatingLabel;
    @FXML
    private Label solvedLabel;
    @FXML
    private Label streakLabel;
    private boolean ratingInitialised;

    private final List<ToggleButton> themeToggles = new ArrayList<>();

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        ratingSlider.valueProperty().addListener((obs, o, n) -> ratingValueLabel.setText(String.valueOf(n.intValue())));

        for (Map.Entry<String, Map<String, String>> section : PuzzleThemes.SECTIONS.entrySet()) {
            Label title = new Label(I18n.t(section.getKey()));
            title.getStyleClass().add("setting-title");
            FlowPane chips = new FlowPane(8, 8);
            for (Map.Entry<String, String> theme : section.getValue().entrySet()) {
                ToggleButton chip = new ToggleButton(theme.getValue());
                chip.getStyleClass().setAll("chip");
                chip.setUserData(theme.getKey());
                themeToggles.add(chip);
                chips.getChildren().add(chip);
            }
            themeSections.getChildren().add(new VBox(10, title, chips));
        }
    }

    @Override
    public void onNavigatedTo() {
        // Progress file I/O off the FX thread.
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            var stats = io.github.hardin22.javachess.Services.PuzzleProgressService.getInstance().getStats();
            Platform.runLater(() -> {
                playerRatingLabel.setText(String.valueOf(stats.rating()));
                solvedLabel.setText(stats.solved() + "/" + stats.attempts());
                streakLabel.setText(String.valueOf(stats.currentStreak()));
                if (!ratingInitialised) {
                    ratingInitialised = true;
                    ratingSlider.setValue(Math.round(stats.rating() / 50.0) * 50);
                }
            });
        });
    }

    @FXML
    public void handleStart() {
        int targetRating = (int) ratingSlider.getValue();
        List<String> selectedThemes = getSelectedThemes();
        startButton.setDisable(true);
        startButton.setText(I18n.t("puzzle.loading"));
        // The search reads the puzzle database: off the FX thread.
        PuzzleService.getInstance().findPuzzleAsync(targetRating, 200, selectedThemes)
                .thenAccept(puzzle -> Platform.runLater(() -> {
                    startButton.setDisable(false);
                    startButton.setText(I18n.t("puzzle.start"));
                    if (puzzle != null) {
                        navigateToPuzzleGame(puzzle, targetRating, selectedThemes);
                    } else {
                        mainController.showToast(I18n.t("puzzle.none"));
                    }
                }));
    }

    private List<String> getSelectedThemes() {
        List<String> themes = new ArrayList<>();
        for (ToggleButton chip : themeToggles) {
            if (chip.isSelected()) {
                themes.add((String) chip.getUserData());
            }
        }
        if (themes.isEmpty()) {
            themes.add("Tutti");
        }
        return themes;
    }

    private void navigateToPuzzleGame(Puzzle puzzle, int rating, List<String> themes) {
        PuzzleController controller = (PuzzleController) mainController.getController("PUZZLE_GAME");
        if (controller != null) {
            mainController.navigateTo("PUZZLE_GAME");
            controller.setPuzzle(puzzle, rating, themes);
        }
    }

    @FXML
    public void handleBack() {
        mainController.navigateTo("HOME");
    }
}
