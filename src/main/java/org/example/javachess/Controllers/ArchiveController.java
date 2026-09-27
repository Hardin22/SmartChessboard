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
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Utils.ConfigManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class ArchiveController implements NavigationAware {

    private MainController mainController;

    @FXML
    private ListView<JSONObject> archiveListView;

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
        archiveListView.setCellFactory(param -> new ListCell<JSONObject>() {
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
            protected void updateItem(JSONObject game, boolean empty) {
                super.updateItem(game, empty);
                if (empty || game == null) {
                    setGraphic(null);
                } else {
                    // Update existing nodes with new data
                    int gameId = game.getInt("id");
                    String datetime = game.getString("datetime");
                    String pgn = game.getString("pgn");
                    String result = game.getString("result");
                    String type = game.getString("type");
                    String opening = game.getString("opening");
                    String fen = game.getString("fen");
                    String initialFen = game.optString("initialFen",
                            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
                    String timeControl = game.getString("time");

                    miniChessboard.setPosition(fen, null);
                    chessboardButton.setOnAction(event -> showReview(gameId, pgn, initialFen));
                    typeLabel.setText(type.toUpperCase());
                    resultLabel.setText(result);

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
        Path archivePath = copyArchiveJsonToWritableLocation();
        try {
            if (Files.exists(archivePath)) {
                String content = new String(Files.readAllBytes(archivePath));
                JSONArray gamesArray = new JSONArray(content);

                archiveListView.getItems().clear();
                // Add in reverse order to show newest first
                for (int i = gamesArray.length() - 1; i >= 0; i--) {
                    archiveListView.getItems().add(gamesArray.getJSONObject(i));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showReview(int gameId, String pgn, String initialFen) {
        mainController.loadView("REVIEW", "/UI/ReviewView.fxml");
        ReviewController reviewController = (ReviewController) mainController.getController("REVIEW");
        reviewController.loadGame(pgn, initialFen);
        mainController.navigateTo("REVIEW");
    }

    private Path copyArchiveJsonToWritableLocation() {
        Path targetPath = Paths.get("archive.json");
        if (!Files.exists(targetPath)) {
            try (InputStream resourceStream = getClass().getResourceAsStream("/archive.json")) {
                if (resourceStream != null) {
                    Files.copy(resourceStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return targetPath;
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }
}
