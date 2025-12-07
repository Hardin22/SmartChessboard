package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class StockfishControlsController {

    @FXML private VBox root;
    @FXML private ToggleButton masterSwitch;
    @FXML private Spinner<Integer> depthSpinner;
    @FXML private Spinner<Integer> multiPvSpinner;

    private BiConsumer<Integer, Integer> onParamsChanged;
    private Consumer<Boolean> onMasterSwitchChanged;
    private Runnable onClose;

    @FXML
    public void initialize() {
        SpinnerValueFactory<Integer> depthFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, 18);
        depthSpinner.setValueFactory(depthFactory);
        
        SpinnerValueFactory<Integer> multiPvFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 5, 1);
        multiPvSpinner.setValueFactory(multiPvFactory);
        
        depthSpinner.valueProperty().addListener((obs, oldVal, newVal) -> notifyParamsChanged());
        multiPvSpinner.valueProperty().addListener((obs, oldVal, newVal) -> notifyParamsChanged());
    }
    
    public void setOnParamsChanged(BiConsumer<Integer, Integer> listener) {
        this.onParamsChanged = listener;
        // Notify immediately with current values
        notifyParamsChanged();
    }
    
    public void setOnMasterSwitchChanged(Consumer<Boolean> listener) {
        this.onMasterSwitchChanged = listener;
        // Notify immediately
        if (onMasterSwitchChanged != null) {
            onMasterSwitchChanged.accept(masterSwitch.isSelected());
        }
    }
    
    public void setOnClose(Runnable listener) {
        this.onClose = listener;
    }
    
    private void notifyParamsChanged() {
        if (onParamsChanged != null) {
            onParamsChanged.accept(depthSpinner.getValue(), multiPvSpinner.getValue());
        }
    }

    @FXML
    private void handleMasterSwitch() {
        boolean isSelected = masterSwitch.isSelected();
        masterSwitch.setText(isSelected ? "ON" : "OFF");
        if (onMasterSwitchChanged != null) {
            onMasterSwitchChanged.accept(isSelected);
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
        return depthSpinner.getValue();
    }
    
    public int getMultiPv() {
        return multiPvSpinner.getValue();
    }
    
    public boolean isMasterEnabled() {
        return masterSwitch.isSelected();
    }
}
