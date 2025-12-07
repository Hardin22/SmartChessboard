package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXML;

public class HomeController implements NavigationAware {

    private MainController mainController;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Images are loaded directly in FXML
    }

    @FXML
    private void showPvCSetup() {
        mainController.loadView("PVC_SETUP", "/UI/PvCSetupView.fxml");
        mainController.navigateTo("PVC_SETUP");
    }

    @FXML
    private void showPvPSetup() {
        mainController.loadView("PVP_SETUP", "/UI/PvPSetupView.fxml");
        mainController.navigateTo("PVP_SETUP");
    }

    @FXML
    private void showArchive() {
        mainController.loadView("ARCHIVE", "/UI/ArchiveView.fxml");
        mainController.navigateTo("ARCHIVE");
    }

    @FXML
    private void showTheme() {
        mainController.loadView("THEME", "/UI/ThemeView.fxml");
        mainController.navigateTo("THEME");
    }

    @FXML
    private void startLichess() {
        // Assuming WebViewExample can be adapted or we just open it
        // For now, let's keep the original logic but maybe we need reference to MainLayout's container?
        // WebViewExample.openLichessInMain(main); 
        // We might need to expose the container from MainController
        System.out.println("Lichess clicked - Implementation pending adaptation");
    }
}
