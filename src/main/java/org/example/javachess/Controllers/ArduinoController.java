package org.example.javachess.Controllers;

import com.fazecast.jSerialComm.SerialPort;
import javafx.application.Platform;
import org.example.javachess.Services.BoardStateManager;

import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class ArduinoController {

    private static ArduinoController instance;
    private SerialPort serialPort;
    private BoardStateManager boardStateManager;
    private ExecutorService serialReaderExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService serialWriterExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isRunning = false;

    private ArduinoController() {
        boardStateManager = new BoardStateManager();
        connect();
    }

    public static synchronized ArduinoController getInstance() {
        if (instance == null) {
            instance = new ArduinoController();
        }
        return instance;
    }

    public BoardStateManager getBoardStateManager() {
        return boardStateManager;
    }

    private void connect() {
        SerialPort[] ports = SerialPort.getCommPorts();
        System.out.println("Available Serial Ports:");
        for (SerialPort port : ports) {
            System.out.println("- " + port.getSystemPortName() + " (" + port.getDescriptivePortName() + ")");
        }

        // Try to find the Arduino port (adjust name as needed)
        // User previously used "/dev/cu.usbmodem1101"
        String targetPortName = "/dev/cu.usbmodem1101";

        for (SerialPort port : ports) {
            // Simple auto-detection logic or fallback to hardcoded
            if (port.getSystemPortName().equals(targetPortName) || port.getDescriptivePortName().contains("Arduino")) {
                serialPort = port;
                break;
            }
        }

        if (serialPort == null && ports.length > 0) {
            // Fallback to first port if specific one not found (risky but helpful for
            // debug)
            serialPort = ports[0];
            System.out.println("Target port not found, trying first available: " + serialPort.getSystemPortName());
        }

        if (serialPort != null) {
            serialPort.setBaudRate(115200);
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);

            if (serialPort.openPort()) {
                System.out.println("Connected to Arduino on " + serialPort.getSystemPortName());
                isRunning = true;
                startReading();

                // Request initial state
                sendCommand("R");
            } else {
                System.err.println("Failed to open serial port.");
            }
        } else {
            System.err.println("No suitable serial port found.");
        }
    }

    private void startReading() {
        serialReaderExecutor.submit(() -> {
            try (InputStream in = serialPort.getInputStream();
                    Scanner scanner = new Scanner(in)) {

                while (isRunning && scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (!line.isEmpty()) {
                        processSerialLine(line);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void processSerialLine(String line) {
        // Protocol: +A1 (Place), -A1 (Remove)
        System.out.println("[Arduino] " + line);

        if (line.startsWith("+")) {
            String square = line.substring(1);
            boardStateManager.updateSquare(square, true);
        } else if (line.startsWith("-")) {
            String square = line.substring(1);
            boardStateManager.updateSquare(square, false);
        } else if (line.equals("READY")) {
            System.out.println("Arduino is READY.");
        }
    }

    public synchronized void sendCommand(String cmd) {
        if (serialPort != null && serialPort.isOpen()) {
            serialWriterExecutor.submit(() -> {
                byte[] bytes = (cmd + "\n").getBytes();
                serialPort.writeBytes(bytes, bytes.length);
            });
        }
    }

    // --- LED CONTROL ---
    public void sendLedCommand(String square, int r, int g, int b) {
        // Format: L:A1:255:0:0
        String cmd = String.format("L:%s:%d:%d:%d", square, r, g, b);
        sendCommand(cmd);
    }

    public void sendLedBatch(java.util.Map<String, int[]> ledCommands) {
        if (serialPort != null && serialPort.isOpen()) {
            serialWriterExecutor.submit(() -> {
                try {
                    for (java.util.Map.Entry<String, int[]> entry : ledCommands.entrySet()) {
                        String square = entry.getKey();
                        int[] rgb = entry.getValue();
                        String cmd = String.format("P:%s:%d:%d:%d\n", square, rgb[0], rgb[1], rgb[2]);
                        serialPort.writeBytes(cmd.getBytes(), cmd.length());
                        Thread.sleep(10); // Small delay between commands in batch
                    }
                    String showCmd = "S\n";
                    serialPort.writeBytes(showCmd.getBytes(), showCmd.length());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    public void clearLeds() {
        sendCommand("C");
    }

    public void flashLed(String square, int r, int g, int b, int count) {
        new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    sendLedCommand(square, r, g, b);
                    Thread.sleep(300);
                    sendLedCommand(square, 0, 0, 0); // Off
                    Thread.sleep(300);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void playVictoryAnimation() {
        new Thread(() -> {
            try {
                System.out.println("[Arduino] Playing Victory Animation (Cyber Wave)!");

                // Colors: White, Cyan (Sky Blue), Fuchsia
                int[][] palette = {
                        { 255, 255, 255 }, // White
                        { 0, 191, 255 }, // Deep Sky Blue (more elegant Cyan)
                        { 255, 0, 255 } // Fuchsia
                };

                // Number of waves
                for (int wave = 0; wave < 3; wave++) {
                    // Diagonal sweep from A1 (0,0) to H8 (7,7)
                    // Sum of indices x+y ranges from 0 to 14
                    for (int k = 0; k <= 14; k++) {
                        java.util.Map<String, int[]> batch = new java.util.HashMap<>();

                        // Calculate color for this wavefront
                        int[] color = palette[wave % palette.length];

                        // Light up diagonal k
                        for (int col = 0; col < 8; col++) {
                            int row = k - col;
                            if (row >= 0 && row < 8) {
                                String sq = getSquareString(col, row);
                                batch.put(sq, color);
                            }
                        }

                        // Fade out the diagonal behind (k-2) to create a "band" effect of width 2
                        if (k >= 2) {
                            for (int col = 0; col < 8; col++) {
                                int row = (k - 2) - col;
                                if (row >= 0 && row < 8) {
                                    String sq = getSquareString(col, row);
                                    // Turn off
                                    batch.put(sq, new int[] { 0, 0, 0 });
                                }
                            }
                        }

                        sendLedBatch(batch);
                        Thread.sleep(70); // Smooth wave speed
                    }

                    // Clear the remaining tail at the end of the wave
                    for (int k = 13; k <= 16; k++) { // Clean up last few diagonals
                        java.util.Map<String, int[]> batch = new java.util.HashMap<>();
                        for (int col = 0; col < 8; col++) {
                            int row = (k - 2) - col;
                            if (row >= 0 && row < 8) {
                                String sq = getSquareString(col, row);
                                batch.put(sq, new int[] { 0, 0, 0 });
                            }
                        }
                        if (!batch.isEmpty()) {
                            sendLedBatch(batch);
                            Thread.sleep(70);
                        }
                    }
                }

                // Final flush
                Thread.sleep(500);
                clearLeds();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private String getSquareString(int col, int row) {
        char file = (char) ('A' + col);
        int rank = row + 1;
        return "" + file + rank;
    }

    public void stop() {
        isRunning = false;
        serialReaderExecutor.shutdownNow();
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            System.out.println("Arduino disconnected.");
        }
    }
}
