package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Training.DrillProgress;
import io.github.hardin22.javachess.Training.EndgameDrills;

/**
 * Endgame positions to win (or hold) against the engine at full strength, by category: a picture of the position,
 * the task, the difficulty and how it went so far. A tap opens the exercise.
 */
public class EndgamesController implements Screen {

    private MainController mainController;
    private final VBox root = new VBox();
    private final VBox body = new VBox(14);

    public EndgamesController() {
        ScreenHeader header = new ScreenHeader(I18n.t("training.endgames"),
                () -> mainController.navigateTo("TRAINING"));
        header.setSubtitle(I18n.t("training.endgames.subtitle"));
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
        fill();
    }

    @Override
    public boolean onBack() {
        mainController.navigateTo("TRAINING");
        return true;
    }

    private void fill() {
        body.getChildren().clear();
        DrillProgress progress = DrillProgress.get();
        for (String category : EndgameDrills.categories()) {
            body.getChildren().add(Ui.sectionLabel(category));
            VBox group = new VBox();
            group.getStyleClass().add("group");
            for (EndgameDrills.Drill drill : EndgameDrills.inCategory(category)) {
                if (!group.getChildren().isEmpty()) {
                    group.getChildren().add(Ui.hairline());
                }
                group.getChildren().add(row(drill, progress.entry(drill.id())));
            }
            body.getChildren().add(group);
        }
    }

    private Node row(EndgameDrills.Drill drill, DrillProgress.Entry entry) {
        ChessBoardUI mini = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 17);
        mini.setShowCoordinates(false);
        mini.setOverlaysEnabled(false);
        mini.setMouseTransparent(true);
        mini.setFlipped(!drill.white());
        mini.showPosition(drill.fen(), null, false);
        StackPane thumb = new StackPane(mini);
        thumb.getStyleClass().add("thumb");

        Label status = Ui.label(entry.label(), "t-small", entry.stars() > 0 ? "t-ok" : "t-muted");
        HBox meta = new HBox(10, dots(drill.level()), status);
        meta.setAlignment(Pos.CENTER_LEFT);
        VBox texts = new VBox(6, Ui.label(drill.title(), "row-title"), Ui.wrap(drill.task(), "row-sub"), meta);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox right = new HBox(2);
        for (int i = 0; i < 2; i++) {
            var star = Icons.of("fth-star", 26);
            star.getStyleClass().add(i < entry.stars() ? "star-on" : "star-off");
            right.getChildren().add(star);
        }
        HBox row = new HBox(18, thumb, texts, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("row", "row-press");
        row.setId("drill-" + drill.id());
        row.setOnMouseClicked(e -> DrillController.open(mainController, drill));
        return row;
    }

    /** Difficulty 1-3 as filled dots. */
    private static HBox dots(int level) {
        HBox box = new HBox(5);
        box.setAlignment(Pos.CENTER_LEFT);
        for (int i = 1; i <= 3; i++) {
            javafx.scene.layout.Region dot = new javafx.scene.layout.Region();
            dot.getStyleClass().addAll("level-dot", i <= level ? "on" : "off");
            box.getChildren().add(dot);
        }
        return box;
    }
}
