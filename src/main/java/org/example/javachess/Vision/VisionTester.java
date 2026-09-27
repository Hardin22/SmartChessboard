package org.example.javachess.Vision;

import java.awt.Rectangle;
import java.util.Scanner;

public class VisionTester {

    // Shared state
    private static volatile Rectangle boardRect = null;
    private static volatile boolean running = true;

    static {
        try {
            nu.pattern.OpenCV.loadLocally();
            System.out.println("[Vision] OpenCV loaded successfully.");
        } catch (Throwable e) {
            System.err.println("[Vision] Failed to load OpenCV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.out.println("=== VISION MODULE TESTER & BOT MOVER ===");

        try {
            // 1. Initialize Components
            PieceClassifier classifier = new PieceClassifier("models/best.onnx");
            // BotMover mover = new BotMover();

            // 2. Start Vision Thread
            Thread visionThread = new Thread(() -> {
                runVisionLoop(classifier);
            });
            visionThread.start();

            // 3. Main Input Loop
            Scanner scanner = new Scanner(System.in);
            System.out.println("Ready! Type a move (e.g., 'e2e4') or 'exit' to quit.");

            while (running) {
                if (scanner.hasNextLine()) {
                    String input = scanner.nextLine().trim();

                    if (input.equalsIgnoreCase("exit")) {
                        running = false;
                        break;
                    }

                    if (input.length() == 4) {
                        System.out.println("[Bot] BotMover is disabled in CLI mode (requires Browser).");
                    } else {
                        System.out.println("[Bot] Invalid input. Use format 'e2e4'.");
                    }
                }
                Thread.sleep(100);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runVisionLoop(PieceClassifier classifier) {
        System.out.println("[Vision] Starting background scan...");

        // Ensure debug dir exists
        java.io.File debugDir = new java.io.File("debug_captures");
        if (!debugDir.exists())
            debugDir.mkdirs();

        String lastFen = "";

        while (running) {
            try {
                // Phase A: Search for Board
                if (boardRect == null) {
                    // Capture full screen
                    java.awt.Robot robot = new java.awt.Robot();
                    java.awt.Rectangle screenRect = new java.awt.Rectangle(
                            java.awt.Toolkit.getDefaultToolkit().getScreenSize());
                    java.awt.image.BufferedImage screen = robot.createScreenCapture(screenRect);

                    Rectangle found = classifier.findBoard(screen);
                    if (found != null) {
                        boardRect = found;
                        System.out.println("[Vision] Board LOCKED at: " + boardRect);
                    } else {
                        Thread.sleep(2000); // Retry every 2s
                    }
                }
                // Phase B: Monitor Board
                else {
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    String debugPath = "debug_captures/scan_" + timestamp + ".png";

                    // --- FIX START ---
                    PieceClassifier.VisionResult result = classifier.getFenFromScreen(boardRect, debugPath);
                    String fen = (result != null) ? result.fen : null;


                    if (fen != null) {
                        if (!fen.equals(lastFen)) {
                            System.out.println("[Monitor] FEN CHANGED: " + fen);
                            lastFen = fen;
                        } else {
                            // Delete duplicate debug image to save space
                            new java.io.File(debugPath).delete();
                        }
                    } else {
                        // If FEN is null, maybe board was lost?
                        // For now, keep retrying.
                    }

                    Thread.sleep(500); // Scan rate
                }

            } catch (Exception e) {
                System.err.println("[Vision] Error: " + e.getMessage());
                boardRect = null; // Reset on error
            }
        }
        System.out.println("[Vision] Thread stopped.");
    }
}
