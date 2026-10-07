package io.github.hardin22.javachess.Engine.review;

import java.util.List;

/**
 * Engine evaluation of one position (or the exact result of a terminal one).
 *
 * @param fen      the position
 * @param eval     evaluation with best play, White's point of view (the top line, or the terminal result)
 * @param lines    MultiPV lines ordered best first (1 line for a normal search, 2+ where the review asked for
 *                 MultiPV); empty for a terminal position
 * @param depth    depth reached (0 for terminal)
 * @param nodes    nodes searched (0 for terminal or a cache hit that did not record it)
 * @param terminal true when the position is checkmate, stalemate or a dead draw (no search was run)
 */
public record PositionEval(String fen, Eval eval, List<EngineLine> lines, int depth, long nodes, boolean terminal) {

    public PositionEval {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static PositionEval terminal(String fen, Eval result) {
        return new PositionEval(fen, result, List.of(), 0, 0, true);
    }

    /** Best line, or null for a terminal position. */
    public EngineLine best() {
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** Best move (UCI), or null for a terminal position. */
    public String bestMove() {
        return lines.isEmpty() ? null : lines.get(0).move();
    }

    /** The line starting with {@code uci}, or null. */
    public EngineLine lineFor(String uci) {
        for (EngineLine l : lines) {
            if (l.move().equals(uci)) {
                return l;
            }
        }
        return null;
    }

    /** Second best line (MultiPV 2), or null when the search had a single line. */
    public EngineLine secondBest() {
        return lines.size() < 2 ? null : lines.get(1);
    }

    /** True when the side to move is White (from the FEN). */
    public boolean whiteToMove() {
        String[] parts = fen.trim().split("\\s+");
        return parts.length < 2 || !"b".equals(parts[1]);
    }
}
