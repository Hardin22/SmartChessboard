package io.github.hardin22.javachess.Controllers;

import javafx.scene.Parent;

/**
 * A screen built in code. {@link MainController} creates it (possibly on the preloader thread), hands it the main
 * controller and shows {@link #getRoot()}; the same instance is kept for the whole session.
 */
public interface Screen extends NavigationAware {

    /** The root node of the screen (built once). */
    Parent getRoot();

    /**
     * The window is wider than tall (landscape monitor, desktop): screens with a board put it beside the rest.
     * Called on the FX thread when the screen is shown and whenever the proportion changes.
     */
    default void setWide(boolean wide) {
    }

    /** Called on the FX thread when the user presses the hardware/keyboard back key; false = not handled. */
    default boolean onBack() {
        return false;
    }
}
