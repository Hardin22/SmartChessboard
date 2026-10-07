package org.example.javachess.Components;

import javafx.application.Platform;
import org.example.javachess.Controllers.ArduinoController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Reads whether the smart board (Arduino over USB serial) is connected, without blocking the FX thread.
 * Uses {@code ArduinoController.isConnected()} (non-blocking in the runtime layer).
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
            return ArduinoController.getInstance().isConnected();
        } catch (RuntimeException e) {
            LOG.debug("Board status unavailable", e);
            return null;
        }
    }

    /** Updates a chip with the board state. */
    public static void bind(StatusChip chip) {
        bind(chip, false);
    }

    /** Same, with short texts ("Collegata") for places already labelled "Scacchiera". */
    public static void bind(StatusChip chip, boolean shortText) {
        String suffix = shortText ? ".short" : "";
        chip.set(I18n.t("status.board.checking"), StatusChip.State.BUSY);
        refresh(connected -> {
            if (Boolean.TRUE.equals(connected)) {
                chip.set(I18n.t("status.board.on" + suffix), StatusChip.State.OK);
            } else {
                chip.set(I18n.t("status.board.off" + suffix), StatusChip.State.OFF);
            }
        });
    }
}
