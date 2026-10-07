package io.github.hardin22.javachess.Controllers;

import javafx.scene.Parent;

/**
 * A screen built in code. {@link MainController} creates it (possibly on the preloader thread), hands it the main
 * controller and shows {@link #getRoot()}; the same instance is kept for the whole session.
 */
public interface Screen extends NavigationAware {

    /** The root node of the screen (built once). */
    Parent getRoot();

    /** Called on the FX thread when the user presses the hardware/keyboard back key; false = not handled. */
    default boolean onBack() {
        return false;
    }
}
