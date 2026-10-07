package org.example.javachess.Controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.example.javachess.Components.BoardThemes;
import org.example.javachess.Components.I18n;
import org.example.javachess.Components.Icons;
import org.example.javachess.Components.PageHeader;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Archive of played games; the file is read off the FX thread, rows are recycled by the ListView. */
public class ArchiveController implements NavigationAware {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveController.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private MainController mainController;

    @FXML
    private ListView<Row> archiveListView;
    @FXML
    private VBox emptyState;
    @FXML
    private PageHeader header;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        archiveListView.setCellFactory(list -> new GameCell());
        archiveListView.setFocusTraversable(false);
        archiveListView.setFixedCellSize(112);
    }

    @Override
    public void onNavigatedTo() {
        loadArchive();
    }

    /**
     * What one archive row shows. Built from the stored games in {@link #readRows()}: that method is the only
     * place that touches the archive data, so the data layer can change without touching the presentation.
     */
    public record Row(String title, String meta, String detail, String score, String finalFen, String moves,
                      String initialFen) {
    }

    private final class GameCell extends ListCell<Row> {
        private final ChessBoardUI miniBoard = new ChessBoardUI(BoardThemes.currentBoard(),
                BoardThemes.currentPieces(), 11);
        private final Label title = new Label();
        private final Label meta = new Label();
        private final Label opening = new Label();
        private final Label result = new Label();
        private final HBox row;

        GameCell() {
            getStyleClass().add("archive-cell");
            title.getStyleClass().add("archive-title");
            meta.getStyleClass().add("archive-meta");
            opening.getStyleClass().add("archive-meta");
            title.setMinWidth(0);
            opening.setMinWidth(0);
            VBox texts = new VBox(3, title, meta, opening);
            texts.setAlignment(Pos.CENTER_LEFT);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            result.setMinWidth(USE_PREF_SIZE);
            row = new HBox(miniBoard, texts, result, Icons.of("fth-chevron-right", 20));
            row.getChildren().get(3).getStyleClass().setAll("icon-muted");
            row.getStyleClass().add("archive-row");
            row.setOnMouseClicked(e -> {
                Row game = getItem();
                if (game != null) {
                    showReview(game.moves(), game.initialFen());
                }
            });
        }

        @Override
        protected void updateItem(Row game, boolean empty) {
            super.updateItem(game, empty);
            if (empty || game == null) {
                setGraphic(null);
                return;
            }
            try {
                miniBoard.setPosition(game.finalFen(), null);
            } catch (RuntimeException e) {
                miniBoard.resetBoard();
            }
            title.setText(game.title());
            meta.setText(game.meta());
            opening.setText(game.detail());
            setResult(game.score());
            setGraphic(row);
        }

        private void setResult(String score) {
            result.setText(score);
            result.getStyleClass().setAll("badge", "mono");
            if ("1-0".equals(score)) {
                result.getStyleClass().add("badge-success");
            } else if ("0-1".equals(score)) {
                result.getStyleClass().add("badge-danger");
            } else if ("½-½".equals(score)) {
                result.getStyleClass().add("badge-warning");
            }
        }
    }

    static String describeType(String type) {
        String t = type.replace("Plaver", "Player");
        if (t.startsWith("Player vs Stockfish livello")) {
            return "Contro Stockfish · livello " + t.substring("Player vs Stockfish livello".length()).trim();
        }
        if (t.equals("Player vs Player")) {
            return I18n.t("pvp.title");
        }
        if (t.startsWith("Online")) {
            return "Online · browser";
        }
        return t.isBlank() ? "Partita" : t;
    }

    static String scoreOf(String raw) {
        String r = raw.toLowerCase(Locale.ROOT);
        if (r.equals("1-0") || (r.contains("bianco") && r.contains("vince"))) {
            return "1-0";
        }
        if (r.equals("0-1") || (r.contains("nero") && r.contains("vince"))) {
            return "0-1";
        }
        if (r.contains("patta") || r.contains("1/2")) {
            return "½-½";
        }
        return "—";
    }

    static String describeOutcome(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("unknown")) {
            return "Risultato non disponibile";
        }
        return raw.endsWith(".") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private void loadArchive() {
        Thread.ofVirtual().name("archive-load").start(() -> {
            List<Row> games = readRows();
            Platform.runLater(() -> {
                archiveListView.getItems().setAll(games);
                emptyState.setVisible(games.isEmpty());
                archiveListView.setVisible(!games.isEmpty());
                header.setSubtitle(games.isEmpty() ? I18n.t("archive.subtitle") : I18n.t("archive.count", games.size()));
            });
        });
    }

    /** Reads the archive (newest first) and maps each game to a {@link Row}. Runs off the FX thread. */
    private List<Row> readRows() {
        List<Row> rows = new ArrayList<>();
        Path archivePath = copyArchiveJsonToWritableLocation();
        try {
            if (Files.exists(archivePath)) {
                JSONArray array = new JSONArray(Files.readString(archivePath, StandardCharsets.UTF_8));
                for (int i = array.length() - 1; i >= 0; i--) {
                    JSONObject game = array.getJSONObject(i);
                    String time = game.optString("time", "");
                    String when = game.optString("datetime", "");
                    String meta = time.isBlank() || "N/A".equals(time) || "∞".equals(time)
                            ? when : when + "  ·  " + time.replace(":00m", " min").replace("m +", " min +");
                    String openingName = game.optString("opening", "");
                    String result = game.optString("result", "");
                    rows.add(new Row(describeType(game.optString("type", "")), meta,
                            openingName.isBlank() ? describeOutcome(result) : openingName, scoreOf(result),
                            game.optString("fen", START_FEN), game.optString("pgn", ""),
                            game.optString("initialFen", START_FEN)));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cannot read the archive", e);
        }
        return rows;
    }

    private void showReview(String pgn, String initialFen) {
        ReviewController reviewController = (ReviewController) mainController.getController("REVIEW");
        mainController.navigateTo("REVIEW");
        reviewController.loadGame(pgn, initialFen);
    }

    private Path copyArchiveJsonToWritableLocation() {
        Path targetPath = Paths.get("archive.json");
        if (!Files.exists(targetPath)) {
            try (InputStream resourceStream = getClass().getResourceAsStream("/archive.json")) {
                if (resourceStream != null) {
                    Files.copy(resourceStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOG.warn("Cannot create archive.json", e);
            }
        }
        return targetPath;
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }
}
