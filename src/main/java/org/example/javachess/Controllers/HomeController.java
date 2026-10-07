package org.example.javachess.Controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.javachess.Components.BoardThemes;
import org.example.javachess.Components.Icons;
import org.example.javachess.Oggetti.ArchivedGame;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Utils.AppExecutors;
import java.util.List;
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
    @FXML
    private StackPane content;
    @FXML
    private VBox hero;
    @FXML
    private VBox overview;
    @FXML
    private VBox actions;
    @FXML
    private VBox resumeCard;
    @FXML
    private Label puzzleRatingLabel;
    @FXML
    private Label streakLabel;
    @FXML
    private Label gamesLabel;

    private Boolean wideLayout;
    private ChessBoardUI resumeBoard;

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
        content.widthProperty().addListener((obs, o, n) -> applyLayout(n.doubleValue()));
        applyLayout(720);
    }

    /**
     * Portrait (board monitor): hero at the top, overview in the middle, actions at the bottom within thumb reach,
     * with the free height shared between the gaps. Landscape: hero and overview on the left, actions on the right.
     */
    private void applyLayout(double width) {
        boolean wide = width >= 1100;
        if (wideLayout != null && wideLayout == wide) {
            return;
        }
        wideLayout = wide;
        for (VBox block : new VBox[] { hero, overview, actions }) {
            if (block.getParent() instanceof Pane parent) {
                parent.getChildren().remove(block);
            }
        }
        if (wide) {
            VBox left = new VBox(32, hero, overview);
            left.setAlignment(Pos.CENTER);
            left.setPrefWidth(560);
            left.setMaxWidth(560);
            actions.setPrefWidth(560);
            actions.setMaxWidth(560);
            HBox row = new HBox(64, left, actions);
            row.setAlignment(Pos.CENTER);
            content.getChildren().setAll(row);
        } else {
            Region gapTop = new Region();
            Region gapBottom = new Region();
            VBox.setVgrow(gapTop, Priority.ALWAYS);
            VBox.setVgrow(gapBottom, Priority.ALWAYS);
            gapTop.setMinHeight(32);
            gapBottom.setMinHeight(32);
            actions.setPrefWidth(Region.USE_COMPUTED_SIZE);
            actions.setMaxWidth(Double.MAX_VALUE);
            VBox column = new VBox(0, hero, gapTop, overview, gapBottom, actions);
            column.setMaxWidth(640);
            column.setFillWidth(true);
            content.getChildren().setAll(column);
        }
    }

    @Override
    public void onNavigatedTo() {
        HardwareStatus.bind(boardStatus, true);
        refreshOverview();
    }

    /** Last or current game, puzzle progress and archive size (files read off the FX thread). */
    private void refreshOverview() {
        ActiveGameController game = activeGame();
        boolean inProgress = game != null && game.isGameInProgress();
        AppExecutors.io().execute(() -> {
            var progress = org.example.javachess.Services.PuzzleProgressService.getInstance().getStats();
            var archive = org.example.javachess.Services.GameArchiveService.getInstance();
            List<ArchivedGame> games = archive.list();
            ArchivedGame last = games.isEmpty() ? null : games.get(0);
            Platform.runLater(() -> {
                puzzleRatingLabel.setText(String.valueOf(progress.rating()));
                streakLabel.setText(String.valueOf(progress.currentStreak()));
                gamesLabel.setText(String.valueOf(games.size()));
                if (inProgress) {
                    showResume(I18n.t("home.resume.current"), game.getTitle(), game.getCurrentFen(), null,
                            I18n.t("home.resume.continue"), () -> mainController.navigateTo("GAME"));
                } else if (last != null) {
                    String when = last.playedAt() == null ? ""
                            : last.playedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
                    String detail = last.opening().isEmpty() ? when : when + "  ·  " + last.opening();
                    String score = ArchiveController.scoreOf(last.result());
                    showResume(I18n.t("home.resume.last"), ArchiveController.describe(last)
                            + ("—".equals(score) ? "" : "  ·  " + score), last.finalFen(), detail,
                            I18n.t("home.resume.review"), () -> {
                                ReviewController review = (ReviewController) mainController.getController("REVIEW");
                                mainController.navigateTo("REVIEW");
                                review.loadGame(last.movesAsUciString(), last.initialFen());
                            });
                } else {
                    resumeCard.setVisible(false);
                    resumeCard.setManaged(false);
                }
            });
        });
    }

    private ActiveGameController activeGame() {
        try {
            return mainController.getController("GAME") instanceof ActiveGameController c ? c : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void showResume(String caption, String title, String fen, String detail, String action, Runnable onAction) {
        if (resumeBoard == null) {
            resumeBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 12);
        }
        try {
            resumeBoard.setPosition(fen == null || fen.isBlank()
                    ? "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" : fen, null);
        } catch (RuntimeException e) {
            resumeBoard.resetBoard();
        }
        Label cap = new Label(caption);
        cap.getStyleClass().add("stat-label");
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        t.setWrapText(true);
        VBox texts = new VBox(4, cap, t);
        if (detail != null && !detail.isBlank()) {
            Label d = new Label(detail);
            d.getStyleClass().add("card-description");
            d.setWrapText(true);
            texts.getChildren().add(d);
        }
        Button go = new Button(action);
        go.getStyleClass().addAll("btn", "btn-secondary");
        go.setGraphic(Icons.of("fth-arrow-right", 18));
        go.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        go.setOnAction(e -> onAction.run());
        texts.getChildren().add(go);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(16, resumeBoard, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        resumeCard.getChildren().setAll(row);
        resumeCard.setVisible(true);
        resumeCard.setManaged(true);
    }

    private void showEngine(EngineProfile profile) {
        if (profile == null) {
            engineStatus.set(I18n.t("status.engine.none"), StatusChip.State.OFF);
        } else {
            EngineStatus status = EngineSelection.get().statusProperty().get();
            StatusChip.State state = !profile.available() ? StatusChip.State.WARN
                    : status == null || status.state() == EngineStatus.State.READY ? StatusChip.State.OK
                    : status.state() == EngineStatus.State.LOADING ? StatusChip.State.BUSY : StatusChip.State.WARN;
            String text = state == StatusChip.State.BUSY ? I18n.t("engine.loading") : profile.displayName();
            engineStatus.set(text, state);
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
