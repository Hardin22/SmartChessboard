package io.github.hardin22.javachess.Components;

import javafx.scene.control.Button;

/**
 * The rotate (180°) button. It is in every header, every sheet and both halves of a two-player game; the action is
 * installed once by the main controller so components do not need a reference to it.
 */
public final class RotateButton {

    private static Runnable action = () -> { };

    private RotateButton() {
    }

    public static void setAction(Runnable rotate) {
        action = rotate == null ? () -> { } : rotate;
    }

    public static void rotate() {
        action.run();
    }

    public static Button create() {
        Button button = Ui.iconButton("fth-rotate-cw", I18n.t("common.rotate"), RotateButton::rotate);
        button.getStyleClass().add("rotate-btn");
        return button;
    }
}
