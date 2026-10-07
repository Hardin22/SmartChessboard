package org.example.javachess.Engine;

/**
 * Observable state of the engine layer, for the UI ("loading Maia...", "Stockfish not installed").
 *
 * @param state     current state
 * @param profileId profile the state refers to
 * @param message   short human readable message (Italian, ready to show), never null
 */
public record EngineStatus(State state, String profileId, String message) {

    public enum State {
        /** Engine starting or switching profile; requests are queued, not lost. */
        LOADING,
        /** Ready. */
        READY,
        /** Engine missing or crashed; message says why. */
        ERROR
    }

    public static EngineStatus loading(String profileId, String name) {
        return new EngineStatus(State.LOADING, profileId, "Avvio di " + name + "...");
    }

    public static EngineStatus ready(String profileId, String name) {
        return new EngineStatus(State.READY, profileId, name + " pronto");
    }

    public static EngineStatus error(String profileId, String message) {
        return new EngineStatus(State.ERROR, profileId, message);
    }
}
