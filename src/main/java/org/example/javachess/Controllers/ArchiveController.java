package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.javachess.Oggetti.ArchivedGame;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Services.GameArchiveService;
import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Utils.ErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArchiveController implements NavigationAware {

    private static final Logger log = LoggerFactory.getLogger(ArchiveController.class);

    private MainController mainController;

    @FXML
    private ListView<ArchivedGame> archiveListView;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        setupListView();
        loadArchive();
    }

    @Override
    public void onNavigatedTo() {
        loadArchive();
    }

    private void setupListView() {
        archiveListView.setCellFactory(param -> new ListCell<ArchivedGame>() {
            private HBox content;
            private ChessBoardUI miniChessboard;
            private Label typeLabel;
            private Label resultLabel;
            private Label timeControlLabel;
            private Label datetimeLabel;
            private Label openingLabel;
            private Button chessboardButton;

            {
                // Initialize the UI structure once
                content = new HBox(20);
                content.getStyleClass().add("glass-card");
                content.setAlignment(Pos.CENTER_LEFT);
                content.setPadding(new Insets(15));
                content.prefWidthProperty().bind(archiveListView.widthProperty().subtract(60));
                content.setMaxWidth(Double.MAX_VALUE);

                miniChessboard = new ChessBoardUI(
                        ConfigManager.getProperty("theme.board", "Marghiacciato.png"),
                        ConfigManager.getProperty("theme.piece", "Classico"),
                        25);
                miniChessboard.setStyle(
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 0, 4); -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1;");

                chessboardButton = new Button();
                chessboardButton.setGraphic(miniChessboard);
                chessboardButton.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");

                VBox gameDetails = new VBox(5);
                gameDetails.setAlignment(Pos.CENTER_LEFT);
                gameDetails.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(gameDetails, javafx.scene.layout.Priority.ALWAYS);

                typeLabel = new Label();
                typeLabel.setStyle(
                        "-fx-text-fill: #D4AF37; -fx-font-weight: bold; -fx-font-size: 16px; -fx-font-family: 'Poppins';");

                resultLabel = new Label();
                resultLabel.setAlignment(Pos.CENTER_LEFT);

                HBox infoBox = new HBox(10);
                infoBox.setAlignment(Pos.CENTER_LEFT);
                timeControlLabel = new Label();
                timeControlLabel.getStyleClass().add("body-label");
                timeControlLabel.setStyle("-fx-text-fill: #E0E0E0; -fx-font-size: 13px;");
                datetimeLabel = new Label();
                datetimeLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");
                infoBox.getChildren().addAll(timeControlLabel, datetimeLabel);

                openingLabel = new Label();
                openingLabel.setStyle("-fx-text-fill: #B0B0B0; -fx-font-style: italic; -fx-font-size: 12px;");
                openingLabel.setWrapText(true);
                openingLabel.maxWidthProperty().bind(content.widthProperty().subtract(250));

                gameDetails.getChildren().addAll(typeLabel, resultLabel, infoBox, openingLabel);
                content.getChildren().addAll(chessboardButton, gameDetails);
            }

            @Override
            protected void updateItem(ArchivedGame game, boolean empty) {
                super.updateItem(game, empty);
                if (empty || game == null) {
                    setGraphic(null);
                } else {
                    // Update existing nodes with new data
                    String datetime = game.playedAt() == null ? ""
                            : game.playedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
                    String result = game.result();
                    String type = game.label().isEmpty() ? game.mode().name() : game.label();
                    String opening = game.opening();
                    String timeControl = game.timeControl().isEmpty() ? "∞" : game.timeControl();

                    miniChessboard.setPosition(game.finalFen(), null);
                    chessboardButton.setOnAction(event -> showReview(game));
                    typeLabel.setText(type.toUpperCase());
                    resultLabel.setText(game.termination().isEmpty() ? result : result + "  " + game.termination());

                    String resultColor = result.equals("1-0") ? "#4CAF50"
                            : (result.equals("0-1") ? "#CF6679" : "#FFC107");
                    resultLabel.setStyle("-fx-text-fill: " + resultColor
                            + "; -fx-font-weight: bold; -fx-font-size: 14px; -fx-border-color: "
                            + resultColor + "; -fx-border-radius: 5; -fx-border-width: 1; -fx-padding: 2 8;");

                    timeControlLabel.setText(timeControl);
                    datetimeLabel.setText(datetime);
                    openingLabel.setText(opening);

                    setGraphic(content);
                }
            }
        });
    }

    private void loadArchive() {
        GameArchiveService archive = GameArchiveService.getInstance();
        archiveListView.getItems().setAll(archive.list()); // newest first
        if (archive.getLoadProblem() != null) {
            ErrorReporter.showError("Archivio", archive.getLoadProblem());
        }
    }

    private void showReview(ArchivedGame game) {
        mainController.loadView("REVIEW", "/UI/ReviewView.fxml");
        ReviewController reviewController = (ReviewController) mainController.getController("REVIEW");
        if (reviewController == null) {
            log.error("Review view not available");
            return;
        }
        reviewController.loadGame(game.movesAsUciString(), game.initialFen());
        mainController.navigateTo("REVIEW");
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }
}
