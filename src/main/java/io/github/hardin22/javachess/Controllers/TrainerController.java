package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import io.github.hardin22.javachess.Analysis.BoardFollower;
import io.github.hardin22.javachess.Analysis.MistakeTrainer;
import io.github.hardin22.javachess.Analysis.MistakeTrainer.Exercise;
import io.github.hardin22.javachess.Analysis.MistakeTrainer.State;
import io.github.hardin22.javachess.Components.BoardFrame;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.ReviewLabels;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.StatusCard;
import io.github.hardin22.javachess.Components.StatusCard.Tone;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;

import java.util.ArrayList;
import java.util.List;

/**
 * "Rigioca i tuoi errori": the positions where a side went wrong in a reviewed game, one at a time, to find the
 * better move. Presentation only; {@link MistakeTrainer} (package Analysis) judges the tries and guides the board.
 */
public class TrainerController implements Screen {

    private static final Color GOOD = Color.web("#5FBF6A");
    private static final Color BAD = Color.web("#E5534B");

    private MainController mainController;
    private final VBox root = new VBox();
    private final ScreenHeader header;
    private final EvalBar evalBar = new EvalBar(22, 600);
    private final BoardFrame boardFrame = new BoardFrame(evalBar);
    private final StatusCard status = new StatusCard();
    private final HBox inGame = new HBox(14);
    private final Button solutionButton;
    private final Button skipButton;
    private final ToggleButton boardToggle;
    private final VBox lower;
    private final HBox tools;

    private ChessBoardUI board;
    private MistakeTrainer trainer;
    private final List<Runnable> unbind = new ArrayList<>();

    public TrainerController() {
        header = new ScreenHeader(I18n.t("trainer.title"), this::leave);
        evalBar.setVisible(false);
        solutionButton = Ui.toolButton(I18n.t("trainer.solution"), "fth-eye", () -> trainer.showSolution());
        skipButton = Ui.toolButton(I18n.t("trainer.skip"), "fth-skip-forward", () -> trainer.next());
        boardToggle = Ui.toolToggle(I18n.t("trainer.board"), "fth-grid");
        boardToggle.setOnAction(e -> {
            if (trainer != null) {
                trainer.useBoard(boardToggle.isSelected());
                refresh();
            }
        });
        inGame.setAlignment(Pos.CENTER_LEFT);
        lower = new VBox(16, status, inGame);
        lower.setPadding(new Insets(16, 24, 0, 24));
        tools = Ui.equalRow(12, solutionButton, skipButton, boardToggle);
        tools.setPadding(new Insets(12, 24, 28, 24));
        root.getChildren().addAll(header, new VBox(boardFrame), lower, Ui.vgrow(), tools);
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        mainController.facingBlackProperty().addListener((obs, o, n) -> {
            if (board != null) {
                board.setFlipped(n);
            }
        });
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void setWide(boolean wide) {
        root.getChildren().clear();
        if (!wide) {
            VBox.setVgrow(boardFrame, Priority.NEVER);
            root.getChildren().addAll(header, new VBox(boardFrame), lower, Ui.vgrow(), tools);
            return;
        }
        VBox.setVgrow(boardFrame, Priority.ALWAYS);
        VBox boardBox = new VBox(boardFrame);
        // 720 px of height: the card and the extras scroll, the tools stay at the bottom
        VBox panel = new VBox(header, Ui.scroll(lower), tools);
        panel.setPrefWidth(Ui.COLUMN);
        panel.setMinWidth(560);
        HBox.setHgrow(boardBox, Priority.ALWAYS);
        HBox columns = new HBox(8, boardBox, panel);
        VBox.setVgrow(columns, Priority.ALWAYS);
        root.getChildren().add(columns);
    }

    @Override
    public boolean onBack() {
        leave();
        return true;
    }

    private void leave() {
        close();
        mainController.navigateTo("REVIEW");
    }

    /** Opens the trainer on the given positions (from the review screen). */
    public static void open(MainController main, List<Exercise> exercises, BoardFollower follower) {
        TrainerController screen = (TrainerController) main.getController("TRAINER");
        main.navigateTo("TRAINER");
        screen.start(new MistakeTrainer(exercises, follower));
    }

    void start(MistakeTrainer t) {
        close();
        trainer = t;
        board = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 80);
        board.setFitToParent(true);
        board.setOverlaysEnabled(false);
        boardFrame.setBoard(board);
        board.setMoveInput(new ChessBoardUI.MoveInput() {
            @Override
            public Board position() {
                Board b = new Board();
                Exercise e = trainer.currentProperty().get();
                if (e != null) {
                    b.loadFromFen(e.fen());
                }
                return b;
            }

            @Override
            public boolean enabled() {
                return trainer.stateProperty().get() == State.YOUR_MOVE && !boardToggle.isSelected();
            }

            @Override
            public void play(String uci) {
                trainer.attempt(uci);
            }

            @Override
            public void choosePromotion(String from, String to, boolean white, java.util.function.Consumer<String> done) {
                PromotionPicker.show(mainController, white, piece -> done.accept(from + to + piece));
            }
        });
        boolean hardware = ArduinoController.getInstance().getBoardStateManager().isHardwareConnected();
        boardToggle.setSelected(false);
        boardToggle.setDisable(!hardware);
        if (hardware) {
            boardToggle.setSelected(true);
            t.useBoard(true);
        }
        javafx.beans.InvalidationListener update = o -> refresh();
        for (javafx.beans.Observable p : List.of(t.currentProperty(), t.stateProperty(), t.messageProperty(),
                t.fenProperty(), t.shownMoveProperty(), t.indexProperty(), t.solvedProperty())) {
            p.addListener(update);
            unbind.add(() -> p.removeListener(update));
        }
        javafx.beans.InvalidationListener turn = o -> faceSolver();
        t.currentProperty().addListener(turn);
        unbind.add(() -> t.currentProperty().removeListener(turn));
        faceSolver();
        refresh();
    }

