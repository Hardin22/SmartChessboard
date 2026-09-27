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
    private void showPuzzles() {
        mainController.loadView("PUZZLE_DASHBOARD", "/UI/PuzzleDashboardView.fxml");
        mainController.navigateTo("PUZZLE_DASHBOARD");
    }

    @FXML
    private javafx.scene.layout.VBox onlineChoicesBox;

    @FXML
    private void toggleOnlineChoices() {
        boolean isVisible = onlineChoicesBox.isVisible();
        onlineChoicesBox.setVisible(!isVisible);
        onlineChoicesBox.setManaged(!isVisible);
    }

    @FXML
    private void playChessCom() {
        launchBrowser("https://www.chess.com/login");
    }

    @FXML
    private void playLichess() {
        launchBrowser("https://lichess.org");
    }

    @FXML
    private void navigateToSettings() {
        mainController.loadView("SETTINGS", "/UI/SettingsView.fxml");
        mainController.navigateTo("SETTINGS");
    }

    private void launchBrowser(String url) {
        if (mainController != null) {
            // Ensure Browser View is loaded
            mainController.loadView("BROWSER", "/UI/BrowserView.fxml");

            // Get Controller and Load Page
            Object controller = mainController.getController("BROWSER");
            if (controller instanceof BrowserController) {
                ((BrowserController) controller).loadPage(url);
            }

            // Navigate (Placeholder, the Swing window will take over)
            mainController.navigateTo("BROWSER");
        }
    }
}
