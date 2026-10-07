package org.example.javachess.Hardware;

import org.example.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real serial code ({@link SerialBoard}, jSerialComm) against the firmware emulator on a pseudo-terminal:
 * handshake, sensor events, coalesced LED frames with acknowledgements, unplug and replug.
 * Skipped where python3 or pseudo-terminals are not available (Windows).
 */
class SerialBoardEmulatorTest {

    @TempDir
    Path dir;
    private Process emulator;
    private PrintWriter emulatorInput;
    private Path log;

    private void startEmulator(Path link) throws Exception {
        log = dir.resolve("emulator.log");
        ProcessBuilder builder = new ProcessBuilder("python3", "firmware/emulator/board_emulator.py", "--link", link.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        emulator = builder.start();
        emulatorInput = new PrintWriter(new OutputStreamWriter(emulator.getOutputStream()), true);
        for (int i = 0; i < 100 && !Files.exists(link); i++) {
            Thread.sleep(50);
        }
        assertTrue(Files.exists(link), "emulator did not start");
    }

    private void stopEmulator() throws InterruptedException {
        if (emulator != null && emulator.isAlive()) {
            List<ProcessHandle> children = emulator.descendants().toList(); // python3 may be a launcher shim
            emulatorInput.println("quit");
            if (!emulator.waitFor(3, TimeUnit.SECONDS)) {
                emulator.destroyForcibly();
                emulator.waitFor(2, TimeUnit.SECONDS);
            }
            children.forEach(ProcessHandle::destroyForcibly);
        }
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        stopEmulator();
    }

    @Test
    void handshakeEventsFramesAndHotReplug() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "needs pseudo-terminals");
        assumeTrue(new File("firmware/emulator/board_emulator.py").exists());
        try {
            assumeTrue(new ProcessBuilder("python3", "--version").start().waitFor() == 0, "needs python3");
        } catch (java.io.IOException e) {
            assumeTrue(false, "needs python3");
        }
        Path link = dir.resolve("fakeboard");
        startEmulator(link);

        List<String> events = new CopyOnWriteArrayList<>();
        BlockingQueue<Boolean> connection = new LinkedBlockingQueue<>();
        // pseudo-terminals ignore the speed but macOS rejects non-standard ones on them
        SerialBoard board = new SerialBoard(link.toString(), 115200);
        LedRenderer leds = new LedRenderer(board, LedMapping.DEFAULT);
        MoveLeds moveLeds = new MoveLeds(leds);
        try {
            board.start(new BoardHardware.SensorListener() {
                @Override
                public void onSquareChanged(int square, boolean occupied) {
                    events.add((occupied ? "+" : "-") + Squares.name(square));
                }

                @Override
                public void onOccupancy(long occupied) {
                    events.add("B" + Long.toHexString(occupied));
                }

                @Override
                public void onConnectionChanged(boolean connected, String description) {
                    connection.add(connected);
                }
            });
            assertEquals(Boolean.TRUE, connection.poll(10, TimeUnit.SECONDS), "handshake");
            Thread.sleep(300);
            assertTrue(events.contains("Bffff00000000ffff"), events.toString());

            emulatorInput.println("move e2e4");
            Thread.sleep(300);
            assertTrue(events.containsAll(List.of("-E2", "+E4")), events.toString());

            moveLeds.showCandidate("e2", "e4", MoveClassification.GOOD);
            Thread.sleep(200);
            long acked = board.acknowledgedCommands();
            assertTrue(acked >= 1, "first frame acknowledged");
            for (int i = 0; i < 200; i++) {
                moveLeds.showCandidate("g1", i % 2 == 0 ? "f3" : "h3", MoveClassification.values()[i % 5]);
            }
            Thread.sleep(500);
            long burstFrames = board.acknowledgedCommands() - acked;
            assertTrue(burstFrames >= 1 && burstFrames <= 5, "200 updates -> " + burstFrames + " serial frames");

            // unplug: detected (heartbeat stops), LEDs changed meanwhile, replug: reconnected and LEDs restored
            stopEmulator();
            assertEquals(Boolean.FALSE, connection.poll(10, TimeUnit.SECONDS), "unplug detected");
            assertFalse(board.isConnected());
            leds.set(LedRenderer.Layer.BASE, Squares.parse("a1"), 0x123456);
            startEmulator(link);
            assertEquals(Boolean.TRUE, connection.poll(15, TimeUnit.SECONDS), "replug detected");
            Thread.sleep(500);
            String received = Files.readString(log);
            assertTrue(received.contains("<- F123456"), "full frame with the current LEDs after replug:\n" + received);
        } finally {
            leds.shutdown();
            board.close();
        }
    }
}
