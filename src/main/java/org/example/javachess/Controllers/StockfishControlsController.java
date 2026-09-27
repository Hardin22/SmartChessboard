package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;

public class StockfishControlsController {

    @FXML
    private VBox root;

    @FXML
    private Label depthLabel;
    @FXML
    private Label multiPvLabel;

    private int currentDepth = 18;
    private int currentMultiPv = 1;

    private BiConsumer<Integer, Integer> onParamsChanged;

    private Runnable onClose;

    @FXML
    public void initialize() {
        this.currentDepth = org.example.javachess.Utils.ConfigManager.getIntProperty("game.depth", 18);
        updateLabels();
    }

    @FXML
    private void increaseDepth() {
        if (currentDepth < 30) {
            currentDepth++;
            updateLabels();
            notifyParamsChanged();
        }
    }

    @FXML
    private void decreaseDepth() {
        if (currentDepth > 1) {
            currentDepth--;
            updateLabels();
            notifyParamsChanged();
        }
    }

    @FXML
    private void increaseMultiPv() {
        if (currentMultiPv < 5) {
            currentMultiPv++;
            updateLabels();
            notifyParamsChanged();
        }
    }

    @FXML
    private void decreaseMultiPv() {
        if (currentMultiPv > 1) {
            currentMultiPv--;
            updateLabels();
            notifyParamsChanged();
        }
    }

    private void updateLabels() {
        if (depthLabel != null)
            depthLabel.setText(String.valueOf(currentDepth));
        if (multiPvLabel != null)
            multiPvLabel.setText(String.valueOf(currentMultiPv));
    }

    public void setOnParamsChanged(BiConsumer<Integer, Integer> listener) {
        this.onParamsChanged = listener;
        notifyParamsChanged();
    }

    public void setOnClose(Runnable listener) {
        this.onClose = listener;
    }

    private void notifyParamsChanged() {
        if (onParamsChanged != null) {
            onParamsChanged.accept(currentDepth, currentMultiPv);
        }
    }

    @FXML
    private void close() {
        if (onClose != null) {
            onClose.run();
        } else {
            root.setVisible(false);
        }
    }

    public int getDepth() {
        return currentDepth;
    }

    public int getMultiPv() {
        return currentMultiPv;
    }

}
