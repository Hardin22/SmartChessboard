package org.example.javachess.Controllers;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import javafx.scene.control.ToggleGroup;
import org.example.javachess.Utils.LichessAPIHelper;

public class LichessSetupController implements NavigationAware {

    private MainController mainController;

    @FXML
    private Label selectedTimeLabel;
    @FXML
    private ToggleGroup ratedGroup;
    @FXML
    private ToggleGroup colorGroup;
    @FXML
    private Label statusLabel;

    private int selectedTime = 10;
    private int selectedIncrement = 0;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void setTimeControl(ActionEvent event) {
        Button btn = (Button) event.getSource();
        String data = (String) btn.getUserData();
        String[] parts = data.split("\\|");
        selectedTime = Integer.parseInt(parts[0]);
        selectedIncrement = Integer.parseInt(parts[1]);

        selectedTimeLabel.setText("Selezionato: " + selectedTime + " + " + selectedIncrement);
    }

    @FXML
    public void startSeek() {
        boolean rated = ratedGroup.getSelectedToggle() != null
                && Boolean.parseBoolean(String.valueOf(ratedGroup.getSelectedToggle().getUserData()));
        String color = colorGroup.getSelectedToggle() != null
                ? String.valueOf(colorGroup.getSelectedToggle().getUserData()) : "random";

        statusLabel.setText("Cercando avversario...");

        Thread seekThread = new Thread(() -> {
            String gameId = LichessAPIHelper.createSeek(selectedTime, selectedIncrement, rated, color);

            Platform.runLater(() -> {
                if (gameId != null && !gameId.startsWith("ERROR:")) {
                    statusLabel.setText("Partita trovata! ID: " + gameId);
                    // Navigate to Game
                    mainController.loadView("GAME", "/UI/GameView.fxml");
                    mainController.navigateTo("GAME");
                    ActiveGameController controller = (ActiveGameController) mainController.getController("GAME");
                    if (controller != null) {
                        controller.startOnlineGame(gameId);
                    }
                } else {
                    // LichessClient already produces a message meant for the user.
                    String msg = (gameId != null) ? gameId.replace("ERROR:", "") : "Nessuna partita trovata.";
                    statusLabel.setText(msg);
                }
            });
        }, "lichess-seek");
        seekThread.setDaemon(true);
        seekThread.start();
    }

    @Override
    public void onNavigatedFrom() {
        LichessAPIHelper.cancelSeek(); // leaving the screen must not leave a seek open on Lichess
    }

    @FXML
    public void goBack() {
        LichessAPIHelper.cancelSeek();
        mainController.navigateTo("HOME");
    }
}
