package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.StatusCard.Tone;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Training.CoordinateTrainer;
import io.github.hardin22.javachess.Training.CoordinateTrainer.Mode;
import io.github.hardin22.javachess.Training.CoordinateTrainer.State;
import io.github.hardin22.javachess.Utils.ConfigManager;

import java.util.List;
import java.util.Locale;

/**
 * Square names in 30 seconds ({@link CoordinateTrainer}, package Training): "Trova la casa" (touch it on the real
 * board or on the screen) and "Nomina la casa" (the square is lit, four names to choose from), from White's or
 * Black's side. The board on screen has no coordinates, like the real one.
 */
public class CoordinatesController extends BoardScreen {

    private static final String EMPTY = "8/8/8/8/8/8/8/8 w - - 0 1";
    private static final String SIDE_KEY = "training.coordinates.white";
    private static final Color TARGET = Color.web("#4F9DFF", 0.55);
    private static final Color WRONG = Color.web("#E5534B", 0.6);

    private final ToggleGroup sides = new ToggleGroup();
    private final ToggleButton fromWhite = new ToggleButton(I18n.t("training.coordinates.white"));
    private final ToggleButton fromBlack = new ToggleButton(I18n.t("training.coordinates.black"));
    private final Label findRecord = Ui.label("", "t-small", "t-muted");
    private final Label nameRecord = Ui.label("", "t-small", "t-muted");
    private final VBox choose;
    private final Label target = Ui.label("", "coordinate-target");
    private final Label time = Ui.label("", "coordinate-time");
    private final Label score = Ui.label("", "t-body");
    private final GridPane choices = new GridPane();
    private final VBox playing;
    private final Button stopButton;
    private CoordinateTrainer trainer;

