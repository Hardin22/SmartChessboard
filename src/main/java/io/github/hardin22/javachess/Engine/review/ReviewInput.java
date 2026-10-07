package io.github.hardin22.javachess.Engine.review;

import java.util.List;

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
 */
public record ReviewInput(String initialFen, List<String> uciMoves, List<PositionEval> positions, OpeningBook book,
                          int whiteRating, int blackRating) {

    /** Players' ratings unknown. */
    public ReviewInput(String initialFen, List<String> uciMoves, List<PositionEval> positions, OpeningBook book) {
        this(initialFen, uciMoves, positions, book, 0, 0);
    }

    public ReviewInput {
        uciMoves = List.copyOf(uciMoves);
        positions = List.copyOf(positions);
        if (positions.size() != uciMoves.size() + 1) {
            throw new IllegalArgumentException("need one evaluation per position: " + positions.size() + " for "
                    + uciMoves.size() + " moves");
        }
        book = book == null ? OpeningBook.NONE : book;
    }
}
