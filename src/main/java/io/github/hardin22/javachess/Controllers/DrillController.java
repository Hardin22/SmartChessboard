package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Analysis.BoardFollower;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.StatusCard.Tone;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Training.DrillProgress;
import io.github.hardin22.javachess.Training.DrillSession;
import io.github.hardin22.javachess.Training.DrillSession.State;
import io.github.hardin22.javachess.Training.EndgameDrills;

import java.util.List;

/**
 * One endgame exercise ({@link DrillSession}, package Training) against the engine at full strength: a large
 * counter of the moves left, the idea behind a button, hint, restart and the real board.
 */
public class DrillController extends BoardScreen {

    private final Label counterValue = Ui.label("", "drill-counter");
    private final Label counterLabel = Ui.label("", "t-small", "t-muted");
    private final Label hintsText = Ui.label("", "t-small", "t-muted");
    private final Label tip = Ui.wrap("", "t-body");
    private final VBox tipBox;
    private final Button hintButton;
    private final ToggleButton ideaToggle;
    private final ToggleButton boardToggle;
    private DrillSession session;
    private BoardFollower follower;

    public DrillController() {
        super(I18n.t("training.endgames"));
        VBox counter = new VBox(0, counterLabel, counterValue);
        HBox counterRow = new HBox(20, counter, Ui.hgrow(), hintsText);
        counterRow.setAlignment(Pos.BOTTOM_LEFT);
        tipBox = new VBox(tip);
        tipBox.getStyleClass().add("tip-card");
        tipBox.managedProperty().bind(tipBox.visibleProperty());
        tipBox.setVisible(false);
        extras.getChildren().addAll(counterRow, tipBox);

        ideaToggle = Ui.toolToggle(I18n.t("training.idea"), "fth-zap");
        ideaToggle.setOnAction(e -> tipBox.setVisible(ideaToggle.isSelected()));
        hintButton = Ui.toolButton(I18n.t("training.hint"), "fth-help-circle", () -> session.hint());
        hintButton.setId("drill-hint");
        Button restart = Ui.toolButton(I18n.t("training.restart"), "fth-rotate-ccw", () -> session.restart());
        boardToggle = Ui.toolToggle(I18n.t("training.useboard"), "fth-grid");
        boardToggle.setOnAction(e -> {
            if (session != null) {
                session.useBoard(boardToggle.isSelected());
                refresh();
            }
        });
        for (javafx.scene.Node n : List.of(ideaToggle, hintButton, restart, boardToggle)) {
            HBox.setHgrow(n, Priority.ALWAYS);
        }
        tools.getChildren().addAll(ideaToggle, hintButton, restart, boardToggle);
    }

    /** Opens an exercise (from the list or "Prossimo"). */
    public static void open(MainController main, EndgameDrills.Drill drill) {
        DrillController screen = (DrillController) main.getController("DRILL");
        main.navigateTo("DRILL");
        screen.start(drill);
    }

    void start(EndgameDrills.Drill drill) {
        close();
        boolean hardware = Hardware.boardState().isHardwareConnected();
        follower = hardware ? new BoardFollower(Hardware.boardState()) : null;
        session = new DrillSession(drill, follower, DrillProgress.get());
        header.setTitle(drill.title());
        header.setSubtitle(drill.category());
        tip.setText(drill.tip());
        ideaToggle.setSelected(false);
        tipBox.setVisible(false);
        face(drill.white());
        newBoard(drill.white() ? Side.WHITE : Side.BLACK, () -> session.fenProperty().get(),
                () -> session.stateProperty().get() == State.YOUR_MOVE && !boardToggle.isSelected(),
                uci -> session.play(uci));
        boardToggle.setSelected(false);
        boardToggle.setDisable(!hardware);
        if (hardware) {
            boardToggle.setSelected(true);
            session.useBoard(true);
        }
        watch(this::refresh, session.stateProperty(), session.messageProperty(), session.fenProperty(),
                session.shownMoveProperty(), session.movesLeftProperty(), session.hintsProperty());
        if (follower != null) {
            watch(this::refresh, follower.messageProperty());
        }
        refresh();
    }

    @Override
    protected void leave() {
        close();
        mainController.navigateTo("ENDGAMES");
    }

    @Override
    protected void close() {
        super.close();
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private void refresh() {
        if (session == null || board == null) {
            return;
        }
        EndgameDrills.Drill drill = session.drill();
        State state = session.stateProperty().get();
        show(session.fenProperty().get(), session.lastMoveProperty().get());
        board.clearArrows();
        arrow(session.shownMoveProperty().get(), HINT);

        counterLabel.setText(I18n.t(drill.goal() == EndgameDrills.Goal.DRAW ? "training.drill.hold"
                : "training.drill.left"));
        counterValue.setText(String.valueOf(session.movesLeftProperty().get()));
        int hints = session.hintsProperty().get();
        hintsText.setText(hints == 0 ? "" : I18n.t(hints == 1 ? "training.hints.one" : "training.hints", hints));

        String message = session.messageProperty().get();
        String onBoard = follower != null && boardToggle.isSelected() ? follower.messageProperty().get() : null;
        status.show(switch (state) {
            case YOUR_MOVE -> card(Tone.TURN, I18n.t("training.yourmove"), message, onBoard != null && !onBoard.isBlank() ? onBoard
                    : I18n.t(boardToggle.isSelected() ? "training.move.board" : "training.move.screen"));
            case THINKING -> card(Tone.PLAIN, I18n.t("training.drill.thinking"), message, onBoard);
            case SUCCESS -> card(Tone.DONE, I18n.t("training.drill.success"), message, null,
                    action(I18n.t("training.next"), "fth-arrow-right", "btn-inverse", this::next),
                    action(I18n.t("training.restart"), "fth-rotate-ccw", "btn-outline", () -> session.restart()));
            case FAILED -> card(Tone.ERROR, I18n.t("training.drill.failed"), message, null,
                    action(I18n.t("training.restart"), "fth-rotate-ccw", "btn-inverse", () -> session.restart()));
        });
        hintButton.setDisable(state != State.YOUR_MOVE);
    }

    /** The next exercise of the list (back to the list after the last one). */
    private void next() {
        List<EndgameDrills.Drill> all = EndgameDrills.all();
        int i = all.indexOf(session.drill());
        if (i >= 0 && i + 1 < all.size()) {
            start(all.get(i + 1));
        } else {
            leave();
        }
    }
}
