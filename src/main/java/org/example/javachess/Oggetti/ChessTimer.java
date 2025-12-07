package org.example.javachess.Oggetti;

import javafx.application.Platform;
import javafx.scene.control.Label;
import java.util.Timer;
import java.util.TimerTask;

public class ChessTimer {
    private Timer whiteTimer;
    private Timer blackTimer;
    private Label whiteLabel;
    private Label blackLabel;
    private int whiteTime;
    private int blackTime;
    private int increment;
    private PvpGame pvpGame; // Add reference to PvpGame

    public ChessTimer(Label whiteLabel, Label blackLabel, int gameDuration, int increment, PvpGame pvpGame) {
        this.whiteLabel = whiteLabel;
        this.blackLabel = blackLabel;
        this.whiteTime = gameDuration;
        this.blackTime = gameDuration;
        this.increment = increment;
        this.pvpGame = pvpGame; // Initialize reference to PvpGame
    }

    public void initializetimer() {
        updateLabel(whiteLabel, whiteTime);
        updateLabel(blackLabel, blackTime);
    }

    public void startWhiteTimer() {
        stopBlackTimer();
        Platform.runLater(() -> whiteLabel.getStyleClass().add("stopwatch-active"));
        whiteTimer = new Timer();
        whiteTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    if (whiteTime > 0) {
                        whiteTime--;
                        updateLabel(whiteLabel, whiteTime);
                    } else {
                        pvpGame.endGame("Il Nero vince per tempo", true);
                    }
                });
            }
        }, 0, 1000);
    }

    public void startBlackTimer() {
        stopWhiteTimer();
        Platform.runLater(() -> blackLabel.getStyleClass().add("stopwatch-active"));
        blackTimer = new Timer();
        blackTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    if (blackTime > 0) {
                        blackTime--;
                        updateLabel(blackLabel, blackTime);
                    } else {
                        pvpGame.endGame("Il Bianco vince per tempo", true);
                    }
                });
            }
        }, 0, 1000);
    }

    public void addIncrementToWhite() {
        whiteTime += increment;
        updateLabel(whiteLabel, whiteTime);
    }

    public void addIncrementToBlack() {
        blackTime += increment;
        updateLabel(blackLabel, blackTime);
    }

    public void stopWhiteTimer() {
        if (whiteTimer != null) {
            whiteTimer.cancel();
            whiteTimer = null;
            Platform.runLater(() -> whiteLabel.getStyleClass().remove("stopwatch-active"));
        }
    }

    public void stopBlackTimer() {
        if (blackTimer != null) {
            blackTimer.cancel();
            blackTimer = null;
            Platform.runLater(() -> blackLabel.getStyleClass().remove("stopwatch-active"));
        }
    }

    private void updateLabel(Label label, int time) {
        int minutes = time / 60;
        int seconds = time % 60;
        label.setText(String.format("%02d:%02d", minutes, seconds));
    }
}