package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.List;

/**
 * Review of one move of a game.
 *
 * @param ply        0-based index of the move in the game
 * @param uci        played move
 * @param san        played move in SAN ("Nf3", "exd5", "O-O", "Qh7#")
 * @param fenBefore  position before the move
 * @param whiteMoved true when White played it
 * @param label      chess.com style label
 * @param before     best evaluation of the position before the move (White POV)
 * @param after      evaluation of the position after the played move (White POV)
 * @param bestMove   engine's best move in the position before (UCI), null when unknown
 * @param bestLine   principal variation of the best move (may be empty)
 * @param winBefore  mover's win chance with the best move (0..1)
 * @param winAfter   mover's win chance after the played move (0..1)
 * @param accuracy   accuracy of this single move (0..100)
 */
public record MoveReview(int ply, String uci, String san, String fenBefore, boolean whiteMoved,
                         MoveClassification label, Eval before, Eval after, String bestMove, List<String> bestLine,
                         double winBefore, double winAfter, double accuracy) {

    public MoveReview {
        bestLine = bestLine == null ? List.of() : List.copyOf(bestLine);
    }

    /** Win chance lost by the mover with this move (0..1). */
    public double winLoss() {
        return Math.max(0, winBefore - winAfter);
    }

    /** Full move number as printed in a PGN (1. e4 e5 → both are move 1). */
    public int moveNumber() {
        String[] parts = fenBefore.trim().split("\\s+");
        try {
            return Integer.parseInt(parts[5]);
        } catch (RuntimeException e) {
            return ply / 2 + 1;
        }
    }
}
