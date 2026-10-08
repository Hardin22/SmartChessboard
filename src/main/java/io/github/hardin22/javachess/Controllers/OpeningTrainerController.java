package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Analysis.BoardFollower;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.StatusCard.Tone;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Training.OpeningCatalog;
import io.github.hardin22.javachess.Training.OpeningExplorer;
import io.github.hardin22.javachess.Training.OpeningProgress;
import io.github.hardin22.javachess.Training.OpeningTrainer;
import io.github.hardin22.javachess.Training.OpeningTrainer.State;

import java.util.List;

/**
 * Training on one opening ({@link OpeningTrainer}, package Training): play the moves of the line on the screen or
 * on the real board, the app answers with moves people really play and says when a move leaves the theory.
 */
public class OpeningTrainerController extends BoardScreen {

    private final Label progressText = Ui.label("", "t-small", "t-muted");
    private final Region progressFill = new Region();
    private final StackPane progressBar;
    private final Label movesText = Ui.wrap("", "opening-moves");
    private final VBox theoryBox = new VBox(10);
    private final Button hintButton;
    private final Button restartButton;
    private final ToggleButton boardToggle;
    private OpeningTrainer trainer;
    private BoardFollower follower;

    public OpeningTrainerController() {
        super(I18n.t("training.openings"));
        Region track = new Region();
        track.getStyleClass().add("progress-track");
        progressFill.getStyleClass().add("progress-fill");
        progressFill.setMaxWidth(0);
        progressBar = new StackPane(track, progressFill);
        StackPane.setAlignment(progressFill, Pos.CENTER_LEFT);
        progressBar.widthProperty().addListener((obs, o, n) -> refresh());
        theoryBox.managedProperty().bind(theoryBox.visibleProperty());
        extras.getChildren().addAll(new VBox(8, progressText, progressBar), movesText, theoryBox);

        hintButton = Ui.toolButton(I18n.t("training.hint"), "fth-help-circle", () -> trainer.hint());
        hintButton.setId("opening-hint");
        restartButton = Ui.toolButton(I18n.t("training.restart"), "fth-rotate-ccw", () -> trainer.restart());
        boardToggle = Ui.toolToggle(I18n.t("training.useboard"), "fth-grid");
        boardToggle.setOnAction(e -> {
            if (trainer != null) {
                trainer.useBoard(boardToggle.isSelected());
                refresh();
            }
        });
        tools.getChildren().addAll(hintButton, restartButton, boardToggle);
    }

    /** Opens the trainer on an opening (from the list). */
    public static void open(MainController main, OpeningCatalog.Opening opening) {
        OpeningTrainerController screen = (OpeningTrainerController) main.getController("OPENING_TRAINER");
        main.navigateTo("OPENING_TRAINER");
        screen.start(opening);
    }

    void start(OpeningCatalog.Opening opening) {
        close();
        boolean hardware = Hardware.boardState().isHardwareConnected();
        follower = hardware ? new BoardFollower(Hardware.boardState()) : null;
        trainer = new OpeningTrainer(opening, follower, OpeningProgress.get());
        header.setTitle(opening.title());
        face(opening.white());
        newBoard(opening.white() ? Side.WHITE : Side.BLACK, () -> trainer.fenProperty().get(),
                () -> trainer.stateProperty().get() != State.DONE && !boardToggle.isSelected(),
                uci -> trainer.play(uci));
        boardToggle.setSelected(false);
        boardToggle.setDisable(!hardware);
        if (hardware) {
            boardToggle.setSelected(true);
            trainer.useBoard(true);
        }
        watch(this::refresh, trainer.stateProperty(), trainer.messageProperty(), trainer.fenProperty(),
                trainer.shownMoveProperty(), trainer.movesTextProperty(), trainer.openingNameProperty(),
                trainer.theoryProperty(), trainer.pliesProperty(), trainer.mistakesProperty());
        if (follower != null) {
            watch(this::refresh, follower.messageProperty());
        }
        refresh();
    }

    @Override
    protected void leave() {
        close();
        mainController.navigateTo("OPENINGS");
    }

    @Override
    protected void close() {
        super.close();
        if (trainer != null) {
            trainer.close();
            trainer = null;
        }
    }

    private void refresh() {
        if (trainer == null || board == null) {
            return;
        }
        State state = trainer.stateProperty().get();
        String name = trainer.openingNameProperty().get();
        header.setSubtitle(name == null || name.isBlank() ? trainer.opening().san() : name);
        show(trainer.fenProperty().get(), trainer.lastMoveProperty().get());
        board.clearArrows();
        arrow(trainer.shownMoveProperty().get(), state == State.WRONG ? GOOD : HINT);

        int plies = trainer.pliesProperty().get();
        int max = Math.max(1, trainer.maxPlies());
        int mistakes = trainer.mistakesProperty().get();
        progressText.setText(I18n.t("training.openings.line", Math.min(plies, max), max)
                + (mistakes > 0 ? " · " + I18n.t("training.mistakes", mistakes) : ""));
        progressFill.setMaxWidth(progressBar.getWidth() * Math.min(1.0, plies / (double) max));
        String moves = trainer.movesTextProperty().get();
        movesText.setText(moves == null || moves.isBlank() ? trainer.opening().san() : moves);

        List<OpeningExplorer.Candidate> theory = trainer.theoryProperty().get();
        theoryBox.getChildren().clear();
        theoryBox.setVisible(theory != null && !theory.isEmpty());
        if (theory != null && !theory.isEmpty()) {
            theoryBox.getChildren().add(Ui.label(I18n.t("training.theory"), "t-small", "t-muted"));
            theory.stream().limit(4).forEach(c -> theoryBox.getChildren().add(theoryRow(c)));
        }

        String message = trainer.messageProperty().get();
        String onBoard = follower != null && boardToggle.isSelected() ? follower.messageProperty().get() : null;
        status.show(switch (state) {
            case YOUR_MOVE -> card(Tone.TURN, I18n.t("training.yourmove"), message,
                    onBoard != null && !onBoard.isBlank() ? onBoard
                            : I18n.t(boardToggle.isSelected() ? "training.move.board" : "training.move.screen"));
            case WRONG -> card(Tone.ERROR, I18n.t("training.wrong"), message, onBoard);
            case DONE -> card(Tone.DONE, I18n.t("training.done"), message, null,
                    action(I18n.t("training.restart"), "fth-rotate-ccw", "btn-inverse", () -> trainer.restart()),
                    action(I18n.t("training.openings.other"), "fth-list", "btn-outline", this::leave));
        });
        hintButton.setDisable(state == State.DONE);
    }

    /** "Cf3 ████████ 45%": the theory moves as proportional bars. */
    private static HBox theoryRow(OpeningExplorer.Candidate c) {
        Label san = Ui.label(c.san(), "theory-move");
        san.setMinWidth(96);
        Region bar = new Region();
        bar.getStyleClass().add("theory-bar");
        StackPane track = new StackPane(bar);
        track.getStyleClass().add("theory-track");
        StackPane.setAlignment(bar, Pos.CENTER_LEFT);
        bar.maxWidthProperty().bind(track.widthProperty().multiply(Math.max(0.03, c.share())));
        HBox.setHgrow(track, Priority.ALWAYS);
        Label pct = Ui.label(c.percent(), "t-small", "t-muted");
        pct.setMinWidth(64);
        pct.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(14, san, track, pct);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
