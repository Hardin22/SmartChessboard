package io.github.hardin22.javachess.Vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a board by comparing each square with the pieces of the same board seen before: a reader calibrated on the
 * site's own theme and piece set, whatever they are.
 *
 * <p><b>Calibration</b> ({@link #learn}): a picture of the board whose position is known for sure (from the page's
 * markup, or the start position) is cut into squares; every occupied square gives an example of that piece: its
 * pixels and its shape (the pixels that differ from the square's background). Examples accumulate (a few per piece
 * and square colour).</p>
 *
 * <p><b>Reading</b> ({@link #read}): every square is compared with every example by shape (overlap of the piece
 * pixels) and colour (on the overlap). The background of each square is measured on its border, so the coloured
 * highlights the sites draw <i>under</i> the pieces (last move, check, selection) do not matter; the coordinates
 * drawn in the corners are outside the compared area. Scores become probabilities for {@link PositionResolver}.</p>
 *
 * <p>Pictures must be cropped to the board (the page gives its rectangle). Not thread-safe.</p>
 */
public final class TemplateReader {

    /** Side of the normalised square picture. */
    static final int N = 40;
    /** Share of the square cut away on each side (coordinates, borders, neighbours' overhang). */
    private static final double MARGIN = 0.06;
    /** Colour distance (sum of |dR|+|dG|+|dB|) above which a pixel belongs to a piece. */
    private static final int PIECE_THRESHOLD = 70;
    /** Examples kept per symbol and square colour. */
    private static final int MAX_EXAMPLES = 3;
    /** Softness of the score -> probability conversion. */
    private static final double TEMPERATURE = 0.06;

    private enum Parity { LIGHT, DARK }

    /** One example of a piece: its pixels and shape. */
    record Example(int[] rgb, boolean[] mask, int area) {
    }

    private final Map<Parity, List<Example>>[] examples;
    /** Colour distance that the texture of empty squares reaches (per parity), learned; the piece threshold is above. */
    private final double[] texture = new double[2];
    /**
     * How each square looks when empty, by screen position (row * 8 + column): a theme's square textures never move
     * on the screen, so a square compared with its own empty look shows even a dark piece on a dark textured square.
     */
    private final int[][] emptyLook = new int[64][];
    /** Colour distance from the remembered empty look above which a pixel belongs to a piece. */
    private static final int MEMORY_THRESHOLD = 40;
    private int boardWidth;
    private int boardHeight;
    private double lastQuality;

    @SuppressWarnings("unchecked")
    public TemplateReader() {
        examples = new Map[BoardReading.SYMBOLS.length() - 1];
        for (int i = 0; i < examples.length; i++) {
            examples[i] = new EnumMap<>(Parity.class);
        }
    }

    /** True when every piece (both colours, six types) has at least one example. */
    public boolean isComplete() {
        for (Map<Parity, List<Example>> e : examples) {
            if (e.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Number of piece symbols with at least one example. */
    public int knownSymbols() {
        int n = 0;
        for (Map<Parity, List<Example>> e : examples) {
            n += e.isEmpty() ? 0 : 1;
        }
        return n;
    }

    /** Size of the board pictures this reader learned on (0 before learning). */
    public int boardWidth() {
        return boardWidth;
    }

    /**
     * Mean score of the best match per square on the last reading: low when the pictures look like the learned ones,
     * high when the theme, the piece set or the zoom changed (then the reader should learn again).
     */
    public double lastQuality() {
        return lastQuality;
    }

    /** Learns the pieces of a picture whose position is known ({@code placement}: FEN, rank 8 first). */
    public void learn(BufferedImage board, String placement, boolean flipped) {
        char[][] grid = BoardReading.parsePlacement(placement);
        boardWidth = board.getWidth();
        boardHeight = board.getHeight();
        for (int col = 0; col < 8; col++) {
            for (int row = 0; row < 8; row++) {
                int file = flipped ? 7 - col : col;
                int rank = flipped ? row : 7 - row;
                char symbol = grid[file][rank];
                int[] rgb = cell(board, col, row);
                if (symbol == BoardReading.EMPTY) {
                    emptyLook[row * 8 + col] = rgb;
                    // the background's own texture (wood, marble, patterns): pieces must stand out more than this
                    Parity p = parity(file, rank);
                    texture[p.ordinal()] = Math.max(texture[p.ordinal()], 0.9 * texture[p.ordinal()]
                            + 0.1 * textureLevel(rgb));
                    texture[p.ordinal()] = Math.max(texture[p.ordinal()], textureLevel(rgb) * 0.8);
                    continue;
                }
                int s = BoardReading.SYMBOLS.indexOf(symbol);
                boolean[] mask = pieceMask(rgb, threshold(parity(file, rank)));
                int area = count(mask);
                if (area < N * N / 25) {
                    continue; // nothing visible there (covered, or a bad crop): not a useful example
                }
                List<Example> list = examples[s].computeIfAbsent(parity(file, rank), p -> new ArrayList<>());
                list.add(new Example(rgb, mask, area));
                if (list.size() > MAX_EXAMPLES) {
                    list.remove(0); // keep the most recent look (themes can change)
                }
            }
        }
    }

    /** Forgets everything (another theme). */
    public void clear() {
        for (Map<Parity, List<Example>> e : examples) {
            e.clear();
        }
        texture[0] = 0;
        texture[1] = 0;
        java.util.Arrays.fill(emptyLook, null);
        boardWidth = 0;
        boardHeight = 0;
    }

    /** True when a picture of this size can be read (same board size as learned, within 10%). */
    public boolean fits(BufferedImage board) {
        return boardWidth > 0 && Math.abs(board.getWidth() - boardWidth) <= 0.1 * boardWidth
                && Math.abs(board.getHeight() - boardHeight) <= 0.1 * boardHeight;
    }

    /** Reads a picture of the board. Symbols without examples get a small probability. */
    public BoardReading read(BufferedImage board, boolean flipped) {
        int n = BoardReading.SYMBOLS.length();
        float[][][] probs = new float[8][8][n];
        double qualitySum = 0;
        for (int col = 0; col < 8; col++) {
            for (int row = 0; row < 8; row++) {
                int file = flipped ? 7 - col : col;
                int rank = flipped ? row : 7 - row;
                int[] rgb = cell(board, col, row);
                int[] empty = emptyLook[row * 8 + col];
                boolean[] mask = empty != null ? differenceMask(rgb, empty)
                        : pieceMask(rgb, threshold(parity(file, rank)));
                int area = count(mask);
                double[] score = new double[n];
                double best = Double.MAX_VALUE;
                for (int s = 0; s < n - 1; s++) {
                    double sc = 3; // no example: unlikely, not impossible
                    for (List<Example> list : examples[s].values()) {
                        for (Example e : list) {
                            sc = Math.min(sc, score(rgb, mask, area, e));
                        }
                    }
                    score[s] = sc;
                    best = Math.min(best, sc);
                }
                // empty: few piece pixels (compared with the square's own empty look when known: more reliable)
                double emptyScore = Math.min(3, (empty != null ? 6.0 : 4.0) * area / (N * N));
                score[n - 1] = emptyScore;
                best = Math.min(best, emptyScore);
                qualitySum += best;
                double sum = 0;
                float[] p = probs[file][rank];
                for (int s = 0; s < n; s++) {
                    double v = Math.exp(-(score[s] - best) / TEMPERATURE);
                    p[s] = (float) v;
                    sum += v;
                }
                for (int s = 0; s < n; s++) {
                    p[s] = (float) Math.max(1e-4, p[s] / sum);
                }
            }
        }
        lastQuality = qualitySum / 64;
        return new BoardReading(probs, true, 0);
    }

    /**
     * Combines a calibrated reading with the model's: geometric mean of the probabilities, square by square. Where
     * the calibrated reader is sure it decides; where it hesitates (a piece that barely stands out from a textured
     * square) the model's view weighs in.
     */
    public static BoardReading fuse(BoardReading calibrated, BoardReading model) {
        float[][][] a = calibrated.probabilities();
        float[][][] b = model.probabilities();
        int n = BoardReading.SYMBOLS.length();
        float[][][] out = new float[8][8][n];
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                double sum = 0;
                for (int s = 0; s < n; s++) {
                    double v = Math.pow(Math.max(1e-4, a[f][r][s]), 0.65) * Math.pow(Math.max(1e-4, b[f][r][s]), 0.35);
                    out[f][r][s] = (float) v;
                    sum += v;
                }
                for (int s = 0; s < n; s++) {
                    out[f][r][s] = (float) (out[f][r][s] / sum);
                }
            }
        }
        return new BoardReading(out, true, model.inferenceMs());
    }

    /**
     * True when the picture shows the start position, by occupancy alone (any theme, any piece set): the two rows
     * at each edge full, the four middle rows empty, and the pieces of the top rows of the other colour than those of
     * the bottom rows. {@code flipped} must match the brighter (white) pieces being at the top.
     */
    public static boolean looksLikeStart(BufferedImage board, boolean flipped) {
        int[][] cells = new int[64][];
        double textureLevel = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int[] rgb = cell(board, col, row);
                cells[row * 8 + col] = rgb;
                if (row >= 2 && row <= 5) {
                    textureLevel = Math.max(textureLevel, textureLevel(rgb));
                }
            }
        }
        int threshold = (int) Math.max(PIECE_THRESHOLD, textureLevel * 1.15);
        double[] luminance = new double[8];
        for (int row = 0; row < 8; row++) {
            double rowLum = 0;
            for (int col = 0; col < 8; col++) {
                int[] rgb = cells[row * 8 + col];
                boolean[] mask = pieceMask(rgb, threshold);
                int area = count(mask);
                boolean occupied = area > N * N / 12;
                boolean edge = row <= 1 || row >= 6;
                if (occupied != edge) {
                    return false;
                }
                if (occupied) {
                    rowLum += meanLuminance(rgb, mask);
                }
            }
            luminance[row] = rowLum / 8;
        }
        double top = (luminance[0] + luminance[1]) / 2;
        double bottom = (luminance[6] + luminance[7]) / 2;
        if (Math.abs(top - bottom) < 20) {
            return false; // both sides look alike: not two armies
        }
        boolean whiteAtBottom = bottom > top;
        return whiteAtBottom != flipped;
    }

    private static double meanLuminance(int[] rgb, boolean[] mask) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < rgb.length; i++) {
            if (mask[i]) {
                int p = rgb[i];
                sum += 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Dissimilarity of a square with an example: shape (1 - overlap) plus colour difference on the overlap. */
    private static double score(int[] rgb, boolean[] mask, int area, Example e) {
        int inter = 0;
        long colour = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] && e.mask[i]) {
                inter++;
                colour += distance(rgb[i], e.rgb[i]);
            }
        }
        int union = area + e.area - inter;
        double iou = union == 0 ? 1 : inter / (double) union;
        double colourDiff = inter == 0 ? 1 : colour / (inter * 765.0);
        return (1 - iou) + 2.0 * colourDiff;
    }

    private int threshold(Parity p) {
        return (int) Math.max(PIECE_THRESHOLD, Math.round(texture[p.ordinal()] * 1.15));
    }

    /** 95th percentile of the colour distance from the square's border colour: how textured an empty square is. */
    static double textureLevel(int[] rgb) {
        int bg = background(rgb);
        int[] d = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            d[i] = distance(rgb[i], bg);
        }
        java.util.Arrays.sort(d);
        return d[(int) (d.length * 0.95)];
    }

    private static Parity parity(int file, int rank) {
        return (file + rank) % 2 == 0 ? Parity.DARK : Parity.LIGHT; // a1 is dark
    }

    /** The square at screen column/row, cut with a margin and scaled to N x N, as packed RGB. */
    static int[] cell(BufferedImage board, int col, int row) {
        double cw = board.getWidth() / 8.0;
        double ch = board.getHeight() / 8.0;
        int x0 = (int) Math.round(col * cw + MARGIN * cw);
        int y0 = (int) Math.round(row * ch + MARGIN * ch);
        int x1 = (int) Math.round((col + 1) * cw - MARGIN * cw);
        int y1 = (int) Math.round((row + 1) * ch - MARGIN * ch);
        BufferedImage out = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(board, 0, 0, N, N, x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), null);
        g.dispose();
        return out.getRGB(0, 0, N, N, null, 0, N);
    }

    /**
     * Pixels that differ from the square's empty look. A highlight drawn over the square (last move, check) shifts
     * every pixel by about the same colour: the median shift is removed first.
     */
    static boolean[] differenceMask(int[] rgb, int[] empty) {
        int[] dr = new int[rgb.length];
        int[] dg = new int[rgb.length];
        int[] db = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            dr[i] = ((rgb[i] >> 16) & 0xFF) - ((empty[i] >> 16) & 0xFF);
            dg[i] = ((rgb[i] >> 8) & 0xFF) - ((empty[i] >> 8) & 0xFF);
            db[i] = (rgb[i] & 0xFF) - (empty[i] & 0xFF);
        }
        int mr = signedMedian(dr);
        int mg = signedMedian(dg);
        int mb = signedMedian(db);
        boolean[] mask = new boolean[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            mask[i] = Math.abs(dr[i] - mr) + Math.abs(dg[i] - mg) + Math.abs(db[i] - mb) > MEMORY_THRESHOLD;
        }
        return mask;
    }

    /** Median of the border ring of a difference map: the shift of the background (a highlight), not the piece. */
    private static int signedMedian(int[] d) {
        int[] ring = new int[4 * (N - 1)];
        int k = 0;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                if (i == 0 || j == 0 || i == N - 1 || j == N - 1) {
                    ring[k++] = d[i * N + j];
                }
            }
        }
        java.util.Arrays.sort(ring);
        return ring[ring.length / 2];
    }

    /** Pixels that differ from the square's background (median colour of its border ring) by more than threshold. */
    static boolean[] pieceMask(int[] rgb, int threshold) {
        int bg = background(rgb);
        boolean[] mask = new boolean[N * N];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = distance(rgb[i], bg) > threshold;
        }
        return mask;
    }

    /** Median colour of the border ring of a normalised square. */
    static int background(int[] rgb) {
        int ring = 4 * (N - 1);
        int[] r = new int[ring];
        int[] g = new int[ring];
        int[] b = new int[ring];
        int k = 0;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                if (i == 0 || j == 0 || i == N - 1 || j == N - 1) {
                    int p = rgb[i * N + j];
                    r[k] = (p >> 16) & 0xFF;
                    g[k] = (p >> 8) & 0xFF;
                    b[k] = p & 0xFF;
                    k++;
                }
            }
        }
        return (median(r) << 16) | (median(g) << 8) | median(b);
    }

    private static int median(int[] v) {
        int[] c = v.clone();
        java.util.Arrays.sort(c);
        return c[c.length / 2];
    }

    private static int distance(int p, int q) {
        return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                + Math.abs((p & 0xFF) - (q & 0xFF));
    }

    private static int count(boolean[] mask) {
        int n = 0;
        for (boolean m : mask) {
            n += m ? 1 : 0;
        }
        return n;
    }
}
