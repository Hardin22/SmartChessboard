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
import io.github.hardin22.javachess.Play.PuzzleInsights;
import io.github.hardin22.javachess.Play.PuzzleReview;
import io.github.hardin22.javachess.Play.PuzzleRush;
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
                Ui.gap(8), Ui.sectionLabel(I18n.t("puzzle.rush")), rushRow(),
                reviewCard, weakBox,
                Ui.gap(8), Ui.sectionLabel(I18n.t("puzzle.rating")), difficulty, quick,
                Ui.gap(8), Ui.sectionLabel(I18n.t("puzzle.themes")),
                Ui.wrap(I18n.t("puzzle.themes.description"), "t-small", "t-muted"), themes);
        body.getStyleClass().add("screen-body");

        VBox footer = Ui.footer(startButton);
        root.setTop(header);
        content = Ui.scroll(body);
        root.setCenter(content);
        root.setBottom(footer);
        this.footer = footer;
    }

    private javafx.scene.Node content;
    private VBox footer;
    private final VBox reviewCard = new VBox(12);
    private final VBox weakBox = new VBox(12);
    private final List<Label> rushRecords = new ArrayList<>();

    /** Three tiles: 3 minutes, 5 minutes, survival, each with its record. */
    private javafx.scene.layout.GridPane rushRow() {
        List<javafx.scene.Node> tiles = new ArrayList<>();
        for (PuzzleRush.Mode m : PuzzleRush.Mode.values()) {
            boolean timed = m.seconds() > 0;
            Label record = Ui.label("", "t-small", "t-muted");
            record.setUserData(m);
            rushRecords.add(record);
            VBox content = timed
                    ? new VBox(2, Ui.label(String.valueOf(m.seconds() / 60), "option-big"),
                            Ui.label(I18n.t("puzzle.rush.minutes"), "option-sub"), record)
                    : new VBox(6, io.github.hardin22.javachess.Components.Icons.of("fth-heart", 38),
                            Ui.label(m.italian(), "option-sub"), record);
            content.setAlignment(Pos.CENTER);
            Button tile = new Button();
            tile.setGraphic(content);
            tile.getStyleClass().setAll("option");
            tile.setMinHeight(150);
            tile.setOnAction(e -> startRush(m));
            tiles.add(tile);
        }
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(12);
        for (int i = 0; i < tiles.size(); i++) {
            javafx.scene.layout.ColumnConstraints col = new javafx.scene.layout.ColumnConstraints();
            col.setPercentWidth(100.0 / tiles.size());
            grid.getColumnConstraints().add(col);
            ((Button) tiles.get(i)).setMaxWidth(Double.MAX_VALUE);
            ((Button) tiles.get(i)).setMinWidth(0);
            grid.add(tiles.get(i), i, 0);
        }
        return grid;
    }

    private void startRush(PuzzleRush.Mode m) {
        PuzzleController controller = (PuzzleController) mainController.getController("PUZZLE_GAME");
        mainController.navigateTo("PUZZLE_GAME");
        controller.startRush(m);
    }

    /** "7 puzzle da rifare · Ripassa", and the themes with the lowest success rate (tap = puzzles on it). */
    private void refreshTraining(int reviewCount, List<PuzzleInsights.ThemeScore> weakest) {
        for (Label record : rushRecords) {
            int best = new PuzzleRush((PuzzleRush.Mode) record.getUserData(), playerRating).bestProperty().get();
            record.setText(best > 0 ? I18n.t("puzzle.rush.best", best) : "");
            record.setVisible(best > 0);
            record.setManaged(best > 0);
        }
        reviewCard.getChildren().clear();
        reviewCard.setVisible(reviewCount > 0);
        reviewCard.setManaged(reviewCount > 0);
        if (reviewCount > 0) {
            Label title = Ui.label(I18n.t("puzzle.review"), "row-title");
            Label count = Ui.wrap(I18n.t("puzzle.review.count", reviewCount), "t-small", "t-muted");
            VBox texts = new VBox(4, title, count);
            HBox.setHgrow(texts, javafx.scene.layout.Priority.ALWAYS);
            Button go = Ui.button(I18n.t("puzzle.review.start"), "fth-rotate-ccw", "btn-inverse", "btn-md");
            go.setOnAction(e -> {
                PuzzleController controller = (PuzzleController) mainController.getController("PUZZLE_GAME");
                mainController.navigateTo("PUZZLE_GAME");
                controller.startReview();
            });
            HBox row = new HBox(16, texts, go);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("card");
            row.setPadding(new Insets(20, 22, 20, 22));
            reviewCard.getChildren().add(row);
        }
        weakBox.getChildren().clear();
        weakBox.setVisible(!weakest.isEmpty());
        weakBox.setManaged(!weakest.isEmpty());
        if (!weakest.isEmpty()) {
            FlowPane chips = new FlowPane(10, 10);
            for (PuzzleInsights.ThemeScore t : weakest) {
                Button chip = Ui.button(t.name() + " · " + t.rateText(), null, "chip");
                chip.setOnAction(e -> {
                    for (ToggleButton toggle : themeToggles) {
                        toggle.setSelected(t.tag().equals(toggle.getUserData()));
                    }
                    handleStart();
                });
                chips.getChildren().add(chip);
            }
            weakBox.getChildren().addAll(Ui.label(I18n.t("puzzle.weak"), "row-title"),
                    Ui.wrap(I18n.t("puzzle.weak.description"), "t-small", "t-muted"), chips);
        }
    }

    /** First start without the puzzle database: say how to install it instead of a "Inizia" that finds nothing. */
    private void showMissingDatabase(boolean missing) {
        if (!missing) {
            root.setCenter(content);
            root.setBottom(footer);
            return;
        }
        VBox empty = new VBox(18, io.github.hardin22.javachess.Components.Icons.of("fth-database", 64),
                Ui.label(I18n.t("puzzle.nodb.title"), "empty-title"),
                Ui.wrap(I18n.t("puzzle.nodb.text"), "empty-sub"),
                Ui.label("scripts/build-puzzle-db.sh --download data/puzzles.db", "pill"),
                Ui.wrap(I18n.t("puzzle.nodb.docs"), "t-small", "t-faint"));
        empty.getStyleClass().add("empty-state");
        root.setCenter(empty);
        root.setBottom(null);
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
            boolean missing = !PuzzleService.hasPuzzleData() && !Boolean.getBoolean("javachess.demo.puzzlesUi");
            int reviewCount = PuzzleReview.get().size();
            List<PuzzleInsights.ThemeScore> weakest = PuzzleInsights.weakest(stats, 3);
            Platform.runLater(() -> {
                refreshTraining(reviewCount, weakest);
                showMissingDatabase(missing);
                playerRating = stats.rating();
                ratingValue.setText(String.valueOf(stats.rating()));
                solvedValue.setText(stats.solved() + "/" + stats.attempts());
                streakValue.setText(String.valueOf(stats.currentStreak()));
                if (!ratingInitialised) {
                    ratingInitialised = true;
                    difficulty.setValue(round(stats.rating()));
                }
                int v = difficulty.getValue();
                difficulty.setValue(v == 600 ? 650 : 600);
                difficulty.setValue(v); // refreshes the quick chips for the player's rating
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
            controller.startPuzzle(puzzle, rating, themes);
        }
    }
}
