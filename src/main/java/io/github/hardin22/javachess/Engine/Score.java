package io.github.hardin22.javachess.Engine;

/**
 * An engine score as reported by UCI, from the point of view of one side (the side to move in the
 * analysed position, unless the score was {@link #negate() negated} or converted {@link #forWhite(boolean)}).
 *
 * <p>A position with the side to move checkmated is {@code mate 0} for the loser. Seen from the winner it is
 * a mate already delivered: {@code Score.mate(0).negate()}, which keeps {@code value == 0} but
 * {@link #delivered()} is true (an int has no negative zero, so the sign needs its own flag).</p>
 *
 * @param mate      true for "score mate N", false for "score cp N"
 * @param value     centipawns, or moves to mate (positive = this side mates, negative = gets mated,
 *                  0 = the game is over by checkmate)
 * @param delivered only for {@code mate 0}: true when this side gave the mate, false when it is mated
 */
public record Score(boolean mate, int value, boolean delivered) {

    /** Centipawn value used for mate scores in win-probability maths (same convention as the review). */
    public static final int MATE_CP = 10_000;

    public Score {
        if (!mate || value != 0) {
            delivered = false;
        }
    }

    public Score(boolean mate, int value) {
        this(mate, value, false);
    }

    public static Score cp(int centipawns) {
        return new Score(false, centipawns);
    }

    /** UCI "score mate N": mate in N moves (N &gt; 0), mated in -N (N &lt; 0), checkmated now (N = 0). */
    public static Score mate(int moves) {
        return new Score(true, moves);
    }

    /** This side has already checkmated the opponent (the game is over and won). */
    public static Score mateDelivered() {
        return new Score(true, 0, true);
    }

    /** The same score seen from the other side (e.g. score of a child position seen by the parent). */
    public Score negate() {
        if (mate && value == 0) {
            return new Score(true, 0, !delivered);
        }
        return new Score(mate, -value);
    }

    /** True when this side is winning by force or has already mated (mate in N, N &gt; 0, or delivered). */
    public boolean isWinningMate() {
        return mate && (value > 0 || delivered);
    }

    /** True when this side gets mated (mate in -N or already mated). */
    public boolean isLosingMate() {
        return mate && (value < 0 || (value == 0 && !delivered));
    }

    /** True for a game already over by checkmate (either side's point of view). */
    public boolean isCheckmate() {
        return mate && value == 0;
    }

    /** Centipawns with mates mapped to +/-{@link #MATE_CP}; useful for ordering and win probability. */
    public int centipawns() {
        if (!mate) {
            return value;
        }
        return isWinningMate() ? MATE_CP : -MATE_CP;
    }

    /**
     * Win probability (0..1) for this side, logistic model {@code 1 / (1 + 10^(-cp/400))}.
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
     * Legacy "pawn" value used by the eval bar and graph: pawns, mate encoded as +/-(1000 - N),
     * a finished checkmate as exactly +/-1000. Same convention as the old UCIEngine so existing UI code keeps working.
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
        return delivered ? 1000.0 : -1000.0;
    }

    /** Human readable text: "+0.35", "-1.20", "M3", "-M2", "#" / "-#" for a finished mate (this side's view). */
    public String format() {
        if (mate) {
            if (value == 0) {
                return delivered ? "#" : "-#";
            }
            return value > 0 ? "M" + value : "-M" + (-value);
        }
        return String.format(java.util.Locale.ROOT, "%+.2f", value / 100.0);
    }
}
