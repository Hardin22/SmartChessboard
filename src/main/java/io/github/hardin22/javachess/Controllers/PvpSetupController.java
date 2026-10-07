package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Stepper;
import io.github.hardin22.javachess.Components.Ui;

import java.util.Map;

/** Two players on the same board: time control as big tiles, or custom with − / +, then "Gioca". */
public class PvpSetupController implements Screen {

    private static final int[][] PRESETS = { { 1, 0 }, { 3, 0 }, { 3, 2 }, { 5, 0 }, { 5, 3 }, { 10, 0 }, { 10, 5 },
            { 15, 10 }, { 30, 0 } };
    private static final int[] MINUTES = { 1, 2, 3, 4, 5, 7, 10, 12, 15, 20, 25, 30, 45, 60, 90 };
    private static final int[] INCREMENTS = { 0, 1, 2, 3, 5, 10, 15, 20, 30 };

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final ToggleGroup presets = new ToggleGroup();
    private final Stepper minutes = new Stepper(MINUTES, 10);
    private final Stepper increment = new Stepper(INCREMENTS, 0);

    public PvpSetupController() {
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
        ScreenHeader header = new ScreenHeader(I18n.t("pvp.title"), () -> mainController.navigateTo("HOME"));
        header.setSubtitle(I18n.t("pvp.subtitle"));

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        for (int c = 0; c < 3; c++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 3);
            col.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(col);
        }
        for (int i = 0; i < PRESETS.length; i++) {
            int[] p = PRESETS[i];
            ToggleButton tile = new ToggleButton();
            VBox content = new VBox(2, Ui.label(p[0] + "+" + p[1], "option-big"), Ui.label(category(p[0], p[1]),
                    "option-sub"));
            content.setAlignment(Pos.CENTER);
            tile.setGraphic(content);
            tile.getStyleClass().setAll("option");
            tile.setMaxWidth(Double.MAX_VALUE);
            tile.setMinHeight(148);
            tile.setUserData(p);
            tile.setToggleGroup(presets);
            tile.setOnAction(e -> {
                minutes.setValue(p[0]);
                increment.setValue(p[1]);
                tile.setSelected(true);
            });
            grid.add(tile, i % 3, i / 3);
        }

        minutes.format(String::valueOf, I18n.t("pvp.minutes.unit"));
        increment.format(v -> "+" + v, I18n.t("pvp.increment.unit"));
        minutes.valueProperty().addListener((obs, o, n) -> syncPresets());
        increment.valueProperty().addListener((obs, o, n) -> syncPresets());

        VBox custom = new VBox(14,
                Ui.label(I18n.t("pvp.duration"), "row-title"), minutes,
                Ui.gap(6),
                Ui.label(I18n.t("pvp.increment"), "row-title"), increment);

        VBox body = new VBox(16,
                Ui.sectionLabel(I18n.t("pvp.timecontrol")), grid,
                Ui.gap(8), Ui.sectionLabel(I18n.t("pvp.custom")), custom,
                Ui.gap(8), Ui.wrap(I18n.t("pvp.hint"), "t-small", "t-muted"));
        body.getStyleClass().add("screen-body");

        Button play = Ui.wide(I18n.t("common.play"), "fth-play", "btn-primary", "btn-lg");
        play.setOnAction(e -> start());
        VBox footer = Ui.footer(play);

        root.setTop(header);
        root.setCenter(Ui.scroll(body));
        root.setBottom(footer);
        refreshDefaults();
    }

    static String category(int minutes, int increment) {
        double estimate = minutes + increment * 40 / 60.0; // FIDE-like estimate over 40 moves
        if (estimate < 3) {
            return "Bullet";
        }
        if (estimate < 10) {
            return "Blitz";
        }
        if (estimate < 60) {
            return "Rapid";
        }
        return I18n.t("pvp.classical");
    }

    private void syncPresets() {
        presets.getToggles().forEach(t -> {
            int[] p = (int[]) t.getUserData();
            t.setSelected(p[0] == minutes.getValue() && p[1] == increment.getValue());
        });
    }

    @Override
    public void onNavigatedTo() {
        refreshDefaults();
    }

    private void refreshDefaults() {
        minutes.setValue(Prefs.integer("game.default.duration", 10));
        increment.setValue(Prefs.integer("game.default.increment", 0));
        syncPresets();
    }

    private void start() {
        int m = minutes.getValue();
        int inc = increment.getValue();
        Prefs.setAll(Map.of("game.default.duration", String.valueOf(m), "game.default.increment", String.valueOf(inc)));
        ActiveGameController game = (ActiveGameController) mainController.getController("GAME");
        mainController.navigateTo("GAME");
        game.startPvP(m, inc);
    }
}
