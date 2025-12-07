package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Utils.ImageCache;

import java.io.File;
import java.net.URL;
import java.util.Objects;

public class ThemeController implements NavigationAware {

    private MainController mainController;

    @FXML private BorderPane themeView;
    @FXML private ImageView boardPreview;
    @FXML private ImageView piecePreview;
    
    private VBox mainSelectionBox; // To hold the initial two buttons

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }
    
    @FXML
    public void initialize() {
        updatePreviews();
        // Store the initial center content to restore it later
        if (themeView.getCenter() instanceof VBox) {
            mainSelectionBox = (VBox) themeView.getCenter();
        }
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
        // If we are in a sub-selection, go back to main theme selection
        if (themeView.getCenter() != mainSelectionBox) {
            themeView.setCenter(mainSelectionBox);
        } else {
            mainController.navigateTo("HOME");
        }
    }

    @FXML
    private void showBoardSelection() {
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(20);
        grid.setPadding(new Insets(20));
        grid.setAlignment(Pos.CENTER);

        String[] boards = {"Bubblegum.png", "Checkers.png", "Legno.png", "Marghiacciato.png", "Marrone.png", "Neon.png"};
        
        int col = 0;
        int row = 0;
        for (String board : boards) {
            Button btn = createSelectionButton("/images/Scacchiere/" + board, board, true);
            grid.add(btn, col, row);
            col++;
            if (col > 2) {
                col = 0;
                row++;
            }
        }
        
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        themeView.setCenter(scroll);
    }

    @FXML
    private void showPieceSelection() {
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(20);
        grid.setPadding(new Insets(20));
        grid.setAlignment(Pos.CENTER);

        String[] pieces = {"Bubblegum", "Classico", "Legno", "Neon", "Spray", "Vetro"};
        
        int col = 0;
        int row = 0;
        for (String piece : pieces) {
            // Show White King as preview
            Button btn = createSelectionButton("/images/Pieces/" + piece + "/wK.png", piece, false);
            grid.add(btn, col, row);
            col++;
            if (col > 2) {
                col = 0;
                row++;
            }
        }
        
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        themeView.setCenter(scroll);
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
        btn.setStyle("-fx-background-color: #333333; -fx-background-radius: 10;");
        
        btn.setOnAction(e -> {
            if (isBoard) {
                ConfigManager.setProperty("theme.board", value);
            } else {
                ConfigManager.setProperty("theme.piece", value);
            }
            updatePreviews();
            themeView.setCenter(mainSelectionBox); // Go back
        });
        
        return btn;
    }
}
