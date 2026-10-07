package org.example.javachess.Controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
import org.example.javachess.Oggetti.ArchivedGame;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Services.GameArchiveService;
import org.example.javachess.Utils.ErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public record Row(int id, String title, String meta, String detail, String score, String finalFen, String moves,
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
            Button more = new Button();
            more.getStyleClass().addAll("btn", "btn-ghost", "icon-btn");
            more.setGraphic(Icons.of("fth-more-horizontal", 22));
            more.setAccessibleText(I18n.t("archive.actions"));
            more.setOnAction(e -> {
                if (getItem() != null) {
                    showActions(getItem());
                }
                e.consume();
            });
            row = new HBox(miniBoard, texts, result, more);
            row.getStyleClass().add("archive-row");
            row.setOnMouseClicked(e -> {
                Row game = getItem();
                if (e.getTarget() instanceof javafx.scene.Node n && isInside(n, more)) {
                    return;
                }
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

    private static boolean isInside(javafx.scene.Node node, javafx.scene.Node parent) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n == parent) {
                return true;
            }
        }
        return false;
    }

    /** Sheet with the actions on one game: open, export as PGN, delete (with confirmation). */
    private void showActions(Row game) {
        Button open = actionButton(I18n.t("archive.open"), "fth-play", "btn-secondary");
        open.setOnAction(e -> {
            mainController.closeSheet();
            showReview(game.moves(), game.initialFen());
        });
        Button export = actionButton(I18n.t("archive.export"), "fth-download", "btn-secondary");
        export.setOnAction(e -> {
            mainController.closeSheet();
            org.example.javachess.Utils.AppExecutors.io().execute(() -> {
                try {
                    java.nio.file.Path file = org.example.javachess.Utils.AppPaths.exportDir()
                            .resolve("javachess-partita-" + game.id() + ".pgn");
                    String pgn = GameArchiveService.getInstance().exportPgn(java.util.List.of(game.id()));
                    java.nio.file.Files.writeString(file, pgn, java.nio.charset.StandardCharsets.UTF_8);
                    mainController.showToast(I18n.t("archive.exported", file));
                } catch (java.io.IOException | RuntimeException ex) {
                    ErrorReporter.showError(I18n.t("archive.title"), ErrorReporter.userMessage(ex));
                }
            });
        });
        Button delete = actionButton(I18n.t("archive.delete"), "fth-trash-2", "btn-danger");
        delete.setOnAction(e -> confirmDelete(game));
        VBox content = new VBox(10, new Label(game.title() + " · " + game.meta()), open, export, delete);
        content.getChildren().get(0).getStyleClass().add("card-description");
        mainController.showSheet(I18n.t("archive.game"), content);
    }

    private void confirmDelete(Row game) {
        Label text = new Label(I18n.t("archive.delete.confirm"));
        text.getStyleClass().add("card-description");
        text.setWrapText(true);
        Button cancel = actionButton(I18n.t("common.cancel"), null, "btn-secondary");
        cancel.setOnAction(e -> mainController.closeSheet());
        Button confirm = actionButton(I18n.t("archive.delete"), "fth-trash-2", "btn-danger");
        confirm.setOnAction(e -> {
            mainController.closeSheet();
            // same queue as the game saves: a delete can never overtake a pending save
            org.example.javachess.Utils.AppExecutors.storage().execute(() -> {
                GameArchiveService.getInstance().delete(game.id());
                Platform.runLater(this::loadArchive);
            });
        });
        HBox buttons = new HBox(12, cancel, confirm);
        HBox.setHgrow(cancel, Priority.ALWAYS);
        HBox.setHgrow(confirm, Priority.ALWAYS);
        mainController.showSheet(I18n.t("archive.delete.title"), new VBox(20, text, buttons));
    }

    private static Button actionButton(String text, String icon, String variant) {
        Button b = new Button(text);
        b.getStyleClass().addAll("btn", variant, "btn-lg");
        if (icon != null) {
            b.setGraphic(Icons.of(icon, 20));
        }
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }

    static String describeType(String type) {
        String t = type.replace("Plaver", "Player");
        if (t.startsWith("Player vs Stockfish livello")) {
            return "Contro Stockfish · livello " + t.substring("Player vs Stockfish livello".length()).trim();
        }
        if (t.startsWith("Player vs ") && !t.equals("Player vs Player")) {
            return "Contro " + t.substring("Player vs ".length()).trim(); // e.g. "Player vs Maia 1500"
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
        if (r.equals("*")) {
            return "—";
        }
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
        org.example.javachess.Utils.AppExecutors.io().execute(() -> {
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
        try {
            GameArchiveService archive = GameArchiveService.getInstance();
            for (ArchivedGame game : archive.list()) {
                String when = game.playedAt() == null ? "" : game.playedAt().format(DATE);
                String meta = game.timeControl().isEmpty() ? when
                        : (when.isEmpty() ? "" : when + "  ·  ")
                                + game.timeControl().replace(":00m", " min").replace("m +", " min +").replace("+", " + ")
                                        .replace("  ", " ");
                String opening = "Opening Name".equals(game.opening()) || "Unknown".equalsIgnoreCase(game.opening())
                        ? "" : game.opening();
                String detail = !opening.isEmpty() ? opening
                        : !game.termination().isEmpty() ? game.termination() : describeOutcome("");
                rows.add(new Row(game.id(), describe(game), meta, detail, scoreOf(game.result()),
                        game.finalFen().isEmpty() ? START_FEN : game.finalFen(), game.movesAsUciString(),
                        game.initialFen().isEmpty() ? START_FEN : game.initialFen()));
            }
            if (archive.getLoadProblem() != null) {
                ErrorReporter.showError("Archivio", archive.getLoadProblem());
            }
        } catch (RuntimeException e) {
            LOG.warn("Cannot read the archive", e);
        }
        return rows;
    }

    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** Title of a row: how the game was played. */
    static String describe(ArchivedGame game) {
        String label = game.label();
        if (label.startsWith("Player") || label.startsWith("Plaver") || label.startsWith("Online")) {
            return describeType(label);
        }
        return switch (game.mode()) {
            case PVC -> label.isEmpty() ? I18n.t("pvc.title") : "Contro " + label.replace(" livello", " · livello");
            case PVP -> I18n.t("pvp.title");
            case LICHESS -> label.isEmpty() ? "Lichess" : "Lichess · " + label;
            case BROWSER -> "Online · browser";
            case PUZZLE -> I18n.t("puzzle.title");
            case IMPORTED -> label.isEmpty() ? "Partita importata" : label;
            default -> label.isEmpty() ? "Partita" : label;
        };
    }

    private void showReview(String pgn, String initialFen) {
        ReviewController reviewController = (ReviewController) mainController.getController("REVIEW");
        mainController.navigateTo("REVIEW");
        reviewController.loadGame(pgn, initialFen);
    }

    @FXML
    private void backToHome() {
        mainController.navigateTo("HOME");
    }
}
