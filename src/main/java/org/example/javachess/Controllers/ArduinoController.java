package org.example.javachess.Controllers;

import javafx.application.Platform;
import org.example.javachess.Oggetti.AbstractGame;
import org.firmata4j.IODevice;
import org.firmata4j.Pin;
import org.firmata4j.firmata.FirmataDevice;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class ArduinoController {

    private static ArduinoController instance;
    private IODevice device;
    private AbstractGame game;
    private GameNavigationListener navigationListener;
    private Timer debounceTimer = new Timer();
    private boolean isDebouncing = false;

    private ArduinoController() {
        // Initialize Arduino connection
        try {
            device = new FirmataDevice("/dev/cu.usbmodem1101"); // Use the port from original code
            device.start();
            device.ensureInitializationIsDone();
            System.out.println("Arduino connected!");
            configurePins();
        } catch (Exception e) {
            System.out.println("Arduino connection failed: " + e.getMessage());
            device = null;
        }
    }

    public static synchronized ArduinoController getInstance() {
        if (instance == null) {
            instance = new ArduinoController();
        }
        return instance;
    }

    public void setNavigationListener(GameNavigationListener listener) {
        this.navigationListener = listener;
    }

    private void configurePins() {
        if (device == null) return;
        try {
            // Example configuration - adjust pins as per original logic or requirements
            device.getPin(2).setMode(Pin.Mode.INPUT);
            device.getPin(3).setMode(Pin.Mode.INPUT);
            device.getPin(4).setMode(Pin.Mode.INPUT);
            device.getPin(5).setMode(Pin.Mode.INPUT);

            device.getPin(2).addEventListener(new ButtonListener("PREV"));
            device.getPin(3).addEventListener(new ButtonListener("NEXT")); 
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setGame(AbstractGame game) {
        this.game = game;
    }

    private class ButtonListener implements org.firmata4j.PinEventListener {
        private String action;

        public ButtonListener(String action) {
            this.action = action;
        }

        @Override
        public void onModeChange(org.firmata4j.IOEvent event) {}

        @Override
        public void onValueChange(org.firmata4j.IOEvent event) {
            if (event.getValue() == 1 && !isDebouncing) {
                isDebouncing = true;
                handleAction(action);
                debounceTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        isDebouncing = false;
                    }
                }, 200);
            }
        }
    }

    private void handleAction(String action) {
        Platform.runLater(() -> {
            if (navigationListener != null) {
                switch (action) {
                    case "NEXT":
                        navigationListener.onNextMove();
                        break;
                    case "PREV":
                        navigationListener.onPreviousMove();
                        break;
                }
            }
        });
    }

    public void stop() {
        if (device != null) {
            try {
                device.stop();
                System.out.println("Arduino disconnected.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
