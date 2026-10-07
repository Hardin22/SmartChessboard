package io.github.hardin22.javachess.Engine;

/**
 * A selectable chess engine configuration shown to the user (settings screen and in-game picker).
 *
 * @param id          stable identifier persisted in config (e.g. "stockfish", "stockfish-lite")
 * @param displayName short label for the UI
 * @param description one-line explanation (strength / speed trade-off)
 * @param available   false when the engine binary or its weights are missing on this machine
 */
public record EngineProfile(String id, String displayName, String description, boolean available) {
}
