package io.github.hardin22.javachess.Engine.review;

import java.util.List;

/**
 * One engine line of a position: a root move and the evaluation of the position after following the line.
 *
 * @param move  first move (UCI)
 * @param eval  evaluation of the line, White's point of view
 * @param pv    principal variation starting with {@code move}
 * @param depth depth at which the line was found
 */
public record EngineLine(String move, Eval eval, List<String> pv, int depth) {

    public EngineLine {
        pv = pv == null ? List.of(move) : List.copyOf(pv);
    }
}
