package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.ThemeManager;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Utils.ImageCache;

import java.util.List;

/** Interface theme, board style and piece set with a live preview. Choices are saved immediately. */
public class ThemeController implements Screen {

    private static final String PREVIEW_FEN = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4";
    private static final double SWATCH = 168;

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final StackPane previewHolder = new StackPane();
    private final ToggleGroup boardGroup = new ToggleGroup();
    private final ToggleGroup pieceGroup = new ToggleGroup();
    private final ToggleGroup modeGroup = new ToggleGroup();
    private boolean updating;

    public ThemeController() {
        build();
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    private void build() {
        ScreenHeader header = new ScreenHeader(I18n.t("theme.title"), () -> mainController.navigateTo("HOME"));
        header.setSubtitle(I18n.t("theme.subtitle"));

        ToggleButton dark = new ToggleButton(I18n.t("theme.dark"), Icons.of("fth-moon", 26));
        dark.setUserData(ThemeManager.Mode.DARK);
        ToggleButton light = new ToggleButton(I18n.t("theme.light"), Icons.of("fth-sun", 26));
        light.setUserData(ThemeManager.Mode.LIGHT);
        ToggleButton system = new ToggleButton(I18n.t("theme.system"), Icons.of("fth-monitor", 26));
        system.setUserData(ThemeManager.Mode.SYSTEM);
        HBox modes = Ui.segmented(modeGroup, List.of(dark, light, system));
        modeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null && !updating) {
                ThemeManager.get().setMode((ThemeManager.Mode) n.getUserData());
            }
        });

        GridPane boards = grid();
        int i = 0;
        for (String id : BoardThemes.flatBoards()) {
            boards.add(card(flatSwatch(BoardThemes.colors(id)), BoardThemes.label(id), id, boardGroup), i % 3, i / 3);
            i++;
        }
        for (String id : BoardThemes.IMAGE_BOARDS) {
            ImageView iv = new ImageView(ImageCache.getInstance().getImage("/images/Scacchiere/" + id, SWATCH, SWATCH));
            iv.setFitWidth(SWATCH);
            iv.setFitHeight(SWATCH);
            boards.add(card(iv, BoardThemes.label(id), id, boardGroup), i % 3, i / 3);
            i++;
        }
        GridPane pieces = grid();
        i = 0;
        for (String set : BoardThemes.PIECE_SETS) {
            HBox pair = new HBox(-12, piece(set, "wk"), piece(set, "bq"));
            pair.setPrefSize(SWATCH, SWATCH);
            pair.setAlignment(Pos.CENTER);
            pieces.add(card(pair, set, set, pieceGroup), i % 3, i / 3);
            i++;
        }
        Ui.keepOneSelected(boardGroup);
        Ui.keepOneSelected(pieceGroup);
        boardGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null && !updating && !n.getUserData().equals(BoardThemes.currentBoard())) {
                Prefs.set("theme.board", n.getUserData());
                updatePreview((String) n.getUserData(), currentPieceChoice());
            }
        });
        pieceGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null && !updating && !n.getUserData().equals(BoardThemes.currentPieces())) {
                Prefs.set("theme.piece", n.getUserData());
                updatePreview(currentBoardChoice(), (String) n.getUserData());
            }
        });

        previewHolder.setMinHeight(400);
        VBox body = new VBox(16,
                previewHolder,
                Ui.sectionLabel(I18n.t("theme.appearance")), modes,
                Ui.sectionLabel(I18n.t("theme.boards")), boards,
                Ui.sectionLabel(I18n.t("theme.pieces")), pieces,
                Ui.wrap(I18n.t("theme.note"), "t-small", "t-muted"));
        body.getStyleClass().add("screen-body");
        root.setTop(header);
        root.setCenter(Ui.scroll(body));
    }

    private static ImageView piece(String set, String name) {
        ImageView view = new ImageView(ImageCache.getInstance().getImage("/images/Pieces/" + set + "/" + name + ".png",
                92, 92));
        view.setFitWidth(92);
        view.setFitHeight(92);
        return view;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        for (int c = 0; c < 3; c++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 3);
            col.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(col);
        }
        return grid;
    }

    private String currentBoardChoice() {
        return boardGroup.getSelectedToggle() == null ? BoardThemes.currentBoard()
                : (String) boardGroup.getSelectedToggle().getUserData();
    }

    private String currentPieceChoice() {
        return pieceGroup.getSelectedToggle() == null ? BoardThemes.currentPieces()
                : (String) pieceGroup.getSelectedToggle().getUserData();
    }

    @Override
    public void onNavigatedTo() {
        updating = true;
        select(boardGroup, BoardThemes.currentBoard());
        select(pieceGroup, BoardThemes.currentPieces());
        modeGroup.getToggles().stream().filter(t -> t.getUserData() == ThemeManager.get().getMode()).findFirst()
                .ifPresent(t -> t.setSelected(true));
        updating = false;
        updatePreview(BoardThemes.currentBoard(), BoardThemes.currentPieces());
    }

    private static void select(ToggleGroup group, String id) {
        group.getToggles().stream().filter(t -> id.equals(t.getUserData())).findFirst()
                .ifPresent(t -> t.setSelected(true));
    }

    private void updatePreview(String board, String pieces) {
        ChessBoardUI preview = new ChessBoardUI(board, pieces, 50);
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
        Rectangle clip = new Rectangle(SWATCH, SWATCH);
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        StackPane swatch = new StackPane(graphic);
        swatch.setMaxSize(SWATCH, SWATCH);
        swatch.setClip(clip);
        VBox content = new VBox(10, swatch, Ui.label(text, "t-small"));
        content.setAlignment(Pos.CENTER);
        ToggleButton button = new ToggleButton();
        button.setGraphic(content);
        button.getStyleClass().setAll("option");
        button.setStyle("-fx-padding: 14px;");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setUserData(id);
        button.setToggleGroup(group);
        return button;
    }
}
