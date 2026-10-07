package org.example.javachess.Engine;

/** Engine failure: binary missing, handshake timeout, crash, closed client. */
public class EngineException extends RuntimeException {

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
