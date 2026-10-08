package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Training.DrillProgress;
import io.github.hardin22.javachess.Training.EndgameDrills;
import io.github.hardin22.javachess.Training.OpeningCatalog;
import io.github.hardin22.javachess.Training.OpeningProgress;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;

/**
 * "Allenamento": the exercises that work offline with the physical board — openings, endgames, square names.
 * One large row each, with what has been done so far; the progress files are read off the FX thread.
 */
public class TrainingController implements Screen {

    private MainController mainController;
    private final VBox root = new VBox();
    private final Label openingsSub = Ui.wrap("", "tile-sub");
    private final Label endgamesSub = Ui.wrap("", "tile-sub");
    private final Label coordinatesSub = Ui.wrap("", "tile-sub");

    public TrainingController() {
        ScreenHeader header = new ScreenHeader(I18n.t("training.title"), () -> mainController.navigateTo("HOME"));
        header.setSubtitle(I18n.t("training.subtitle"));
        HBox openings = row("fth-book-open", I18n.t("training.openings"), I18n.t("training.openings.what"),
                openingsSub, () -> mainController.navigateTo("OPENINGS"));
        openings.setId("training-openings");
        HBox endgames = row("fth-award", I18n.t("training.endgames"), I18n.t("training.endgames.what"),
                endgamesSub, () -> mainController.navigateTo("ENDGAMES"));
        endgames.setId("training-endgames");
        HBox coordinates = row("fth-crosshair", I18n.t("training.coordinates"),
                I18n.t("training.coordinates.what"), coordinatesSub, () -> mainController.navigateTo("COORDINATES"));
        coordinates.setId("training-coordinates");
        HBox analysis = row("fth-activity", I18n.t("training.analysis"), I18n.t("training.analysis.what"),
                new Label(), this::openAnalysis);
        analysis.setId("training-analysis");
        Label note = Ui.wrap(I18n.t("training.note"), "t-small", "t-muted");
        VBox body = new VBox(16, openings, endgames, coordinates, analysis, Ui.gap(8), note);
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
        AppExecutors.io().execute(() -> {
            OpeningProgress op = OpeningProgress.get();
            long sure = OpeningCatalog.all().stream().filter(o -> op.entry(o.id()).level() >= 3).count();
            int openings = OpeningCatalog.all().size();
            DrillProgress dp = DrillProgress.get();
            long solved = EndgameDrills.all().stream().filter(d -> dp.entry(d.id()).solved() > 0).count();
            int drills = EndgameDrills.all().size();
            int best = 0;
            for (String mode : new String[] { "find", "name" }) {
                for (String side : new String[] { "white", "black" }) {
                    best = Math.max(best, ConfigManager.getIntProperty(
                            "training.coordinates." + mode + "." + side + ".best", 0));
                }
            }
            int record = best;
            javafx.application.Platform.runLater(() -> {
                openingsSub.setText(I18n.t("training.openings.progress", openings, sure));
                endgamesSub.setText(I18n.t("training.endgames.progress", drills, solved));
                coordinatesSub.setText(record > 0 ? I18n.t("training.coordinates.record", record)
                        : I18n.t("training.coordinates.none"));
            });
        });
    }

    /** Free analysis: the position editor, then the analysis board on the chosen position. */
    private void openAnalysis() {
        io.github.hardin22.javachess.Components.PositionEditor editor =
                new io.github.hardin22.javachess.Components.PositionEditor(null, fen -> {
                    mainController.closeSheet();
                    ReviewController.openPosition(mainController, fen);
                }, I18n.t("training.analysis.go"));
        mainController.showSheet(I18n.t("training.analysis"), editor);
    }

    @Override
    public boolean onBack() {
        mainController.navigateTo("HOME");
        return true;
    }

    /** A large tappable row: icon, title, one line about the exercise, the progress so far. */
    private static HBox row(String icon, String title, String what, Label progress, Runnable action) {
        StackPane iconBox = new StackPane(Icons.of(icon, 36));
        iconBox.getStyleClass().add("tile-icon");
        progress.getStyleClass().add("t-accent");
        VBox texts = new VBox(6, Ui.label(title, "tile-title"), Ui.wrap(what, "tile-sub"), progress);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(24, iconBox, texts, Icons.of("fth-chevron-right", 32));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("tile");
        row.setMinHeight(176);
        row.setOnMouseClicked(e -> action.run());
        return row;
    }
}
