package io.github.hardin22.javachess.Hardware;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

/**
 * LED palette (0xRRGGBB). WS2812 greens are much brighter than reds, so yellow and orange use little green
 * (values tuned on the real board).
 */
public final class LedColors {

    public static final int OFF = 0x000000;
    public static final int WHITE = 0xFFFFFF;
    /** Square of the lifted piece. */
    public static final int SOURCE = 0xFFFFFF;
    /** Legal destination without evaluation. */
    public static final int LEGAL = 0x0064FF;
    public static final int BEST = 0x00FFFF;
    public static final int GOOD = 0x00FF00;
    public static final int INACCURACY = 0xFF4600;
    public static final int MISTAKE = 0xFF2000;
    public static final int BLUNDER = 0xFF0000;
    /** Setup: a piece is missing here. */
    public static final int MISSING = 0xFFF0C8;
    /** Setup / play: a piece is here but should not be. */
    public static final int WRONG = 0xDC143C;
    /** Opponent (bot / online) move to reproduce on the board. */
    public static final int REPLICATE = 0x00FFFF;
    /** King in check. */
    public static final int CHECK = 0xFF4500;
    public static final int ERROR = 0xFF0000;

    private LedColors() {
    }

    public static int forQuality(MoveClassification quality) {
        if (quality == null) {
            return LEGAL;
        }
        return switch (quality) {
            case BRILLIANT, GREAT, BEST -> BEST;
            case EXCELLENT, GOOD, BOOK_MOVE, FORCED -> GOOD;
            case INACCURACY -> INACCURACY;
            case MISS, MISTAKE -> MISTAKE;
            case BLUNDER -> BLUNDER;
        };
    }

    /** Same color at a fraction of its brightness (0-100 %). */
    public static int dim(int rgb, int percent) {
        int r = ((rgb >> 16) & 0xFF) * percent / 100;
        int g = ((rgb >> 8) & 0xFF) * percent / 100;
        int b = (rgb & 0xFF) * percent / 100;
        return (r << 16) | (g << 8) | b;
    }

    public static int rgb(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
