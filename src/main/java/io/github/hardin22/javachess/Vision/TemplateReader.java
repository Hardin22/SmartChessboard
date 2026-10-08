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
 * drawn in the corners are outside the compared area. A square never seen empty (the back ranks, after a
 * calibration on the start position) is also weighed on every pixel against the learned pieces ({@link Evidence}),
 * which finds pieces that barely stand out from a textured square. Scores become probabilities for
 * {@link PositionResolver}.</p>
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
    /** Lowest threshold tried when learning a piece known to be on a square where it barely stands out. */
    private static final int LOW_CONTRAST_THRESHOLD = 40;
    /** {@link Evidence#gain} above which a square never seen empty holds a piece, below which it is empty. */
    private static final double OCCUPIED_GAIN = 0.2;
    private static final double EMPTY_GAIN = -0.05;
    /** Scale of {@link Evidence#symbolError} (mean colour error per pixel) to the shape scores. */
    private static final double ERROR_SCALE = 20;
    /** Examples kept per symbol and square colour. */
    private static final int MAX_EXAMPLES = 3;
    /** Softness of the score -> probability conversion. */
    private static final double TEMPERATURE = 0.06;

    private enum Parity { LIGHT, DARK }

    /** One example of a piece: its pixels, its shape and how much they stand out from the square (colour mass). */
    record Example(int[] rgb, boolean[] mask, int area, long contrast) {
        static Example of(int[] rgb, boolean[] mask, int area) {
            int bg = background(rgb);
            long contrast = 0;
            for (int i = 0; i < rgb.length; i++) {
                if (mask[i]) {
                    contrast += distance(rgb[i], bg);
                }
            }
            return new Example(rgb, mask, area, contrast);
        }
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
                int threshold = threshold(parity(file, rank));
                boolean[] mask = keepBlobs(pieceMask(rgb, threshold));
                int area = count(mask);
                // the piece is known to be there: when it barely stands out from a textured square (black on dark
                // stone) look closer, as long as the texture does not flood the square
                for (int t : new int[] {PIECE_THRESHOLD, LOW_CONTRAST_THRESHOLD}) {
                    if (area >= N * N / 25) {
                        break;
                    }
                    if (t >= threshold) {
                        continue;
                    }
                    boolean[] closer = keepBlobs(pieceMask(rgb, t));
                    int closerArea = count(closer);
                    if (closerArea <= N * N * 0.7) {
                        mask = closer;
                        area = closerArea;
                    }
                }
                if (area < N * N / 25) {
                    continue; // nothing visible there (covered, or a bad crop): not a useful example
                }
                List<Example> list = examples[s].computeIfAbsent(parity(file, rank), p -> new ArrayList<>());
                list.add(Example.of(rgb, mask, area));
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
                boolean[] mask = keepBlobs(empty != null ? differenceMask(rgb, empty)
                        : pieceMask(rgb, threshold(parity(file, rank))));
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
                if (empty == null) {
                    // the square was never seen empty: weigh every pixel against the learned pieces
                    Evidence ev = evidence(rgb);
                    if (ev.gain() > OCCUPIED_GAIN) {
                        if (area < N * N / 25) { // no shape to compare: which piece explains the pixels best
                            best = Double.MAX_VALUE;
                            for (int s = 0; s < n - 1; s++) {
                                score[s] = ev.symbolError()[s];
                                best = Math.min(best, score[s]);
                            }
                        }
                        emptyScore = Math.max(emptyScore, best + 0.25);
                    } else if (ev.gain() < EMPTY_GAIN) {
                        emptyScore = Math.min(emptyScore, Math.max(0, best - 0.25));
                    }
                }
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

    /**
     * The opening position the picture shows, recognised by occupancy (any theme): the start position or a position
     * one or two plies after it (a bot that moves at once). The occupied squares must match exactly one candidate
     * and the two armies must be where {@code flipped} says. Null when unsure.
     */
    public static String recogniseOpening(BufferedImage board, boolean flipped) {
        boolean[] occupied = new boolean[64]; // by screen position, row * 8 + col
        double textureLevel = 0;
        int[][] cells = new int[64][];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                cells[row * 8 + col] = cell(board, col, row);
            }
        }
        // the middle rows are empty but for at most two pawns: their 75th percentile is the texture of empty squares
        double[] levels = new double[16];
        int k = 0;
        for (int row = 3; row <= 4; row++) {
            for (int col = 0; col < 8; col++) {
                levels[k++] = textureLevel(cells[row * 8 + col]);
            }
        }
        java.util.Arrays.sort(levels);
        textureLevel = levels[11];
        int threshold = (int) Math.max(PIECE_THRESHOLD, textureLevel * 1.15);
        long seen = 0;
        double top = 0;
        double bottom = 0;
        int topN = 0;
        int bottomN = 0;
        for (int i = 0; i < 64; i++) {
            boolean[] mask = keepBlobs(pieceMask(cells[i], threshold));
            occupied[i] = count(mask) > N * N / 12;
            if (occupied[i]) {
                int row = i / 8;
                int col = i % 8;
                int file = flipped ? 7 - col : col;
                int rank = flipped ? row : 7 - row;
                seen |= 1L << (rank * 8 + file);
                if (row <= 1) {
                    top += meanLuminance(cells[i], mask);
                    topN++;
                } else if (row >= 6) {
                    bottom += meanLuminance(cells[i], mask);
                    bottomN++;
                }
            }
        }
        if (topN < 12 || bottomN < 12 || Math.abs(top / topN - bottom / bottomN) < 20
                || (bottom / bottomN > top / topN) == flipped) {
            return null; // not two armies on their home rows, or the other way round
        }
        java.util.Map<Long, String> byOccupancy = new java.util.HashMap<>();
        java.util.Set<Long> ambiguous = new java.util.HashSet<>();
        com.github.bhlangonijr.chesslib.Board start = new com.github.bhlangonijr.chesslib.Board();
        java.util.ArrayDeque<com.github.bhlangonijr.chesslib.Board> frontier = new java.util.ArrayDeque<>();
        frontier.add(start);
        for (int ply = 0; ply <= 2; ply++) {
            java.util.ArrayDeque<com.github.bhlangonijr.chesslib.Board> next = new java.util.ArrayDeque<>();
            for (com.github.bhlangonijr.chesslib.Board b : frontier) {
                long occ = b.getBitboard();
                String placement = b.getFen().split(" ")[0];
                String known = byOccupancy.putIfAbsent(occ, placement);
                if (known != null && !known.equals(placement)) {
                    ambiguous.add(occ);
                }
                if (ply < 2) {
                    for (com.github.bhlangonijr.chesslib.move.Move m
                            : com.github.bhlangonijr.chesslib.move.MoveGenerator.generateLegalMoves(b)) {
                        com.github.bhlangonijr.chesslib.Board c = b.clone();
                        c.doMove(m);
                        next.add(c);
                    }
                }
            }
            frontier = next;
        }
        return ambiguous.contains(seen) ? null : byOccupancy.get(seen);
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

    /**
     * What every pixel of a square says about the learned pieces. {@code gain}: how much better the best example
     * explains the square than its background does, over that example's own contrast (about 1 when the piece is
     * there, 0 or below when the square is empty); summing all the pixels, not only those above a threshold, lets a
     * piece that barely stands out (pale on hatching, black on dark stone) add up. {@code symbolError}: per symbol, the
     * mean error of "the example's piece pixels, the background elsewhere", scaled like the shape scores. Without
     * examples the gain is NaN (no decision).
     */
    record Evidence(double gain, double[] symbolError) {
    }

    private Evidence evidence(int[] rgb) {
        int bg = background(rgb);
        int[] toBg = new int[rgb.length];
        long emptyError = 0;
        for (int i = 0; i < rgb.length; i++) {
            toBg[i] = distance(rgb[i], bg);
            emptyError += toBg[i];
        }
        double bestGain = Double.NaN;
        double[] symbolError = new double[examples.length];
        for (int s = 0; s < examples.length; s++) {
            long bestError = Long.MAX_VALUE;
            for (List<Example> list : examples[s].values()) {
                for (Example e : list) {
                    long gain = 0;
                    for (int i = 0; i < rgb.length; i++) {
                        if (e.mask[i]) {
                            gain += toBg[i] - distance(rgb[i], e.rgb[i]);
                        }
                    }
                    if (e.contrast > 0) {
                        double g = gain / (double) e.contrast;
                        bestGain = Double.isNaN(bestGain) ? g : Math.max(bestGain, g);
                    }
                    bestError = Math.min(bestError, emptyError - gain);
                }
            }
            symbolError[s] = bestError == Long.MAX_VALUE ? 3
                    : Math.min(3, ERROR_SCALE * bestError / (rgb.length * 765.0));
        }
        return new Evidence(bestGain, symbolError);
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
        // a bit below the texture's own level: the specks of texture it lets through are removed by connectivity
        return (int) Math.max(PIECE_THRESHOLD, Math.round(texture[p.ordinal()] * 0.9));
    }

    /** Keeps the large connected regions of a mask (a piece is one blob; textures leave small specks). */
    static boolean[] keepBlobs(boolean[] mask) {
        int[] label = new int[mask.length];
        int[] stack = new int[mask.length];
        boolean[] out = new boolean[mask.length];
        int minArea = N * N / 50;
        int next = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || label[start] != 0) {
                continue;
            }
            next++;
            int top = 0;
            int area = 0;
            stack[top++] = start;
            label[start] = next;
            int[] members = new int[mask.length];
            while (top > 0) {
                int i = stack[--top];
                members[area++] = i;
                int x = i % N;
                int y = i / N;
                int[] nb = {x > 0 ? i - 1 : -1, x < N - 1 ? i + 1 : -1, y > 0 ? i - N : -1, y < N - 1 ? i + N : -1};
                for (int j : nb) {
                    if (j >= 0 && mask[j] && label[j] == 0) {
                        label[j] = next;
                        stack[top++] = j;
                    }
                }
            }
            if (area >= minArea) {
                for (int k = 0; k < area; k++) {
                    out[members[k]] = true;
                }
            }
        }
        return out;
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
