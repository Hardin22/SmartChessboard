package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Analysis.AnalysisTree;

/**
 * What a game against the computer was set up with, to offer a rematch at the end: same level, clock and starting
 * position (or odds), with the colours swapped — the usual way two players go on at a board.
 *
 * @param level      the computer's level
 * @param humanWhite the player had White
 * @param timeControl the clock ({@link TimeControl#UNLIMITED} for none)
 * @param startFen   the starting position (null or the usual one for a normal game)
 */
public record PvcRematch(BotLevels.Level level, boolean humanWhite, TimeControl timeControl, String startFen) {

    public PvcRematch {
        timeControl = timeControl == null ? TimeControl.UNLIMITED : timeControl;
        startFen = startFen == null || startFen.isBlank() || startFen.equals(AnalysisTree.START_FEN) ? null : startFen;
    }

    /** The rematch: the other colour, everything else the same. */
    public PvcRematch swapped() {
        return new PvcRematch(level, !humanWhite, timeControl, startFen);
    }

    /** "La rivincita: giochi con il Nero" (colour of the new game), for the line next to the Rivincita button. */
    public String colourText() {
        return "La rivincita: giochi con il " + (humanWhite ? "Bianco" : "Nero");
    }
}