    public CoordinatesController() {
        super(I18n.t("training.coordinates"));
        header.setSubtitle(I18n.t("training.coordinates.subtitle"));
        fromWhite.setUserData(true);
        fromBlack.setUserData(false);
        HBox segments = Ui.segmented(sides, List.of(fromWhite, fromBlack));
        Ui.keepOneSelected(sides);
        (Prefs.bool(SIDE_KEY, true) ? fromWhite : fromBlack).setSelected(true);
        sides.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null) {
                Prefs.set(SIDE_KEY, n.getUserData());
                prepare(null);
            }
        });
        VBox find = tileCard("fth-crosshair", Mode.FIND.label, I18n.t("training.coordinates.find.what"), findRecord,
                () -> prepare(Mode.FIND));
        find.setId("coordinates-find");
        VBox name = tileCard("fth-type", Mode.NAME.label, I18n.t("training.coordinates.name.what"), nameRecord,
                () -> prepare(Mode.NAME));
        name.setId("coordinates-name");
        choose = new VBox(14, segments, Ui.equalRow(14, find, name));

        choices.setHgap(14);
        choices.setVgap(14);
        javafx.scene.layout.ColumnConstraints half = new javafx.scene.layout.ColumnConstraints();
        half.setPercentWidth(50);
        choices.getColumnConstraints().addAll(half, half);
        HBox clockRow = new HBox(16, time, Ui.hgrow(), score);
        clockRow.setAlignment(Pos.CENTER_LEFT);
        HBox targetRow = new HBox(target);
        targetRow.setAlignment(Pos.CENTER);
        targetRow.managedProperty().bind(targetRow.visibleProperty());
        playing = new VBox(14, clockRow, targetRow, choices);
        playing.managedProperty().bind(playing.visibleProperty());
        choose.managedProperty().bind(choose.visibleProperty());
        extras.getChildren().addAll(choose, playing);

        stopButton = Ui.toolButton(I18n.t("training.coordinates.stop"), "fth-square", () -> {
            if (trainer != null) {
                trainer.finish();
            }
        });
        tools.getChildren().add(stopButton);
    }

    @Override
    public void onNavigatedTo() {
        prepare(null);
    }

    @Override
    protected void leave() {
        close();
        mainController.navigateTo("TRAINING");
    }

    @Override
    protected void close() {
        super.close();
        if (trainer != null) {
            trainer.close();
            trainer = null;
        }
    }

    /** {@code mode} null: the choice of the exercise; otherwise a trainer ready to start ("Via!"). */
    private void prepare(Mode mode) {
        close();
        boolean white = fromWhite.isSelected();
        findRecord.setText(record(Mode.FIND, white));
        nameRecord.setText(record(Mode.NAME, white));
        face(white);
        newBoard(white ? Side.WHITE : Side.BLACK, () -> EMPTY, () -> false, null);
        board.setShowCoordinates(false);
        board.showPosition(EMPTY, null, false);
        if (mode == null) {
            header.setTitle(I18n.t("training.coordinates"));
            choose.setVisible(true);
            playing.setVisible(false);
            tools.setVisible(false);
            status.show(card(Tone.PLAIN, I18n.t("training.coordinates.kicker"), I18n.t("training.coordinates.pick"),
                    I18n.t("training.coordinates.pick.detail")));
            return;
        }
        trainer = new CoordinateTrainer(mode, white);
        CoordinateTrainer t = trainer;
        header.setTitle(mode.label);
        board.setOnSquareTapped(sq -> {
            if (t.stateProperty().get() == State.PLAYING && mode == Mode.FIND && sq != null) {
                t.answer(sq.name().toLowerCase(Locale.ROOT));
            }
        });
        watch(this::refresh, t.stateProperty(), t.targetProperty(), t.choicesProperty(), t.scoreProperty(),
                t.mistakesProperty(), t.messageProperty(), t.timeTextProperty(), t.wrongSquareProperty());
        refresh();
    }

    private static String record(Mode mode, boolean white) {
        int best = ConfigManager.getIntProperty("training.coordinates." + (mode == Mode.FIND ? "find" : "name")
                + "." + (white ? "white" : "black") + ".best", 0);
        return best > 0 ? I18n.t("training.coordinates.best", best) : I18n.t("training.coordinates.nobest");
    }

    private void refresh() {
        if (trainer == null || board == null) {
            return;
        }
        State state = trainer.stateProperty().get();
        Mode mode = trainer.mode();
        choose.setVisible(false);
        playing.setVisible(state == State.PLAYING);
        tools.setVisible(state == State.PLAYING);
        time.setText(trainer.timeTextProperty().get());
        score.setText(I18n.t("training.coordinates.score", trainer.scoreProperty().get(),
                trainer.mistakesProperty().get()));

        String square = trainer.targetProperty().get();
        target.getParent().setVisible(mode == Mode.FIND);
        target.setText(square == null ? "" : square);
        board.clearHighlights();
        if (state == State.PLAYING && mode == Mode.NAME) {
            mark(square, TARGET);
        }
        String wrong = trainer.wrongSquareProperty().get();
        if (wrong != null && !wrong.isBlank()) {
            mark(wrong, WRONG);
        }
        choices.getChildren().clear();
        if (state == State.PLAYING && mode == Mode.NAME) {
            List<String> names = trainer.choicesProperty().get();
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i);
                Button b = Ui.button(n, null, "btn", "btn-lg", "coordinate-choice");
                b.setMaxWidth(Double.MAX_VALUE);
                b.setOnAction(e -> trainer.answer(n));
                choices.add(b, i % 2, i / 2);
            }
        }

        String message = trainer.messageProperty().get();
        status.show(switch (state) {
            case READY -> card(Tone.ACTION, mode.label, message, I18n.t("training.coordinates.time",
                            CoordinateTrainer.SECONDS),
                    action(I18n.t("training.coordinates.go"), "fth-play", "btn-inverse", () -> trainer.start()),
                    action(I18n.t("training.coordinates.change"), "fth-list", "btn-outline", () -> prepare(null)));
            case PLAYING -> card(Tone.TURN, mode.label, message == null || message.isBlank()
                    ? I18n.t(mode == Mode.FIND ? "training.coordinates.find.ask" : "training.coordinates.name.ask")
                    : message, null);
            case FINISHED -> card(Tone.DONE, trainer.newRecordProperty().get() ? I18n.t("training.coordinates.record.new")
                            : I18n.t("training.coordinates.over"), message,
                    I18n.t("training.coordinates.best", trainer.bestProperty().get()),
                    action(I18n.t("training.coordinates.again"), "fth-rotate-ccw", "btn-inverse", () -> prepare(mode)),
                    action(I18n.t("training.coordinates.change"), "fth-list", "btn-outline", () -> prepare(null)));
        });
    }
}
