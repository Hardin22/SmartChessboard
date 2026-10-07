package org.example.javachess.Hardware;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The real board on a USB serial port (Arduino running firmware/smartboard).
 *
 * <ul>
 *   <li>Finds the port by itself ({@code /dev/ttyACM0} on the Pi, {@code cu.usbmodem*} on a Mac...) unless
 *       {@code board.port} is set, and confirms it by waiting for a valid protocol line.</li>
 *   <li>Reconnects on its own when the cable is unplugged and plugged back in, or when the heartbeat stops.</li>
 *   <li>LED frames are coalesced: only the newest frame is sent, as a delta against what the firmware already
 *       shows, and the next command waits for the firmware's {@code K} (the WS2812 update disables the
 *       Arduino's interrupts, so bytes sent during it would be lost).</li>
 * </ul>
 * All work happens on two daemon threads; no method blocks the caller.
 */
public final class SerialBoard implements BoardHardware {

    private static final Logger log = LoggerFactory.getLogger(SerialBoard.class);

    private static final Pattern LIKELY_BOARD_NAME = Pattern.compile(
            "(?i)(ttyACM\\d+|ttyUSB\\d+|cu\\.usbmodem.*|cu\\.usbserial.*|cu\\.wchusbserial.*|cu\\.SLAB_USBtoUART.*|COM\\d+)");
    private static final Pattern LIKELY_BOARD_DESCRIPTION = Pattern.compile("(?i).*(arduino|ch34\\d|cp210|ftdi|usb.?serial).*");
    private static final Pattern NEVER_BOARD = Pattern.compile("(?i).*(bluetooth|debug-console|^tty\\.).*");

    private static final long HANDSHAKE_TIMEOUT_MS = 3500;
    private static final long HELLO_RETRY_MS = 1800;
    private static final long RESCAN_DELAY_MS = 3000;
    private static final long HEARTBEAT_TIMEOUT_MS = 4500;
    private static final long ACK_TIMEOUT_MS = 250;

    private final String configuredPort;
    private final int baudRate;

    private final ReentrantLock txLock = new ReentrantLock();
    private final Condition txSignal = txLock.newCondition();
    /** Newest frame requested by the app (wire order); kept to restore the LEDs after a reconnection. */
    private int[] requestedFrame;
    /** Frame the firmware is known to show; null when unknown (forces a full frame). */
    private int[] confirmedFrame;
    private int[] inFlightFrame;
    private long inFlightSince;
    private boolean frameDirty;
    private int requestedBrightness = -1;
    private long inFlightSinceNanos;
    private volatile long maxRoundTripNanos;
    private volatile long acknowledged;

    private volatile SensorListener listener;
    private volatile SerialPort port;
    private volatile boolean connected;
    private volatile boolean legacyFirmware;
    private volatile boolean running;
    private volatile long lastLineAt;
    private Thread readerThread;
    private Thread writerThread;

    /**
     * @param configuredPort port name or path to use, or null/blank to detect it
     * @param baudRate       serial speed; must match the firmware (BAUD_RATE in smartboard.ino)
     */
    public SerialBoard(String configuredPort, int baudRate) {
        this.configuredPort = configuredPort == null || configuredPort.isBlank() ? null : configuredPort.trim();
        this.baudRate = baudRate;
    }

    @Override
    public synchronized void start(SensorListener listener) {
        if (running) {
            return;
        }
        this.listener = listener;
        running = true;
        readerThread = Thread.ofPlatform().daemon().name("board-serial-rx").start(this::connectionLoop);
        writerThread = Thread.ofPlatform().daemon().name("board-serial-tx").start(this::writerLoop);
    }

    @Override
    public void sendFrame(int[] wireFrame) {
        txLock.lock();
        try {
            requestedFrame = wireFrame.clone();
            frameDirty = true;
            txSignal.signalAll();
        } finally {
            txLock.unlock();
        }
    }

