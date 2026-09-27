package org.example.javachess.Oggetti;

public class MoveAnalysis {
    private final int moveNumber;
    private final String move; // Algebraic notation
    private final String fen;
    private final double score; // Centipawns (positive for white advantage)
    private final String bestMove;
    private final MoveClassification classification;
    private final double cpl; // Centipawn loss
    private final int toSquareIndex; // 0-63 index of destination square
    private final boolean isMate;
    private final double accuracy;
    private final double winProbability;
    private final double bestWp;

    public enum MoveClassification {
        BRILLIANT,
        GREAT,
        BEST,
        EXCELLENT,
        GOOD,
        BOOK_MOVE,
        INACCURACY,
        MISS,
        MISTAKE,
        BLUNDER,
        FORCED
    }

    public MoveAnalysis(int moveNumber, String move, String fen, double score, String bestMove,
            MoveClassification classification, double cpl, int toSquareIndex, boolean isMate, double accuracy,
            double winProbability, double bestWp) {
        this.moveNumber = moveNumber;
        this.move = move;
        this.fen = fen;
        this.score = score;
        this.bestMove = bestMove;
        this.classification = classification;
        this.cpl = cpl;
        this.toSquareIndex = toSquareIndex;
        this.isMate = isMate;
        this.accuracy = accuracy;
        this.winProbability = winProbability;
        this.bestWp = bestWp;
    }

    public int getMoveNumber() {
        return moveNumber;
    }

    public String getMove() {
        return move;
    }

    public String getFen() {
        return fen;
    }

    public double getScore() {
        return score;
    }

    public String getBestMove() {
        return bestMove;
    }

    public MoveClassification getClassification() {
        return classification;
    }

    public double getCpl() {
        return cpl;
    }

    public int getToSquareIndex() {
        return toSquareIndex;
    }

    public boolean isMate() {
        return isMate;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public double getWinProbability() {
        return winProbability;
    }

    public double getBestWp() {
        return bestWp;
    }
}
