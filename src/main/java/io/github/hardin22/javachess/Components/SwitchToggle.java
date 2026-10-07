package io.github.hardin22.javachess.Components;

import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** On/off switch (52x32, touch friendly). A plain {@link ToggleButton}, so existing controller code keeps working. */
public class SwitchToggle extends ToggleButton {

    private final Region knob = new Region();

    public SwitchToggle() {
        getStyleClass().setAll("switch");
        Region track = new Region();
        track.getStyleClass().add("switch-track");
        knob.getStyleClass().add("switch-knob");
        StackPane graphic = new StackPane(track, knob);
        graphic.getStyleClass().add("switch-graphic");
        setGraphic(graphic);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        updateKnob();
        selectedProperty().addListener((obs, o, n) -> updateKnob());
    }

    private void updateKnob() {
        StackPane.setAlignment(knob, isSelected() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    }
}
