package io.github.hardin22.javachess.Utils;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns failures into short messages for the user (Italian, like the rest of the UI) and logs the details.
 *
 * <p>Also installs the global handler for uncaught exceptions so that no failure is silent: the stack trace
 * goes to the log file and the user sees a dialog instead of a frozen screen.
 */
public final class ErrorReporter {

    private static final Logger log = LoggerFactory.getLogger(ErrorReporter.class);

    /** Do not open more than one error dialog every few seconds (a failing loop must not flood the screen). */
    private static final long DIALOG_INTERVAL_MS = 5000;
    private static final AtomicLong lastDialog = new AtomicLong();
    private static volatile boolean dialogsEnabled = true;
    /** Shows errors inside the app (set by the UI); null = a separate Alert window. */
    private static volatile java.util.function.BiConsumer<String, String> presenter;

    private ErrorReporter() {
    }

    /** Installs the default uncaught exception handler (idempotent). */
    public static void installGlobalHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> handleUncaught(thread, error));
    }

    /** Disables dialogs (tests, headless runs). Errors are still logged. */
    public static void setDialogsEnabled(boolean enabled) {
        dialogsEnabled = enabled;
    }

    static void handleUncaught(Thread thread, Throwable error) {
        try {
            log.error("Uncaught exception in thread {}", thread.getName(), error);
            if (error instanceof OutOfMemoryError) {
                return; // nothing safe to do on the UI
            }
            showError("Errore inatteso",
                    "Si è verificato un errore inatteso: " + userMessage(error)
                            + "\n\nI dettagli sono nel file di log: " + AppPaths.logsDir().resolve("javachess.log"));
        } catch (Throwable secondary) {
            // Never let the handler itself throw.
            System.err.println("Uncaught exception (handler failed): " + error);
        }
    }

    /**
     * Lets the UI show errors inside the main window (a sheet that turns with the screen) instead of an Alert,
     * which on the board's touch screen would open as a separate small window. Called on the FX thread.
     */
    public static void setPresenter(java.util.function.BiConsumer<String, String> uiPresenter) {
        presenter = uiPresenter;
    }

    /** Shows an error dialog on the FX thread (rate limited); safe to call from any thread. */
    public static void showError(String title, String message) {
        if (!dialogsEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastDialog.get();
        if (now - last < DIALOG_INTERVAL_MS || !lastDialog.compareAndSet(last, now)) {
            return;
        }
        Runnable show = () -> {
            java.util.function.BiConsumer<String, String> inApp = presenter;
            if (inApp != null) {
                try {
                    inApp.accept(title, message);
                    return;
                } catch (RuntimeException e) {
                    log.warn("Cannot show the error in the app: {}", e.getMessage());
                }
            }
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
                alert.setTitle(title);
                alert.setHeaderText(title);
                io.github.hardin22.javachess.Components.ThemeManager.styleDialog(alert.getDialogPane());
                // Owned by the main window, otherwise it can open behind the full-screen stage on the Pi.
                javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing).findFirst()
                        .ifPresent(alert::initOwner);
                alert.show();
            } catch (RuntimeException e) {
                log.warn("Cannot show error dialog: {}", e.getMessage());
            }
        };
        try {
            if (Platform.isFxApplicationThread()) {
                show.run();
            } else {
                Platform.runLater(show);
            }
        } catch (IllegalStateException toolkitNotRunning) {
            // JavaFX not started (tests, command line tools): the log is enough.
        }
    }

    /** A short, human readable description of a failure (no stack traces, no class names when avoidable). */
    public static String userMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            if (root instanceof UnknownHostException || root instanceof ConnectException
                    || root instanceof SocketTimeoutException) {
                break;
            }
            root = root.getCause();
        }
        if (root instanceof UnknownHostException) {
            return "nessuna connessione a Internet (impossibile raggiungere " + root.getMessage() + ")";
        }
        if (root instanceof ConnectException) {
            return "connessione rifiutata dal server";
        }
        if (root instanceof SocketTimeoutException) {
            return "il server non risponde (timeout)";
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
