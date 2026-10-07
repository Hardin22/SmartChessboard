package org.example.javachess.Vision;

import java.util.ArrayList;
import java.util.List;

/**
 * What the classifier saw on the 64 squares, with probabilities.
 *
 * <p>Squares are addressed in board coordinates: {@code file} 0..7 = a..h, {@code rank} 0..7 = 1..8, whatever the
 * orientation of the picture. Each square has a probability for each of the 13 symbols
 * {@code PNBRQKpnbrqk.} ({@code '.'} = empty).
 */
public final class BoardReading {

    /** Symbols in probability-vector order; the last one is the empty square. */
    public static final String SYMBOLS = "PNBRQKpnbrqk.";
    public static final char EMPTY = '.';

    private final float[][][] probs; // [file][rank][symbol]
    private final char[][] piece;    // [file][rank]
    private final boolean hasBoard;
    private final long inferenceMs;

    public BoardReading(float[][][] probs, boolean hasBoard, long inferenceMs) {
        this.probs = probs;
        this.hasBoard = hasBoard;
        this.inferenceMs = inferenceMs;
        this.piece = new char[8][8];
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                piece[f][r] = SYMBOLS.charAt(argmax(probs[f][r]));
            }
        }
    }

    /** Builds a reading that is certain of the given placement (FEN first field); handy for tests. */
    public static BoardReading certain(String placement) {
        float[][][] p = new float[8][8][SYMBOLS.length()];
        char[][] grid = parsePlacement(placement);
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                p[f][r][SYMBOLS.indexOf(grid[f][r])] = 1f;
            }
        }
        return new BoardReading(p, true, 0);
    }

    /** Piece seen on a square ({@code '.'} when empty). */
    public char pieceAt(int file, int rank) {
        return piece[file][rank];
    }

    /** Probability that {@code symbol} (a char of {@link #SYMBOLS}) is on the square. */
    public float probability(int file, int rank, char symbol) {
        int i = SYMBOLS.indexOf(symbol);
        return i < 0 ? 0f : probs[file][rank][i];
    }

    /** Probability of the most likely symbol on the square. */
    public float confidence(int file, int rank) {
        return probs[file][rank][argmax(probs[file][rank])];
    }

    public float minConfidence() {
        float min = 1f;
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                min = Math.min(min, confidence(f, r));
            }
        }
        return min;
    }

    public float meanConfidence() {
        float sum = 0f;
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                sum += confidence(f, r);
            }
        }
        return sum / 64f;
    }

    /** Squares (e.g. "e4") whose best guess is below {@code threshold}. */
    public List<String> uncertainSquares(float threshold) {
        List<String> out = new ArrayList<>();
        for (int r = 7; r >= 0; r--) {
            for (int f = 0; f < 8; f++) {
                if (confidence(f, r) < threshold) {
                    out.add(squareName(f, r));
                }
            }
        }
        return out;
    }

    public boolean hasBoard() {
        return hasBoard;
    }

    public long inferenceMs() {
        return inferenceMs;
    }

    /** FEN piece placement (first FEN field) of the most likely symbols. */
    public String placement() {
        return placementOf(piece);
    }

    /** Copy of the probabilities, [file][rank][symbol]. */
    public float[][][] probabilities() {
        float[][][] copy = new float[8][8][];
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                copy[f][r] = probs[f][r].clone();
            }
        }
        return copy;
    }

    /**
     * Applies the rules every real position obeys, using the probabilities to pick the replacement:
     * exactly one king per side, no pawns on the first and last rank, at most 8 pawns and 16 pieces per side.
     * Returns a new reading (this one is unchanged).
     */
    public BoardReading withPlacementRules() {
        float[][][] p = probabilities();
        int takenF = -1;
        int takenR = -1;
        for (char king : new char[]{'K', 'k'}) {
            int k = SYMBOLS.indexOf(king);
            int bestF = -1;
            int bestR = -1;
            float best = 0f;
            for (int f = 0; f < 8; f++) {
                for (int r = 0; r < 8; r++) {
                    if (f == takenF && r == takenR) {
                        continue; // already holds the white king
                    }
                    if (p[f][r][k] > best) {
                        best = p[f][r][k];
                        bestF = f;
                        bestR = r;
                    }
                }
            }
            for (int f = 0; f < 8; f++) {
                for (int r = 0; r < 8; r++) {
                    if (f == bestF && r == bestR) {
                        if (best > 0.02f) {
                            forceSymbol(p[f][r], k); // the most likely square gets the king
                            takenF = f;
                            takenR = r;
                        }
                    } else if (argmax(p[f][r]) == k) {
                        suppress(p[f][r], k); // a second king: take the next best guess
                    }
                }
            }
        }
        for (int f = 0; f < 8; f++) {
            for (int r : new int[]{0, 7}) {
                suppress(p[f][r], SYMBOLS.indexOf('P'));
                suppress(p[f][r], SYMBOLS.indexOf('p'));
            }
        }
        limitCount(p, 'P', 8);
        limitCount(p, 'p', 8);
        return new BoardReading(p, hasBoard, inferenceMs);
    }

    private static void limitCount(float[][][] p, char symbol, int max) {
        int s = SYMBOLS.indexOf(symbol);
        List<int[]> squares = new ArrayList<>();
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                if (argmax(p[f][r]) == s) {
                    squares.add(new int[]{f, r});
                }
            }
        }
        if (squares.size() <= max) {
            return;
        }
        squares.sort((a, b) -> Float.compare(p[a[0]][a[1]][s], p[b[0]][b[1]][s]));
        for (int i = 0; i < squares.size() - max; i++) {
            suppress(p[squares.get(i)[0]][squares.get(i)[1]], s);
        }
    }

    private static void forceSymbol(float[] v, int s) {
        float max = 0f;
        for (float x : v) {
            max = Math.max(max, x);
        }
        v[s] = Math.max(v[s], max + 1e-3f);
        normalize(v);
    }

    /**
     * Removes a symbol that the rules forbid on this square. Its probability goes to the other pieces (something
     * was detected there, so "empty" should not win just because the best piece was ruled out).
     */
    private static void suppress(float[] v, int s) {
        float mass = v[s];
        v[s] = 0f;
        int empty = v.length - 1;
        float others = 0f;
        for (int i = 0; i < empty; i++) {
            others += v[i];
        }
        if (s != empty && others > 0f && mass > v[empty]) {
            for (int i = 0; i < empty; i++) {
                v[i] += mass * (v[i] / others);
            }
        }
        normalize(v);
    }

    private static void normalize(float[] v) {
        float sum = 0f;
        for (float x : v) {
            sum += x;
        }
        if (sum <= 0f) {
            v[v.length - 1] = 1f;
            return;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= sum;
        }
    }

    static int argmax(float[] v) {
        int best = v.length - 1;
        for (int i = 0; i < v.length; i++) {
            if (v[i] > v[best]) {
                best = i;
            }
        }
        return best;
    }

    static String squareName(int file, int rank) {
        return "" + (char) ('a' + file) + (char) ('1' + rank);
    }

    static String placementOf(char[][] grid) {
        StringBuilder fen = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                char c = grid[f][r];
                if (c == EMPTY) {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(c);
                }
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (r > 0) {
                fen.append('/');
            }
        }
        return fen.toString();
    }

    /** Parses a FEN placement into [file][rank]; malformed input gives an empty board. */
    static char[][] parsePlacement(String placement) {
        char[][] grid = new char[8][8];
        for (char[] col : grid) {
            java.util.Arrays.fill(col, EMPTY);
        }
        String[] ranks = placement.split(" ")[0].split("/");
        for (int i = 0; i < Math.min(8, ranks.length); i++) {
            int r = 7 - i;
            int f = 0;
            for (char c : ranks[i].toCharArray()) {
                if (Character.isDigit(c)) {
                    f += c - '0';
                } else if (f < 8 && SYMBOLS.indexOf(c) >= 0) {
                    grid[f++][r] = c;
                }
            }
        }
        return grid;
    }
}
