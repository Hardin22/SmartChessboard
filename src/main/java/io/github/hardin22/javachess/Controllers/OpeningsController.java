package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Training.OpeningCatalog;
import io.github.hardin22.javachess.Training.OpeningProgress;

import java.util.List;

/**
 * Openings to learn, with the White or with the Black pieces: one row each (name, moves, idea, how well it is
 * known), the ones to review first. A tap opens the trainer.
 */
public class OpeningsController implements Screen {

    private static final String SIDE_KEY = "training.openings.white";

    private MainController mainController;
    private final VBox root = new VBox();
    private final VBox list = new VBox();
    private final ToggleGroup sides = new ToggleGroup();
    private final ToggleButton white = new ToggleButton(I18n.t("training.side.white"));
    private final ToggleButton black = new ToggleButton(I18n.t("training.side.black"));

    public OpeningsController() {
        ScreenHeader header = new ScreenHeader(I18n.t("training.openings"),
                () -> mainController.navigateTo("TRAINING"));
        header.setSubtitle(I18n.t("training.openings.subtitle"));
        white.setUserData(true);
        black.setUserData(false);
        HBox segments = Ui.segmented(sides, List.of(white, black));
        Ui.keepOneSelected(sides);
        (Prefs.bool(SIDE_KEY, true) ? white : black).setSelected(true);
        sides.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null) {
                Prefs.set(SIDE_KEY, n.getUserData());
                fill();
            }
        });
        list.getStyleClass().add("group");
        VBox body = new VBox(16, segments, list);
        body.getStyleClass().add("screen-body");
        root.getChildren().addAll(header, Ui.scroll(body));
        root.getStyleClass().add("screen");
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void onNavigatedTo() {
        fill(); // labels change after a training run
    }

    @Override
    public boolean onBack() {
        mainController.navigateTo("TRAINING");
        return true;
    }

    private void fill() {
        boolean forWhite = white.isSelected();
        OpeningProgress progress = OpeningProgress.get();
        List<OpeningCatalog.Opening> openings = progress.toReview(OpeningCatalog.forSide(forWhite));
        list.getChildren().clear();
        for (int i = 0; i < openings.size(); i++) {
            if (i > 0) {
                list.getChildren().add(Ui.hairline());
            }
            list.getChildren().add(row(openings.get(i), progress.entry(openings.get(i).id())));
        }
    }

    private Node row(OpeningCatalog.Opening opening, OpeningProgress.Entry entry) {
        Label san = Ui.wrap(opening.san(), "opening-san");
        Label idea = Ui.wrap(opening.idea(), "row-sub");
        VBox texts = new VBox(6, Ui.label(opening.title(), "row-title"), san, idea);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        Label level = Ui.label(entry.label(), "level-chip", "level-" + entry.level());
        level.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox right = new VBox(8, level);
        right.setAlignment(Pos.TOP_RIGHT);
        if (entry.runs() > 0) {
            right.getChildren().add(Ui.label(I18n.t(entry.runs() == 1 ? "training.runs.one" : "training.runs",
                    entry.runs()), "t-small", "t-muted"));
        }
        HBox row = new HBox(16, texts, right, Icons.of("fth-chevron-right", 28));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("row", "row-press");
        row.setId("opening-" + opening.id());
        row.setOnMouseClicked(e -> OpeningTrainerController.open(mainController, opening));
        return row;
    }
}
