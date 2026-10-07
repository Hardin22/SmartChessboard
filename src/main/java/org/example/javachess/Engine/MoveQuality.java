package org.example.javachess.Engine;

/** Quality of a move for the board LEDs (coarser than the review labels of {@code MoveAnalysis}). */
public enum MoveQuality {
    /** The engine's top move, or equivalent to it (win probability loss &lt; 0.5%). */
    BEST(0, 255, 255),
    /** Loss below the inaccuracy threshold. */
    GOOD(0, 255, 0),
    INACCURACY(255, 255, 0),
    MISTAKE(255, 60, 0),
    BLUNDER(255, 0, 0);

    private final int r;
    private final int g;
    private final int b;

    MoveQuality(int r, int g, int b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    /** Suggested LED colour (same palette the board used before); the renderer may override it. */
    public int[] defaultRgb() {
        return new int[] { r, g, b };
    }

    /** The matching review label (used by the LED renderer, which shares the review palette). */
    public org.example.javachess.Oggetti.MoveAnalysis.MoveClassification toClassification() {
        return switch (this) {
            case BEST -> org.example.javachess.Oggetti.MoveAnalysis.MoveClassification.BEST;
            case GOOD -> org.example.javachess.Oggetti.MoveAnalysis.MoveClassification.GOOD;
            case INACCURACY -> org.example.javachess.Oggetti.MoveAnalysis.MoveClassification.INACCURACY;
            case MISTAKE -> org.example.javachess.Oggetti.MoveAnalysis.MoveClassification.MISTAKE;
            case BLUNDER -> org.example.javachess.Oggetti.MoveAnalysis.MoveClassification.BLUNDER;
        };
    }

    /** True for inaccuracy, mistake and blunder. */
    public boolean isError() {
        return ordinal() >= INACCURACY.ordinal();
    }
}
