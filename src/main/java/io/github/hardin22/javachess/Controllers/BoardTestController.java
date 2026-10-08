package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.StatusCard.Tone;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Hardware.BoardDiagnostics;
import io.github.hardin22.javachess.Hardware.BoardDiagnostics.Phase;

/**
 * Settings → "Prova la scacchiera" ({@link BoardDiagnostics}, package Hardware): every LED in four colours then one
 * square at a time with its name written big (checks the wiring order), and every sensor (place and lift a piece
 * on each square, the square turns green on the board drawn here).
 */
public class BoardTestController extends BoardScreen {

    private static final String EMPTY = "8/8/8/8/8/8/8/8 w - - 0 1";
    private static final Color CHECKED = Color.web("#3DD68C", 0.55);
    private static final Color OCCUPIED = Color.web("#FFFFFF", 0.55);
    private static final Color LIT = Color.web("#4F9DFF", 0.6);

    private final Label step = Ui.label("", "coordinate-target");
    private final Label countText = Ui.label("", "t-body");
    private final Region countFill = new Region();
    private final StackPane countBar;
    private final VBox sensorBox;
    private final Label help = Ui.wrap(I18n.t("boardtest.help"), "t-small", "t-muted");
    private final Button ledButton;
    private final Button sensorButton;
    private BoardDiagnostics diagnostics;

    public BoardTestController() {
        super(I18n.t("boardtest.title"));
        header.setSubtitle(I18n.t("boardtest.subtitle"));
        HBox stepRow = new HBox(step);
        stepRow.setAlignment(Pos.CENTER);
        stepRow.managedProperty().bind(stepRow.visibleProperty());
        Region track = new Region();
        track.getStyleClass().add("progress-track");
        countFill.getStyleClass().add("progress-fill");
        countFill.setMaxWidth(0);
        countBar = new StackPane(track, countFill);
        StackPane.setAlignment(countFill, Pos.CENTER_LEFT);
        countBar.widthProperty().addListener((obs, o, n) -> refresh());
        sensorBox = new VBox(8, countText, countBar);
        sensorBox.managedProperty().bind(sensorBox.visibleProperty());
        extras.getChildren().addAll(stepRow, sensorBox, help);

        ledButton = Ui.toolButton(I18n.t("boardtest.leds"), "fth-sun", () -> diagnostics.startLedTest());
        ledButton.setId("boardtest-leds");
        sensorButton = Ui.toolButton(I18n.t("boardtest.sensors"), "fth-grid", () -> diagnostics.startSensorTest());
        sensorButton.setId("boardtest-sensors");
        Button done = Ui.toolButton(I18n.t("boardtest.done"), "fth-check", this::leave);
        tools.getChildren().addAll(ledButton, sensorButton, done);
    }

    @Override
    public void onNavigatedTo() {
        close();
        diagnostics = new BoardDiagnostics();
        newBoard(Side.WHITE, () -> EMPTY, () -> false, null);
        board.showPosition(EMPTY, null, false);
        watch(this::refresh, diagnostics.phaseProperty(), diagnostics.messageProperty(),
                diagnostics.ledStepProperty(), diagnostics.checkedCountProperty(), diagnostics.checkedProperty(),
                diagnostics.occupiedProperty());
        refresh();
    }

    @Override
    protected void leave() {
        close();
        mainController.navigateTo("SETTINGS");
    }

    @Override
    protected void close() {
        super.close();
        if (diagnostics != null) {
            diagnostics.stop(); // gives the board back: mandatory
            diagnostics = null;
        }
    }

    private void refresh() {
        if (diagnostics == null || board == null) {
            return;
        }
        Phase phase = diagnostics.phaseProperty().get();
        String led = diagnostics.ledStepProperty().get();
        step.getParent().setVisible(phase == Phase.LEDS && led != null && !led.isBlank());
        step.setText(led == null ? "" : led);
        boolean sensors = phase == Phase.SENSORS || phase == Phase.DONE;
        sensorBox.setVisible(sensors);
        int count = diagnostics.checkedCountProperty().get();
        countText.setText(I18n.t("boardtest.count", count));
        countFill.setMaxWidth(countBar.getWidth() * count / 64.0);

        board.clearHighlights();
        if (sensors) {
            long checked = diagnostics.checkedProperty().get();
            long occupied = diagnostics.occupiedProperty().get();
            for (int sq = 0; sq < 64; sq++) {
                long bit = 1L << sq;
                if ((checked & bit) != 0) {
                    board.highlightSquare(sq % 8, 7 - sq / 8, CHECKED);
                } else if ((occupied & bit) != 0) {
                    board.highlightSquare(sq % 8, 7 - sq / 8, OCCUPIED);
                }
            }
        } else if (phase == Phase.LEDS && led != null && led.matches("[a-h][1-8]")) {
            mark(led, LIT);
        }

        String message = diagnostics.messageProperty().get();
        Tone tone = switch (phase) {
            case IDLE -> message == null || message.isBlank() ? Tone.PLAIN : Tone.ACTION;
            case LEDS, SENSORS -> Tone.TURN;
            case DONE -> Tone.DONE;
        };
        String title = switch (phase) {
            case IDLE -> I18n.t("boardtest.idle");
            case LEDS -> I18n.t("boardtest.leds.running");
            case SENSORS -> I18n.t("boardtest.sensors.running");
            case DONE -> I18n.t("boardtest.sensors.ok");
        };
        status.show(card(tone, I18n.t("boardtest.kicker"), title, message == null || message.isBlank()
                ? I18n.t("boardtest.idle.detail") : message));
        ledButton.setDisable(phase == Phase.LEDS);
    }
}
