package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Utils.LichessAPIHelper;

/** Lichess seek setup: time control, rated/casual, colour. The seek runs off the FX thread. */
public class LichessSetupController implements NavigationAware {

    private MainController mainController;

    @FXML
    private Label selectedTimeLabel;
    @FXML
    private ToggleGroup ratedGroup;
    @FXML
    private ToggleGroup colorGroup;
    @FXML
    private ToggleGroup timeGroup;
    @FXML
    private Label statusLabel;
    @FXML
    private Button seekButton;

    private int selectedTime = 10;
    private int selectedIncrement = 0;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        for (ToggleGroup group : new ToggleGroup[] { ratedGroup, colorGroup, timeGroup }) {
            group.selectedToggleProperty().addListener((obs, o, n) -> {
                if (n == null && o != null) {
                    o.setSelected(true);
                }
            });
        }
    }

    @FXML
    public void setTimeControl(ActionEvent event) {
        Toggle source = (Toggle) event.getSource();
        String[] parts = ((String) source.getUserData()).split("\\|");
        selectedTime = Integer.parseInt(parts[0]);
        selectedIncrement = Integer.parseInt(parts[1]);
        selectedTimeLabel.setText(selectedTime + " + " + selectedIncrement);
    }

    @FXML
    public void startSeek() {
        boolean rated = ratedGroup.getSelectedToggle() != null
                && Boolean.parseBoolean(String.valueOf(ratedGroup.getSelectedToggle().getUserData()));
        String color = colorGroup.getSelectedToggle() != null
                ? String.valueOf(colorGroup.getSelectedToggle().getUserData()) : "random";
        statusLabel.setText(I18n.t("lichess.searching"));
        seekButton.setDisable(true);
        int time = selectedTime;
        int increment = selectedIncrement;
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            String gameId = LichessAPIHelper.createSeek(time, increment, rated, color);
            Platform.runLater(() -> {
                seekButton.setDisable(false);
                if (gameId != null && !gameId.startsWith("ERROR:")) {
                    statusLabel.setText(I18n.t("lichess.found"));
                    ActiveGameController controller = (ActiveGameController) mainController.getController("GAME");
                    mainController.navigateTo("GAME");
                    controller.startOnlineGame(gameId);
                } else {
                    // LichessClient already produces a message meant for the user.
                    statusLabel.setText(gameId != null ? gameId.replace("ERROR:", "").trim()
                            : I18n.t("lichess.error.timeout"));
                }
            });
        });
    }

    @Override
    public void onNavigatedFrom() {
        LichessAPIHelper.cancelSeek(); // leaving the screen must not leave a seek open on Lichess
    }

    @FXML
    private void openInBrowser() {
        Object controller = mainController.getController("BROWSER");
        if (controller instanceof BrowserController browser) {
            browser.loadPage("https://lichess.org");
        }
        mainController.navigateTo("BROWSER");
    }

    @FXML
    public void goBack() {
        mainController.navigateTo("HOME");
    }
}
