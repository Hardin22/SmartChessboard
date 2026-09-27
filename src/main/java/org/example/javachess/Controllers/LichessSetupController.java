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
        boolean rated = Boolean.parseBoolean(ratedGroup.getSelectedToggle().getUserData().toString());
        String color = colorGroup.getSelectedToggle().getUserData().toString();

        statusLabel.setText("Cercando avversario...");

        new Thread(() -> {
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
                    String msg = (gameId != null) ? gameId.replace("ERROR:", "") : "Nessuna partita trovata o timeout.";
                    statusLabel.setText("Errore: " + msg);
                    // Parse specific errors for user friendliness
                    if (msg.contains("Invalid time control")) {
                        statusLabel.setText("Errore: Tempo non valido per partita Classificata.");
                    }
                }
            });
        }).start();
    }

    @FXML
    public void goBack() {
        mainController.navigateTo("HOME");
    }
}
