package org.example.javachess.Components;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.example.javachess.Engine.EngineProfile;
import org.example.javachess.Engine.EngineSelection;

/**
 * List of engine profiles from {@link EngineSelection}; tapping one switches engine at runtime.
 * Unavailable profiles are shown disabled with their description. Used in Settings and in the game sheet.
 */
public class EnginePicker extends VBox {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private final EngineSelection selection;
    private final ChangeListener<EngineProfile> activeListener = (obs, o, n) -> refreshSelection();
    private Runnable onPicked;

    public EnginePicker() {
        this(EngineSelection.get());
    }

    public EnginePicker(EngineSelection selection) {
        this.selection = selection;
        getStyleClass().addAll("option-list", "engine-picker");
        for (EngineProfile profile : selection.profiles()) {
            getChildren().add(row(profile));
        }
        selection.activeProfileProperty().addListener(new WeakChangeListener<>(activeListener));
        refreshSelection();
    }

    /** Called after the user picked a profile (e.g. to close a sheet). */
    public void setOnPicked(Runnable onPicked) {
        this.onPicked = onPicked;
    }

    private Button row(EngineProfile profile) {
        Label name = new Label(profile.displayName());
        name.getStyleClass().add("option-title");
        Label desc = new Label(profile.available() ? profile.description()
                : I18n.t("engine.unavailable", profile.description()));
        desc.getStyleClass().add("option-description");
        VBox texts = new VBox(2, name, desc);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        Region radio = new Region();
        radio.getStyleClass().add("radio-mark");
        HBox content = new HBox(16, texts, radio);
        content.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.getStyleClass().add("option-row");
        button.setGraphic(content);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setUserData(profile.id());
        button.setDisable(!profile.available());
        button.setOnAction(e -> {
            selection.select(profile.id());
            if (onPicked != null) {
                onPicked.run();
            }
        });
        return button;
    }

    private void refreshSelection() {
        EngineProfile active = selection.activeProfileProperty().get();
        String activeId = active == null ? null : active.id();
        getChildren().forEach(node -> node.pseudoClassStateChanged(SELECTED, node.getUserData() != null
                && node.getUserData().equals(activeId)));
    }
}
