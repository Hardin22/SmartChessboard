package io.github.hardin22.javachess.Components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Header of every screen: back (80x80) on the left, title and subtitle, optional actions, and the rotate button
 * (80x80) always in the same corner, so it is found without looking — also after the screen has been turned.
 */
public class ScreenHeader extends HBox {

    private final Label title = Ui.label("", "header-title");
    private final Label subtitle = Ui.label("", "header-subtitle");
    private final HBox actions = new HBox(12);
    private final Button back;
    private javafx.event.EventHandler<javafx.event.ActionEvent> onBackHandler;

    /** For FXML: {@code <ScreenHeader title="%key" subtitle="%key" onBack="#method"/>}. */
    public ScreenHeader() {
        this("", null);
        back.setOnAction(e -> {
            if (onBackHandler != null) {
                onBackHandler.handle(e);
            }
        });
    }

    public ScreenHeader(String titleText, Runnable onBack) {
        getStyleClass().add("screen-header");
        setAlignment(Pos.CENTER_LEFT);
        back = Ui.iconButton("fth-arrow-left", I18n.t("common.back"), () -> {
            if (onBack != null) {
                onBack.run();
            }
        });
        back.setVisible(onBack != null);
        back.setManaged(onBack != null);
        title.setText(titleText);
        subtitle.managedProperty().bind(subtitle.textProperty().isNotEmpty());
        subtitle.visibleProperty().bind(subtitle.managedProperty());
        title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        subtitle.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        VBox texts = new VBox(2, title, subtitle);
        texts.setAlignment(Pos.CENTER_LEFT);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(back, texts, actions, RotateButton.create());
    }

    public void setTitle(String text) {
        title.setText(text);
    }

    public String getTitle() {
        return title.getText();
    }

    public void setSubtitle(String text) {
        subtitle.setText(text == null ? "" : text);
    }

    public String getSubtitle() {
        return subtitle.getText();
    }

    public javafx.event.EventHandler<javafx.event.ActionEvent> getOnBack() {
        return onBackHandler;
    }

    public void setOnBack(javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        onBackHandler = handler;
        back.setVisible(handler != null);
        back.setManaged(handler != null);
    }

    public Label titleLabel() {
        return title;
    }

    /** Extra buttons placed before the rotate button. */
    public void setActions(Node... nodes) {
        actions.getChildren().setAll(nodes);
    }
}
