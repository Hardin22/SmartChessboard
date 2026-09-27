package org.example.javachess.Services;

import org.example.javachess.Vision.BotMover;
import org.example.javachess.Vision.PieceClassifier;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

public class VisionService {

    private PieceClassifier classifier;
    // private BotMover botMover;
    private volatile boolean running = false;
    private Rectangle boardRect = null;
    private Rectangle candidateRect = null;
    private int boardStabilityCount = 0;
    private Thread scanThread;

    private Consumer<String> onFenChanged;
    private Consumer<Rectangle> onBoardFound;

    public VisionService() {
        try {
            // Initialize components
            this.classifier = new PieceClassifier("models/best.onnx");
            // this.botMover = new BotMover(); // Removed

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setOnFenChanged(Consumer<String> callback) {
        this.onFenChanged = callback;
    }

    public void setOnBoardFound(Consumer<Rectangle> callback) {
        this.onBoardFound = callback;
    }

    public void startScanning() {
        if (running)
            return;
        running = true;

        scanThread = new Thread(this::scanLoop);
        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[VisionService] Scanning started.");
    }

    public void stopScanning() {
        running = false;
        if (scanThread != null) {
            try {
                scanThread.join(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("[VisionService] Scanning stopped.");
    }

    // executeMove removed as BotMover now requires CefBrowser injection

    private volatile boolean isFlipped = false;

    public void setFlipped(boolean flipped) {
        this.isFlipped = flipped;
        System.out.println("[VisionService] Orientation set to: " + (flipped ? "FLIPPED (Black)" : "STANDARD (White)"));
    }

    /**
     * Hard reset of the vision state.
     * Clears any locked board rectangle and forces a full-screen search.
     * Call this when navigating away or disabling vision.
     */
    public void resetState() {
        this.boardRect = null;
        this.candidateRect = null;
        this.boardStabilityCount = 0;
        System.out.println("[VisionService] State HARD RESET via Manual Trigger.");
    }

    // ...

    private void scanLoop() {
        String lastSeenFen = "";
        String lastNotifiedFen = "";
        int stabilityCount = 0;
        int lostBoardFrames = 0; // NEW: Counter for lost board detection
        BufferedImage lastImage = null;

        Robot robot = null;
        try {
            robot = new Robot();
        } catch (Exception e) {
            System.err.println("[VisionService] Failed to create Robot: " + e.getMessage());
            running = false;
            return;
        }

        while (running) {
            try {
                // Phase A: Find Board
                if (boardRect == null) {
                    lostBoardFrames = 0; // Reset counter
                    java.awt.Rectangle screenRect = new java.awt.Rectangle(
                            java.awt.Toolkit.getDefaultToolkit().getScreenSize());
                    BufferedImage screen = robot.createScreenCapture(screenRect);

                    // DEBUG: Save the first screenshot to check what the bot sees
                    try {
                        String desktopPath = System.getProperty("user.home") + "/Desktop/debug_screen.png";
                        java.io.File debugFile = new java.io.File(desktopPath);
                        if (!debugFile.exists()) {
                            javax.imageio.ImageIO.write(screen, "png", debugFile);
                            System.out.println(
                                    "[VisionService] DEBUG: Saved screen dump to " + debugFile.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        System.out.println("[VisionService] Failed to save debug screenshot: " + e.getMessage());
                    }

                    Rectangle found = classifier.findBoard(screen);

                    if (found != null) {
                        // STABILITY CHECK: Don't lock immediately. Verify consistency.
                        if (candidateRect != null && isSimilarRect(candidateRect, found)) {
                            boardStabilityCount++;
                            System.out
                                    .println("[VisionService] Board Candidate Stable (" + boardStabilityCount + "/5)");
                        } else {
                            candidateRect = found;
                            boardStabilityCount = 1;
                            System.out.println("[VisionService] New Board Candidate Found: " + found);
                        }

                        // LOCK CONDITION: 5 Consecutive Stable Frames
                        if (boardStabilityCount >= 5) {
                            boardRect = candidateRect;
                            System.out.println("[VisionService] Board LOCKED at: " + boardRect);
                            if (onBoardFound != null)
                                onBoardFound.accept(boardRect);
                        }
                    } else {
                        // Reset if we lose the board completely
                        boardStabilityCount = 0;
                        candidateRect = null;

                        System.out.println("[VisionService] Searching for board... (Screen Size: " + screen.getWidth()
                                + "x" + screen.getHeight() + ")");
                        Thread.sleep(1000);
                    }
                }
                // Phase B: Monitor Board
                else {
                    // Phase B: Monitor Board with Pixel-Perfect Motion Detection
                    BufferedImage currentImage = robot.createScreenCapture(boardRect);

                    // DEBUG: Save the CROPPED board to check alignment
                    try {
                        String cropPath = System.getProperty("user.home") + "/Desktop/debug_board_crop.png";
                        java.io.File debugCrop = new java.io.File(cropPath);
                        // Save only if it's the first time or every 5 seconds (to avoid lag)?
                        // For now, just overwrite to see the LATEST state.
                        javax.imageio.ImageIO.write(currentImage, "png", debugCrop);
                    } catch (Exception e) {
                        System.out.println("[VisionService] Debug Crop Save Failed");
                    }

                    boolean isMoving = false;
                    if (lastImage != null) {
                        isMoving = hasImageChanged(lastImage, currentImage);
                    }

                    if (isMoving) {
                        // ANIMATION DETECTED - IGNORE FRAME
                        stabilityCount = 0; // Reset stability
                        lastImage = currentImage; // Update last image to detect when motion stops
                        lostBoardFrames = 0; // Motion implies activity, assume board is somewhat valid or transitioning
                    } else {
                        // STATIC IMAGE - SAFE TO CLASSIFY
                        PieceClassifier.VisionResult result = classifier.getFenFromImage(currentImage, null, isFlipped);

                        if (result != null) {
                            // CHECK FOR BOARD PRESENCE
                            if (!result.hasBoard) {
                                lostBoardFrames++;

                                // FORCE RESET IF BOARD IS LOST FOR > 30 FRAMES (approx 1s)
                                // Let's check the context.
                                // I will replace the WHOLE scanLoop method to be safe and add the variable.
                            }

                            if (result.hasBoard) {
                                String currentFen = result.fen;
                                if (currentFen.equals(lastSeenFen)) {
                                    stabilityCount++;
                                } else {
                                    stabilityCount = 0;
                                    lastSeenFen = currentFen;
                                }

                                // Aggressive Threshold: 2 frames of identical static image = Valid
                                if (stabilityCount >= 2) {
                                    if (!currentFen.equals(lastNotifiedFen)) {
                                        System.out.println("[VisionService] FEN STABLE (Static): " + currentFen);
                                        lastNotifiedFen = currentFen;
                                        if (onFenChanged != null)
                                            onFenChanged.accept(currentFen);
                                    }
                                }
                            }
                        }
                        // Keep lastImage for next comparison
                        lastImage = currentImage;
                    }

                    Thread.sleep(30); // 33fps scan rate
                }
            } catch (Exception e) {
                System.err.println("[VisionService] Error in scan loop: " + e.getMessage());
                boardRect = null; // Reset on error
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                }
            }
        }
    }

    public Rectangle getBoardRect() {
        return boardRect;
    }

    private boolean hasImageChanged(BufferedImage img1, BufferedImage img2) {
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight())
            return true;

        // Fast Pixel Comparison
        // We don't need to check every single pixel. Checking a grid or random sample
        // is faster.
        // But for 640x640, checking all is fast enough in Java.
        // Let's check center and corners first for speed.

        int w = img1.getWidth();
        int h = img1.getHeight();

        // Check center pixel
        if (img1.getRGB(w / 2, h / 2) != img2.getRGB(w / 2, h / 2))
            return true;

        // Check stride (e.g., every 10th pixel) to be super fast
        for (int y = 0; y < h; y += 10) {
            for (int x = 0; x < w; x += 10) {
                if (img1.getRGB(x, y) != img2.getRGB(x, y))
                    return true;
            }
        }

        return false;
    }

    private boolean isSimilarRect(Rectangle r1, Rectangle r2) {
        // Calculate Intersection over Union (IoU)
        int x1 = Math.max(r1.x, r2.x);
        int y1 = Math.max(r1.y, r2.y);
        int x2 = Math.min(r1.x + r1.width, r2.x + r2.width);
        int y2 = Math.min(r1.y + r1.height, r2.y + r2.height);

        if (x2 < x1 || y2 < y1)
            return false;

        double intersection = (long) (x2 - x1) * (y2 - y1);
        double area1 = (long) r1.width * r1.height;
        double area2 = (long) r2.width * r2.height;
        double union = area1 + area2 - intersection;

        double iou = intersection / union;
        return iou > 0.90; // 90% Overlap required
    }
}
