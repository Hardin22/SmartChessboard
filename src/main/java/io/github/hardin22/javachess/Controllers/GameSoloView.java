package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardFrame;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.PlayerRow;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.StatusCard;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.EvalBar;

import java.util.List;

/**
 * Layout of a game with one person at the board (against the computer, Lichess): the screen faces that person.
 * Top to bottom: header, opponent, board with evaluation bar, player, status card (what to do now), coach line,
 * recent moves, toolbar.
 */
final class GameSoloView extends VBox {

    final ScreenHeader header;
    final PlayerRow blackRow = new PlayerRow(false);
    final PlayerRow whiteRow = new PlayerRow(true);
    final BoardFrame boardFrame;
    final StatusCard status = new StatusCard();
    final ToggleButton hintsToggle = Ui.toolToggle(I18n.t("game.hints"), "fth-navigation");
    final ToggleButton evalToggle = Ui.toolToggle(I18n.t("game.eval"), "fth-bar-chart-2");
    final Button engineButton;
    final Button resignButton;
    final Button undoButton;
    final Button hintButton;
    final Button drawButton;
    private final HBox coach = new HBox();
    private final Label coachEval = Ui.label("", "eval-chip");
    private final Label coachLine = Ui.label("", "t-body-m");
    private final Label coachCaption = Ui.label(I18n.t("game.coach"), "t-overline");
    private final javafx.scene.layout.FlowPane moves = new javafx.scene.layout.FlowPane();
    private final ScrollPane movesScroll;
    private final VBox boardBlock;

    GameSoloView(ActiveGameController controller, EvalBar evalBar) {
        getStyleClass().add("game-solo");
        header = new ScreenHeader("", controller::requestLeave);
        Button menu = Ui.iconButton("fth-more-horizontal", I18n.t("game.menu"), () -> controller.showMenu(false));
        menu.setId("game-menu");
        header.setActions(menu);

        boardFrame = new BoardFrame(evalBar);
        boardBlock = new VBox(4, blackRow, boardFrame, whiteRow);
        for (PlayerRow row : new PlayerRow[] { blackRow, whiteRow }) {
            VBox.setMargin(row, new Insets(0, 24, 0, 24));
        }

        coach.getStyleClass().add("coach-line");
        coachLine.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        VBox coachTexts = new VBox(2, coachCaption, coachLine);
        coachTexts.setMinWidth(0);
        HBox.setHgrow(coachTexts, Priority.ALWAYS);
        coach.getChildren().addAll(coachEval, coachTexts);
        coach.managedProperty().bind(coach.visibleProperty());

        moves.setHgap(10);
        moves.setVgap(10);
        moves.setPadding(new Insets(4, 0, 4, 0));
        movesScroll = new ScrollPane(moves);
        movesScroll.setFitToWidth(true);
        movesScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        movesScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        movesScroll.setPannable(true);
        movesScroll.setMinHeight(64);
        VBox.setVgrow(movesScroll, Priority.ALWAYS);

        engineButton = Ui.toolButton(I18n.t("game.engine"), "fth-cpu", controller::showEngines);
        resignButton = Ui.toolButton(I18n.t("game.resign"), "fth-flag", controller::requestResign);
        resignButton.getStyleClass().add("danger");
        undoButton = Ui.toolButton(I18n.t("game.takeback"), "fth-corner-up-left", controller::takeBack);
        hintButton = Ui.toolButton(I18n.t("game.hint"), "fth-help-circle", controller::requestHint);
        drawButton = Ui.toolButton(I18n.t("game.draw.offer.short"), "fth-minus-circle", controller::offerBotDraw);
        hintsToggle.setOnAction(e -> controller.setShowBestMoves(hintsToggle.isSelected()));
        evalToggle.setOnAction(e -> controller.setShowEvaluation(evalToggle.isSelected()));
        // the always-on suggestions, evaluation and engine are in the "⋯" sheet
        undoButton.setId("game-undo");
        hintButton.setId("game-hint");
        drawButton.setId("game-draw");
        HBox tools = Ui.equalRow(12, undoButton, hintButton, drawButton, resignButton);

        Label movesTitle = Ui.label(I18n.t("game.moves"), "t-overline");
        VBox lower = new VBox(16, status, coach, movesTitle, movesScroll);
        lower.setPadding(new Insets(4, 24, 0, 24));
        VBox.setVgrow(lower, Priority.ALWAYS);
        tools.setPadding(new Insets(12, 24, 28, 24));

        this.lower = lower;
        this.tools = tools;
        getChildren().addAll(header, boardBlock, lower, tools);
        setFillWidth(true);
    }

