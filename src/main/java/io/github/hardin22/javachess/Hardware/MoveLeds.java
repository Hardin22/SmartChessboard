package io.github.hardin22.javachess.Hardware;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * LED feedback about moves, for the engine layer and the games. Every method returns immediately and can be
 * called from any thread; rapid calls are merged into one LED frame by {@link LedRenderer}.
 */
public final class MoveLeds {

    /** How long the verdict of a played move stays on the board. */
    public static final long VERDICT_MS = 2500;

    private final LedRenderer renderer;
    private final Object lock = new Object();
    private int hintSource = -1;
    private final Map<Integer, Integer> hintPixels = new HashMap<>();

    public MoveLeds(LedRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * A legal destination of the lifted piece, colored by the quality of the move (call it once per destination
     * as the evaluation progresses). Starting a new source square clears the previous hints.
     */
    public void showCandidate(String fromSquare, String toSquare, MoveClassification quality) {
        int from = Squares.parse(fromSquare);
        int to = Squares.parse(toSquare);
        if (from < 0 || to < 0) {
            return;
        }
        synchronized (lock) {
            startHintFrom(from);
            hintPixels.put(to, LedColors.forQuality(quality));
            renderer.replace(LedRenderer.Layer.HINT, hintPixels);
        }
    }

    /** Legal destinations of the lifted piece without evaluation (online games, suggestions off). */
    public void showLegalTargets(String fromSquare, Collection<String> toSquares) {
        int from = Squares.parse(fromSquare);
        if (from < 0) {
            return;
        }
        synchronized (lock) {
            hintSource = -1;
            startHintFrom(from);
            for (String target : toSquares) {
                int to = Squares.parse(target);
                if (to >= 0) {
                    hintPixels.put(to, LedColors.LEGAL);
                }
            }
            renderer.replace(LedRenderer.Layer.HINT, hintPixels);
        }
    }

    /**
     * Verdict on the move just played: start and destination in the quality color (blunders blink), and the
     * destination of the engine's best move blinking in cyan when the player chose something else.
     * Disappears after {@link #VERDICT_MS}.
     *
     * @param moveUci     the move played, e.g. "e2e4"
     * @param quality     its classification
     * @param bestMoveUci best move in the position before, or null
     */
    public void showVerdict(String moveUci, MoveClassification quality, String bestMoveUci) {
        if (moveUci == null || moveUci.length() < 4) {
            return;
        }
        int from = Squares.parse(moveUci.substring(0, 2));
        int to = Squares.parse(moveUci.substring(2, 4));
        if (from < 0 || to < 0) {
            return;
        }
        clearCandidates();
        int color = LedColors.forQuality(quality);
        renderer.clear(LedRenderer.Layer.VERDICT);
        Map<Integer, Integer> pixels = new HashMap<>();
        pixels.put(from, LedColors.dim(color, 35));
        if (quality != MoveClassification.BLUNDER) {
            pixels.put(to, color);
        }
        renderer.showFor(LedRenderer.Layer.VERDICT, pixels, VERDICT_MS);
        if (quality == MoveClassification.BLUNDER) {
            renderer.flash(LedRenderer.Layer.VERDICT, new int[]{to}, color, 5, VERDICT_MS / 5);
        }
        if (bestMoveUci != null && bestMoveUci.length() >= 4 && !bestMoveUci.regionMatches(true, 0, moveUci, 0, 4)) {
            int bestFrom = Squares.parse(bestMoveUci.substring(0, 2));
            int bestTo = Squares.parse(bestMoveUci.substring(2, 4));
            if (bestFrom >= 0 && bestTo >= 0) {
                if (bestFrom != from && bestFrom != to) {
                    renderer.showFor(LedRenderer.Layer.VERDICT, Map.of(bestFrom, LedColors.dim(LedColors.BEST, 35)), VERDICT_MS);
                }
                if (bestTo != to) {
                    renderer.flash(LedRenderer.Layer.VERDICT, new int[]{bestTo}, LedColors.BEST, 5, VERDICT_MS / 5);
                }
            }
        }
    }

    /** Removes the hints for the lifted piece. */
    public void clearCandidates() {
        synchronized (lock) {
            hintSource = -1;
            hintPixels.clear();
            renderer.clear(LedRenderer.Layer.HINT);
        }
    }

    /** Removes the verdict of the last move before it expires (e.g. new piece lifted). */
    public void clearVerdict() {
        renderer.clear(LedRenderer.Layer.VERDICT);
    }

    /** Lock held. */
    private void startHintFrom(int from) {
        if (hintSource != from) {
            hintSource = from;
            hintPixels.clear();
            hintPixels.put(from, LedColors.SOURCE);
        }
    }
}
