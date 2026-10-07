package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardFrame;
import io.github.hardin22.javachess.Components.ClockFace;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.MaterialView;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.EvalBar;

/**
 * Two players sitting at the two ends of the screen. Each has a half turned towards them — clock, name, state and
 * their own buttons, confirmations included — and the middle shows the position read by the sensors with the
 * evaluation bar: drawn with White towards White's half it reads right from both ends, like the real board.
 */
final class GameDuelView extends StackPane {

    /** What a half asks the game screen to do. */
    interface Actions {
        void togglePause();

        void requestResign(Side side);

        void offerDraw(Side side);

        void answerDraw(boolean accepted);

        void withdrawDraw();

        void showDuelMenu(Side side);

        void reviewGame();

        void rematch();

        void leaveToHome();
    }

    final Half far;
    final Half near;
    final BoardFrame boardFrame;
    private final VBox center = new VBox();
    private final VBox veil = new VBox();
    private final Actions actions;

    GameDuelView(Actions actions, EvalBar evalBar) {
        this.actions = actions;
        getStyleClass().add("game-duel");
        far = new Half(Side.BLACK);
        near = new Half(Side.WHITE);
        far.setRotate(180);

        boardFrame = new BoardFrame(evalBar);
        center.getStyleClass().add("center-band");
        center.getChildren().add(boardFrame);
        center.setPadding(new Insets(8, 0, 8, 0));
        center.managedProperty().bind(center.visibleProperty());
        center.setMinHeight(Region.USE_PREF_SIZE);

        VBox.setVgrow(far, Priority.ALWAYS);
        VBox.setVgrow(near, Priority.ALWAYS);
        VBox column = new VBox(far, center, near);
        column.setFillWidth(true);

        veil.getStyleClass().add("pause-veil");
        veil.setAlignment(Pos.CENTER);
        Node farCard = pauseCard();
        farCard.setRotate(180);
        veil.getChildren().addAll(farCard, Ui.vgrow(), pauseCard());
        veil.setPadding(new Insets(260, 60, 260, 60));
        veil.setMinSize(0, 0); // never forces the screen to be taller than the window
        veil.setVisible(false);
        veil.setOnMouseClicked(e -> actions.togglePause());

        this.column = column;
        getChildren().addAll(column, veil);
    }

    private final VBox column;

    /**
     * Wide window (landscape monitor, desktop): the halves stand upright on the two sides of the board instead of
     * facing the two ends of a portrait screen.
     */
    void setWide(boolean wide) {
        getChildren().remove(0);
        column.getChildren().clear();
        center.setMinHeight(wide ? 64 : Region.USE_PREF_SIZE);
        veil.setPadding(wide ? new Insets(40) : new Insets(260, 60, 260, 60));
        if (!wide) {
            far.setRotate(180);
            column.getChildren().addAll(far, center, near);
            getChildren().add(0, column);
            return;
        }
        far.setRotate(0);
        HBox row = new HBox(far, center, near);
        HBox.setHgrow(far, Priority.ALWAYS);
        HBox.setHgrow(near, Priority.ALWAYS);
        far.setPrefWidth(1);
        near.setPrefWidth(1);
        center.setAlignment(Pos.CENTER);
        getChildren().add(0, row);
    }

