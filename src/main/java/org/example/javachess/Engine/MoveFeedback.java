package org.example.javachess.Engine;

/**
 * Classification of a move just played by a human.
 *
 * @param fenBefore              position before the move
 * @param uci                    the move ("e2e4")
 * @param fromSquare             "E2"
 * @param toSquare               "E4"
 * @param quality                classification
 * @param preliminary            true when a deeper result may follow (only if the class changes)
 * @param changedFromPreliminary true for a final result that corrects a preliminary one
 * @param bestMoveUci            engine best move in {@code fenBefore}, null when unknown or equal to the move played
 * @param bestFromSquare         from-square of the best move or null
 * @param bestToSquare           to-square of the best move or null
 * @param winPercentLoss         win probability lost by the move, 0..100
 * @param depth                  depth of the evaluation behind this verdict
 * @param latencyMs              time from {@code onMovePlayed} to this event
 */
public record MoveFeedback(String fenBefore, String uci, String fromSquare, String toSquare, MoveQuality quality,
                           boolean preliminary, boolean changedFromPreliminary, String bestMoveUci,
                           String bestFromSquare, String bestToSquare, double winPercentLoss, int depth,
                           long latencyMs) {
}