    private final VBox lower;
    private final HBox tools;

    /** Portrait: board on top of the panel. Wide: board on the left (as tall as the window), panel on the right. */
    void setWide(boolean wide) {
        getChildren().clear();
        if (!wide) {
            VBox.setVgrow(boardFrame, Priority.NEVER);
            getChildren().addAll(header, boardBlock, lower, tools);
            return;
        }
        VBox.setVgrow(boardFrame, Priority.ALWAYS);
        VBox panel = new VBox(header, lower, tools);
        panel.setPrefWidth(Ui.COLUMN);
        panel.setMinWidth(560);
        HBox.setHgrow(boardBlock, Priority.ALWAYS);
        HBox columns = new HBox(8, boardBlock, panel);
        VBox.setVgrow(columns, Priority.ALWAYS);
        getChildren().add(columns);
    }

    void setPlayers(String whiteName, String whiteMeta, String blackName, String blackMeta) {
        whiteRow.setName(whiteName);
        whiteRow.setMeta(whiteMeta);
        blackRow.setName(blackName);
        blackRow.setMeta(blackMeta);
    }

    /** Board seen from Black's side: Black's row goes below the board, next to the person reading. */
    void setFlipped(boolean flipped) {
        if (boardFrame.getBoard() != null) {
            boardFrame.getBoard().setFlipped(flipped);
        }
        boardBlock.getChildren().setAll(flipped ? whiteRow : blackRow, boardFrame, flipped ? blackRow : whiteRow);
    }

    PlayerRow row(boolean white) {
        return white ? whiteRow : blackRow;
    }

    void setPosition(String fen) {
        whiteRow.setPosition(fen);
        blackRow.setPosition(fen);
    }

    /** A hint asked for ("Muovi il Cavallo in g1"): shown in the coach line until the move is made. */
    void setHint(String text) {
        coach.setVisible(true);
        coachCaption.setText(I18n.t("game.hint"));
        coachEval.setVisible(false);
        coachEval.setManaged(false);
        coachLine.setText(text);
    }

    /** Coach line: best move for the player and its evaluation; hidden when suggestions and evaluation are off. */
    void setCoach(boolean visible, String evalText, boolean blackAhead, String line) {
        coach.setVisible(visible);
        if (!visible) {
            return;
        }
        coachCaption.setText(I18n.t("game.coach"));
        coachEval.setText(evalText == null || evalText.isBlank() ? "–" : evalText);
        coachEval.getStyleClass().removeAll("white-adv", "black-adv");
        coachEval.getStyleClass().add(blackAhead ? "black-adv" : "white-adv");
        coachEval.setVisible(evalText != null);
        coachEval.setManaged(evalText != null);
        coachLine.setText(line == null || line.isBlank() ? I18n.t("game.coach.waiting") : line);
    }

    /** The moves as chips ("12. Cf3 Cc6") wrapping in rows, the last one highlighted, scrolled to the end. */
    void setMoves(List<String> san, int firstNumber, boolean blackStarts) {
        moves.getChildren().clear();
        if (san.isEmpty()) {
            moves.getChildren().add(Ui.label(I18n.t("moves.empty"), "t-small", "t-faint"));
            return;
        }
        int i = 0;
        int number = firstNumber;
        int lastIndex = san.size() - 1;
        if (blackStarts) {
            moves.getChildren().add(chip(number + "… " + san.get(0), lastIndex == 0));
            i = 1;
            number++;
        }
        for (; i < san.size(); i += 2) {
            String text = number + ". " + san.get(i) + (i + 1 < san.size() ? "  " + san.get(i + 1) : "");
            moves.getChildren().add(chip(text, i == lastIndex || i + 1 == lastIndex));
            number++;
        }
        moves.applyCss();
        moves.layout();
        movesScroll.setVvalue(1);
        javafx.application.Platform.runLater(() -> movesScroll.setVvalue(1));
    }

    private static Label chip(String text, boolean last) {
        Label chip = Ui.label(text, "move-chip");
        chip.setMinWidth(Label.USE_PREF_SIZE);
        if (last) {
            chip.getStyleClass().add("last");
        }
        return chip;
    }

    void setAlignmentForWide(boolean wide) {
        boardBlock.setAlignment(wide ? Pos.CENTER : Pos.TOP_CENTER);
    }
}