    private Node pauseCard() {
        VBox card = new VBox(Icons.of("fth-pause", 52), Ui.label(I18n.t("duel.paused"), "t-h1"),
                Ui.label(I18n.t("duel.paused.resume"), "t-body", "t-muted"));
        card.getStyleClass().add("pause-card");
        card.setMaxWidth(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        return card;
    }

    Half half(Side side) {
        return near.side == side ? near : far;
    }

    /** Swaps which colour sits at which end (the screen was turned, or White sits at the far end). */
    void setNearSide(Side side) {
        near.setSide(side);
        far.setSide(side.flip());
        if (boardFrame.getBoard() != null) {
            boardFrame.getBoard().setFlipped(side == Side.BLACK);
        }
    }

    void setBoardVisible(boolean visible) {
        center.setVisible(visible);
    }

    boolean isBoardVisible() {
        return center.isVisible();
    }

    void setPaused(boolean paused) {
        veil.setVisible(paused);
        near.clock.setPaused(paused);
        far.clock.setPaused(paused);
    }

    void setPosition(String fen) {
        near.material.setPosition(fen);
        far.material.setPosition(fen);
    }

    /** One player's half. Content top to bottom in its own orientation: centre of the screen -> player's edge. */
    final class Half extends VBox {

        Side side;
        final ClockFace clock = new ClockFace();
        private final Region avatar = new Region();
        private final Label name = Ui.label("", "half-name");
        private MaterialView material;
        private final HBox info = new HBox(16);
        private final Label state = Ui.label("", "half-state");
        private final Label moveNo = Ui.label("", "pill");
        private final StackPane body = new StackPane();
        private final StackPane bottomSlot = new StackPane();
        private final HBox buttons;
        private final Button draw;

        Half(Side side) {
            getStyleClass().add("half");
            avatar.getStyleClass().add("avatar");
            info.setAlignment(Pos.CENTER_LEFT);

            clock.setMaxFontSize(230);
            body.getChildren().add(clock);
            VBox.setVgrow(body, Priority.ALWAYS);
            body.setMinHeight(150);

            state.setMinWidth(0);
            HBox stateRow = new HBox(16, state, Ui.hgrow(), moveNo);
            stateRow.setAlignment(Pos.CENTER_LEFT);
            moveNo.managedProperty().bind(moveNo.textProperty().isNotEmpty());
            moveNo.visibleProperty().bind(moveNo.managedProperty());

            Button pause = halfButton(I18n.t("duel.pause"), "fth-pause", actions::togglePause);
            draw = halfButton(I18n.t("duel.draw"), "fth-minus-circle", () -> actions.offerDraw(this.side));
            Button resign = halfButton(I18n.t("game.resign"), "fth-flag", () -> actions.requestResign(this.side));
            resign.getStyleClass().add("danger");
            Button menu = halfButton(I18n.t("game.menu.short"), "fth-more-horizontal",
                    () -> actions.showDuelMenu(this.side));
            buttons = Ui.equalRow(12, pause, draw, resign, menu);
            bottomSlot.getChildren().add(buttons);

            getChildren().addAll(info, body, stateRow, bottomSlot);
            setSide(side);
        }

        private Button halfButton(String text, String icon, Runnable action) {
            Button b = new Button(text, Icons.of(icon, 30));
            b.getStyleClass().setAll("half-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setMnemonicParsing(false);
            b.setOnAction(e -> action.run());
            return b;
        }

        void setSide(Side newSide) {
            side = newSide;
            boolean white = side == Side.WHITE;
            avatar.getStyleClass().removeAll("white", "black");
            avatar.getStyleClass().add(white ? "white" : "black");
            name.setText(I18n.t(white ? "common.white" : "common.black"));
            material = new MaterialView(white, 34);
            info.getChildren().setAll(avatar, name, Ui.hgrow(), material);
        }

        void setState(String text, boolean turn) {
            state.setText(text);
            state.getStyleClass().remove("turn");
            if (turn) {
                state.getStyleClass().add("turn");
            }
        }

        void setMoveNumber(String text) {
            moveNo.setText(text == null ? "" : text);
        }

        /** Replaces the buttons with a question and its answers (confirmations, draw offers). */
        void prompt(String question, Node... answers) {
            Label q = Ui.wrap(question, "t-title");
            HBox row = Ui.equalRow(12, answers);
            VBox panel = new VBox(16, q, row);
            panel.getStyleClass().addAll("half-panel", "accent");
            bottomSlot.getChildren().setAll(panel);
        }

        void clearPrompt() {
            bottomSlot.getChildren().setAll(buttons);
            noticeShown = false;
        }

        private boolean noticeShown;

        /** A board instruction (place the pieces) in place of the buttons, large, until it is done. */
        void notice(String title, String detail) {
            Label t = Ui.wrap(title, "t-title");
            Label d = Ui.wrap(detail, "t-body", "t-muted");
            // inside a StackPane a wrapping label is measured on one line: give it the half's width
            d.setPrefWidth(600);
            t.setPrefWidth(600);
            VBox panel = new VBox(10, t, d);
            panel.getStyleClass().addAll("half-panel", "accent");
            bottomSlot.getChildren().setAll(panel);
            noticeShown = true;
        }

        void clearNotice() {
            if (noticeShown) {
                clearPrompt();
            }
        }

        void setDrawEnabled(boolean enabled) {
            draw.setDisable(!enabled);
        }

        /** End of the game, from this player's point of view. */
        void showResult(String title, String detail) {
            Label big = Ui.label(title, "t-display");
            Label sub = Ui.wrap(detail, "t-body", "t-muted");
            sub.setAlignment(Pos.CENTER);
            sub.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            VBox result = new VBox(10, big, sub);
            result.setAlignment(Pos.CENTER);
            body.getChildren().setAll(result);
            Button review = Ui.wide(I18n.t("duel.review"), "fth-bar-chart-2", "btn-inverse");
            review.setOnAction(e -> actions.reviewGame());
            Button again = Ui.wide(I18n.t("duel.rematch"), "fth-repeat", "btn-outline");
            again.setOnAction(e -> actions.rematch());
            Button home = Ui.wide(I18n.t("duel.home"), "fth-home", "btn-outline");
            home.setOnAction(e -> actions.leaveToHome());
            bottomSlot.getChildren().setAll(Ui.equalRow(12, review, again, home));
            state.setText("");
        }

        void reset() {
            body.getChildren().setAll(clock);
            clearPrompt();
            setDrawEnabled(true);
        }
    }
}
