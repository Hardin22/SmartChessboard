package io.github.hardin22.javachess.Engine.review;

import java.util.List;

/**
 * Accuracy of each move and of each player, from the evaluation of every position (best play, not the played lines).
 *
 * <p>Default model, chosen to be no worse than the previous one on any of three chess.com references (our Stockfish
 * 19 evaluations): win percent {@code 100 / (1 + exp(-0.0016 * cp))} (cp not clamped, mate = 0/100), move accuracy
 * {@code 100 * exp(-0.14 * winPercentLost)}, player accuracy = power mean of the move accuracies with exponent 0.35.
 * Mean absolute error vs chess.com: 2.79 (88% within 5 points) on 90 games reviewed with Stockfish 16 depth 22, 2.72
 * (86%) on the same games reviewed with Torch depth 18, 2.75 (84%) on 384 games of the public API (previous model:
 * 2.94 / 2.90 / 3.06). chess.com itself moves an accuracy by up to ~3 points between two reviews of a game. The
 * exact Lichess formula ({@code research/SPEC.md} §6), much harsher than chess.com, stays available with
 * {@code -Djavachess.accuracy=lichess} for comparisons.</p>
 */
public final class Accuracy {

    /** Win percent slope per centipawn of the accuracy model (flatter than the classification curve). */
    static final double K = 0.0016;
    /** Move accuracy decay per win percent point lost. */
    static final double A = 0.14;
    /** Exponent of the power mean of the move accuracies. */
    static final double P = 0.35;

    /** Lichess uses +0.15 for the standard starting position. */
    private static final Eval START_EVAL = Eval.cp(15);

    private Accuracy() {
    }

    private static boolean lichess() {
        return "lichess".equalsIgnoreCase(System.getProperty("javachess.accuracy", ""));
    }

    /** Win percent (0..100) of {@code white} (or Black) in the accuracy model. */
    static double winPercent(Eval e, boolean white) {
        if (lichess()) {
            return WinModel.accuracyWinPercent(e, white);
        }
        if (e.isMate()) {
            return e.isMateFor(white) ? 100 : 0;
        }
        return 100 / (1 + Math.exp(-K * (white ? e.value() : -e.value())));
    }

    /** Accuracy (0..100) of a move that took the mover's win percent (0..100) from {@code before} to {@code after}. */
    public static double moveAccuracy(double before, double after) {
        if (after >= before) {
            return 100;
        }
        if (lichess()) {
            double a = 103.1668100711649 * Math.exp(-0.04354415386753951 * (before - after)) - 3.166924740191411 + 1;
            return Math.max(0, Math.min(100, a));
        }
        return 100 * Math.exp(-A * (before - after));
    }

    /**
     * Per-move accuracy and volatility weight (Lichess, used only by the Lichess aggregation) of a game.
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
            wps[i] = winPercent(e, true);
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

    /** Accuracy (0..100) of a player, NaN when the player made no move. */
    public static double forPlayer(List<MoveReview> moves, double[][] perMove, boolean white) {
        boolean lichess = lichess();
        double weighted = 0;
        double weightSum = 0;
        double harmonicDen = 0;
        double powerSum = 0;
        int count = 0;
        for (int i = 0; i < moves.size(); i++) {
            if (moves.get(i).whiteMoved() != white) {
                continue;
            }
            double a = perMove[0][i];
            double w = perMove[1][i];
            weighted += a * w;
            weightSum += w;
            harmonicDen += 1.0 / Math.max(1, a);
            powerSum += Math.pow(Math.max(1e-3, a), P);
            count++;
        }
        if (count == 0) {
            return Double.NaN;
        }
        if (lichess) {
            return (weighted / weightSum + count / harmonicDen) / 2;
        }
        return Math.pow(powerSum / count, 1 / P);
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