    @Override
    public void setBrightness(int value) {
        txLock.lock();
        try {
            requestedBrightness = Math.max(0, Math.min(255, value));
            txSignal.signalAll();
        } finally {
            txLock.unlock();
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String description() {
        SerialPort p = port;
        return p != null && connected ? p.getSystemPortName() + (legacyFirmware ? " (old firmware)" : "") : "not connected";
    }

    @Override
    public void close() {
        flush(400);
        running = false;
        txLock.lock();
        try {
            txSignal.signalAll();
        } finally {
            txLock.unlock();
        }
        SerialPort p = port;
        if (p != null) {
            p.closePort();
        }
        joinQuietly(readerThread);
        joinQuietly(writerThread);
    }

    /** Waits until the last requested frame has been acknowledged (e.g. LEDs off before exit). */
    private void flush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        txLock.lock();
        try {
            while (connected && !legacyFirmware && (frameDirty || inFlightSince != 0)) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                txSignal.await(left, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            txLock.unlock();
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- connection / reader -------------------------------------------------------------------------------

    private void connectionLoop() {
        while (running) {
            try {
                connectOnce();
            } catch (RuntimeException e) {
                log.warn("Serial connection error: {}", e.toString());
                connected = false;
                sleep(RESCAN_DELAY_MS);
            }
        }
    }

    private boolean announcedMissing;
    private String lastOpenFailure;

    /** Finds the board, then reads from it until it goes away. Returns to be called again. */
    private void connectOnce() {
        SerialPort candidatePort = null;
        for (SerialPort candidate : candidatePorts()) {
            if (!running) {
                return;
            }
            if (handshake(candidate)) {
                candidatePort = candidate;
                break;
            }
        }
        if (candidatePort == null) {
            if (!announcedMissing) {
                log.info("No chessboard found on the serial ports; running without hardware (retrying every {} s)",
                        RESCAN_DELAY_MS / 1000);
                announcedMissing = true;
            }
            sleep(RESCAN_DELAY_MS);
            return;
        }
        announcedMissing = false;
        onConnected(candidatePort);
        try {
            readUntilDisconnected(candidatePort);
        } finally {
            onDisconnected(candidatePort);
        }
    }

    private List<SerialPort> candidatePorts() {
        SerialPort[] ports;
        try {
            ports = SerialPort.getCommPorts();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.warn("Serial ports unavailable: {}", e.toString());
            return List.of();
        }
        List<PortInfo> infos = new ArrayList<>();
        for (SerialPort p : ports) {
            infos.add(new PortInfo(p.getSystemPortName(), p.getDescriptivePortName(), p.getSystemPortPath()));
        }
        List<PortInfo> ranked = rankPorts(infos, configuredPort);
        List<SerialPort> result = new ArrayList<>();
        for (PortInfo info : ranked) {
            for (SerialPort p : ports) {
                if (p.getSystemPortName().equals(info.name())) {
                    result.add(p);
                }
            }
        }
        if (configuredPort != null && result.isEmpty()) {
            // not enumerated (pseudo-terminal, udev symlink such as /dev/serial/by-id/...): open it directly
            try {
                result.add(SerialPort.getCommPort(resolveSymlink(configuredPort)));
            } catch (RuntimeException e) {
                log.debug("Port {} not available: {}", configuredPort, e.toString());
            }
        }
        return result;
    }

    private static String resolveSymlink(String port) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(port);
            return java.nio.file.Files.exists(path) ? path.toRealPath().toString() : port;
        } catch (java.io.IOException | RuntimeException e) {
            return port;
        }
    }

    /** Port name, description and path as reported by the OS. */
    public record PortInfo(String name, String description, String path) {
    }

    /**
     * Orders the ports that may be the board, best first. With a configured port only that one is returned.
     * Bluetooth, debug consoles and macOS "tty." duplicates of "cu." ports are never chosen automatically.
     */
    public static List<PortInfo> rankPorts(List<PortInfo> ports, String configuredPort) {
        if (configuredPort != null) {
            return ports.stream()
                    .filter(p -> p.name().equals(configuredPort) || configuredPort.equals(p.path())
                            || ("/dev/" + p.name()).equals(configuredPort))
                    .toList();
        }
        return ports.stream()
                .filter(p -> !NEVER_BOARD.matcher(p.name()).matches() && !NEVER_BOARD.matcher(p.description()).matches())
                .filter(p -> LIKELY_BOARD_NAME.matcher(p.name()).matches()
                        || LIKELY_BOARD_DESCRIPTION.matcher(p.description()).matches())
                .sorted(Comparator.comparingInt((PortInfo p) ->
                        p.description().toLowerCase(Locale.ROOT).contains("arduino") ? 0 : 1).thenComparing(PortInfo::name))
                .toList();
    }

    private boolean handshake(SerialPort candidate) {
        candidate.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
        if (!candidate.openPort()) {
            String failure = candidate.getSystemPortName() + " (error " + candidate.getLastErrorCode() + ")";
            if (!failure.equals(lastOpenFailure)) {
                log.info("Cannot open {} at {} baud", failure, baudRate);
                lastOpenFailure = failure;
            }
            return false;
        }
        lastOpenFailure = null;
        legacyFirmware = false;
        LineReader reader = new LineReader();
        long start = System.currentTimeMillis();
        long nextHello = start;
        byte[] buffer = new byte[256];
        while (running && System.currentTimeMillis() - start < HANDSHAKE_TIMEOUT_MS) {
            if (System.currentTimeMillis() >= nextHello) {
                write(candidate, BoardProtocol.command("?"));
                nextHello += HELLO_RETRY_MS;
            }
            int n = candidate.readBytes(buffer, buffer.length);
            if (n < 0) {
                break;
            }
            for (String line : reader.feed(buffer, n)) {
                BoardProtocol.Message message = BoardProtocol.parse(line);
                if (message != null) {
                    if (message instanceof BoardProtocol.Hello hello) {
                        log.info("Chessboard firmware {} (protocol {}) on {}", hello.firmware(), hello.protocol(),
                                candidate.getSystemPortName());
                    } else if (message instanceof BoardProtocol.Legacy || message instanceof BoardProtocol.SquareChange) {
                        legacyFirmware = !line.contains("*");
                    }
                    port = candidate;
                    dispatch(message);
                    return true;
                }
            }
        }
        candidate.closePort();
        return false;
    }

    private void onConnected(SerialPort p) {
        connected = true;
        lastLineAt = System.currentTimeMillis();
        if (legacyFirmware) {
            log.warn("Board on {} runs the old firmware: sensors work, LEDs need firmware/smartboard (protocol {})",
                    p.getSystemPortName(), BoardProtocol.VERSION);
        }
        write(p, BoardProtocol.command("R"));
        txLock.lock();
        try {
            confirmedFrame = null;
            inFlightFrame = null;
            frameDirty = requestedFrame != null;
            txSignal.signalAll();
        } finally {
            txLock.unlock();
        }
        SensorListener l = listener;
        if (l != null) {
            l.onConnectionChanged(true, p.getSystemPortName());
        }
    }

    private void onDisconnected(SerialPort p) {
        connected = false;
        p.closePort();
        log.warn("Chessboard disconnected from {}; waiting for it to come back", p.getSystemPortName());
        SensorListener l = listener;
        if (l != null) {
            l.onConnectionChanged(false, p.getSystemPortName());
        }
    }

    private void readUntilDisconnected(SerialPort p) {
        LineReader reader = new LineReader();
        byte[] buffer = new byte[512];
        while (running) {
            int n = p.readBytes(buffer, buffer.length);
            if (n < 0) {
                return;
            }
            if (n == 0) {
                if (!legacyFirmware && System.currentTimeMillis() - lastLineAt > HEARTBEAT_TIMEOUT_MS) {
                    log.warn("No heartbeat from the board for {} ms", HEARTBEAT_TIMEOUT_MS);
                    return;
                }
                if (p.bytesAvailable() < 0) {
                    return;
                }
                continue;
            }
            for (String line : reader.feed(buffer, n)) {
                BoardProtocol.Message message = BoardProtocol.parse(line);
                if (message == null) {
                    log.debug("Dropped serial line: {}", line);
                    continue;
                }
                lastLineAt = System.currentTimeMillis();
                dispatch(message);
            }
        }
    }

    private void dispatch(BoardProtocol.Message message) {
        SensorListener l = listener;
        switch (message) {
            case BoardProtocol.SquareChange change -> {
                if (l != null) {
                    l.onSquareChanged(change.square(), change.occupied());
                }
            }
            case BoardProtocol.Occupancy occupancy -> {
                if (l != null) {
                    l.onOccupancy(occupancy.bits());
                }
            }
            case BoardProtocol.Ack ack -> onAck(true);
            case BoardProtocol.Error error -> {
                log.debug("Board rejected a command: {}", error.reason());
                onAck(false);
            }
            case BoardProtocol.Hello hello -> log.debug("Hello again from firmware {}", hello.firmware());
            case BoardProtocol.Legacy legacy -> log.debug("Old firmware says {}", legacy.text());
        }
    }

    // --- writer --------------------------------------------------------------------------------------------

    /** LED commands acknowledged by the firmware since start. */
    public long acknowledgedCommands() {
        return acknowledged;
    }

    /** Longest write-to-acknowledge time seen, in microseconds (serial transfer + firmware + USB). */
    public long maxRoundTripMicros() {
        return maxRoundTripNanos / 1000;
    }

    private void onAck(boolean applied) {
        txLock.lock();
        try {
            if (inFlightSinceNanos != 0) {
                long roundTrip = System.nanoTime() - inFlightSinceNanos;
                maxRoundTripNanos = Math.max(maxRoundTripNanos, roundTrip);
                inFlightSinceNanos = 0;
                acknowledged++;
            }
            if (inFlightFrame != null) {
                if (applied) {
                    confirmedFrame = inFlightFrame;
                } else {
                    confirmedFrame = null;
                    frameDirty = true;
                }
            }
            inFlightFrame = null;
            inFlightSince = 0;
            txSignal.signalAll();
        } finally {
            txLock.unlock();
        }
    }

    private void writerLoop() {
        while (running) {
            String command;
            SerialPort p;
            txLock.lock();
            try {
                while (running && !readyToSend()) {
                    long waitMs = inFlightSince > 0 ? Math.max(1, ACK_TIMEOUT_MS - (System.currentTimeMillis() - inFlightSince)) : 1000;
                    txSignal.await(waitMs, TimeUnit.MILLISECONDS);
                    if (inFlightSince > 0 && System.currentTimeMillis() - inFlightSince >= ACK_TIMEOUT_MS) {
                        // ack lost: the firmware state is unknown, resend a full frame
                        confirmedFrame = null;
                        frameDirty = requestedFrame != null;
                        inFlightFrame = null;
                        inFlightSince = 0;
                        inFlightSinceNanos = 0;
                    }
                }
                if (!running) {
                    return;
                }
                p = port;
                if (requestedBrightness >= 0) {
                    command = BoardProtocol.brightness(requestedBrightness);
                    requestedBrightness = -1;
                    inFlightFrame = confirmedFrame;
                } else {
                    int[] frame = requestedFrame;
                    frameDirty = false;
                    command = BoardProtocol.frameUpdate(confirmedFrame, frame);
                    if (command == null) {
                        continue;
                    }
                    inFlightFrame = frame;
                }
                inFlightSince = System.currentTimeMillis();
                inFlightSinceNanos = System.nanoTime();
            } catch (InterruptedException e) {
                return;
            } finally {
                txLock.unlock();
            }
            write(p, command);
        }
    }

    /** Called with txLock held. */
    private boolean readyToSend() {
        return connected && !legacyFirmware && port != null && inFlightSince == 0
                && (frameDirty && requestedFrame != null || requestedBrightness >= 0);
    }

    private static void write(SerialPort p, String text) {
        if (p == null) {
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        p.writeBytes(bytes, bytes.length);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Splits a byte stream into lines; overlong lines (noise) are discarded. */
    static final class LineReader {
        private final StringBuilder current = new StringBuilder();
        private boolean overflow;

        List<String> feed(byte[] data, int length) {
            List<String> lines = new ArrayList<>(2);
            for (int i = 0; i < length; i++) {
                char c = (char) (data[i] & 0xFF);
                if (c == '\n' || c == '\r') {
                    if (!overflow && !current.isEmpty()) {
                        lines.add(current.toString());
                    }
                    current.setLength(0);
                    overflow = false;
                } else if (current.length() >= BoardProtocol.MAX_LINE) {
                    overflow = true;
                } else {
                    current.append(c);
                }
            }
            return lines;
        }
    }
}
