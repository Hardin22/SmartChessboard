package org.example.javachess.Components;

import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

/** Small pill with a coloured dot: hardware / engine / connection state. Text always says the state (not colour only). */
public class StatusChip extends Label {

    public enum State { OK, WARN, OFF, BUSY }

    private static final PseudoClass OK = PseudoClass.getPseudoClass("ok");
    private static final PseudoClass WARN = PseudoClass.getPseudoClass("warn");
    private static final PseudoClass OFF = PseudoClass.getPseudoClass("off");
    private static final PseudoClass BUSY = PseudoClass.getPseudoClass("busy");

    public StatusChip() {
        this("", State.OFF);
    }

    public StatusChip(String text, State state) {
        super(text);
        getStyleClass().add("status-chip");
        Region dot = new Region();
        dot.getStyleClass().add("status-dot");
        setGraphic(dot);
        setGraphicTextGap(8);
        setMinWidth(USE_PREF_SIZE);
        setState(state);
    }

    public void set(String text, State state) {
        setText(text);
        setState(state);
    }

    public void setState(State state) {
        pseudoClassStateChanged(OK, state == State.OK);
        pseudoClassStateChanged(WARN, state == State.WARN);
        pseudoClassStateChanged(OFF, state == State.OFF);
        pseudoClassStateChanged(BUSY, state == State.BUSY);
    }
}
