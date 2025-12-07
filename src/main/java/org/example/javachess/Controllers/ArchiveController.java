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

    @FXML private ListView<JSONObject> archiveListView;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        setupListView();
        loadArchive();
    }

    private void setupListView() {
        archiveListView.setCellFactory(param -> new ListCell<JSONObject>() {
            @Override
            protected void updateItem(JSONObject game, boolean empty) {
                super.updateItem(game, empty);
                if (empty || game == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    setGraphic(createGameItem(game));
                }
            }
        });
    }

    private HBox createGameItem(JSONObject game) {
        int gameId = game.getInt("id");
        String datetime = game.getString("datetime");
        String pgn = game.getString("pgn");
        String result = game.getString("result");
        String type = game.getString("type");
        String opening = game.getString("opening");
        String fen = game.getString("fen");
        String timeControl = game.getString("time");

        HBox gameItemBox = new HBox();
        gameItemBox.setSpacing(15);
        // Neo-Brutalist Item Style: Dark Grey Panel, Black Border, Sharp Edges
        gameItemBox.setStyle("-fx-background-color: #2a2a2a; -fx-border-color: #000000; -fx-border-width: 2px; -fx-padding: 10;");
        gameItemBox.setAlignment(Pos.CENTER_LEFT);
        
        String boardStyle = ConfigManager.getProperty("theme.board", "Marghiacciato.png");
        String pieceStyle = ConfigManager.getProperty("theme.piece", "Classico");
        
        ChessBoardUI miniChessboard = new ChessBoardUI(boardStyle, pieceStyle, 25);
        miniChessboard.setPosition(fen, null);
        // Add border to mini board
        miniChessboard.setStyle("-fx-border-color: #000000; -fx-border-width: 2px;");

        Button chessboardButton = new Button();
        chessboardButton.setGraphic(miniChessboard);
        chessboardButton.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
        chessboardButton.setOnAction(event -> showReview(gameId, pgn));

        VBox gameDetails = new VBox();
        gameDetails.setSpacing(2);
        gameDetails.setAlignment(Pos.CENTER_LEFT);

        Label typeLabel = new Label(type);
        typeLabel.setStyle("-fx-text-fill: #ccff00; -fx-font-weight: bold; -fx-font-size: 16px;"); // Neon Yellow Title
        
        Label resultLabel = new Label(result);
        String resultColor = result.equals("1-0") ? "#00ff00" : (result.equals("0-1") ? "#ff3333" : "#ffa500");
        resultLabel.setStyle("-fx-text-fill: " + resultColor + "; -fx-font-weight: bold; -fx-font-size: 14px;");
        
        Label timeControlLabel = new Label("Time: " + timeControl);
        timeControlLabel.setStyle("-fx-text-fill: white; -fx-font-size: 12px;");
        
        Label openingLabel = new Label(opening);
        openingLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-style: italic; -fx-font-size: 12px;");
        openingLabel.setWrapText(true);
        
        Label datetimeLabel = new Label(datetime);
        datetimeLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 10px;");

        gameDetails.getChildren().addAll(typeLabel, resultLabel, timeControlLabel, openingLabel, datetimeLabel);
        gameItemBox.getChildren().addAll(chessboardButton, gameDetails);
        
        return gameItemBox;
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

    private void showReview(int gameId, String pgn) {
        mainController.loadView("REVIEW", "/UI/ReviewView.fxml");
        ReviewController reviewController = (ReviewController) mainController.getController("REVIEW");
        reviewController.loadGame(pgn);
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
