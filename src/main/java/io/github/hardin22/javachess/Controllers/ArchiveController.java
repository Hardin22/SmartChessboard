package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Analysis.OpeningNames;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.TouchKeyboard;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Stats.OnlineImport;
import io.github.hardin22.javachess.Stats.PgnTransfer;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Archive of played games. Large rows grouped by day, a search by name with the on-screen keyboard, three filters
 * (mode, result, period) and a preview sheet with the actions. The file is read off the FX thread; rows are
 * virtualised and recycled by the ListView.
 */
public class ArchiveController implements Screen {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveController.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN);
    private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ITALIAN);

    enum ModeFilter { ALL, COMPUTER, TWO_PLAYERS, ONLINE }

    enum ResultFilter { ALL, WON, LOST, DRAWN, UNFINISHED }

    enum PeriodFilter { ALL, TODAY, WEEK, MONTH }

    /**
     * What one archive row shows. Built from the stored games in {@link #readRows()}: that method is the only place
     * that touches the archive data, so the data layer can change without touching the presentation.
     *
     * @param outcome 1 won, 0 drawn, -1 lost from the local player's point of view, null when there is none
     */
    public record Row(int id, String title, String meta, String detail, String result, Integer outcome,
                      boolean decisive, String finalFen, LocalDate day, ArchivedGame game, String search) {
    }

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final ScreenHeader header;
    private final ListView<Row> archiveListView = new ListView<>();
    private final VBox emptyState = new VBox();
    private final Label emptyTitle = Ui.label("", "empty-title");
    private final Label emptySub = Ui.wrap("", "empty-sub");
    private final Label searchText = Ui.label("", "search-text");
    private final HBox searchBox = new HBox();
    private final Button modeButton = filterButton();
    private final Button resultButton = filterButton();
    private final Button periodButton = filterButton();
    private List<Row> allRows = List.of();
    private String query = "";
    private ModeFilter modeFilter = ModeFilter.ALL;
    private ResultFilter resultFilter = ResultFilter.ALL;
    private PeriodFilter periodFilter = PeriodFilter.ALL;

    public ArchiveController() {
        header = new ScreenHeader(I18n.t("archive.title"), () -> mainController.navigateTo("HOME"));
        Button transfer = Ui.iconButton("fth-download", I18n.t("transfer.title"), this::openTransfer);
        transfer.setId("archive-transfer");
        header.setActions(transfer);
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
        // search
        searchBox.getStyleClass().add("search-box");
        searchBox.setId("archive-search");
        searchText.setMinWidth(0);
        HBox.setHgrow(searchText, Priority.ALWAYS);
        searchText.setMaxWidth(Double.MAX_VALUE);
        Button clear = Ui.iconButton("fth-x", I18n.t("archive.search.clear"), () -> setQuery(""));
        clear.getStyleClass().add("plain");
        clear.visibleProperty().bind(searchText.textProperty().isNotEqualTo(I18n.t("archive.search")));
        searchBox.getChildren().addAll(Icons.of("fth-search", 28), searchText, clear);
        searchBox.setOnMouseClicked(e -> {
            if (!(e.getTarget() instanceof Node n && isInside(n, clear))) {
                openSearch();
            }
        });

        modeButton.setOnAction(e -> chooseMode());
        resultButton.setOnAction(e -> chooseResult());
        periodButton.setOnAction(e -> choosePeriod());
        HBox filters = Ui.equalRow(10, modeButton, resultButton, periodButton);

        VBox top = new VBox(14, header, padded(new VBox(14, searchBox, filters)));
        top.setPadding(new Insets(0, 0, 8, 0));

        archiveListView.setCellFactory(list -> new GameCell());
        archiveListView.setFocusTraversable(false);


        emptyState.getStyleClass().add("empty-state");
        emptyState.getChildren().addAll(Icons.of("fth-archive", 64), emptyTitle, emptySub);
        emptyState.setVisible(false);

        StackPane center = new StackPane(archiveListView, emptyState);
        center.setPadding(new Insets(0, 20, 0, 24));
        root.setTop(top);
        root.setCenter(center);
        refreshFilterLabels();
    }

    private static Node padded(Node node) {
        VBox box = new VBox(node);
        box.setPadding(new Insets(0, 24, 0, 24));
        return box;
    }

    private static Button filterButton() {
        Button b = new Button();
        b.getStyleClass().setAll("chip");
        b.setGraphic(Icons.of("fth-chevron-down", 22));
        b.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMnemonicParsing(false);
        return b;
    }

    private static boolean isInside(Node node, Node parent) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == parent) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onNavigatedTo() {
        loadArchive();
    }

    // ================================================================== filters

    private void refreshFilterLabels() {
        modeButton.setText(switch (modeFilter) {
            case ALL -> I18n.t("archive.filter.mode");
            case COMPUTER -> I18n.t("archive.mode.computer");
            case TWO_PLAYERS -> I18n.t("archive.mode.pvp");
            case ONLINE -> I18n.t("archive.mode.online");
        });
        resultButton.setText(switch (resultFilter) {
            case ALL -> I18n.t("archive.filter.result");
            case WON -> I18n.t("archive.result.won");
            case LOST -> I18n.t("archive.result.lost");
            case DRAWN -> I18n.t("archive.result.drawn");
            case UNFINISHED -> I18n.t("archive.result.unfinished");
        });
        periodButton.setText(switch (periodFilter) {
            case ALL -> I18n.t("archive.filter.period");
            case TODAY -> I18n.t("archive.period.today");
            case WEEK -> I18n.t("archive.period.week");
            case MONTH -> I18n.t("archive.period.month");
        });
        markActive(modeButton, modeFilter != ModeFilter.ALL);
        markActive(resultButton, resultFilter != ResultFilter.ALL);
        markActive(periodButton, periodFilter != PeriodFilter.ALL);
        searchText.setText(query.isEmpty() ? I18n.t("archive.search") : query);
        searchText.getStyleClass().remove("t-faint");
        if (query.isEmpty()) {
            searchText.getStyleClass().add("t-faint");
        }
    }

    private static void markActive(Button b, boolean active) {
        b.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), active);
    }

    private void chooseMode() {
        choose(I18n.t("archive.filter.mode"), ModeFilter.values(), modeFilter, f -> switch (f) {
            case ALL -> I18n.t("archive.mode.all");
            case COMPUTER -> I18n.t("archive.mode.computer");
            case TWO_PLAYERS -> I18n.t("archive.mode.pvp");
            case ONLINE -> I18n.t("archive.mode.online");
        }, f -> modeFilter = f);
    }

    private void chooseResult() {
        choose(I18n.t("archive.filter.result"), ResultFilter.values(), resultFilter, f -> switch (f) {
            case ALL -> I18n.t("archive.result.all");
            case WON -> I18n.t("archive.result.won");
            case LOST -> I18n.t("archive.result.lost");
            case DRAWN -> I18n.t("archive.result.drawn");
            case UNFINISHED -> I18n.t("archive.result.unfinished");
        }, f -> resultFilter = f);
    }

    private void choosePeriod() {
        choose(I18n.t("archive.filter.period"), PeriodFilter.values(), periodFilter, f -> switch (f) {
            case ALL -> I18n.t("archive.period.all");
            case TODAY -> I18n.t("archive.period.today");
            case WEEK -> I18n.t("archive.period.week");
            case MONTH -> I18n.t("archive.period.month");
        }, f -> periodFilter = f);
    }

    private <T> void choose(String title, T[] values, T current, java.util.function.Function<T, String> label,
                            java.util.function.Consumer<T> apply) {
        VBox options = new VBox(10);
        for (T value : values) {
            Region dot = new Region();
            dot.getStyleClass().add("check-dot");
            HBox content = new HBox(16, Ui.label(label.apply(value), "option-title"), Ui.hgrow(), dot);
            content.setAlignment(Pos.CENTER_LEFT);
            Button option = new Button();
            option.getStyleClass().setAll("option");
            option.setGraphic(content);
            option.setMaxWidth(Double.MAX_VALUE);
            content.prefWidthProperty().bind(option.widthProperty().subtract(52));
            option.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), value == current);
            option.setOnAction(e -> {
                apply.accept(value);
                mainController.closeSheet();
                applyFilters();
            });
            options.getChildren().add(option);
        }
        mainController.showSheet(title, options);
    }

    // ================================================================== import / export

    /** "Importa ed esporta": recent games from Lichess or Chess.com, PGN files on a USB drive. */
    private void openTransfer() {
        VBox content = new VBox(12);
        for (OnlineImport.Source source : OnlineImport.Source.values()) {
            content.getChildren().add(transferOption("fth-globe", I18n.t("transfer.online", source.label()),
                    I18n.t("transfer.online.description"), () -> openOnlineImport(source, 50)));
        }
        content.getChildren().add(transferOption("fth-hard-drive", I18n.t("transfer.usb"),
                I18n.t("transfer.usb.description"), this::openUsb));
        mainController.showSheet(I18n.t("transfer.title"), content);
    }

    private static Button transferOption(String icon, String title, String description, Runnable action) {
        Label t = Ui.label(title, "row-title");
        Label d = Ui.wrap(description, "row-sub");
        VBox texts = new VBox(4, t, d);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(18, Icons.of(icon, 30), texts,
                Icons.of("fth-chevron-right", 26));
        row.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(row);
        b.getStyleClass().setAll("option");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMinHeight(104);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Username (on-screen keyboard), how many games, Import; then the result in the same sheet. */
    private void openOnlineImport(OnlineImport.Source source, int count) {
        TouchKeyboard keyboard = new TouchKeyboard(I18n.t("transfer.username", source.label()));
        keyboard.textProperty().set(OnlineImport.lastUsername(source));
        ToggleGroup counts = new ToggleGroup();
        List<ToggleButton> segments = new ArrayList<>();
        for (int n : new int[] { 20, 50, 100 }) {
            ToggleButton seg = new ToggleButton(I18n.t("transfer.count", n));
            seg.setUserData(n);
            seg.setSelected(n == count);
            segments.add(seg);
        }
        HBox countBar = Ui.segmented(counts, segments);
        Ui.keepOneSelected(counts);
        Button start = Ui.wide(I18n.t("transfer.start"), "fth-download", "btn-primary", "btn-lg");
        Runnable go = () -> {
            int max = counts.getSelectedToggle() == null ? 50 : (int) counts.getSelectedToggle().getUserData();
            runOnlineImport(source, keyboard.textProperty().get(), max);
        };
        start.setOnAction(e -> go.run());
        keyboard.setOnDone(go);
        start.disableProperty().bind(keyboard.textProperty().isEmpty());
        VBox content = new VBox(16, keyboard, Ui.label(I18n.t("transfer.count.title"), "row-title"), countBar,
                Ui.wrap(I18n.t("transfer.online.note"), "t-small", "t-muted"), start);
        mainController.showSheet(I18n.t("transfer.online", source.label()), content);
    }

    private void runOnlineImport(OnlineImport.Source source, String user, int max) {
        VBox waiting = new VBox(18, Icons.of("fth-download-cloud", 56),
                Ui.label(I18n.t("transfer.running"), "empty-title"),
                Ui.wrap(I18n.t("transfer.running.detail", user, source.label()), "empty-sub"));
        waiting.getStyleClass().add("empty-state");
        mainController.showSheet(I18n.t("transfer.online", source.label()), waiting);
        new OnlineImport().importRecent(source, user, max).thenAccept(r -> Platform.runLater(() -> {
            showTransferResult(source.label(), r.ok(), r.message());
            if (!r.imported().isEmpty()) {
                loadArchive();
            }
        }));
    }

    private void showTransferResult(String title, boolean ok, String message) {
        VBox done = new VBox(18, Icons.of(ok ? "fth-check-circle" : "fth-alert-circle", 56),
                Ui.wrap(message, "empty-title"));
        done.getStyleClass().add("empty-state");
        Button close = Ui.wide(I18n.t("common.done"), null, "btn-inverse", "btn-lg");
        close.setOnAction(e -> mainController.closeSheet());
        mainController.showSheet(title, new VBox(16, done, close));
    }

    /** The drives plugged in, each with "Esporta tutto" and its PGN files to import. */
    private void openUsb() {
        PgnTransfer transfer = new PgnTransfer();
        AppExecutors.io().execute(() -> {
            List<PgnTransfer.Drive> drives = transfer.drives();
            java.util.Map<PgnTransfer.Drive, List<PgnTransfer.PgnFile>> files = new java.util.LinkedHashMap<>();
            for (PgnTransfer.Drive d : drives) {
                files.put(d, transfer.pgnFiles(d));
            }
            Platform.runLater(() -> showDrives(transfer, files));
        });
    }

    private void showDrives(PgnTransfer transfer, java.util.Map<PgnTransfer.Drive, List<PgnTransfer.PgnFile>> drives) {
        VBox content = new VBox(16);
        if (drives.isEmpty()) {
            VBox empty = new VBox(18, Icons.of("fth-hard-drive", 56),
                    Ui.label(I18n.t("transfer.usb.none"), "empty-title"),
                    Ui.wrap(I18n.t("transfer.usb.none.detail"), "empty-sub"));
            empty.getStyleClass().add("empty-state");
            Button retry = Ui.wide(I18n.t("transfer.usb.retry"), "fth-refresh-cw", "btn-outline", "btn-lg");
            retry.setOnAction(e -> openUsb());
            content.getChildren().addAll(empty, retry);
        }
        drives.forEach((drive, files) -> {
            Button export = Ui.wide(I18n.t("transfer.usb.export"), "fth-upload", "btn-inverse", "btn-lg");
            export.setOnAction(e -> AppExecutors.io().execute(() -> {
                String message;
                boolean ok;
                try {
                    var path = transfer.exportAll(drive, GameArchiveService.getInstance());
                    message = I18n.t("transfer.usb.exported", path.getFileName());
                    ok = true;
                } catch (java.io.IOException | RuntimeException ex) {
                    message = I18n.t("transfer.usb.failed", ErrorReporter.userMessage(ex));
                    ok = false;
                }
                String m = message;
                boolean k = ok;
                Platform.runLater(() -> showTransferResult(drive.label(), k, m));
            }));
            VBox card = new VBox(12, Ui.label(drive.label(), "row-title"), export);
            if (files.isEmpty()) {
                card.getChildren().add(Ui.wrap(I18n.t("transfer.usb.nofiles"), "t-small", "t-muted"));
            } else {
                card.getChildren().add(Ui.label(I18n.t("transfer.usb.files"), "t-small", "t-muted"));
            }
            for (PgnTransfer.PgnFile f : files) {
                card.getChildren().add(transferOption("fth-file-text", f.name(), f.description(),
                        () -> prepareImport(transfer, f)));
            }
            card.getStyleClass().add("card");
            card.setPadding(new Insets(20));
            content.getChildren().add(card);
        });
        mainController.showSheet(I18n.t("transfer.usb"), content);
    }

    /** Counts the games first: a big file takes minutes on the Raspberry, so the first 500 can be chosen. */
    private void prepareImport(PgnTransfer transfer, PgnTransfer.PgnFile file) {
        AppExecutors.io().execute(() -> {
            int games = PgnTransfer.countGames(file);
            Platform.runLater(() -> {
                if (games <= BIG_IMPORT) {
                    runFileImport(transfer, file, Integer.MAX_VALUE);
                    return;
                }
                Button first = Ui.wide(I18n.t("transfer.usb.first", BIG_IMPORT), null, "btn-inverse", "btn-lg");
                first.setOnAction(e -> runFileImport(transfer, file, BIG_IMPORT));
                Button all = Ui.wide(I18n.t("transfer.usb.all", games), null, "btn-outline", "btn-lg");
                all.setOnAction(e -> runFileImport(transfer, file, Integer.MAX_VALUE));
                VBox content = new VBox(16, Ui.wrap(I18n.t("transfer.usb.big", games), "t-body"), first, all);
                mainController.showSheet(file.name(), content);
            });
        });
    }

    private static final int BIG_IMPORT = 500;

    private void runFileImport(PgnTransfer transfer, PgnTransfer.PgnFile file, int max) {
        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("progress-bar");
        Label percent = Ui.label(I18n.t("transfer.usb.importing", 0), "t-body");
        VBox waiting = new VBox(18, Icons.of("fth-file-text", 56), Ui.label(file.name(), "empty-title"), bar, percent);
        waiting.getStyleClass().add("empty-state");
        mainController.showSheet(I18n.t("transfer.usb"), waiting);
        AppExecutors.io().execute(() -> {
            String message;
            boolean ok;
            try {
                var report = transfer.importFile(file, GameArchiveService.getInstance(), max, f -> Platform.runLater(() -> {
                    bar.setProgress(f);
                    percent.setText(I18n.t("transfer.usb.importing", Math.round(f * 100)));
                }));
                message = PgnTransfer.describe(report) + (report.warnings().isEmpty() ? ""
                        : "\n" + String.join("\n", report.warnings().subList(0, Math.min(3, report.warnings().size()))));
                ok = true;
            } catch (java.io.IOException | RuntimeException ex) {
                message = I18n.t("transfer.usb.failed", ErrorReporter.userMessage(ex));
                ok = false;
            }
            String m = message;
            boolean k = ok;
            Platform.runLater(() -> {
                showTransferResult(file.name(), k, m);
                loadArchive();
            });
        });
    }

    /** For demos: the Lichess import sheet. */
    public void devOpenImport() {
        openOnlineImport(OnlineImport.Source.LICHESS, 50);
    }

    private void openSearch() {
        TouchKeyboard keyboard = new TouchKeyboard(I18n.t("archive.search.prompt"));
        keyboard.textProperty().set(query);
        keyboard.textProperty().addListener((obs, o, n) -> setQuery(n));
        keyboard.setOnDone(mainController::closeSheet);
        mainController.showSheet(I18n.t("archive.search.title"), keyboard);
    }

    private void setQuery(String text) {
        query = text == null ? "" : text.trim();
        applyFilters();
    }

    private void applyFilters() {
        refreshFilterLabels();
        String q = query.toLowerCase(Locale.ITALIAN);
        LocalDate today = LocalDate.now();
        List<Row> shown = new ArrayList<>();
        for (Row row : allRows) {
            if (!q.isEmpty() && !row.search().contains(q)) {
                continue;
            }
            ArchivedGame.GameMode mode = row.game().mode();
            boolean modeOk = switch (modeFilter) {
                case ALL -> true;
                case COMPUTER -> mode == ArchivedGame.GameMode.PVC;
                case TWO_PLAYERS -> mode == ArchivedGame.GameMode.PVP;
                case ONLINE -> mode == ArchivedGame.GameMode.LICHESS || mode == ArchivedGame.GameMode.BROWSER;
            };
            boolean resultOk = switch (resultFilter) {
                case ALL -> true;
                case WON -> row.outcome() != null ? row.outcome() > 0 : row.decisive();
                case LOST -> row.outcome() != null ? row.outcome() < 0 : row.decisive();
                case DRAWN -> "1/2-1/2".equals(row.game().result());
                case UNFINISHED -> "*".equals(row.game().result());
            };
            boolean periodOk = periodFilter == PeriodFilter.ALL || (row.day() != null && switch (periodFilter) {
                case TODAY -> row.day().equals(today);
                case WEEK -> !row.day().isBefore(today.minusDays(6));
                case MONTH -> !row.day().isBefore(today.minusDays(29));
                default -> true;
            });
            if (modeOk && resultOk && periodOk) {
                shown.add(row);
            }
        }
        archiveListView.getItems().setAll(shown);
        archiveListView.scrollTo(0);
        boolean filtered = !q.isEmpty() || modeFilter != ModeFilter.ALL || resultFilter != ResultFilter.ALL
                || periodFilter != PeriodFilter.ALL;
        header.setSubtitle(allRows.isEmpty() ? I18n.t("archive.subtitle")
                : filtered ? I18n.t("archive.count.filtered", shown.size(), allRows.size())
                : I18n.t("archive.count", allRows.size()));
        boolean empty = shown.isEmpty();
        emptyState.setVisible(empty);
        archiveListView.setVisible(!empty);
        emptyTitle.setText(I18n.t(allRows.isEmpty() ? "archive.empty" : "archive.empty.filtered"));
        emptySub.setText(I18n.t(allRows.isEmpty() ? "archive.empty.description" : "archive.empty.filtered.hint"));
    }

    // ================================================================== rows

    private final class GameCell extends ListCell<Row> {
        private final ChessBoardUI miniBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(),
                14);
        private final Label day = Ui.label("", "archive-day");
        private final Label title = Ui.label("", "archive-title");
        private final Label meta = Ui.label("", "archive-meta");
        private final Label opening = Ui.label("", "archive-opening");
        private final Label result = Ui.label("", "result-tile");
        private final HBox row;
        private final VBox box;

        GameCell() {
            miniBoard.setShowCoordinates(false);
            VBox texts = new VBox(4, title, meta, opening);
            texts.setAlignment(Pos.CENTER_LEFT);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            result.setMinWidth(Region.USE_PREF_SIZE);
            StackPane thumb = new StackPane(miniBoard);
            thumb.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            row = new HBox(thumb, texts, result);
            row.getStyleClass().add("archive-row");
            row.setId("archive-row");
            row.setOnMouseClicked(e -> {
                if (getItem() != null) {
                    showActions(getItem());
                }
            });
            day.managedProperty().bind(day.visibleProperty());
            box = new VBox(0, day, row);
            box.setPadding(new Insets(0, 0, 12, 0));
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
            setResult(result, game);
            int index = getIndex();
            Row previous = index > 0 && index - 1 < getListView().getItems().size()
                    ? getListView().getItems().get(index - 1) : null;
            boolean newDay = previous == null || !java.util.Objects.equals(previous.day(), game.day());
            day.setVisible(newDay);
            day.setText(dayLabel(game.day()));
            setGraphic(box);
        }
    }

    private static void setResult(Label result, Row game) {
        result.setText(game.result());
        result.getStyleClass().setAll("label", "result-tile");
        if (game.outcome() != null) {
            result.getStyleClass().add(game.outcome() > 0 ? "win" : game.outcome() < 0 ? "loss" : "draw");
        } else if ("1/2-1/2".equals(game.game().result())) {
            result.getStyleClass().add("draw");
        } else if (game.decisive()) {
            result.getStyleClass().add("score");
        }
    }

    static String dayLabel(LocalDate day) {
        if (day == null) {
            return I18n.t("archive.day.unknown");
        }
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return I18n.t("archive.day.today");
        }
        if (day.equals(today.minusDays(1))) {
            return I18n.t("archive.day.yesterday");
        }
        String text = day.format(day.getYear() == today.getYear() ? DAY : DAY_YEAR);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ================================================================== preview and actions

    /** Preview sheet of one game: large final position, players, result, and the actions. */
    private void showActions(Row row) {
        ArchivedGame game = row.game();
        ChessBoardUI board = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 40);
        try {
            board.setPosition(row.finalFen(), null);
        } catch (RuntimeException e) {
            board.resetBoard();
        }
        Label title = Ui.wrap(row.title(), "t-title");
        Label when = Ui.wrap(game.playedAt() == null ? "" : game.playedAt().format(FULL), "t-small", "t-muted");
        Label outcome = Ui.wrap(outcomeLine(game), "t-body-m");
        Label players = Ui.wrap(I18n.t("archive.players", game.white(), game.black()), "t-small", "t-muted");
        Label moves = Ui.wrap(I18n.t("archive.moves", game.fullMoves())
                + (game.opening().isBlank() ? "" : " · " + OpeningNames.italian(game.opening())), "t-small", "t-muted");
        VBox info = new VBox(8, title, when, outcome, players, moves);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);
        HBox top = new HBox(22, board, info);
        top.setAlignment(Pos.TOP_LEFT);

        Button open = Ui.wide(I18n.t("archive.open"), "fth-bar-chart-2", "btn-inverse", "btn-lg");
        open.setOnAction(e -> {
            mainController.closeSheet();
            ReviewController.open(mainController, game);
        });
        Button export = Ui.wide(I18n.t("archive.export"), "fth-download", "btn-outline");
        export.setOnAction(e -> {
            mainController.closeSheet();
            exportGame(row);
        });
        Button delete = Ui.wide(I18n.t("archive.delete"), "fth-trash-2", "btn-danger");
        delete.setOnAction(e -> confirmDelete(row));
        VBox content = new VBox(18, top, Ui.gap(4), open, Ui.equalRow(12, export, delete));
        mainController.showSheet(I18n.t("archive.game"), content);
    }

    private void exportGame(Row row) {
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            try {
                java.nio.file.Path file = io.github.hardin22.javachess.Utils.AppPaths.exportDir()
                        .resolve("javachess-partita-" + row.id() + ".pgn");
                String pgn = GameArchiveService.getInstance().exportPgn(List.of(row.id()));
                java.nio.file.Files.writeString(file, pgn, java.nio.charset.StandardCharsets.UTF_8);
                mainController.showToast(I18n.t("archive.exported", file));
            } catch (java.io.IOException | RuntimeException ex) {
                ErrorReporter.showError(I18n.t("archive.title"), ErrorReporter.userMessage(ex));
            }
        });
    }

    private void confirmDelete(Row row) {
        Label text = Ui.wrap(I18n.t("archive.delete.confirm"), "t-body", "t-muted");
        Button cancel = Ui.wide(I18n.t("common.cancel"), null, "btn-outline", "btn-lg");
        cancel.setOnAction(e -> mainController.closeSheet());
        Button confirm = Ui.wide(I18n.t("archive.delete"), "fth-trash-2", "btn-danger-solid", "btn-lg");
        confirm.setOnAction(e -> {
            mainController.closeSheet();
            // same queue as the game saves: a delete can never overtake a pending save
            io.github.hardin22.javachess.Utils.AppExecutors.storage().execute(() -> {
                GameArchiveService.getInstance().delete(row.id());
                Platform.runLater(this::loadArchive);
            });
        });
        mainController.showModalSheet(I18n.t("archive.delete.title"),
                new VBox(24, text, Ui.equalRow(14, cancel, confirm)));
    }

    // ================================================================== data

    private void loadArchive() {
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            List<Row> rows = readRows();
            Platform.runLater(() -> {
                allRows = rows;
                applyFilters();
            });
        });
    }

    /** Reads the archive (newest first) and maps each game to a {@link Row}. Runs off the FX thread. */
    private List<Row> readRows() {
        List<Row> rows = new ArrayList<>();
        try {
            GameArchiveService archive = GameArchiveService.getInstance();
            String lichessUser = Prefs.string("lichess.username", "").trim().toLowerCase(Locale.ROOT);
            for (ArchivedGame game : archive.list()) {
                String time = game.playedAt() == null ? "" : game.playedAt().format(TIME);
                StringBuilder meta = new StringBuilder(time);
                appendMeta(meta, I18n.t("archive.moves", game.fullMoves()));
                if (!game.timeControl().isEmpty()) {
                    appendMeta(meta, game.timeControl().replace("+", " + "));
                }
                String openingName = "Opening Name".equals(game.opening()) || "Unknown".equalsIgnoreCase(game.opening())
                        ? "" : OpeningNames.italian(game.opening());
                String detail = !openingName.isEmpty() ? openingName
                        : !game.termination().isEmpty() ? game.termination() : "";
                Integer outcome = outcome(game, lichessUser);
                boolean decisive = "1-0".equals(game.result()) || "0-1".equals(game.result());
                String search = (describe(game) + " " + game.white() + " " + game.black() + " " + game.label() + " "
                        + game.opening() + " " + openingName).toLowerCase(Locale.ITALIAN);
                rows.add(new Row(game.id(), describe(game), meta.toString(), detail, resultText(game, outcome),
                        outcome, decisive, game.finalFen().isEmpty() ? START_FEN : game.finalFen(),
                        game.playedAt() == null ? null : game.playedAt().toLocalDate(), game, search));
            }
            String problem = archive.takeLoadProblemNotice(); // once, not at every visit
            if (problem != null) {
                ErrorReporter.showError(I18n.t("archive.title"), problem);
            }
        } catch (RuntimeException e) {
            LOG.warn("Cannot read the archive", e);
        }
        return rows;
    }

    private static void appendMeta(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(part);
    }

    /** Result from the local player's point of view (against the computer, online), null when there is none. */
    static Integer outcome(ArchivedGame game, String lichessUser) {
        Boolean localWhite = localPlaysWhite(game, lichessUser);
        if (localWhite == null) {
            return null;
        }
        return switch (game.result()) {
            case "1-0" -> localWhite ? 1 : -1;
            case "0-1" -> localWhite ? -1 : 1;
            case "1/2-1/2" -> 0;
            default -> null;
        };
    }

    private static Boolean localPlaysWhite(ArchivedGame game, String lichessUser) {
        if (game.mode() == ArchivedGame.GameMode.PVC) {
            if (isLocal(game.white())) {
                return true;
            }
            if (isLocal(game.black())) {
                return false;
            }
            return null;
        }
        if (game.mode() == ArchivedGame.GameMode.LICHESS && !lichessUser.isEmpty()) {
            if (game.white().equalsIgnoreCase(lichessUser)) {
                return true;
            }
            if (game.black().equalsIgnoreCase(lichessUser)) {
                return false;
            }
        }
        return null;
    }

    private static boolean isLocal(String name) {
        return "Giocatore".equalsIgnoreCase(name) || "Tu".equalsIgnoreCase(name);
    }

    private static String resultText(ArchivedGame game, Integer outcome) {
        if (outcome != null) {
            return I18n.t(outcome > 0 ? "archive.tile.won" : outcome < 0 ? "archive.tile.lost" : "archive.tile.draw");
        }
        return switch (game.result()) {
            case "1-0" -> "1-0";
            case "0-1" -> "0-1";
            case "1/2-1/2" -> "½-½";
            default -> "—";
        };
    }

    /** "Persa · scacco matto", "1-0 · per tempo", "Interrotta" (home card, review header, preview). */
    static String outcomeLine(ArchivedGame game) {
        Integer outcome = outcome(game, Prefs.string("lichess.username", "").trim().toLowerCase(Locale.ROOT));
        String head = outcome != null
                ? I18n.t(outcome > 0 ? "archive.outcome.won" : outcome < 0 ? "archive.outcome.lost"
                : "archive.outcome.draw")
                : switch (game.result()) {
                    case "1-0" -> I18n.t("archive.outcome.white");
                    case "0-1" -> I18n.t("archive.outcome.black");
                    case "1/2-1/2" -> I18n.t("archive.outcome.draw");
                    default -> I18n.t("archive.outcome.none");
                };
        String termination = game.termination();
        if (termination.isBlank() || termination.equalsIgnoreCase("Interrotta") && "*".equals(game.result())) {
            return head;
        }
        return head + " · " + termination.toLowerCase(Locale.ITALIAN);
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

    static String describeType(String type) {
        String t = type.replace("Plaver", "Player");
        if (t.startsWith("Player vs Stockfish livello")) {
            return "Stockfish · livello " + t.substring("Player vs Stockfish livello".length()).trim();
        }
        if (t.startsWith("Player vs ") && !t.equals("Player vs Player")) {
            return t.substring("Player vs ".length()).trim(); // e.g. "Maia 1500"
        }
        if (t.equals("Player vs Player")) {
            return I18n.t("pvp.title");
        }
        if (t.startsWith("Online")) {
            return I18n.t("archive.mode.browser");
        }
        return t.isBlank() ? I18n.t("archive.game") : t;
    }

    /** Title of a row: the opponent, or how the game was played. */
    static String describe(ArchivedGame game) {
        String label = game.label();
        if (label.startsWith("Player") || label.startsWith("Plaver") || label.startsWith("Online")) {
            return describeType(label);
        }
        if (game.mode() == ArchivedGame.GameMode.BROWSER) {
            // the browser stores the site ("Chess.com", "Lichess (browser)"): the site is the useful title
            String site = label.replace("(browser)", "").trim();
            return site.isEmpty() ? I18n.t("archive.mode.browser") : I18n.t("archive.mode.site", site);
        }
        return switch (game.mode()) {
            case PVC -> label.isEmpty() ? I18n.t("pvc.title") : label.replace(" livello", " · livello");
            case PVP -> I18n.t("pvp.title");
            case LICHESS -> !"?".equals(game.white()) && !"?".equals(game.black())
                    ? game.white() + " – " + game.black() : label.isEmpty() ? "Lichess" : "Lichess · " + label;
            case BROWSER -> I18n.t("archive.mode.browser");
            case PUZZLE -> I18n.t("puzzle.title");
            case IMPORTED -> label.isEmpty() ? I18n.t("archive.imported") : label;
            default -> label.isEmpty() ? I18n.t("archive.game") : label;
        };
    }
}
