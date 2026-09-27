package org.example.javachess.Vision;

import javafx.application.Platform;
import org.example.javachess.Controllers.ArduinoController;
import org.example.javachess.Services.BoardStateManager;

import java.awt.Rectangle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VisionController {

    private BoardDetector detector;
    private PieceClassifier classifier;
    private MoveExecutor executor;
    private Rectangle boardRect;

    private ScheduledExecutorService visionLoop;
    private boolean isRunning = false;

    private String lastFen = "";

    public VisionController() {
        try {
            this.detector = new BoardDetector();
            // Assumes model is at project root/models/best.onnx
            this.classifier = new PieceClassifier("models/best.onnx");
            this.executor = new MoveExecutor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void start() {
        if (isRunning)
            return;
        isRunning = true;

        System.out.println("[Vision] Starting Vision Controller...");

        // 1. Initial Board Detection
        this.boardRect = detector.detectBoard();
        if (boardRect == null) {
            System.err.println("[Vision] FATAL: Could not find board on screen.");
            return;
        }
        System.out.println("[Vision] Board locked at: " + boardRect);

        // 2. Start Monitoring Loop
        this.visionLoop = Executors.newSingleThreadScheduledExecutor();
        this.visionLoop.scheduleWithFixedDelay(this::visionTick, 0, 500, TimeUnit.MILLISECONDS);

        // 3. Listen to Physical Board (Arduino)
        // When user moves physically -> We move on screen
        ArduinoController.getInstance().getBoardStateManager().setListener(new BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                System.out.println("[Vision] Physical Move Detected: " + from + to);
                // Execute on Screen
                executor.makeMove(from, to, boardRect);
            }

            @Override
            public void onBoardSetupComplete() {
            }

            @Override
            public void onSetupProgress(String message) {
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
            }

            @Override
            public void onBotMoveReplicated() {
            }
        });
    }

    private void visionTick() {
        if (!isRunning || boardRect == null)
            return;

        // 1. Get FEN from Screen
        // --- FIX START ---
            PieceClassifier.VisionResult result = classifier.getFenFromScreen(boardRect);
            String currentFen = (result != null) ? result.fen : null;
            // --- FIX END ---

        if (currentFen != null && !currentFen.equals(lastFen)) {
            System.out.println("[Vision] Screen FEN Changed: " + currentFen);
            lastFen = currentFen;

            // TODO: Sync back to Physical Board?
            // If screen changes (opponent move), we should tell BoardStateManager?
            // But BoardStateManager expects "Bot Move Replication".
            // We need to parse the move from FEN difference.
            // For now, just logging.
        }
    }

    public void stop() {
        isRunning = false;
        if (visionLoop != null)
            visionLoop.shutdown();
    }

    // Main for standalone testing
    public static void main(String[] args) {
        VisionController vc = new VisionController();
        vc.start();

        // Keep alive
        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {
        }
        vc.stop();
    }
}
