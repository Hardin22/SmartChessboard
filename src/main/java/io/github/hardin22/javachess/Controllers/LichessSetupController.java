package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Utils.LichessAPIHelper;

import java.util.List;

/**
 * Lichess game through the Board API (advanced, reached from Settings → Avanzate; the Home "Lichess" button opens
 * lichess.org in the integrated browser). Time control, rated/casual, colour; the seek runs off the FX thread.
 */
public class LichessSetupController implements Screen {

    private static final int[][] TIMES = { { 3, 2 }, { 5, 0 }, { 5, 3 }, { 10, 0 }, { 10, 5 }, { 15, 10 } };

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final ToggleGroup timeGroup = new ToggleGroup();
    private final ToggleGroup ratedGroup = new ToggleGroup();
    private final ToggleGroup colorGroup = new ToggleGroup();
    private final Label statusLabel = Ui.wrap("", "t-body", "t-muted");
    private final Button seekButton;
    private int selectedTime = 10;
    private int selectedIncrement = 0;

    public LichessSetupController() {
        seekButton = Ui.wide(I18n.t("lichess.seek"), "fth-search", "btn-primary", "btn-lg");
        seekButton.setOnAction(e -> startSeek());
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
        ScreenHeader header = new ScreenHeader(I18n.t("lichess.title"), () -> mainController.navigateTo("SETTINGS"));
        header.setSubtitle(I18n.t("lichess.subtitle"));

        GridPane times = new GridPane();
        times.setHgap(14);
        times.setVgap(14);
        for (int c = 0; c < 3; c++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 3);
            col.setHgrow(Priority.ALWAYS);
            times.getColumnConstraints().add(col);
        }
        for (int i = 0; i < TIMES.length; i++) {
            int[] t = TIMES[i];
            ToggleButton tile = new ToggleButton();
            VBox content = new VBox(2, Ui.label(t[0] + "+" + t[1], "option-big"),
                    Ui.label(PvpSetupController.category(t[0], t[1]), "option-sub"));
            content.setAlignment(Pos.CENTER);
            tile.setGraphic(content);
            tile.getStyleClass().setAll("option");
            tile.setMaxWidth(Double.MAX_VALUE);
            tile.setMinHeight(140);
            tile.setToggleGroup(timeGroup);
            tile.setOnAction(e -> {
                selectedTime = t[0];
                selectedIncrement = t[1];
            });
            if (t[0] == 10 && t[1] == 0) {
                tile.setSelected(true);
            }
            times.add(tile, i % 3, i / 3);
        }
        Ui.keepOneSelected(timeGroup);

        ToggleButton casual = new ToggleButton(I18n.t("lichess.casual"));
        casual.setUserData(false);
        ToggleButton rated = new ToggleButton(I18n.t("lichess.rated"));
        rated.setUserData(true);
        var ratedRow = Ui.segmented(ratedGroup, List.of(casual, rated));
        casual.setSelected(true);

        ToggleButton white = new ToggleButton(I18n.t("common.white"));
        white.setUserData("white");
        ToggleButton random = new ToggleButton(I18n.t("common.random"));
        random.setUserData("random");
        ToggleButton black = new ToggleButton(I18n.t("common.black"));
        black.setUserData("black");
        var colorRow = Ui.segmented(colorGroup, List.of(white, random, black));
        random.setSelected(true);

        Button browser = Ui.wide(I18n.t("lichess.browser"), "fth-external-link", "btn-outline");
        browser.setOnAction(e -> mainController.openBrowser("https://lichess.org"));

        VBox body = new VBox(16,
                Ui.sectionLabel(I18n.t("pvp.timecontrol")), times,
                Ui.sectionLabel(I18n.t("lichess.mode")), ratedRow,
                Ui.sectionLabel(I18n.t("pvc.color")), colorRow,
                Ui.gap(8), statusLabel, browser);
        body.getStyleClass().add("screen-body");
        VBox footer = new VBox(seekButton);
        footer.setPadding(new Insets(16, 32, 36, 32));
        root.setTop(header);
        root.setCenter(Ui.scroll(body));
        root.setBottom(footer);
    }

    public void startSeek() {
        boolean isRated = ratedGroup.getSelectedToggle() != null
                && Boolean.TRUE.equals(ratedGroup.getSelectedToggle().getUserData());
        String color = colorGroup.getSelectedToggle() != null
                ? String.valueOf(colorGroup.getSelectedToggle().getUserData()) : "random";
        statusLabel.setText(I18n.t("lichess.searching"));
        seekButton.setDisable(true);
        int time = selectedTime;
        int increment = selectedIncrement;
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            String gameId = LichessAPIHelper.createSeek(time, increment, isRated, color);
            Platform.runLater(() -> {
                seekButton.setDisable(false);
                if (gameId != null && !gameId.startsWith("ERROR:")) {
                    statusLabel.setText(I18n.t("lichess.found"));
                    ActiveGameController controller = (ActiveGameController) mainController.getController("GAME");
                    mainController.navigateTo("GAME");
                    controller.startOnlineGame(gameId);
                } else {
                    // LichessClient already produces a message meant for the user.
                    statusLabel.setText(gameId != null ? gameId.replace("ERROR:", "").trim()
                            : I18n.t("lichess.error.timeout"));
                }
            });
        });
    }

    @Override
    public void onNavigatedFrom() {
        LichessAPIHelper.cancelSeek(); // leaving the screen must not leave a seek open on Lichess
    }
}
