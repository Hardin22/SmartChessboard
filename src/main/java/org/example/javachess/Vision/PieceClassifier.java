package org.example.javachess.Vision;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a chess position from a picture of a 2D board (a screenshot of chess.com / lichess) with a YOLOv8 ONNX
 * model that detects the 12 piece types and the board itself.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Pre-processing: grey scale (the model was trained on grey images), robust contrast stretch (1st-99th
 *       percentile) so dim or washed-out screens look the same, resize to 640x640 (letterbox for full screens).</li>
 *   <li>Inference (onnxruntime, CPU, thread count capped on small machines such as the Raspberry Pi).</li>
 *   <li>Decoding: every candidate box keeps its 13 class scores; non-maximum suppression per class-agnostic box.</li>
 *   <li>Grid: squares are taken from the detected "board" box when it is reliable, otherwise from the whole image;
 *       each piece box is assigned to the square under the centre of its lower half (tall pieces overlap the
 *       square above).</li>
 *   <li>Output: a {@link BoardReading} with a probability for each symbol on each square; legality is applied later
 *       ({@link BoardReading#withPlacementRules()}, {@link PositionResolver}).</li>
 * </ol>
 * Instances are thread-safe for inference (onnxruntime sessions are) but are meant to be used from one worker
 * thread, never from the JavaFX thread.
 */
public class PieceClassifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PieceClassifier.class);

    public static final String DEFAULT_MODEL = "models/best.onnx";
    private static final int INPUT = 640;
    /** Low decode threshold: weak boxes still inform the per-square probabilities. */
    private static final float DECODE_THRESHOLD = 0.20f;
    private static final float NMS_IOU = 0.45f;
    /**
     * Minimum "board" score on a full screenshot. A board seen small on a cluttered screen scores ~0.6-0.8, so the
     * threshold is moderate and false positives are rejected by requiring pieces inside the box (and, in
     * VisionService, the same box on several consecutive frames).
     */
    private static final float BOARD_FIND_THRESHOLD = 0.50f;

    static {
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable e) {
            LoggerFactory.getLogger(PieceClassifier.class).error("OpenCV native library not available", e);
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String[] classNames;
    private final int boardClass;
    private final int[] symbolIndexOfClass; // class id -> index in BoardReading.SYMBOLS, -1 for "board"
    private volatile boolean contrastStretch = true;
    /** Vertical anchor of a piece box (0 = top, 0.5 = centre): tall pieces overlap the square above. */
    private volatile float anchorY = 0.70f;

    /** Loads the model from a file path, or from the classpath when no such file exists. */
    public PieceClassifier(String modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        int cores = Runtime.getRuntime().availableProcessors();
        options.setIntraOpNumThreads(Math.max(1, Math.min(cores <= 4 ? 2 : 4, cores)));
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(loadModel(modelPath), options);
        this.inputName = session.getInputNames().iterator().next();

        String[] names = readClassNames(session);
        this.classNames = names;
        int board = -1;
        symbolIndexOfClass = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            if ("board".equals(names[i])) {
                board = i;
                symbolIndexOfClass[i] = -1;
            } else {
                symbolIndexOfClass[i] = names[i].length() == 1 ? BoardReading.SYMBOLS.indexOf(names[i].charAt(0)) : -1;
            }
        }
        this.boardClass = board;
        log.info("Vision model loaded ({} classes, {} threads)", names.length,
                Math.max(1, Math.min(cores <= 4 ? 2 : 4, cores)));
    }

    private static byte[] loadModel(String modelPath) throws OrtException {
        try {
            Path file = Paths.get(modelPath);
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
            try (InputStream in = PieceClassifier.class.getResourceAsStream("/" + modelPath)) {
                if (in == null) {
                    throw new OrtException(OrtException.OrtErrorCode.ORT_NO_SUCHFILE, "Model not found: " + modelPath);
                }
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new OrtException(OrtException.OrtErrorCode.ORT_NO_SUCHFILE, "Cannot read model: " + e.getMessage());
        }
    }

    /** Class names from the Ultralytics metadata ("{0: 'B', 1: 'K', ...}"), with the known list as fallback. */
    static String[] readClassNames(OrtSession session) {
        String[] fallback = {"B", "K", "N", "P", "Q", "R", "b", "board", "k", "n", "p", "q", "r"};
        try {
            String names = session.getMetadata().getCustomMetadata().get("names");
            String[] parsed = parseNames(names);
            return parsed != null ? parsed : fallback;
        } catch (OrtException | RuntimeException e) {
            return fallback;
        }
    }

    static String[] parseNames(String names) {
        if (names == null) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d+)\\s*:\\s*'([^']*)'").matcher(names);
        Map<Integer, String> map = new java.util.TreeMap<>();
        while (m.find()) {
            map.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        if (map.isEmpty() || map.size() != map.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1) {
            return null;
        }
        return map.values().toArray(new String[0]);
    }

    /** Sets the vertical anchor used to assign a piece box to a square (tests compare 0.5 and 0.7). */
    public void setAnchorY(float anchorY) {
        this.anchorY = anchorY;
    }

    private volatile boolean gridRefinement = true;

    /** Enables the checker-pattern refinement of the grid (tests measure its effect). */
    public void setGridRefinement(boolean enabled) {
        this.gridRefinement = enabled;
    }

    /** Disables the contrast normalisation (used by the tests to measure its effect). */
    public void setContrastStretch(boolean enabled) {
        this.contrastStretch = enabled;
    }

    // =================================================================== public API

    /** Result kept for the existing callers: FEN placement in screen orientation and board presence. */
    public static class VisionResult {
        public final String fen;
        public final boolean hasBoard;
        public final BoardReading reading;

        public VisionResult(String fen, boolean hasBoard, BoardReading reading) {
            this.fen = fen;
            this.hasBoard = hasBoard;
            this.reading = reading;
        }
    }

    /**
     * Finds the board on a full screenshot. Returns its rectangle in screen pixels, or null.
     */
    public Rectangle findBoard(BufferedImage screen) {
        Mat src = null;
        Mat input = null;
        try {
            src = toMat(screen);
            double scale = Math.min((double) INPUT / src.width(), (double) INPUT / src.height());
            int newW = (int) Math.round(src.width() * scale);
            int newH = (int) Math.round(src.height() * scale);
            int offX = (INPUT - newW) / 2;
            int offY = (INPUT - newH) / 2;
            input = preprocess(src, newW, newH, offX, offY);
            List<Detection> detections = detect(input);
            Detection best = null;
            double bestValue = 0;
            for (Detection d : detections) {
                if (d.classId != boardClass || d.score < BOARD_FIND_THRESHOLD) {
                    continue;
                }
                float ratio = d.w / d.h;
                if (ratio < 0.85f || ratio > 1.15f) {
                    continue; // a chessboard is square
                }
                if (countPiecesInside(detections, d) < 2) {
                    continue; // every real position has at least the two kings
                }
                double value = d.score * Math.sqrt(d.w * d.h); // prefer confident, then large
                if (value > bestValue) {
                    bestValue = value;
                    best = d;
                }
            }
            if (best == null) {
                best = gridFromPieces(input, detections);
                if (best == null) {
                    return null;
                }
            } else if (gridRefinement) {
                // The network's box is approximate (a few % off): snap it to the checker pattern.
                GridFinder.Grid g = new GridFinder(input).refine(new Rectangle(Math.round(best.x), Math.round(best.y),
                        Math.round(best.w), Math.round(best.h)), 0.08);
                if (g.score() >= GridFinder.MIN_SCORE) {
                    float size = (float) (g.cell() * 8);
                    best = new Detection(boardClass, best.score, (float) g.x(), (float) g.y(), size, size, best.scores);
                }
            }
            int x = (int) Math.round((best.x - offX) / scale);
            int y = (int) Math.round((best.y - offY) / scale);
            int w = (int) Math.round(best.w / scale);
            int h = (int) Math.round(best.h / scale);
            x = Math.max(0, x);
            y = Math.max(0, y);
            w = Math.min(screen.getWidth() - x, w);
            h = Math.min(screen.getHeight() - y, h);
            return w > 0 && h > 0 ? new Rectangle(x, y, w, h) : null;
        } catch (OrtException | RuntimeException e) {
            log.error("Board search failed", e);
            return null;
        } finally {
            release(src, input);
        }
    }

    /**
     * Reads the position from a picture of the board (roughly cropped to it).
     *
     * @param isFlipped true when the picture shows black at the bottom
     * @param debugPath optional PNG path where the detections are drawn (null = none)
     */
    public BoardReading read(BufferedImage boardImage, boolean isFlipped, String debugPath) throws OrtException {
        Mat src = toMat(boardImage);
        Mat input = null;
        try {
            long start = System.nanoTime();
            input = preprocess(src, INPUT, INPUT, 0, 0);
            List<Detection> detections = detect(input);
            long ms = (System.nanoTime() - start) / 1_000_000;

            // Grid: the detected board box when it is reliable and covers most of the picture, else the picture.
            float gx = 0;
            float gy = 0;
            float gw = INPUT;
            float gh = INPUT;
            boolean hasBoard = false;
            for (Detection d : detections) {
                if (d.classId == boardClass && d.score >= 0.5f) {
                    hasBoard = true;
                    if (d.w * d.h >= 0.6f * INPUT * INPUT && d.w * d.h <= 1.05f * INPUT * INPUT) {
                        gx = Math.max(0, d.x);
                        gy = Math.max(0, d.y);
                        gw = Math.min(INPUT - gx, d.w);
                        gh = Math.min(INPUT - gy, d.h);
                    }
                    break;
                }
            }
            // Sharpen the grid on the checker pattern when the picture is (close to) square.
            double aspect = (double) src.width() / src.height();
            if (gridRefinement && aspect > 0.97 && aspect < 1.03) {
                GridFinder.Grid g = new GridFinder(input).refine(
                        new Rectangle(Math.round(gx), Math.round(gy), Math.round(gw), Math.round(gh)), 0.06);
                if (g.score() >= GridFinder.MIN_SCORE) {
                    gx = (float) g.x();
                    gy = (float) g.y();
                    gw = (float) (g.cell() * 8);
                    gh = gw;
                }
            }
            float[][][] probs = squareProbabilities(detections, gx, gy, gw, gh, isFlipped);
            BoardReading reading = new BoardReading(probs, hasBoard || hasPieces(detections), ms);
            if (debugPath != null) {
                drawDebug(src, detections, debugPath);
            }
            return reading;
        } finally {
            release(src, input);
        }
    }

    /** Compatibility wrapper returning the FEN placement (board orientation, rank 8 first). */
    public VisionResult getFenFromImage(BufferedImage boardCapture, String debugPath, boolean isFlipped) {
        try {
            BoardReading reading = read(boardCapture, isFlipped, debugPath);
            return new VisionResult(reading.placement(), reading.hasBoard(), reading);
        } catch (OrtException | RuntimeException e) {
            log.error("Position reading failed", e);
            return null;
        }
    }

    public VisionResult getFenFromImage(BufferedImage boardCapture, String debugPath) {
        return getFenFromImage(boardCapture, debugPath, false);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            log.debug("Session close failed: {}", e.getMessage());
        }
    }

    // =================================================================== pre-processing

    /**
     * Grey scale, robust contrast stretch and resize into a 640x640 canvas (black borders when letterboxing).
     * Returns an 8-bit, 1-channel Mat.
     */
    Mat preprocess(Mat bgr, int w, int h, int offX, int offY) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        if (contrastStretch) {
            stretchContrast(gray);
        }
        Mat resized = new Mat();
        Imgproc.resize(gray, resized, new Size(w, h), 0, 0,
                w < bgr.width() ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
        gray.release();
        if (w == INPUT && h == INPUT && offX == 0 && offY == 0) {
            return resized;
        }
        Mat canvas = new Mat(INPUT, INPUT, CvType.CV_8UC1, new Scalar(0));
        resized.copyTo(canvas.submat(new Rect(offX, offY, w, h)));
        resized.release();
        return canvas;
    }

    /** Maps the 1st..99th percentile of the grey levels to 0..255 (no-op for already full-range images). */
    static void stretchContrast(Mat gray) {
        int[] hist = new int[256];
        byte[] data = new byte[(int) gray.total()];
        gray.get(0, 0, data);
        for (byte b : data) {
            hist[b & 0xFF]++;
        }
        int total = data.length;
        int lo = percentile(hist, total, 0.01);
        int hi = percentile(hist, total, 0.99);
        if (hi - lo < 16 || (lo <= 2 && hi >= 253)) {
            return; // flat image or already using the full range
        }
        double scale = 255.0 / (hi - lo);
        gray.convertTo(gray, CvType.CV_8UC1, scale, -lo * scale);
    }

    private static int percentile(int[] hist, int total, double q) {
        long target = Math.round(total * q);
        long acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= target) {
                return i;
            }
        }
        return 255;
    }

    // =================================================================== inference

    private List<Detection> detect(Mat gray640) throws OrtException {
        byte[] pixels = new byte[INPUT * INPUT];
        gray640.get(0, 0, pixels);
        float[] chw = new float[3 * INPUT * INPUT];
        int plane = INPUT * INPUT;
        for (int i = 0; i < plane; i++) {
            float v = (pixels[i] & 0xFF) / 255f;
            chw[i] = v;
            chw[plane + i] = v;
            chw[2 * plane + i] = v;
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, INPUT, INPUT});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            float[][][] out = (float[][][]) result.get(0).getValue();
            return decode(out[0], classNames.length);
        }
    }

    /** Decodes YOLOv8 output [4 + classes][anchors] into boxes with all class scores, then applies NMS. */
    static List<Detection> decode(float[][] output, int numClasses) {
        int anchors = output[0].length;
        List<Detection> candidates = new ArrayList<>();
        for (int i = 0; i < anchors; i++) {
            float best = 0f;
            int cls = -1;
            float[] scores = new float[numClasses];
            for (int c = 0; c < numClasses; c++) {
                float s = output[4 + c][i];
                scores[c] = s;
                if (s > best) {
                    best = s;
                    cls = c;
                }
            }
            if (best > DECODE_THRESHOLD) {
                float w = output[2][i];
                float h = output[3][i];
                candidates.add(new Detection(cls, best, output[0][i] - w / 2, output[1][i] - h / 2, w, h, scores));
            }
        }
        candidates.sort((a, b) -> Float.compare(b.score, a.score));
        List<Detection> kept = new ArrayList<>();
        for (Detection d : candidates) {
            boolean suppressed = false;
            for (Detection k : kept) {
                // the board box contains every piece: only compare boards with boards and pieces with pieces
                boolean sameKind = (k.classId == d.classId) || (!isBoardLike(k) && !isBoardLike(d));
                if (sameKind && iou(k, d) > NMS_IOU) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(d);
            }
        }
        return kept;
    }

    private static boolean isBoardLike(Detection d) {
        return d.w > 640f / 3 || d.h > 640f / 3; // a piece is at most ~1/8 of the board
    }

    static float iou(Detection a, Detection b) {
        float x1 = Math.max(a.x, b.x);
        float y1 = Math.max(a.y, b.y);
        float x2 = Math.min(a.x + a.w, b.x + b.w);
        float y2 = Math.min(a.y + a.h, b.y + b.h);
        if (x2 <= x1 || y2 <= y1) {
            return 0f;
        }
        float inter = (x2 - x1) * (y2 - y1);
        return inter / (a.w * a.h + b.w * b.h - inter);
    }

    /**
     * Per-square probabilities. The piece box whose anchor point (centre of its lower half) falls in a square
     * decides it; with no box the square is empty with high probability.
     */
    float[][][] squareProbabilities(List<Detection> detections, float gx, float gy, float gw, float gh,
                                    boolean isFlipped) {
        int n = BoardReading.SYMBOLS.length();
        float[][][] probs = new float[8][8][n];
        Detection[][] owner = new Detection[8][8];
        float cw = gw / 8f;
        float ch = gh / 8f;
        for (Detection d : detections) {
            if (d.classId == boardClass || symbolIndexOfClass[d.classId] < 0) {
                continue;
            }
            float ax = d.x + d.w / 2f;
            float ay = d.y + d.h * anchorY;
            int col = (int) Math.floor((ax - gx) / cw);
            int row = (int) Math.floor((ay - gy) / ch);
            if (col < 0 || col > 7 || row < 0 || row > 7) {
                continue;
            }
            if (owner[col][row] == null || d.score > owner[col][row].score) {
                owner[col][row] = d;
            }
        }
        for (int col = 0; col < 8; col++) {
            for (int row = 0; row < 8; row++) {
                int file = isFlipped ? 7 - col : col;
                int rank = isFlipped ? row : 7 - row;
                float[] p = probs[file][rank];
                Detection d = owner[col][row];
                if (d == null) {
                    java.util.Arrays.fill(p, 0.03f / (n - 1));
                    p[n - 1] = 0.97f;
                    continue;
                }
                float max = 0f;
                for (int c = 0; c < d.scores.length; c++) {
                    int s = symbolIndexOfClass[c];
                    if (s >= 0) {
                        p[s] += d.scores[c];
                        max = Math.max(max, d.scores[c]);
                    }
                }
                p[n - 1] = Math.max(0.01f, 1f - max);
                float sum = 0f;
                for (float v : p) {
                    sum += v;
                }
                for (int i = 0; i < n; i++) {
                    p[i] /= sum;
                }
            }
        }
        return probs;
    }

    /**
     * Fallback when the network does not recognise the board itself (unusual themes): the pieces it does detect
     * give the square size and a region, and {@link GridFinder} finds the checker pattern there.
     */
    private Detection gridFromPieces(Mat gray640, List<Detection> detections) {
        List<Detection> pieces = new ArrayList<>();
        for (Detection d : detections) {
            if (d.classId != boardClass && d.score >= 0.5f) {
                pieces.add(d);
            }
        }
        if (pieces.size() < 2) {
            return null;
        }
        // Drop isolated false positives (icons, text) far from the bulk of the pieces.
        float[] cxs = new float[pieces.size()];
        float[] cys = new float[pieces.size()];
        float[] sz = new float[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            Detection d = pieces.get(i);
            cxs[i] = d.x + d.w / 2;
            cys[i] = d.y + d.h / 2;
            sz[i] = Math.max(d.w, d.h);
        }
        java.util.Arrays.sort(cxs);
        java.util.Arrays.sort(cys);
        java.util.Arrays.sort(sz);
        float medX = cxs[cxs.length / 2];
        float medY = cys[cys.length / 2];
        float reach = 7.5f * sz[sz.length / 2] * 1.3f;
        pieces.removeIf(d -> Math.abs(d.x + d.w / 2 - medX) > reach || Math.abs(d.y + d.h / 2 - medY) > reach);
        if (pieces.size() < 2) {
            return null;
        }
        float[] sizes = new float[pieces.size()];
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = 0;
        float maxY = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Detection d = pieces.get(i);
            sizes[i] = Math.max(d.w, d.h);
            minX = Math.min(minX, d.x);
            minY = Math.min(minY, d.y);
            maxX = Math.max(maxX, d.x + d.w);
            maxY = Math.max(maxY, d.y + d.h);
        }
        java.util.Arrays.sort(sizes);
        float median = sizes[sizes.length / 2];
        double cellMin = Math.max(6, median * 0.9);
        double cellMax = Math.max(cellMin + 1, Math.max(sizes[sizes.length - 1], median * 1.5));
        GridFinder finder = new GridFinder(gray640);
        // Piece boxes can stick out of their square by a few pixels: leave half a square of slack.
        double slack = cellMax / 2;
        GridFinder.Grid g = finder.search(Math.max(0, maxX - 8 * cellMax - slack), minX + slack,
                Math.max(0, maxY - 8 * cellMax - slack), minY + slack, cellMin, cellMax);
        if (g.score() < GridFinder.MIN_SCORE) {
            return null;
        }
        float size = (float) (g.cell() * 8);
        log.debug("Board found from the checker pattern (score {})", String.format("%.1f", g.score()));
        return new Detection(boardClass, 0.5f, (float) g.x(), (float) g.y(), size, size, new float[0]);
    }

    private int countPiecesInside(List<Detection> detections, Detection board) {
        int n = 0;
        for (Detection d : detections) {
            if (d.classId == boardClass || d.score < 0.4f) {
                continue;
            }
            float cx = d.x + d.w / 2;
            float cy = d.y + d.h / 2;
            if (cx >= board.x && cx <= board.x + board.w && cy >= board.y && cy <= board.y + board.h) {
                n++;
            }
        }
        return n;
    }

    private boolean hasPieces(List<Detection> detections) {
        int count = 0;
        for (Detection d : detections) {
            if (d.classId != boardClass && d.score >= 0.5f) {
                count++;
            }
        }
        return count >= 2;
    }

    // =================================================================== helpers

    static Mat toMat(BufferedImage bi) {
        BufferedImage img = bi;
        // Sub-images share the parent's buffer: copy them (and any non-BGR image) into a compact BGR image.
        boolean compactBgr = img.getType() == BufferedImage.TYPE_3BYTE_BGR && img.getRaster().getParent() == null
                && ((DataBufferByte) img.getRaster().getDataBuffer()).getData().length
                == img.getWidth() * img.getHeight() * 3;
        if (!compactBgr) {
            BufferedImage converted = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            java.awt.Graphics2D g = converted.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            img = converted;
        }
        Mat mat = new Mat(img.getHeight(), img.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, ((DataBufferByte) img.getRaster().getDataBuffer()).getData());
        return mat;
    }

    private void drawDebug(Mat src, List<Detection> detections, String path) {
        Mat canvas = src.clone();
        double sx = canvas.width() / (double) INPUT;
        double sy = canvas.height() / (double) INPUT;
        for (int i = 1; i < 8; i++) {
            Imgproc.line(canvas, new Point(i * canvas.width() / 8.0, 0), new Point(i * canvas.width() / 8.0,
                    canvas.height()), new Scalar(255, 0, 0), 1);
            Imgproc.line(canvas, new Point(0, i * canvas.height() / 8.0), new Point(canvas.width(),
                    i * canvas.height() / 8.0), new Scalar(255, 0, 0), 1);
        }
        for (Detection d : detections) {
            Imgproc.rectangle(canvas, new Point(d.x * sx, d.y * sy), new Point((d.x + d.w) * sx, (d.y + d.h) * sy),
                    new Scalar(0, 255, 0), 1);
            Imgproc.putText(canvas, classNames[d.classId] + String.format(" %.2f", d.score),
                    new Point(d.x * sx, Math.max(10, d.y * sy - 3)), Imgproc.FONT_HERSHEY_SIMPLEX, 0.4,
                    new Scalar(0, 255, 0), 1);
        }
        Imgcodecs.imwrite(path, canvas);
        canvas.release();
    }

    private static void release(Mat... mats) {
        for (Mat m : mats) {
            if (m != null) {
                m.release();
            }
        }
    }

    /** One detected box in 640x640 input coordinates, with the scores of every class. */
    static final class Detection {
        final int classId;
        final float score;
        final float x;
        final float y;
        final float w;
        final float h;
        final float[] scores;

        Detection(int classId, float score, float x, float y, float w, float h, float[] scores) {
            this.classId = classId;
            this.score = score;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.scores = scores;
        }
    }

}
