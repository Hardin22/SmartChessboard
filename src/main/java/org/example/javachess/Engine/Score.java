package org.example.javachess.Engine;

/**
 * An engine score as reported by UCI, always from the point of view of the side to move
 * in the analysed position.
 *
 * @param mate  true for "score mate N", false for "score cp N"
 * @param value centipawns, or moves to mate (positive = side to move mates, negative = gets mated,
 *              0 = side to move is already mated)
 */
public record Score(boolean mate, int value) {

    /** Centipawn value used for mate scores in win-probability maths (same convention as the review). */
    public static final int MATE_CP = 10_000;

    public static Score cp(int centipawns) {
        return new Score(false, centipawns);
    }

    public static Score mate(int moves) {
        return new Score(true, moves);
    }

    /** The same score seen from the other side (e.g. score of a child position seen by the parent). */
    public Score negate() {
        return new Score(mate, -value);
    }

    /** True when the side to move is winning by force (mate in N, N &gt; 0). */
    public boolean isWinningMate() {
        return mate && value > 0;
    }

    /** True when the side to move gets mated (mate in -N or already mated). */
    public boolean isLosingMate() {
        return mate && value <= 0;
    }

    /** Centipawns with mates mapped to +/-{@link #MATE_CP}; useful for ordering and win probability. */
    public int centipawns() {
        if (!mate) {
            return value;
        }
        return value > 0 ? MATE_CP : -MATE_CP;
    }

    /**
     * Win probability (0..1) for the side to move, logistic model {@code 1 / (1 + 10^(-cp/400))}.
     * This is the model used by the game review ({@code GameAnalyzer}), so LEDs and review agree.
     */
    public double winProbability() {
        return MoveClassifier.winProbability(centipawns());
    }

    /** Score from White's point of view given the side to move of the analysed position. */
    public Score forWhite(boolean whiteToMove) {
        return whiteToMove ? this : negate();
    }

    /**
     * Legacy "pawn" value used by the eval bar and graph: pawns, mate encoded as +/-(1000 - N).
     * Same convention as the old UCIEngine so existing UI code keeps working.
     */
    public double legacyPawns() {
        if (!mate) {
            return value / 100.0;
        }
        if (value > 0) {
            return 1000.0 - value;
        }
        if (value < 0) {
            return -1000.0 - value;
        }
        return -1000.0;
    }

    /** Human readable text: "+0.35", "-1.20", "M3", "-M2" (from the point of view this score is in). */
    public String format() {
        if (mate) {
            if (value == 0) {
                return "#";
            }
            return value > 0 ? "M" + value : "-M" + (-value);
        }
        return String.format(java.util.Locale.ROOT, "%+.2f", value / 100.0);
    }
}
