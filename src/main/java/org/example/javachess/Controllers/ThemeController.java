package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Utils.ImageCache;

public class ThemeController implements NavigationAware {

    private MainController mainController;

    @FXML
    private BorderPane themeView;
    @FXML
    private ImageView boardPreview;
    @FXML
    private ImageView piecePreview;

    @FXML
    private StackPane selectionOverlay;
    @FXML
    private Label selectionTitle;
    @FXML
    private FlowPane selectionContainer;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        updatePreviews();
    }

    private void updatePreviews() {
        String currentBoard = ConfigManager.getProperty("theme.board", "Marghiacciato.png");
        String currentPiece = ConfigManager.getProperty("theme.piece", "Classico");

        try {
            ImageCache cache = ImageCache.getInstance();
            boardPreview.setImage(cache.getImage("/images/Scacchiere/" + currentBoard));
            // Preview a piece (e.g., White King)
            piecePreview.setImage(cache.getImage("/images/Pieces/" + currentPiece + "/wK.png"));
        } catch (Exception e) {
            System.err.println("Error loading previews: " + e.getMessage());
        }
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }

    @FXML
    private void closeSelection() {
        selectionOverlay.setVisible(false);
    }

    @FXML
    private void showBoardSelection() {
        selectionTitle.setText("Seleziona Scacchiera");
        selectionContainer.getChildren().clear();

        String[] boards = { "Bubblegum.png", "Checkers.png", "Legno.png", "Marghiacciato.png", "Marrone.png",
                "Neon.png" };

        for (String board : boards) {
            Button btn = createSelectionButton("/images/Scacchiere/" + board, board, true);
            selectionContainer.getChildren().add(btn);
        }

        selectionOverlay.setVisible(true);
    }

    @FXML
    private void showPieceSelection() {
        selectionTitle.setText("Seleziona Pezzi");
        selectionContainer.getChildren().clear();

        String[] pieces = { "Bubblegum", "Classico", "Legno", "Neon", "Spray", "Vetro" };

        for (String piece : pieces) {
            // Show White King as preview
            Button btn = createSelectionButton("/images/Pieces/" + piece + "/wK.png", piece, false);
            selectionContainer.getChildren().add(btn);
        }

        selectionOverlay.setVisible(true);
    }

    private Button createSelectionButton(String imagePath, String value, boolean isBoard) {
        Button btn = new Button();
        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER);

        ImageView iv = new ImageView();
        iv.setFitWidth(150);
        iv.setFitHeight(150);
        iv.setPreserveRatio(true);
        try {
            iv.setImage(ImageCache.getInstance().getImage(imagePath));
        } catch (Exception e) {
            System.err.println("Error loading image: " + imagePath);
        }

        Label lbl = new Label(value.replace(".png", ""));
        lbl.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        content.getChildren().addAll(iv, lbl);
        btn.setGraphic(content);
        btn.setStyle("-fx-background-color: transparent; -fx-background-radius: 10; -fx-cursor: hand;");

        // Hover effect
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: rgba(255, 255, 255, 0.1); -fx-background-radius: 10; -fx-cursor: hand;"));
        btn.setOnMouseExited(
                e -> btn.setStyle("-fx-background-color: transparent; -fx-background-radius: 10; -fx-cursor: hand;"));

        btn.setOnAction(e -> {
            if (isBoard) {
                ConfigManager.setProperty("theme.board", value);
            } else {
                ConfigManager.setProperty("theme.piece", value);
            }
            updatePreviews();
            closeSelection();
        });

        return btn;
    }
}
