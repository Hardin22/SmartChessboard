package io.github.hardin22.javachess.Utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorReporterTest {

    @Test
    void networkFailuresBecomeReadableMessages() {
        assertTrue(ErrorReporter.userMessage(new IOException(new UnknownHostException("lichess.org")))
                .contains("nessuna connessione a Internet"));
        assertEquals("connessione rifiutata dal server", ErrorReporter.userMessage(new ConnectException("x")));
        assertEquals("il server non risponde (timeout)",
                ErrorReporter.userMessage(new RuntimeException(new SocketTimeoutException())));
        assertEquals("NullPointerException", ErrorReporter.userMessage(new NullPointerException()));
        assertTrue(ErrorReporter.userMessage(new RuntimeException("x".repeat(1000))).length() < 310);
    }

    @Test
    void uncaughtHandlerNeverThrowsWithoutJavaFx() {
        ErrorReporter.installGlobalHandler();
        assertNotNull(Thread.getDefaultUncaughtExceptionHandler());
        assertDoesNotThrow(() -> ErrorReporter.handleUncaught(Thread.currentThread(), new IllegalStateException("boom")));
        assertDoesNotThrow(() -> ErrorReporter.showError("t", "m"));
    }
}
