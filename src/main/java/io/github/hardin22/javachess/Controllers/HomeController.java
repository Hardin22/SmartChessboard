package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Play.BotLevels;
import io.github.hardin22.javachess.Play.GameResume;
import io.github.hardin22.javachess.Play.GameSnapshot;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.HardwareStatus;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Logo;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.RotateButton;
import io.github.hardin22.javachess.Components.StatusChip;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Engine.EngineProfile;
import io.github.hardin22.javachess.Engine.EngineSelection;
import io.github.hardin22.javachess.Engine.EngineStatus;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Utils.AppExecutors;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Home. Portrait: brand and state at the top, a quiet greeting, the game to resume or review, and the big actions at
 * the bottom, near the hand of whoever sits at the board.
 */
public class HomeController implements Screen {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN);

    private MainController mainController;
    private final VBox root = new VBox();
    private final StatusChip boardStatus = new StatusChip();
    private final StatusChip engineStatus = new StatusChip();
    private final Label greeting = Ui.label("", "hello");
    private final Label today = Ui.label("", "t-body", "t-muted");
    private final VBox resumeSlot = new VBox();
    private final Label pvcSub = Ui.label("", "tile-sub");
    private final Label pvpSub = Ui.label("", "tile-sub");
    private final Label puzzleValue = Ui.label("—", "tile-value");
    private final Label gamesValue = Ui.label("—", "tile-value");
    private final Label statsValue = Ui.label("", "tile-value");
    private ChessBoardUI resumeBoard;
    private int refreshGeneration;

    public HomeController() {
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
        root.setPadding(new Insets(28, 32, 36, 32));
        root.setSpacing(0);
        root.setFillWidth(true);

        // --- brand row
        Logo logo = new Logo(64);
        Label brand = Ui.label("javaChess", "brand");
        HBox brandRow = new HBox(18, logo, brand, Ui.hgrow(),
                Ui.iconButton("fth-settings", I18n.t("home.settings"), () -> mainController.navigateTo("SETTINGS")),
                RotateButton.create());
        brandRow.setAlignment(Pos.CENTER_LEFT);

        HBox status = new HBox(12, boardStatus, engineStatus);
        status.setAlignment(Pos.CENTER_LEFT);
        status.setPadding(new Insets(20, 0, 0, 0));

        VBox hello = new VBox(4, greeting, today);
        hello.setPadding(new Insets(0, 4, 0, 4));

        // --- actions
        Button pvc = heroTile("fth-cpu", I18n.t("home.pvc.title"), pvcSub, true,
                () -> mainController.navigateTo("PVC_SETUP"));
        Button pvp = heroTile("fth-users", I18n.t("home.pvp.title"), pvpSub, false,
                () -> mainController.navigateTo("PVP_SETUP"));
        Button puzzles = smallTile("fth-target", I18n.t("home.puzzles"), puzzleValue, I18n.t("home.stats.puzzle"),
                () -> mainController.navigateTo("PUZZLE_DASHBOARD"));
        Button archive = smallTile("fth-archive", I18n.t("home.archive"), gamesValue, I18n.t("home.stats.games"),
                () -> mainController.navigateTo("ARCHIVE"));
        Button online = smallTile("fth-globe", I18n.t("home.online.title"), null, I18n.t("home.online.description"),
                this::showOnline);
        online.setId("home-online");
        Button stats = smallTile("fth-bar-chart-2", I18n.t("home.stats"), statsValue, I18n.t("home.stats.description"),
                () -> mainController.navigateTo("STATS"));

        VBox actions = new VBox(16,
                Ui.sectionLabel(I18n.t("home.play")),
                pvc, pvp,
                Ui.gap(4),
                Ui.equalRow(16, puzzles, archive),
                Ui.equalRow(16, online, stats));

        resumeSlot.managedProperty().bind(resumeSlot.visibleProperty());

        this.brandRow = brandRow;
        this.statusRow = status;
        this.helloBox = hello;
        this.actionsBox = actions;
        setWide(false);
    }

    private HBox brandRow;
    private HBox statusRow;
    private VBox helloBox;
    private VBox actionsBox;

    /** Portrait: one column, actions at the bottom. Wide (landscape monitor, desktop): actions on the right. */
    @Override
    public void setWide(boolean wide) {
        root.getChildren().clear();
        if (!wide) {
            actionsBox.setMaxWidth(Double.MAX_VALUE);
            root.getChildren().addAll(brandRow, statusRow, Ui.vgrow(), helloBox, Ui.gap(40), resumeSlot, Ui.vgrow(),
                    actionsBox);
            return;
        }
        VBox left = new VBox(0, brandRow, statusRow, Ui.vgrow(), helloBox, Ui.gap(32), resumeSlot, Ui.vgrow());
        left.setMaxWidth(680);
        actionsBox.setMaxWidth(680);
        VBox right = new VBox(Ui.vgrow(), actionsBox, Ui.vgrow());
        HBox.setHgrow(left, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(right, javafx.scene.layout.Priority.ALWAYS);
        HBox columns = new HBox(64, left, right);
        columns.setAlignment(Pos.CENTER);
        VBox.setVgrow(columns, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().add(columns);
    }

    private Button heroTile(String icon, String title, Label sub, boolean lit, Runnable action) {
        StackPane iconBox = new StackPane(Icons.of(icon, 36));
        iconBox.getStyleClass().add("tile-icon");
        VBox texts = new VBox(6, Ui.label(title, "tile-title"), sub);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox content = new HBox(24, iconBox, texts, Icons.of("fth-arrow-right", 34));
        content.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(content);
        b.getStyleClass().setAll("tile");
        if (lit) {
            b.getStyleClass().add("tile-hero");
        }
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMinHeight(156);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button smallTile(String icon, String title, Label value, String caption, Runnable action) {
        HBox top = new HBox(Icons.of(icon, 32), Ui.hgrow());
        top.setAlignment(Pos.CENTER_LEFT);
        if (value != null) {
            top.getChildren().add(value);
        }
        VBox content = new VBox(14, top, Ui.label(title, "tile-title"), Ui.wrap(caption, "tile-sub"));
        content.setMinWidth(0);
        Button b = new Button();
        b.setGraphic(content);
        b.getStyleClass().setAll("tile");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMaxHeight(Double.MAX_VALUE);
        b.setAlignment(Pos.TOP_LEFT);
        b.setMinHeight(200);
        content.prefWidthProperty().bind(b.widthProperty().subtract(64));
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Online: both sites open in the integrated browser (the physical board follows through the vision module). */
    private void showOnline() {
        Button chessCom = option("fth-monitor", "Chess.com", I18n.t("home.online.chesscom"),
                () -> mainController.openBrowser("https://www.chess.com/login"));
        Button lichess = option("fth-globe", "Lichess", I18n.t("home.online.lichess"),
                () -> mainController.openBrowser("https://lichess.org"));
        VBox content = new VBox(14, chessCom, lichess);
        mainController.showSheet(I18n.t("home.online.title"), content);
    }

    private Button option(String icon, String title, String sub, Runnable action) {
        StackPane iconBox = new StackPane(Icons.of(icon, 32));
        iconBox.getStyleClass().add("tile-icon");
        HBox content = new HBox(22, iconBox, Ui.texts(title, sub, "option-title", "option-sub"),
                Icons.of("fth-external-link", 28));
        content.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(content);
        b.getStyleClass().setAll("option");
        b.setMaxWidth(Double.MAX_VALUE);
        content.prefWidthProperty().bind(b.widthProperty().subtract(52));
        b.setOnAction(e -> {
            mainController.closeSheet();
            action.run();
        });
        return b;
    }

    @Override
    public void onNavigatedTo() {
        HardwareStatus.bind(boardStatus);
        showEngine();
        refresh();
    }

    private void refresh() {
        int hour = LocalTime.now().getHour();
        greeting.setText(I18n.t(hour < 5 ? "home.hello.night" : hour < 13 ? "home.hello.morning"
                : hour < 18 ? "home.hello.afternoon" : "home.hello.evening"));
        String day = LocalDate.now().format(DAY);
        today.setText(Character.toUpperCase(day.charAt(0)) + day.substring(1));
        pvcSub.setText(PvcSetupController.describeLastChoice());
        pvpSub.setText(I18n.t("home.pvp.last", Prefs.integer("game.default.duration", 10),
                Prefs.integer("game.default.increment", 0)));

        ActiveGameController game = activeGame();
        boolean inProgress = game != null && game.isGameInProgress();
        // The card is filled from files read on another thread: until then the one of the last visit must not stay
        // on screen (tapping its "Riprendi" resumed the game saved before the one just left). Refreshes may also
        // finish out of order: only the newest one is shown.
        resumeSlot.setVisible(false);
        resumeSlot.getChildren().clear();
        int generation = ++refreshGeneration;
        AppExecutors.io().execute(() -> {
            var progress = io.github.hardin22.javachess.Services.PuzzleProgressService.getInstance().getStats();
            var archive = io.github.hardin22.javachess.Services.GameArchiveService.getInstance();
            List<ArchivedGame> games = archive.list();
            ArchivedGame last = games.isEmpty() ? null : games.get(0);
            String percent;
            try {
                var score = io.github.hardin22.javachess.Stats.PlayerStats.compute().total();
                percent = score.games() == 0 ? "" : score.percentText();
            } catch (RuntimeException e) {
                percent = "";
            }
            String statsText = percent;
            var interrupted = inProgress ? java.util.Optional.<GameSnapshot>empty() : GameResume.available();
            Platform.runLater(() -> {
                if (generation != refreshGeneration) {
                    return;
                }
                puzzleValue.setText(String.valueOf(progress.rating()));
                gamesValue.setText(String.valueOf(games.size()));
                statsValue.setText(statsText);
                if (inProgress) {
                    showResume(I18n.t("home.resume.current"), game.getTitle(), game.getCurrentFen(),
                            game.getSubtitle(), I18n.t("home.resume.continue"), "fth-play",
                            () -> mainController.navigateTo("GAME"));
                } else if (interrupted.isPresent()) {
                    showInterrupted(interrupted.get());
                } else if (last != null) {
                    showResume(I18n.t("home.resume.last"), ArchiveController.describe(last),
                            last.finalFen(), ArchiveController.outcomeLine(last), I18n.t("home.resume.review"),
                            "fth-bar-chart-2", () -> ReviewController.open(mainController, last));
                } else {
                    resumeSlot.setVisible(false);
                }
            });
        });
    }

    private ActiveGameController activeGame() {
        try {
            return mainController.getController("GAME") instanceof ActiveGameController c ? c : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A game cut short by a restart or a power cut: resume it where it was, or forget it. */
    private void showInterrupted(GameSnapshot s) {
        String title;
        if (s.mode() == GameSnapshot.Mode.PVP) {
            title = I18n.t("home.resume.pvp", s.timeControl().isUnlimited()
                    ? I18n.t("pvc.timecontrol.none") : s.timeControl().label());
        } else {
            title = (s.botLevelId() == null ? java.util.Optional.<BotLevels.Level>empty() : BotLevels.byId(s.botLevelId()))
                    .map(l -> l.name() + " · " + l.eloText()).orElse(I18n.t("game.vs.computer"));
        }
        String detail = I18n.t("home.resume.move", s.moveNumber());
        if (s.savedAt() != null) {
            detail += " · " + s.savedAt().format(SAVED_AT);
        }
        showResume(I18n.t("home.resume.interrupted"), title, s.currentFen(), detail, I18n.t("home.resume.continue"),
                "fth-play", () -> {
                    if (mainController.getController("GAME") instanceof ActiveGameController game) {
                        game.resumeSnapshot(s);
                        mainController.navigateTo("GAME");
                    }
                });
        Button discard = Ui.button(I18n.t("home.resume.discard"), null, "btn-ghost", "btn-md");
        discard.setOnAction(e -> {
            resumeSlot.setVisible(false);
            AppExecutors.io().execute(() -> {
                GameResume.discard();
                Platform.runLater(this::refresh);
            });
        });
        if (resumeActions != null) {
            resumeActions.getChildren().add(discard);
        }
    }

    private static final java.time.format.DateTimeFormatter SAVED_AT =
            java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.ITALIAN);

    private HBox resumeActions;

    private void showResume(String caption, String title, String fen, String detail, String action, String icon,
                            Runnable onAction) {
        if (resumeBoard == null) {
            resumeBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 24);
            resumeBoard.setShowCoordinates(false);
        }
        try {
            resumeBoard.setPosition(fen == null || fen.isBlank()
                    ? "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" : fen, null);
        } catch (RuntimeException e) {
            resumeBoard.resetBoard();
        }
        Label cap = Ui.label(caption, "t-overline");
        Label t = Ui.wrap(title, "t-title");
        VBox texts = new VBox(6, cap, t);
        if (detail != null && !detail.isBlank()) {
            texts.getChildren().add(Ui.wrap(detail, "t-small", "t-muted"));
        }
        Button go = Ui.button(action, icon, "btn-inverse", "btn-md");
        go.setOnAction(e -> onAction.run());
        resumeActions = new HBox(12, go);
        resumeActions.setAlignment(Pos.CENTER_LEFT);
        texts.getChildren().addAll(Ui.vgrow(), resumeActions);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        StackPane boardBox = new StackPane(resumeBoard);
        boardBox.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        HBox row = new HBox(24, boardBox, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(row);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));
        resumeSlot.getChildren().setAll(card);
        resumeSlot.setVisible(true);
    }

    private void showEngine() {
        EngineSelection selection = EngineSelection.get();
        EngineProfile profile = selection.activeProfileProperty().get();
        if (profile == null) {
            engineStatus.set(I18n.t("status.engine.none"), StatusChip.State.OFF);
            return;
        }
        EngineStatus status = selection.statusProperty().get();
        StatusChip.State state = !profile.available() ? StatusChip.State.WARN
                : status == null || status.state() == EngineStatus.State.READY ? StatusChip.State.OK
                : status.state() == EngineStatus.State.LOADING ? StatusChip.State.BUSY : StatusChip.State.WARN;
        engineStatus.set(state == StatusChip.State.BUSY ? I18n.t("engine.loading") : profile.displayName(), state);
    }

    /** Re-reads the engine chip when the engine changes while Home is shown. */
    {
        EngineSelection.get().activeProfileProperty().addListener((obs, o, n) -> {
            if (Platform.isFxApplicationThread()) {
                showEngine();
            }
        });
        EngineSelection.get().statusProperty().addListener((obs, o, n) -> {
            if (Platform.isFxApplicationThread()) {
                showEngine();
            }
        });
    }

    /** For tests and demos. */
    Node resumeCard() {
        return resumeSlot;
    }
}
