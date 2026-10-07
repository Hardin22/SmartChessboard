package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.List;

/**
 * Accuracy of each move and of each player: exact port of Lichess {@code AccuracyPercent} ({@code research/SPEC.md}
 * §6). Uses the evaluation of every position (best play), not the played lines.
 *
 * <ul>
 *   <li>Move: {@code 103.1668 * exp(-0.04354 * (wpBefore - wpAfter)) - 3.1669 + 1}, clamped to 0..100 (100 when the
 *       win percent did not drop), win percents from {@link WinModel#accuracyWinPercent}.</li>
 *   <li>Player: mean of the volatility-weighted mean and the harmonic mean of the move accuracies; the weight of a
 *       move is the standard deviation of White's win percent in a sliding window, clamped to 0.5..12.</li>
 * </ul>
 */
public final class Accuracy {

    /** Lichess uses +0.15 for the standard starting position. */
    private static final Eval START_EVAL = Eval.cp(15);

    private Accuracy() {
    }

    /** Accuracy (0..100) of a move that took the mover's win percent (0..100) from {@code before} to {@code after}. */
    public static double moveAccuracy(double before, double after) {
        if (after >= before) {
            return 100;
        }
        double a = 103.1668100711649 * Math.exp(-0.04354415386753951 * (before - after)) - 3.166924740191411 + 1;
        return Math.max(0, Math.min(100, a));
    }

    /**
     * Per-move accuracy and volatility weight of a game.
     *
     * @param initialFen starting position
     * @param positions  evaluation of every position ({@code moves + 1})
     * @return {@code [0][i]} accuracy of move i, {@code [1][i]} its weight
     */
    public static double[][] perMove(String initialFen, List<PositionEval> positions) {
        int n = positions.size() - 1;
        double[][] out = new double[2][Math.max(0, n)];
        if (n <= 0) {
            return out;
        }
        double[] wps = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            Eval e = positions.get(i).eval();
            if (i == 0 && isStandardStart(initialFen)) {
                e = START_EVAL;
            }
            wps[i] = WinModel.accuracyWinPercent(e, true);
        }
        int window = Math.max(2, Math.min(8, n / 10));
        // (window - 2) copies of the first window, then every sliding window: one window per move
        double[] weights = new double[n];
        int w = Math.min(window, wps.length);
        double first = Math.max(0.5, Math.min(12, stddev(wps, 0, w)));
        int k = 0;
        for (int c = 0; c < w - 2 && k < n; c++) {
            weights[k++] = first;
        }
        for (int start = 0; start + w <= wps.length && k < n; start++) {
            weights[k++] = Math.max(0.5, Math.min(12, stddev(wps, start, w)));
        }
        for (int i = 0; i < n; i++) {
            boolean white = positions.get(i).whiteToMove();
            double before = white ? wps[i] : 100 - wps[i];
            double after = white ? wps[i + 1] : 100 - wps[i + 1];
            out[0][i] = moveAccuracy(before, after);
            out[1][i] = k > i ? weights[i] : first;
        }
        return out;
    }

    /** Accuracy (0..100) of a player, NaN when the player made no counted move. */
    public static double forPlayer(List<MoveReview> moves, double[][] perMove, boolean white) {
        double weighted = 0;
        double weightSum = 0;
        double harmonicDen = 0;
        int count = 0;
        for (int i = 0; i < moves.size(); i++) {
            MoveReview m = moves.get(i);
            if (m.whiteMoved() != white || !counted(m.label())) {
                continue;
            }
            double a = perMove[0][i];
            double w = perMove[1][i];
            weighted += a * w;
            weightSum += w;
            harmonicDen += 1.0 / Math.max(1, a);
            count++;
        }
        if (count == 0) {
            return Double.NaN;
        }
        return (weighted / weightSum + count / harmonicDen) / 2;
    }

    /** Moves that count in the player's accuracy (Lichess counts all of them). */
    static boolean counted(MoveClassification label) {
        return true;
    }

    private static boolean isStandardStart(String fen) {
        return fen == null || fen.isBlank() || OpeningBook.key(fen).equals(OpeningBook.key(GameReplay.START_FEN));
    }

    private static double stddev(double[] xs, int from, int len) {
        double mean = 0;
        for (int i = from; i < from + len; i++) {
            mean += xs[i];
        }
        mean /= len;
        double var = 0;
        for (int i = from; i < from + len; i++) {
            var += (xs[i] - mean) * (xs[i] - mean);
        }
        return Math.sqrt(var / len);
    }
}
