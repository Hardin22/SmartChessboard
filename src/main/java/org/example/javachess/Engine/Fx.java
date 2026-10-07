package org.example.javachess.Engine;

import javafx.application.Platform;

/** Runs code on the JavaFX thread when the toolkit is up, inline otherwise (tests, headless tools). */
final class Fx {

    private static volatile boolean toolkitMissing;

    private Fx() {
    }

    static void run(Runnable r) {
        if (toolkitMissing) {
            r.run();
            return;
        }
        try {
            if (Platform.isFxApplicationThread()) {
                r.run();
            } else {
                Platform.runLater(r);
            }
        } catch (IllegalStateException toolkitNotInitialized) {
            toolkitMissing = true;
            r.run();
        }
    }
}
