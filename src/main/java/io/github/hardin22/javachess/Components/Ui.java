package io.github.hardin22.javachess.Components;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Small factory for the building blocks of the v2 interface (style classes in Style.css). Screens are built in code
 * with these helpers so sizes, spacing and touch targets stay consistent everywhere.
 */
public final class Ui {

    private Ui() {
    }

    // ------------------------------------------------------------------ text

    public static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        label.setMinWidth(0);
        return label;
    }

    /** A label that wraps on several lines and takes the available width. */
    public static Label wrap(String text, String... styleClasses) {
        Label label = label(text, styleClasses);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    // ------------------------------------------------------------------ buttons

    public static Button button(String text, String icon, String... styleClasses) {
        Button button = new Button(text);
        button.getStyleClass().setAll("btn");
        button.getStyleClass().addAll(styleClasses);
        if (icon != null) {
            button.setGraphic(Icons.of(icon, 26));
        }
        button.setMnemonicParsing(false);
        button.setFocusTraversable(false);
        return button;
    }

    /** Button that fills the width of its container. */
    public static Button wide(String text, String icon, String... styleClasses) {
        Button button = button(text, icon, styleClasses);
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    public static Button iconButton(String icon, String accessibleText, Runnable action) {
        Button button = new Button();
        button.getStyleClass().setAll("icon-btn");
        button.setGraphic(Icons.of(icon, 30));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setFocusTraversable(false);
        button.setAccessibleText(accessibleText);
        button.setOnAction(e -> action.run());
        return button;
    }

    /** Game toolbar button: icon over a short label. */
    public static Button toolButton(String text, String icon, Runnable action) {
        Button button = new Button(text, Icons.of(icon, 30));
        button.getStyleClass().setAll("tool-btn");
        button.setContentDisplay(ContentDisplay.TOP);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMnemonicParsing(false);
        button.setFocusTraversable(false);
        HBox.setHgrow(button, Priority.ALWAYS);
        button.setOnAction(e -> action.run());
        return button;
    }

    public static ToggleButton toolToggle(String text, String icon) {
        ToggleButton button = new ToggleButton(text, Icons.of(icon, 30));
        button.getStyleClass().setAll("tool-btn");
        button.setContentDisplay(ContentDisplay.TOP);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMnemonicParsing(false);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    public static ToggleButton chip(String text) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().setAll("chip");
        chip.setMnemonicParsing(false);
        chip.setMinWidth(Region.USE_PREF_SIZE);
        chip.setFocusTraversable(false);
        return chip;
    }

    /** Segmented control: equal segments in a rounded track; one is always selected. */
    public static HBox segmented(ToggleGroup group, List<ToggleButton> segments) {
        HBox box = new HBox();
        box.getStyleClass().add("segmented");
        for (ToggleButton segment : segments) {
            segment.getStyleClass().setAll("segment");
            segment.setToggleGroup(group);
            segment.setMaxWidth(Double.MAX_VALUE);
            segment.setMnemonicParsing(false);
            HBox.setHgrow(segment, Priority.ALWAYS);
            box.getChildren().add(segment);
        }
        keepOneSelected(group);
        return box;
    }

    public static void keepOneSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null && o != null) {
                o.setSelected(true);
            }
        });
    }

    /** A large on/off switch (ToggleButton with a drawn track and knob). */
    public static ToggleButton toggleSwitch(boolean selected) {
        ToggleButton toggle = new ToggleButton();
        toggle.getStyleClass().setAll("switch");
        Region track = new Region();
        track.getStyleClass().add("switch-track");
        Region knob = new Region();
        knob.getStyleClass().add("switch-knob");
        StackPane graphic = new StackPane(track, knob);
        graphic.setMaxSize(92, 52);
        StackPane.setAlignment(knob, selected ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        StackPane.setMargin(knob, new javafx.geometry.Insets(0, 6, 0, 6));
        toggle.selectedProperty().addListener((obs, o, n) ->
                StackPane.setAlignment(knob, n ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT));
        toggle.setGraphic(graphic);
        toggle.setSelected(selected);
        toggle.setMinSize(92, 72);
        return toggle;
    }

    // ------------------------------------------------------------------ layout

    public static Region hgrow() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        region.setMinWidth(0);
        return region;
    }

    public static Region vgrow() {
        Region region = new Region();
        VBox.setVgrow(region, Priority.ALWAYS);
        region.setMinHeight(0);
        return region;
    }

    public static Region gap(double height) {
        Region region = new Region();
        region.setMinHeight(height);
        region.setPrefHeight(height);
        region.setMaxHeight(height);
        return region;
    }

    public static HBox row(double spacing, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static VBox column(double spacing, Node... children) {
        VBox box = new VBox(spacing, children);
        box.setFillWidth(true);
        return box;
    }

    /** Children share the width equally. */
    public static HBox equalRow(double spacing, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER);
        for (Node child : children) {
            HBox.setHgrow(child, Priority.ALWAYS);
            if (child instanceof Region r) {
                r.setMaxWidth(Double.MAX_VALUE);
                r.setPrefWidth(1);
            }
        }
        return box;
    }

    public static VBox card(Node... children) {
        VBox box = new VBox(16, children);
        box.getStyleClass().add("card");
        return box;
    }

    public static Region hairline() {
        Region line = new Region();
        line.getStyleClass().add("hairline");
        return line;
    }

    public static Label sectionLabel(String text) {
        return label(text, "section-label");
    }

    /** Widest a column of settings-like content gets on wide screens (landscape monitor, desktop). */
    public static final double COLUMN = 760;

    /**
     * Vertical scroller for touch: no horizontal bar, content as wide as the viewport (at most {@link #COLUMN},
     * centred, on wide screens).
     */
    public static ScrollPane scroll(Node content) {
        if (content instanceof Region region) {
            region.setMaxWidth(COLUMN);
        }
        StackPane centred = new StackPane(content);
        centred.setAlignment(Pos.TOP_CENTER);
        ScrollPane pane = new ScrollPane(centred);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        pane.setFocusTraversable(false);
        pane.setPannable(true);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    /** Bottom bar with the main action of a screen, as wide as the content column. */
    public static VBox footer(Node action) {
        if (action instanceof Region region) {
            region.setMaxWidth(COLUMN - 64);
        }
        VBox box = new VBox(action);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new javafx.geometry.Insets(16, 32, 36, 32));
        return box;
    }

    /** Text + description column used by settings rows and option cards. */
    public static VBox texts(String title, String subtitle, String titleClass, String subtitleClass) {
        Label t = wrap(title, titleClass);
        VBox box = new VBox(4, t);
        if (subtitle != null && !subtitle.isBlank()) {
            box.getChildren().add(wrap(subtitle, subtitleClass));
        }
        box.setMinWidth(0);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** True when screen transitions and other short animations are allowed ({@code -Djavachess.animations=off}). */
    public static boolean animations() {
        return !"off".equalsIgnoreCase(System.getProperty("javachess.animations", "on"));
    }

    /** Shared flag a few components use to know whether the scene is shown upside down. */
    public static final BooleanProperty ROTATED = new SimpleBooleanProperty(false);
}
