package io.github.hardin22.javachess.Services;

import io.github.hardin22.javachess.Engine.EngineManager;

/**
 * Compatibility shim for the game setup screen: the bot types it offers, mapped to engine profiles.
 * Engine processes are owned by {@link EngineManager}; use it (or {@code EngineSelection}) in new code.
 */
public final class EngineService {

    private EngineService() {
    }

    public enum EngineType {
        STOCKFISH(EngineManager.STOCKFISH),
        MAIA_1100(EngineManager.MAIA_1100),
        MAIA_1500(EngineManager.MAIA_1500),
        MAIA_1900(EngineManager.MAIA_1900);

        private final String profileId;

        EngineType(String profileId) {
            this.profileId = profileId;
        }

        /**
         * Profile to activate for this bot type. STOCKFISH keeps a Stockfish profile chosen in the settings
         * (e.g. "Stockfish Lite") instead of forcing the full one.
         */
        public String profileId() {
            if (this == STOCKFISH) {
                String active = EngineManager.get().activeProfile().id();
                if (active.startsWith("stockfish")) {
                    return active;
                }
            }
            return profileId;
        }
    }

    /** Closes every engine process (kept for callers of the old API). */
    public static void close() {
        EngineManager.get().shutdown();
    }
}
