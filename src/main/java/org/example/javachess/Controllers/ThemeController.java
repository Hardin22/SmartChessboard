package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.example.javachess.Components.BoardThemes;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Utils.ImageCache;

/** Board and piece style picker with a live preview. Choices are saved immediately. */
public class ThemeController implements NavigationAware {

    private static final String PREVIEW_FEN = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4";
    private static final double SWATCH = 104;

    private MainController mainController;

    @FXML
    private StackPane previewHolder;
    @FXML
    private FlowPane flatBoards;
    @FXML
    private FlowPane imageBoards;
    @FXML
    private FlowPane pieceSets;

    private final ToggleGroup boardGroup = new ToggleGroup();
    private final ToggleGroup pieceGroup = new ToggleGroup();

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        for (String id : BoardThemes.flatBoards()) {
            flatBoards.getChildren().add(card(flatSwatch(BoardThemes.colors(id)), BoardThemes.label(id), id, boardGroup));
        }
        for (String id : BoardThemes.IMAGE_BOARDS) {
            ImageView iv = new ImageView(ImageCache.getInstance().getImage("/images/Scacchiere/" + id, SWATCH, SWATCH));
            iv.setFitWidth(SWATCH);
            iv.setFitHeight(SWATCH);
            imageBoards.getChildren().add(card(iv, BoardThemes.label(id), id, boardGroup));
        }
        for (String set : BoardThemes.PIECE_SETS) {
            ImageView king = new ImageView(ImageCache.getInstance().getImage("/images/Pieces/" + set + "/wk.png", 52, 52));
            ImageView queen = new ImageView(ImageCache.getInstance().getImage("/images/Pieces/" + set + "/bq.png", 52, 52));
            HBox pair = new HBox(0, king, queen);
            pair.setPrefSize(SWATCH, SWATCH);
            pair.setAlignment(javafx.geometry.Pos.CENTER);
            pieceSets.getChildren().add(card(pair, set, set, pieceGroup));
        }
        keepOneSelected(boardGroup);
        keepOneSelected(pieceGroup);
        boardGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null && !n.getUserData().equals(BoardThemes.currentBoard())) {
                ConfigManager.setProperty("theme.board", (String) n.getUserData());
                updatePreview();
            }
        });
        pieceGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null && !n.getUserData().equals(BoardThemes.currentPieces())) {
                ConfigManager.setProperty("theme.piece", (String) n.getUserData());
                updatePreview();
            }
        });
    }

    @Override
    public void onNavigatedTo() {
        select(boardGroup, BoardThemes.currentBoard());
        select(pieceGroup, BoardThemes.currentPieces());
        updatePreview();
    }

    private static void keepOneSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null && o != null) {
                o.setSelected(true);
            }
        });
    }

    private static void select(ToggleGroup group, String id) {
        group.getToggles().stream().filter(t -> id.equals(t.getUserData())).findFirst()
                .ifPresent(t -> {
                    if (!t.isSelected()) {
                        t.setSelected(true);
                    }
                });
    }

    private void updatePreview() {
        ChessBoardUI preview = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 44);
        preview.setPosition(PREVIEW_FEN, null);
        previewHolder.getChildren().setAll(preview);
    }

    private static Node flatSwatch(BoardThemes.Colors colors) {
        GridPane grid = new GridPane();
        double s = SWATCH / 4;
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                Color fill = (r + c) % 2 == 0 ? colors.light() : colors.dark();
                grid.add(new Rectangle(s, s, fill), c, r);
            }
        }
        return grid;
    }

    private static ToggleButton card(Node graphic, String text, String id, ToggleGroup group) {
        ToggleButton button = new ToggleButton(text, graphic);
        button.getStyleClass().setAll("preview-card");
        button.setUserData(id);
        button.setToggleGroup(group);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }
}