    /** The screen (and the board drawn on it) turns towards the side that has to find the move. */
    private void faceSolver() {
        Exercise e = trainer == null ? null : trainer.currentProperty().get();
        if (e != null && mainController != null) {
            mainController.face(e.white() ? Side.WHITE : Side.BLACK);
        }
        if (board != null && mainController != null) {
            board.setFlipped(mainController.isFacingBlack());
        }
    }

    private void close() {
        unbind.forEach(Runnable::run);
        unbind.clear();
        if (trainer != null) {
            trainer.close();
        }
    }

    private void refresh() {
        if (trainer == null || board == null) {
            return;
        }
        Exercise e = trainer.currentProperty().get();
        State state = trainer.stateProperty().get();
        int total = trainer.total();
        int index = Math.min(trainer.indexProperty().get() + 1, total);
        header.setSubtitle(state == State.FINISHED ? I18n.t("trainer.done.subtitle", trainer.solvedProperty().get(), total)
                : I18n.t("trainer.progress", index, total));

        String fen = trainer.fenProperty().get();
        String shown = trainer.shownMoveProperty().get();
        if (fen != null) {
            board.showPosition(fen, shown, false);
        }
        board.clearArrows();
        if (shown != null && shown.length() >= 4 && state != State.YOUR_MOVE && e != null) {
            Color c = state == State.WRONG ? BAD : GOOD;
            board.drawArrowOnBoard(shown.charAt(0) - 'a', '8' - shown.charAt(1), shown.charAt(2) - 'a',
                    '8' - shown.charAt(3), c);
        }

        inGame.getChildren().clear();
        if (e != null && state != State.FINISHED) {
            Label text = Ui.wrap(I18n.t("trainer.ingame", e.moveText()), "t-body", "t-muted");
            HBox.setHgrow(text, Priority.ALWAYS);
            inGame.getChildren().addAll(ReviewLabels.tile(e.label(), 40), text);
        }

        String message = trainer.messageProperty().get();
        String task = e == null ? "" : e.task();
        StatusCard.Content content = switch (state) {
            case YOUR_MOVE -> new StatusCard.Content(Tone.TURN, I18n.t("trainer.kicker"), task, null,
                    boardToggle.isSelected() ? I18n.t("trainer.move.board") : I18n.t("trainer.move.screen"), List.of());
            case CHECKING -> StatusCard.Content.of(Tone.PLAIN, I18n.t("trainer.kicker"), message, null);
            case CORRECT, ALSO_GOOD -> new StatusCard.Content(Tone.DONE, I18n.t("trainer.right"), message, null, null,
                    List.of(action(I18n.t("trainer.next"), "fth-arrow-right", "btn-inverse", trainer::next)));
            case WRONG -> new StatusCard.Content(Tone.ERROR, I18n.t("trainer.wrong"), message, null, null,
                    List.of(action(I18n.t("trainer.retry"), "fth-rotate-ccw", "btn-inverse", trainer::retry),
                            action(I18n.t("trainer.solution"), "fth-eye", "btn-outline", trainer::showSolution)));
            case SOLUTION -> new StatusCard.Content(Tone.ACTION, I18n.t("trainer.solution"), message, null, null,
                    List.of(action(I18n.t("trainer.next"), "fth-arrow-right", "btn-inverse", trainer::next)));
            case FINISHED -> new StatusCard.Content(Tone.DONE, I18n.t("trainer.done"), message, null, null,
                    List.of(action(I18n.t("trainer.back"), "fth-bar-chart-2", "btn-inverse", this::leave)));
        };
        status.show(content);
        boolean playing = state == State.YOUR_MOVE || state == State.WRONG || state == State.CHECKING;
        solutionButton.setDisable(!playing || state == State.CHECKING);
        skipButton.setDisable(state == State.FINISHED);
        tools.setVisible(state != State.FINISHED);
    }

    /** For demos: tries the game move ("played") or the best one ("best"). */
    public void devAttempt(String which) {
        Exercise e = trainer == null ? null : trainer.currentProperty().get();
        if (e != null) {
            trainer.attempt("best".equals(which) ? e.best() : e.played());
        }
    }

    private static Node action(String text, String icon, String style, Runnable run) {
        Button b = Ui.button(text, icon, style, "btn-md");
        b.setOnAction(ev -> run.run());
        return b;
    }
}
