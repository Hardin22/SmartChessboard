package org.example.javachess.Components;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Page header used by every screen: a large back button (always visible, 56px touch target), the title,
 * an optional subtitle and a slot for trailing actions.
 *
 * <pre>{@code <PageHeader title="%settings.title" onBack="#backToHome"/>}</pre>
 */
public class PageHeader extends HBox {

    private final Button back = new Button();
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final HBox trailing = new HBox(8);
    private final ObjectProperty<EventHandler<ActionEvent>> onBack = new SimpleObjectProperty<>(this, "onBack");

    public PageHeader() {
        getStyleClass().add("page-header");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);

        back.getStyleClass().addAll("btn", "btn-ghost", "icon-btn", "back-button");
        back.setGraphic(Icons.of("fth-arrow-left", 24));
        back.setAccessibleText(I18n.t("common.back"));
        back.onActionProperty().bind(onBack);
        back.visibleProperty().bind(onBack.isNotNull());
        back.managedProperty().bind(back.visibleProperty());

        title.getStyleClass().add("page-title");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.visibleProperty().bind(subtitle.textProperty().isNotEmpty());
        subtitle.managedProperty().bind(subtitle.visibleProperty());
        title.setMinWidth(0);
        subtitle.setMinWidth(0);

        VBox texts = new VBox(2, title, subtitle);
        texts.setAlignment(Pos.CENTER_LEFT);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);

        trailing.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(back, texts, trailing);
    }

    public StringProperty titleProperty() {
        return title.textProperty();
    }

    public String getTitle() {
        return title.getText();
    }

    public void setTitle(String value) {
        title.setText(value);
    }

    public StringProperty subtitleProperty() {
        return subtitle.textProperty();
    }

    public String getSubtitle() {
        return subtitle.getText();
    }

    public void setSubtitle(String value) {
        subtitle.setText(value);
    }

    public ObjectProperty<EventHandler<ActionEvent>> onBackProperty() {
        return onBack;
    }

    public EventHandler<ActionEvent> getOnBack() {
        return onBack.get();
    }

    public void setOnBack(EventHandler<ActionEvent> handler) {
        onBack.set(handler);
    }

    /** Nodes shown on the right of the header (status chips, actions). */
    public ObservableList<Node> getActions() {
        return trailing.getChildren();
    }

    public Label getTitleLabel() {
        return title;
    }

    public Label getSubtitleLabel() {
        return subtitle;
    }
}
