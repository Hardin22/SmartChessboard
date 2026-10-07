package org.example.javachess.Components;

import javafx.application.Platform;
import org.example.javachess.Controllers.ArduinoController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Reads whether the smart board (Arduino over USB serial) is connected, without blocking the FX thread.
 * Uses {@code ArduinoController.isConnected()} when the runtime layer provides it, else its running flag.
 */
public final class HardwareStatus {

    private static final Logger LOG = LoggerFactory.getLogger(HardwareStatus.class);

    private HardwareStatus() {
    }

    /** Probes off the FX thread and delivers the result on the FX thread (null = unknown). */
    public static void refresh(Consumer<Boolean> onResult) {
        Thread.ofVirtual().name("hw-status").start(() -> {
            Boolean connected = probe();
            Platform.runLater(() -> onResult.accept(connected));
        });
    }

    private static Boolean probe() {
        if (Boolean.getBoolean("javachess.demo.board")) {
            return true;
        }
        try {
            ArduinoController arduino = ArduinoController.getInstance();
            try {
                Method m = ArduinoController.class.getMethod("isConnected");
                return (Boolean) m.invoke(arduino);
            } catch (NoSuchMethodException e) {
                Field f = ArduinoController.class.getDeclaredField("isRunning");
                f.setAccessible(true);
                return f.getBoolean(arduino);
            }
        } catch (Throwable t) {
            LOG.debug("Board status unavailable", t);
            return null;
        }
    }

    /** Updates a chip with the board state. */
    public static void bind(StatusChip chip) {
        chip.set(I18n.t("status.board.checking"), StatusChip.State.BUSY);
        refresh(connected -> {
            if (Boolean.TRUE.equals(connected)) {
                chip.set(I18n.t("status.board.on"), StatusChip.State.OK);
            } else {
                chip.set(I18n.t("status.board.off"), StatusChip.State.OFF);
            }
        });
    }
}
