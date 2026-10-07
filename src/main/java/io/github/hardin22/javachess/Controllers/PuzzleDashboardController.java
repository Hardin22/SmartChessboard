package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.PuzzleThemes;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Stepper;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Services.PuzzleService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Puzzle dashboard: progress, difficulty (− / + by 50) and theme chips, then "Inizia". */
public class PuzzleDashboardController implements Screen {

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final Label ratingValue = Ui.label("—", "t-number");
    private final Label solvedValue = Ui.label("—", "t-number");
    private final Label streakValue = Ui.label("—", "t-number");
    private final Stepper difficulty = new Stepper(600, 3000, 50, 1500);
    private final List<ToggleButton> themeToggles = new ArrayList<>();
    private final Button startButton;
    private boolean ratingInitialised;
    private int playerRating = 1500;

    public PuzzleDashboardController() {
        startButton = Ui.wide(I18n.t("puzzle.start"), "fth-play", "btn-primary", "btn-lg");
        startButton.setOnAction(e -> handleStart());
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

    private void build() {
        ScreenHeader header = new ScreenHeader(I18n.t("puzzle.title"), () -> mainController.navigateTo("HOME"));
        header.setSubtitle(I18n.t("puzzle.subtitle"));

        HBox stats = Ui.equalRow(14, stat(ratingValue, I18n.t("puzzle.stats.rating")),
                stat(solvedValue, I18n.t("puzzle.stats.solved")), stat(streakValue, I18n.t("puzzle.stats.streak")));

        difficulty.format(String::valueOf, I18n.t("puzzle.rating.unit"));
        ToggleButton easy = Ui.chip(I18n.t("puzzle.level.easy"));
        ToggleButton right = Ui.chip(I18n.t("puzzle.level.right"));
        ToggleButton hard = Ui.chip(I18n.t("puzzle.level.hard"));
        easy.setOnAction(e -> difficulty.setValue(round(playerRating - 300)));
        right.setOnAction(e -> difficulty.setValue(round(playerRating)));
        hard.setOnAction(e -> difficulty.setValue(round(playerRating + 300)));
        HBox quick = Ui.equalRow(10, easy, right, hard);
        difficulty.valueProperty().addListener((obs, o, n) -> {
            easy.setSelected(n.intValue() == round(playerRating - 300));
            right.setSelected(n.intValue() == round(playerRating));
            hard.setSelected(n.intValue() == round(playerRating + 300));
        });

        VBox themes = new VBox(18);
        for (Map.Entry<String, Map<String, String>> section : PuzzleThemes.SECTIONS.entrySet()) {
            FlowPane chips = new FlowPane(10, 10);
            for (Map.Entry<String, String> theme : section.getValue().entrySet()) {
                ToggleButton chip = Ui.chip(theme.getValue());
                chip.setUserData(theme.getKey());
                themeToggles.add(chip);
                chips.getChildren().add(chip);
            }
            themes.getChildren().add(new VBox(12, Ui.label(I18n.t(section.getKey()), "row-title"), chips));
        }

        VBox body = new VBox(16,
                stats,
                Ui.gap(8), Ui.sectionLabel(I18n.t("puzzle.rating")), difficulty, quick,
                Ui.gap(8), Ui.sectionLabel(I18n.t("puzzle.themes")),
                Ui.wrap(I18n.t("puzzle.themes.description"), "t-small", "t-muted"), themes);
        body.getStyleClass().add("screen-body");

        VBox footer = new VBox(startButton);
        footer.setPadding(new Insets(16, 32, 36, 32));
        root.setTop(header);
        root.setCenter(Ui.scroll(body));
        root.setBottom(footer);
    }

    private static int round(int rating) {
        return Math.max(600, Math.min(3000, Math.round(rating / 50f) * 50));
    }

    private static VBox stat(Label value, String caption) {
        VBox box = new VBox(2, value, Ui.label(caption, "t-small", "t-muted"));
        box.getStyleClass().add("card");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(20, 22, 20, 22));
        return box;
    }

    @Override
    public void onNavigatedTo() {
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            var stats = io.github.hardin22.javachess.Services.PuzzleProgressService.getInstance().getStats();
            Platform.runLater(() -> {
                playerRating = stats.rating();
                ratingValue.setText(String.valueOf(stats.rating()));
                solvedValue.setText(stats.solved() + "/" + stats.attempts());
                streakValue.setText(String.valueOf(stats.currentStreak()));
                if (!ratingInitialised) {
                    ratingInitialised = true;
                    difficulty.setValue(round(stats.rating()));
                }
            });
        });
    }

    public void handleStart() {
        int targetRating = difficulty.getValue();
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
}
