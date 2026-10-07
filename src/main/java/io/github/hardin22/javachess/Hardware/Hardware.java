package io.github.hardin22.javachess.Hardware;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import io.github.hardin22.javachess.Services.BoardStateManager;
import io.github.hardin22.javachess.Utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Entry point to the smart board: sensors, LEDs and the board state machine, created once on first use.
 *
 * <p>Configuration (config.properties, or {@code -Djavachess.board=...} for the mode):</p>
 * <ul>
 *   <li>{@code board.mode}: {@code auto} (default: serial board, detected and reconnected automatically; the app
 *       runs without it), {@code sim} (software board, optionally with the debug window
 *       {@code -Djavachess.simulator.window=true}), {@code off}.</li>
 *   <li>{@code board.port}: serial port to use instead of detecting it (e.g. {@code /dev/ttyACM0}).</li>
 *   <li>{@code board.baud}: serial speed, default 250000 (must match the firmware).</li>
 *   <li>{@code led.layout} snake|rows, {@code led.origin} a1|h1|a8|h8, {@code led.direction} ranks|files.</li>
 *   <li>{@code hardware.led.brightness}: 0-100 %.</li>
 * </ul>
 */
public final class Hardware {

    private static final Logger log = LoggerFactory.getLogger(Hardware.class);
    public static final int DEFAULT_BAUD = 250_000;

    private static volatile Hardware instance;

    private final BoardHardware board;
    private final LedRenderer leds;
    private final MoveLeds moveLeds;
    private final BoardStateManager boardState;
    private final ReadOnlyBooleanWrapper connected = new ReadOnlyBooleanWrapper(false);
    private boolean closed;

    private Hardware(BoardHardware board, LedMapping mapping) {
        this.board = board;
        this.leds = new LedRenderer(board, mapping);
        this.moveLeds = new MoveLeds(leds);
        this.boardState = new BoardStateManager(leds, moveLeds);
        leds.setBrightnessPercent(ConfigManager.getIntProperty("hardware.led.brightness", 100));
        board.start(new BoardHardware.SensorListener() {
            @Override
            public void onSquareChanged(int square, boolean occupied) {
                boardState.onSquareChanged(square, occupied);
            }

            @Override
            public void onOccupancy(long occupied) {
                boardState.onOccupancy(occupied);
            }

            @Override
            public void onConnectionChanged(boolean isConnected, String description) {
                boardState.onConnectionChanged(isConnected, description);
                try {
                    Platform.runLater(() -> connected.set(isConnected));
                } catch (IllegalStateException e) {
                    connected.set(isConnected); // JavaFX not running (tests, tools)
                }
            }
        });
        log.info("Board hardware: {} (LED mapping {})", board.getClass().getSimpleName(), mapping);
        try {
            // the engine's move-quality feedback is drawn on the LEDs from now on
            io.github.hardin22.javachess.Engine.MoveCoach.get().setFeedbackListener(new MoveLedsFeedback(moveLeds));
        } catch (RuntimeException | LinkageError e) {
            log.warn("Move feedback LEDs unavailable: {}", e.toString());
        }
    }

    public static Hardware get() {
        Hardware h = instance;
        if (h == null) {
            synchronized (Hardware.class) {
                h = instance;
                if (h == null) {
                    h = new Hardware(createBoard(), LedMapping.fromConfig(
                            ConfigManager.getProperty("led.layout", "snake"),
                            ConfigManager.getProperty("led.origin", "a1"),
                            ConfigManager.getProperty("led.direction", "ranks")));
                    instance = h;
                }
            }
        }
        return h;
    }

    /** True once {@link #get()} has created the hardware layer. */
    public static boolean isInitialized() {
        return instance != null;
    }

    private static BoardHardware createBoard() {
        String mode = System.getProperty("javachess.board", ConfigManager.getProperty("board.mode", "auto"))
                .trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "sim", "simulator" -> new SimulatedBoard();
            case "off", "none" -> new NoBoard();
            default -> new SerialBoard(System.getProperty("javachess.board.port", ConfigManager.getProperty("board.port")),
                    ConfigManager.getIntProperty("board.baud", DEFAULT_BAUD));
        };
    }

    public static MoveLeds moveLeds() {
        return get().moveLeds;
    }

    public static LedRenderer leds() {
        return get().leds;
    }

    public static BoardStateManager boardState() {
        return get().boardState;
    }

    /** Board connected (sensors available); updated on the JavaFX thread. */
    public ReadOnlyBooleanProperty connectedProperty() {
        return connected.getReadOnlyProperty();
    }

    public BoardHardware board() {
        return board;
    }

    /** The software board when running with {@code board.mode=sim}, otherwise null. */
    public static SimulatedBoard simulator() {
        return get().board instanceof SimulatedBoard sim ? sim : null;
    }

    /** Turns the LEDs off and closes the serial port. Called on application exit. */
    public static synchronized void shutdown() {
        Hardware h = instance;
        if (h == null || h.closed) {
            return;
        }
        h.closed = true;
        h.boardState.shutdown();
        h.leds.shutdown();
        h.board.close();
    }

    /** Used with {@code board.mode=off}: no sensors, LEDs go nowhere. */
    static final class NoBoard implements BoardHardware {
        @Override
        public void start(SensorListener listener) {
            listener.onConnectionChanged(false, "disabled");
        }

        @Override
        public void sendFrame(int[] wireFrame) {
        }

        @Override
        public void setBrightness(int value) {
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public String description() {
            return "disabled";
        }

        @Override
        public void close() {
        }
    }
}
