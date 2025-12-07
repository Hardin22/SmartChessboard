package org.example.javachess.Oggetti;

public class AnalysisResult {
    public final double score;
    public final String bestMove;
    public final boolean isMate;

    public AnalysisResult(double score, String bestMove, boolean isMate) {
        this.score = score;
        this.bestMove = bestMove;
        this.isMate = isMate;
    }
    
    public AnalysisResult(double score, String bestMove) {
        this(score, bestMove, false);
    }
}
