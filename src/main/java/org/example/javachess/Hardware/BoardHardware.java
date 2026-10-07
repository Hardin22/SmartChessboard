package org.example.javachess.Hardware;

/**
 * A physical (or simulated) smart board: 64 occupancy sensors and 64 RGB LEDs.
 * Implementations never block the caller and never throw when the hardware is missing.
 */
public interface BoardHardware extends AutoCloseable {

    /** Receives sensor events. Called on the hardware's own thread, never on the JavaFX thread. */
    interface SensorListener {
        /** One square changed (already debounced by the firmware). */
        void onSquareChanged(int square, boolean occupied);

        /** Full occupancy snapshot (bit i = square i); sent on connection and periodically as a heartbeat. */
        void onOccupancy(long occupied);

        default void onConnectionChanged(boolean connected, String description) {
        }
    }

    /** Starts the connection (or reconnection loop) and delivers events to {@code listener}. */
    void start(SensorListener listener);

    /**
     * Shows a frame of 64 colors (0xRRGGBB) in wire (LED index) order. Non-blocking: if the link is busy only
     * the latest frame is sent.
     */
    void sendFrame(int[] wireFrame);

    /** Global brightness 0-255, applied by the firmware on top of its power cap. */
    void setBrightness(int value);

    boolean isConnected();

    /** Short human readable description (port name, "simulator", ...). */
    String description();

    @Override
    void close();
}
