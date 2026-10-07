package io.github.hardin22.javachess.Engine.review;

import java.util.List;
import java.util.Map;

/**
 * Everything the pure classification needs: the game and the engine evaluation of each position. No engine is
 * involved, so tests and the validation harness can classify from stored evaluations.
 *
 * @param initialFen starting position
 * @param uciMoves   the legal moves of the game (already validated, see {@link GameReplay})
 * @param positions  evaluation of every position, {@code positions.size() == uciMoves.size() + 1}; position i is
 *                   the one before move i. Where {@link ReviewClassifier#needsSecondLine} asked for it, position i
 *                   carries 2+ MultiPV lines.
 * @param book       opening book for the Book label ({@link OpeningBook#NONE} to disable)
 * @param whiteRating White's rating, 0 when unknown (chess.com judges a loss of win chance by the player's level)
 * @param blackRating Black's rating, 0 when unknown
 * @param afterCapture optional extra searches, by move index i (0-based): when move i leaves a piece en prise, the
 *                     opponent's capture of it ({@link EngineLine#move()} is that capture) and our engine's evaluation
 *                     of the position after the capture ({@link EngineLine#eval()}, White POV, with its line); empty
 *                     when not searched (Phase 4 "threat ignored" Brilliant)
 */
public record ReviewInput(String initialFen, List<String> uciMoves, List<PositionEval> positions, OpeningBook book,
                          int whiteRating, int blackRating, Map<Integer, EngineLine> afterCapture) {

    /** Players' ratings unknown. */
    public ReviewInput(String initialFen, List<String> uciMoves, List<PositionEval> positions, OpeningBook book) {
        this(initialFen, uciMoves, positions, book, 0, 0);
    }

    /** No after-capture searches. */
    public ReviewInput(String initialFen, List<String> uciMoves, List<PositionEval> positions, OpeningBook book,
                       int whiteRating, int blackRating) {
        this(initialFen, uciMoves, positions, book, whiteRating, blackRating, Map.of());
    }

    /** The same input with the after-capture searches of move indices (see {@link #afterCapture()}). */
    public ReviewInput withAfterCapture(Map<Integer, EngineLine> searches) {
        return new ReviewInput(initialFen, uciMoves, positions, book, whiteRating, blackRating, searches);
    }

    public ReviewInput {
        uciMoves = List.copyOf(uciMoves);
        positions = List.copyOf(positions);
        if (positions.size() != uciMoves.size() + 1) {
            throw new IllegalArgumentException("need one evaluation per position: " + positions.size() + " for "
                    + uciMoves.size() + " moves");
        }
        book = book == null ? OpeningBook.NONE : book;
        afterCapture = afterCapture == null ? Map.of() : Map.copyOf(afterCapture);
    }
}
