package org.example.javachess.Engine;

import java.util.List;

/**
 * Final result of a search.
 *
 * @param bestMove   UCI best move, or null when the position has no legal move ("bestmove (none)")
 * @param ponder     ponder move or null
 * @param lines      last exact line for each multipv index, ordered by multipv (may be empty)
 * @param depth      deepest completed depth seen
 * @param nodes      nodes searched
 * @param elapsedMs  wall clock time from "go" to "bestmove"
 * @param stopped    true when the search was interrupted (stop, timeout, superseded) instead of reaching its limit
 */
public record SearchResult(String bestMove, String ponder, List<InfoLine> lines, int depth, long nodes,
                           long elapsedMs, boolean stopped) {

    public SearchResult {
        lines = List.copyOf(lines);
    }

    /** The first line, or null when the engine printed none. */
    public InfoLine best() {
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** Score of the best line (side to move POV), or null. */
    public Score score() {
        InfoLine b = best();
        return b == null ? null : b.score();
    }

    /** The line whose first move is {@code uci}, or null. */
    public InfoLine lineFor(String uci) {
        for (InfoLine l : lines) {
            if (l.move().equals(uci)) {
                return l;
            }
        }
        return null;
    }

    public long nps() {
        return elapsedMs <= 0 ? 0 : nodes * 1000 / elapsedMs;
    }
}
