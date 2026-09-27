package org.example.javachess.Oggetti;

public class AnalysisResult {
    public final double score;
    public final String bestMove;
    public final boolean isMate;
    public final int mateIn;

    public final String fullPv;

    public AnalysisResult(double score, String bestMove, boolean isMate, int mateIn, String fullPv) {
        this.score = score;
        this.bestMove = bestMove;
        this.isMate = isMate;
        this.mateIn = mateIn;
        this.fullPv = fullPv;
    }

    public AnalysisResult(double score, String bestMove, boolean isMate, int mateIn) {
        this(score, bestMove, isMate, mateIn, "");
    }

    public AnalysisResult(double score, String bestMove, boolean isMate) {
        this(score, bestMove, isMate, 0, "");
    }

    public AnalysisResult(double score, String bestMove) {
        this(score, bestMove, false, 0, "");
    }
}
