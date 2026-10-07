package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.layout.HBox;
import org.example.javachess.Components.HardwareStatus;
import org.example.javachess.Components.I18n;
import org.example.javachess.Components.StatusChip;
import org.example.javachess.Engine.EngineProfile;
import org.example.javachess.Engine.EngineSelection;
import org.example.javachess.Engine.EngineStatus;
import org.kordamp.ikonli.javafx.FontIcon;

public class HomeController implements NavigationAware {

    private MainController mainController;

    @FXML
    private HBox onlineChoicesBox;
    @FXML
    private FontIcon onlineChevron;
    @FXML
    private StatusChip boardStatus;
    @FXML
    private StatusChip engineStatus;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        EngineSelection selection = EngineSelection.get();
        selection.activeProfileProperty().addListener((obs, o, n) -> showEngine(n));
        selection.statusProperty().addListener((obs, o, n) -> showEngine(selection.activeProfileProperty().get()));
        showEngine(selection.activeProfileProperty().get());
    }

    @Override
    public void onNavigatedTo() {
        HardwareStatus.bind(boardStatus);
    }

    private void showEngine(EngineProfile profile) {
        if (profile == null) {
            engineStatus.set(I18n.t("status.engine.none"), StatusChip.State.OFF);
        } else {
            EngineStatus status = EngineSelection.get().statusProperty().get();
            StatusChip.State state = !profile.available() ? StatusChip.State.WARN
                    : status == null || status.state() == EngineStatus.State.READY ? StatusChip.State.OK
                    : status.state() == EngineStatus.State.LOADING ? StatusChip.State.BUSY : StatusChip.State.WARN;
            engineStatus.set(I18n.t("status.engine", profile.displayName()), state);
        }
    }

    @FXML
    private void showPvCSetup() {
        mainController.navigateTo("PVC_SETUP");
    }

    @FXML
    private void showPvPSetup() {
        mainController.navigateTo("PVP_SETUP");
    }

    @FXML
    private void showArchive() {
        mainController.navigateTo("ARCHIVE");
    }

    @FXML
    private void showTheme() {
        mainController.navigateTo("THEME");
    }

    @FXML
    private void showPuzzles() {
        mainController.navigateTo("PUZZLE_DASHBOARD");
    }

    @FXML
    private void navigateToSettings() {
        mainController.navigateTo("SETTINGS");
    }

    @FXML
    private void toggleOnlineChoices() {
        boolean show = !onlineChoicesBox.isVisible();
        onlineChoicesBox.setVisible(show);
        onlineChoicesBox.setManaged(show);
        onlineChevron.setIconLiteral(show ? "fth-chevron-up" : "fth-chevron-down");
    }

    @FXML
    private void playChessCom() {
        launchBrowser("https://www.chess.com/login");
    }

    @FXML
    private void playLichess() {
        mainController.openLichess();
    }

    private void launchBrowser(String url) {
        Object controller = mainController.getController("BROWSER");
        if (controller instanceof BrowserController browser) {
            browser.loadPage(url);
        }
        mainController.navigateTo("BROWSER");
    }
}
