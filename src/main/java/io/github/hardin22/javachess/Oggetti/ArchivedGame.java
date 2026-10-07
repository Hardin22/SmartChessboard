package io.github.hardin22.javachess.Oggetti;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A finished (or interrupted) game stored in the archive.
 *
 * @param id          unique, positive, assigned by the archive
 * @param mode        how the game was played
 * @param label       free description shown in the list (e.g. "Stockfish livello 10")
 * @param white       white player's name ("?" when unknown)
 * @param black       black player's name ("?" when unknown)
 * @param result      PGN result: "1-0", "0-1", "1/2-1/2" or "*" (unfinished / unknown)
 * @param termination human readable reason (e.g. "Scaccomatto", "Tempo", "Abbandono", "Interrotta"), may be empty
 * @param opening     opening name, may be empty
 * @param timeControl e.g. "10+0", empty when unlimited/unknown
 * @param playedAt    when the game ended, may be null for very old records
 * @param initialFen  starting position (standard start for normal games)
 * @param finalFen    final position
 * @param movesUci    moves in UCI notation (e2e4, e7e8q), legal from {@code initialFen}
 */
public record ArchivedGame(int id, GameMode mode, String label, String white, String black, String result,
                           String termination, String opening, String timeControl, LocalDateTime playedAt,
                           String initialFen, String finalFen, List<String> movesUci) {

    public enum GameMode {
        PVC, PVP, LICHESS, BROWSER, PUZZLE, IMPORTED, UNKNOWN
    }

    public ArchivedGame {
        Objects.requireNonNull(mode, "mode");
        label = nz(label);
        white = blankTo(white, "?");
        black = blankTo(black, "?");
        result = result == null || result.isBlank() ? "*" : result;
        termination = nz(termination);
        opening = nz(opening);
        timeControl = nz(timeControl);
        initialFen = nz(initialFen);
        finalFen = nz(finalFen);
        movesUci = movesUci == null ? List.of() : List.copyOf(movesUci);
    }

    /** Moves as a space separated UCI string (the format {@code ReviewController.loadGame} expects). */
    public String movesAsUciString() {
        return String.join(" ", movesUci);
    }

    /** Number of full moves (rounded up). */
    public int fullMoves() {
        return (movesUci.size() + 1) / 2;
    }

    public ArchivedGame withId(int newId) {
        return new ArchivedGame(newId, mode, label, white, black, result, termination, opening, timeControl,
                playedAt, initialFen, finalFen, movesUci);
    }

    public ArchivedGame withNames(String newWhite, String newBlack) {
        return new ArchivedGame(id, mode, label, newWhite, newBlack, result, termination, opening, timeControl,
                playedAt, initialFen, finalFen, movesUci);
    }

    public ArchivedGame withOpening(String newOpening) {
        return new ArchivedGame(id, mode, label, white, black, result, termination, newOpening, timeControl,
                playedAt, initialFen, finalFen, movesUci);
    }

    public ArchivedGame withResult(String newResult, String newTermination) {
        return new ArchivedGame(id, mode, label, white, black, newResult, newTermination, opening, timeControl,
                playedAt, initialFen, finalFen, movesUci);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }
}
