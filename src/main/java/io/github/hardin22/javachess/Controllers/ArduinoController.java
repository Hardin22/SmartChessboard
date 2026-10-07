package io.github.hardin22.javachess.Controllers;

import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Services.BoardStateManager;

import java.util.Map;

/**
 * Compatibility facade over {@link Hardware} for code written against the old Arduino API.
 * New code should use {@code Hardware.boardState()}, {@code Hardware.moveLeds()} and {@code Hardware.leds()}.
 * Every method returns immediately; nothing here sleeps or blocks on the serial port.
 */
public final class ArduinoController {

    private static final ArduinoController INSTANCE = new ArduinoController();

    private ArduinoController() {
    }

    public static ArduinoController getInstance() {
        return INSTANCE;
    }

    public BoardStateManager getBoardStateManager() {
        return Hardware.boardState();
    }

    /** Lights one square (LEGACY layer, cleared by {@link #clearLeds()}). */
    public void sendLedCommand(String square, int r, int g, int b) {
        int index = Squares.parse(square);
        if (index >= 0) {
            Hardware.leds().set(LedRenderer.Layer.LEGACY, index, LedColors.rgb(r, g, b));
        }
    }

    /** Lights several squares at once; they reach the board in the same frame. */
    public void sendLedBatch(Map<String, int[]> ledCommands) {
        LedRenderer leds = Hardware.leds();
        ledCommands.forEach((square, rgb) -> {
            int index = Squares.parse(square);
            if (index >= 0) {
                leds.set(LedRenderer.Layer.LEGACY, index, LedColors.rgb(rgb[0], rgb[1], rgb[2]));
            }
        });
    }

    /** Clears the per-square commands and the move hints (setup and replication guidance stay). */
    public void clearLeds() {
        Hardware.leds().clear(LedRenderer.Layer.LEGACY);
        Hardware.moveLeds().clearCandidates();
    }

    public void flashLed(String square, int r, int g, int b, int count) {
        int index = Squares.parse(square);
        if (index >= 0) {
            Hardware.leds().flash(LedRenderer.Layer.ALERT, new int[]{index}, LedColors.rgb(r, g, b), count, 600);
        }
    }

    public void playVictoryAnimation() {
        Hardware.leds().playVictoryWave();
    }

    public boolean isConnected() {
        return Hardware.get().board().isConnected();
    }

    /** Connection state for the UI, updated on the JavaFX thread. */
    public javafx.beans.property.ReadOnlyBooleanProperty connectedProperty() {
        return Hardware.get().connectedProperty();
    }

    public void stop() {
        Hardware.shutdown();
    }
}
