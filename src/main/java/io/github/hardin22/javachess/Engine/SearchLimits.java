package io.github.hardin22.javachess.Engine;

import java.util.List;

/**
 * Limits for one {@code go} command. Combine freely: the engine stops at the first limit reached.
 * {@code timeoutMs} is enforced on our side: when it expires we send {@code stop} and use the best
 * result found so far (it protects against engines that ignore the limits or take too long).
 *
 * @param depth       max depth, 0 = none
 * @param nodes       max nodes, 0 = none
 * @param movetimeMs  engine-side time limit, 0 = none
 * @param multiPv     number of lines (1 = only best)
 * @param searchMoves restrict the search to these UCI moves (empty = all)
 * @param timeoutMs   client-side soft cap, 0 = none (an infinite search without limits needs an explicit stop)
 */
public record SearchLimits(int depth, long nodes, int movetimeMs, int multiPv, List<String> searchMoves,
                           long timeoutMs) {

    public SearchLimits {
        searchMoves = searchMoves == null ? List.of() : List.copyOf(searchMoves);
        multiPv = Math.max(1, multiPv);
    }

    public static SearchLimits depth(int depth) {
        return new SearchLimits(depth, 0, 0, 1, List.of(), 0);
    }

    public static SearchLimits nodes(long nodes) {
        return new SearchLimits(0, nodes, 0, 1, List.of(), 0);
    }

    public static SearchLimits movetime(int ms) {
        return new SearchLimits(0, 0, ms, 1, List.of(), 0);
    }

    public static SearchLimits infinite() {
        return new SearchLimits(0, 0, 0, 1, List.of(), 0);
    }

    public SearchLimits withDepth(int d) {
        return new SearchLimits(d, nodes, movetimeMs, multiPv, searchMoves, timeoutMs);
    }

    public SearchLimits withNodes(long n) {
        return new SearchLimits(depth, n, movetimeMs, multiPv, searchMoves, timeoutMs);
    }

    public SearchLimits withMovetime(int ms) {
        return new SearchLimits(depth, nodes, ms, multiPv, searchMoves, timeoutMs);
    }

    public SearchLimits withMultiPv(int k) {
        return new SearchLimits(depth, nodes, movetimeMs, k, searchMoves, timeoutMs);
    }

    public SearchLimits withSearchMoves(List<String> moves) {
        return new SearchLimits(depth, nodes, movetimeMs, multiPv, moves, timeoutMs);
    }

    public SearchLimits withTimeout(long ms) {
        return new SearchLimits(depth, nodes, movetimeMs, multiPv, searchMoves, ms);
    }

    public boolean isInfinite() {
        return depth <= 0 && nodes <= 0 && movetimeMs <= 0;
    }

    /**
     * The UCI {@code go} command for these limits. {@code searchmoves} is always LAST: engines (Stockfish
     * included) read every remaining token as a move, so any limit after it would be silently ignored.
     */
    public String toGoCommand() {
        StringBuilder sb = new StringBuilder("go");
        if (isInfinite()) {
            sb.append(" infinite");
        }
        if (depth > 0) {
            sb.append(" depth ").append(depth);
        }
        if (nodes > 0) {
            sb.append(" nodes ").append(nodes);
        }
        if (movetimeMs > 0) {
            sb.append(" movetime ").append(movetimeMs);
        }
        if (!searchMoves.isEmpty()) {
            sb.append(" searchmoves");
            for (String m : searchMoves) {
                sb.append(' ').append(m);
            }
        }
        return sb.toString();
    }
}
