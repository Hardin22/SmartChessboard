package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.ArrayList;
import java.util.List;

/**
 * Accuracy of a move and of a player.
 *
 * <p>Move accuracy (Lichess {@code AccuracyPercent}): {@code 103.1668 * exp(-0.04354 * lossPct) - 3.1669}, clamped
 * to 0..100, where {@code lossPct} is the win chance lost in percentage points. Player accuracy: mean of the
 * volatility-weighted mean and the harmonic mean of the move accuracies (Lichess); the weight of a move is the
 * standard deviation of the win chances in a sliding window around it, clamped to 0.5..12. Calibrated against
 * chess.com by the validation harness (see {@code research/SPEC.md}).</p>
 */
public final class Accuracy {

    private Accuracy() {
    }

    /** Accuracy (0..100) of a move that took the mover's win chance from {@code winBefore} to {@code winAfter}. */
    public static double moveAccuracy(double winBefore, double winAfter) {
        double lossPct = Math.max(0, winBefore - winAfter) * 100;
        double a = 103.1668 * Math.exp(-0.04354 * lossPct) - 3.1669 + 1; // +1: uncertainty bonus (Lichess)
        return Math.max(0, Math.min(100, a));
    }

    /** Accuracy (0..100) of {@code white} (or Black) over a reviewed game, NaN when the side made no move. */
    public static double forPlayer(List<MoveReview> moves, boolean white) {
        if (moves.isEmpty()) {
            return Double.NaN;
        }
        // White's win chance in every position of the game
        List<Double> wins = new ArrayList<>(moves.size() + 1);
        for (MoveReview m : moves) {
            wins.add(m.whiteMoved() ? m.winBefore() : 1 - m.winBefore());
        }
        MoveReview last = moves.get(moves.size() - 1);
        wins.add(last.whiteMoved() ? last.winAfter() : 1 - last.winAfter());

        int window = Math.max(2, Math.min(8, moves.size() / 10));
        double weightedSum = 0;
        double weightSum = 0;
        double harmonicDen = 0;
        int n = 0;
        for (int i = 0; i < moves.size(); i++) {
            MoveReview m = moves.get(i);
            if (m.whiteMoved() != white || m.label() == MoveClassification.BOOK_MOVE) {
                continue;
            }
            double acc = m.accuracy();
            double w = Math.max(0.5, Math.min(12, stddevPct(wins, i, window)));
            weightedSum += acc * w;
            weightSum += w;
            harmonicDen += 1.0 / Math.max(acc, 1e-3);
            n++;
        }
        if (n == 0) {
            return Double.NaN;
        }
        double weighted = weightedSum / weightSum;
        double harmonic = n / harmonicDen;
        return (weighted + harmonic) / 2;
    }

    /** Standard deviation (percentage points) of the win chances in the window that ends after move {@code i}. */
    private static double stddevPct(List<Double> wins, int i, int window) {
        int from = Math.max(0, Math.min(i + 1, wins.size() - window));
        int to = Math.min(wins.size(), from + window);
        double mean = 0;
        for (int k = from; k < to; k++) {
            mean += wins.get(k) * 100;
        }
        mean /= (to - from);
        double var = 0;
        for (int k = from; k < to; k++) {
            double d = wins.get(k) * 100 - mean;
            var += d * d;
        }
        return Math.sqrt(var / (to - from));
    }
}
