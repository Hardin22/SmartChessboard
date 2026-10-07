package org.example.javachess.Vision;

import ai.onnxruntime.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.FloatBuffer;
import java.util.*;

public class PieceClassifier {

    static {
        org.example.javachess.Application.NativeLibraries.loadOpenCv(); // no longer loaded at app start-up
    }

    private OrtEnvironment env;
    private OrtSession session;

    // Correct mapping from model metadata
    private String[] classNames = {
            "B", "K", "N", "P", "Q", "R",
            "b", "board", "k", "n", "p", "q", "r"
    };

    public PieceClassifier(String modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();

        // Try to load from file system first
        java.io.File modelFile = new java.io.File(modelPath);
        if (modelFile.exists()) {
            this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
        } else {
            // Try to load from resources (JAR)
            try (java.io.InputStream is = getClass().getResourceAsStream("/" + modelPath)) {
                if (is == null) {
                    throw new java.io.FileNotFoundException("Model not found in resources: " + modelPath);
                }
                // Extract to temp file
                java.io.File tempFile = java.io.File.createTempFile("onnx_model", ".onnx");
                tempFile.deleteOnExit();
                java.nio.file.Files.copy(is, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                this.session = env.createSession(tempFile.getAbsolutePath(), new OrtSession.SessionOptions());
                System.out.println("[Vision] Model extracted from JAR to: " + tempFile.getAbsolutePath());
            } catch (java.io.IOException e) {
                throw new OrtException(OrtException.OrtErrorCode.ORT_NO_SUCHFILE,
                        "Failed to extract model from JAR: " + e.getMessage());
            }
        }

        System.out.println("[Vision] Model loaded: " + modelPath);
        try {
            System.out.println("[Vision] Model Metadata: " + session.getMetadata().getCustomMetadata());
        } catch (Exception e) {
            System.out.println("[Vision] Could not read metadata.");
        }
    }

    // NEW: Use YOLO to find the board on the full screen
    public Rectangle findBoard(BufferedImage screen) {
        Mat src = null;
        Mat resized = null;
        try {
            // 1. Pre-process with Letterboxing
            src = bufferedImageToMat(screen);

            // Letterbox Logic
            int targetW = 640;
            int targetH = 640;
            double scale = Math.min((double) targetW / src.width(), (double) targetH / src.height());
            int newW = (int) (src.width() * scale);
            int newH = (int) (src.height() * scale);

            Mat scaled = new Mat();
            Imgproc.resize(src, scaled, new Size(newW, newH));

            // Create black canvas
            resized = new Mat(new Size(targetW, targetH), src.type(), new Scalar(0, 0, 0));

            // Center the image
            int offsetX = (targetW - newW) / 2;
            int offsetY = (targetH - newH) / 2;

            Mat roi = resized.submat(offsetY, offsetY + newH, offsetX, offsetX + newW);
            scaled.copyTo(roi);

            // Convert to Grayscale then to RGB
            Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(resized, resized, Imgproc.COLOR_GRAY2RGB);

            // 2. Inference
            float[] floatData = prepareInput(resized);
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData),
                    new long[] { 1, 3, 640, 640 });
                    OrtSession.Result result = session
                            .run(Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor))) {

                float[][][] output = (float[][][]) result.get(0).getValue();

                // 3. Parse for "board" class (ID 7)
                // INCREASED THRESHOLD: 0.75 for stricter detection
                List<Detection> detections = parseDetections(output[0], 0.90f);

                Detection bestBoard = null;
                double maxArea = 0;

                for (Detection d : detections) {
                    if (d.classId == 7) {
                        // GEOMETRY CHECK: Ignore non-square detections (AspectRatio must be ~1.0)
                        float ratio = d.w / d.h;
                        if (ratio < 0.90f || ratio > 1.10f) {
                            System.out.println(
                                    "[PieceClassifier] Ignored 'board' candidate with bad Aspect Ratio: " + ratio);
                            continue;
                        }

                        double area = d.w * d.h;
                        if (area > maxArea) {
                            maxArea = area;
                            bestBoard = d;
                        }
                    }
                }

                if (bestBoard != null) {
                    // Reverse Letterboxing to get original coordinates
                    // (x_detected - offsetX) / scale

                    int x = (int) ((bestBoard.x - offsetX) / scale);
                    int y = (int) ((bestBoard.y - offsetY) / scale);
                    int w = (int) (bestBoard.w / scale);
                    int h = (int) (bestBoard.h / scale);

                    // Clamp to screen bounds
                    x = Math.max(0, x);
                    y = Math.max(0, y);
                    w = Math.min(screen.getWidth() - x, w);
                    h = Math.min(screen.getHeight() - y, h);

                    return new Rectangle(x, y, w, h);
                }
            }

            return null;

        } catch (Exception e) {
            System.err.println("[PieceClassifier] findBoard CRITICAL ERROR: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (src != null)
                src.release();
            if (resized != null)
                resized.release();
        }
    }

    public static class VisionResult {
        public String fen;
        public boolean hasBoard;

        public VisionResult(String fen, boolean hasBoard) {
            this.fen = fen;
            this.hasBoard = hasBoard;
        }
    }

    public VisionResult getFenFromScreen(Rectangle boardRect, String debugPath) {
        try {
            // 1. Capture Board Area
            Robot robot = new Robot();
            BufferedImage boardCapture = robot.createScreenCapture(boardRect);
            return getFenFromImage(boardCapture, debugPath, false);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Overload for backward compatibility and VisionController
    public VisionResult getFenFromScreen(Rectangle boardRect) {
        return getFenFromScreen(boardRect, null);
    }

    public VisionResult getFenFromImage(BufferedImage boardCapture, String debugPath, boolean isFlipped) {
        Mat src = null;
        Mat resized = null;
        try {
            long start = System.currentTimeMillis();

            // 2. Pre-process
            src = bufferedImageToMat(boardCapture);
            resized = new Mat();
            Imgproc.resize(src, resized, new Size(640, 640));

            // Convert to Grayscale then to RGB (to match model training but keep 3
            // channels)
            Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(resized, resized, Imgproc.COLOR_GRAY2RGB);

            // 3. Inference
            float[] floatData = prepareInput(resized);
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData),
                    new long[] { 1, 3, 640, 640 });
                    OrtSession.Result result = session
                            .run(Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor))) {

                float[][][] output = (float[][][]) result.get(0).getValue();

                // 4. Parse Output
                List<Detection> detections = parseDetections(output[0], 0.6f); // Threshold 0.6

                long duration = System.currentTimeMillis() - start;

                // CHECK FOR BOARD PRESENCE
                // We assume that even in a cropped image, the model should detect the "board"
                // class
                // with at least some confidence if it's actually a board.
                // We lower the threshold slightly for this specific check to be safe.
                boolean hasBoard = detections.stream().anyMatch(d -> d.classId == 7);

                if (debugPath != null) {
                    System.out.println("[Vision] Inference Time: " + duration + "ms | Detections: " + detections.size()
                            + " | Flipped: " + isFlipped + " | HasBoard: " + hasBoard);

                    // Convert src to grayscale for debug to show what model saw
                    // Note: src is currently BGR.
                    Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2GRAY);
                    Imgproc.cvtColor(src, src, Imgproc.COLOR_GRAY2BGR); // Back to BGR for drawing colored boxes

                    drawDebug(src, detections, debugPath);
                }

                // 5. Generate FEN
                String fen = generateFen(detections, isFlipped);
                return new VisionResult(fen, hasBoard);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (src != null)
                src.release();
            if (resized != null)
                resized.release();
        }
    }

    // Backward compatibility
    public VisionResult getFenFromImage(BufferedImage boardCapture, String debugPath) {
        return getFenFromImage(boardCapture, debugPath, false);
    }

    private float[] prepareInput(Mat resized) {
        float[] floatData = new float[3 * 640 * 640];
        for (int y = 0; y < 640; y++) {
            for (int x = 0; x < 640; x++) {
                double[] pixel = resized.get(y, x);
                floatData[0 * 640 * 640 + y * 640 + x] = (float) (pixel[0] / 255.0);
                floatData[1 * 640 * 640 + y * 640 + x] = (float) (pixel[1] / 255.0);
                floatData[2 * 640 * 640 + y * 640 + x] = (float) (pixel[2] / 255.0);
            }
        }
        return floatData;
    }

    private void drawDebug(Mat src, List<Detection> detections, String path) {
        double scaleX = (double) src.width() / 640.0;
        double scaleY = (double) src.height() / 640.0;

        // Draw Grid
        double cellW = src.width() / 8.0;
        double cellH = src.height() / 8.0;
        for (int i = 1; i < 8; i++) {
            Imgproc.line(src, new Point(i * cellW, 0), new Point(i * cellW, src.height()), new Scalar(255, 0, 0), 1);
            Imgproc.line(src, new Point(0, i * cellH), new Point(src.width(), i * cellH), new Scalar(255, 0, 0), 1);
        }

        for (Detection d : detections) {
            double x = d.x * scaleX;
            double y = d.y * scaleY;
            double w = d.w * scaleX;
            double h = d.h * scaleY;

            Imgproc.rectangle(src, new Point(x, y), new Point(x + w, y + h), new Scalar(0, 255, 0), 2);

            String label = classNames[d.classId] + " (" + String.format("%.2f", d.score) + ")";
            Imgproc.putText(src, label, new Point(x, y - 5), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 255, 0),
                    2);
        }
        Imgcodecs.imwrite(path, src);
    }

    private List<Detection> parseDetections(float[][] output, float threshold) {
        int numClasses = output.length - 4;
        int anchors = output[0].length;

        List<Detection> detections = new ArrayList<>();

        for (int i = 0; i < anchors; i++) {
            float maxScore = 0;
            int classId = -1;

            for (int c = 0; c < numClasses; c++) {
                float score = output[4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    classId = c;
                }
            }

            if (maxScore > threshold) { // Increased threshold to 0.9
                float cx = output[0][i];
                float cy = output[1][i];
                float w = output[2][i];
                float h = output[3][i];

                float x = cx - w / 2;
                float y = cy - h / 2;

                detections.add(new Detection(classId, maxScore, x, y, w, h));
            }
        }
        return applyNMS(detections);
    }

    private List<Detection> applyNMS(List<Detection> detections) {
        detections.sort((a, b) -> Float.compare(b.score, a.score));
        List<Detection> result = new ArrayList<>();

        while (!detections.isEmpty()) {
            Detection best = detections.remove(0);
            result.add(best);
            detections.removeIf(d -> calculateIoU(best, d) > 0.45f);
        }
        return result;
    }

    private float calculateIoU(Detection a, Detection b) {
        float x1 = Math.max(a.x, b.x);
        float y1 = Math.max(a.y, b.y);
        float x2 = Math.min(a.x + a.w, b.x + b.w);
        float y2 = Math.min(a.y + a.h, b.y + b.h);

        if (x2 < x1 || y2 < y1)
            return 0;

        float intersection = (x2 - x1) * (y2 - y1);
        float areaA = a.w * a.h;
        float areaB = b.w * b.h;

        return intersection / (areaA + areaB - intersection);
    }

    private String generateFen(List<Detection> detections, boolean isFlipped) {
        Piece[][] grid = new Piece[8][8];
        float cellW = 640f / 8;
        float cellH = 640f / 8;

        for (Detection d : detections) {
            // Ignore board class (7)
            if (d.classId == 7)
                continue;

            int col = (int) ((d.x + d.w / 2) / cellW);
            int row = (int) ((d.y + d.h / 2) / cellH);

            if (col >= 0 && col < 8 && row >= 0 && row < 8) {
                if (grid[row][col] == null) {
                    grid[row][col] = getPieceFromClass(d.classId);
                }
            }
        }

        StringBuilder fen = new StringBuilder();

        if (!isFlipped) {
            // STANDARD ORIENTATION (White at Bottom)
            // Screen Top (Row 0) is Rank 8. Left (Col 0) is A.
            // Loop Row 0 -> 7. Loop Col 0 -> 7.
            for (int row = 0; row < 8; row++) {
                int empty = 0;
                for (int col = 0; col < 8; col++) {
                    if (grid[row][col] == null) {
                        empty++;
                    } else {
                        if (empty > 0) {
                            fen.append(empty);
                            empty = 0;
                        }
                        fen.append(grid[row][col].fenChar);
                    }
                }
                if (empty > 0)
                    fen.append(empty);
                if (row < 7)
                    fen.append("/");
            }
        } else {
            // FLIPPED ORIENTATION (Black at Bottom)
            // Screen Top (Row 0) is Rank 1. Left (Col 0) is H.
            // Screen Bottom (Row 7) is Rank 8. Right (Col 7) is A.
            // FEN expects Rank 8 first (A..H).
            // So start at Row 7 (Bottom).
            // In Row 7, A is at Col 7 (Right). H is at Col 0 (Left).
            // Loop Row 7 -> 0. Loop Col 7 -> 0.
            for (int row = 7; row >= 0; row--) {
                int empty = 0;
                for (int col = 7; col >= 0; col--) {
                    if (grid[row][col] == null) {
                        empty++;
                    } else {
                        if (empty > 0) {
                            fen.append(empty);
                            empty = 0;
                        }
                        fen.append(grid[row][col].fenChar);
                    }
                }
                if (empty > 0)
                    fen.append(empty);
                if (row > 0)
                    fen.append("/");
            }
        }

        // fen.append(" w KQkq - 0 1"); // REMOVED: Let Controller handle turn/castling
        return fen.toString();
    }

    private Piece getPieceFromClass(int classId) {
        // CORRECT MAPPING from Metadata:
        // 0: 'B', 1: 'K', 2: 'N', 3: 'P', 4: 'Q', 5: 'R' (White)
        // 6: 'b' (Black Bishop)
        // 7: 'board'
        // 8: 'k', 9: 'n', 10: 'p', 11: 'q', 12: 'r' (Black)

        switch (classId) {
            case 0:
                return new Piece('B');
            case 1:
                return new Piece('K');
            case 2:
                return new Piece('N');
            case 3:
                return new Piece('P');
            case 4:
                return new Piece('Q');
            case 5:
                return new Piece('R');
            case 6:
                return new Piece('b');
            case 7:
                return null; // board
            case 8:
                return new Piece('k');
            case 9:
                return new Piece('n');
            case 10:
                return new Piece('p');
            case 11:
                return new Piece('q');
            case 12:
                return new Piece('r');
            default:
                return null;
        }
    }

    private Mat bufferedImageToMat(BufferedImage bi) {
        Mat mat;
        if (bi.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage converted = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            converted.getGraphics().drawImage(bi, 0, 0, null);
            bi = converted;
        }
        mat = new Mat(bi.getHeight(), bi.getWidth(), CvType.CV_8UC3);
        byte[] data = ((DataBufferByte) bi.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, data);
        return mat;
    }

    static class Detection {
        int classId;
        float score;
        float x, y, w, h;

        public Detection(int classId, float score, float x, float y, float w, float h) {
            this.classId = classId;
            this.score = score;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    static class Piece {
        char fenChar;

        public Piece(char c) {
            this.fenChar = c;
        }
    }
}
