package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Board;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Analysis.AnalysisSession;
import io.github.hardin22.javachess.Analysis.BoardFollower;
import io.github.hardin22.javachess.Analysis.EngineLines;
import io.github.hardin22.javachess.Analysis.ReviewInsights;
import io.github.hardin22.javachess.Components.BoardFrame;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Notation;
import io.github.hardin22.javachess.Components.ReviewLabels;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Stepper;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;
import io.github.hardin22.javachess.Oggetti.EvaluationGraph;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import io.github.hardin22.javachess.Services.GameAnalyzer;
import io.github.hardin22.javachess.Analysis.MistakeTrainer;
import io.github.hardin22.javachess.Stats.ReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Review of a game. Accuracy of both players, the board (full width) with the live evaluation, the current move card
 * ("Cf3 è la mossa migliore", "Mostra la migliore", variations), the computer lines under it, the evaluation graph
 * with the notable moves, the move list with label tiles, a summary with the phases and the key moments, and huge
 * navigation buttons; horizontal drags on the board step through the moves and taps on it try other moves.
 *
 * <p>Navigation, variations, computer lines, opening name and the physical board follow the
 * {@link AnalysisSession} view-model (package Analysis); this class only presents it.</p>
 */
public class ReviewController implements Screen, GameNavigationListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final ScreenHeader header;
    private final EvalBar evalBar = new EvalBar(22, 560);
    private final BoardFrame boardFrame = new BoardFrame(evalBar);
    private final EvaluationGraph evaluationGraph = new EvaluationGraph(672, 104);

    // summary area: analyse button, progress, accuracy
    private final StackPane summary = new StackPane();
    private final VBox analyzeBox = new VBox(14);
    private final VBox progressBox = new VBox(12);
    private final Region progressFill = new Region();
    private final Label percentLabel = Ui.label("", "t-body-m");
    private final HBox accuracyWrapper = new HBox(14);
    /** Accuracy texts ("82,5%"), read by the end-to-end tests. */
    private final Label whiteAccuracyLabel = Ui.label("0%", "accuracy-value");
    private final Label blackAccuracyLabel = Ui.label("0%", "accuracy-value");
    private final Button analyzeButton;

    // current move card
    private final HBox moveCard = new HBox(16);
    private final StackPane moveBadge = new StackPane();
    private final Label moveTitle = Ui.wrap("", "move-card-title");
    private final Label moveSub = Ui.wrap("", "move-card-sub");
    private final Label liveEval = Ui.label("", "eval-chip");
    private final HBox moveActions = new HBox(10);

    // computer lines
    private final VBox linesBox = new VBox(6);
    private final Label followStatus = Ui.wrap("", "t-small", "t-accent");

    // tabs
    private final ToggleGroup tabs = new ToggleGroup();
    private final ListView<Integer> moveList = new ListView<>();
    private final VBox breakdown = new VBox(8);
    private final StackPane tabContent = new StackPane();

    // navigation
    private final Label plyLabel = Ui.label("", "ply-label");
    private Button prevButton;
    private Button nextButton;
    private Button firstButton;
    private Button lastButton;

    private ChessBoardUI reviewChessBoard;
    private AnalysisSession session;
    private final List<Runnable> unbind = new ArrayList<>();
    private ArduinoController arduinoController;
    /** Rows of the review (labels for tiles, graph, list); also read by the end-to-end tests. */
    private List<MoveAnalysis> currentAnalysis;
    private GameReview currentReview;
    private String currentPgn;
    private String currentInitialFen = START_FEN;
    private List<String> uciMoves = List.of();
    private List<String> sanMoves = List.of();
    private int currentWhiteRating;
    private int currentBlackRating;
    private String backTarget = "ARCHIVE";
    private String gameTitle = "";
    private String gameDetail = "";
    /** Incremented when another game is loaded: a running full analysis of the previous game is discarded. */
    private final java.util.concurrent.atomic.AtomicInteger analysisGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Thread of the running full-game review: interrupted when the screen is left or another game is loaded. */
    private volatile Thread analysisThread;
    private int[] whiteCounts = new int[0];
    private int[] blackCounts = new int[0];
    private double dragStartX = Double.NaN;
    private int shownPly = -1;

    public ReviewController() {
        header = new ScreenHeader(I18n.t("review.title"), this::back);
        header.setActions(Ui.iconButton("fth-more-horizontal", I18n.t("game.menu"), this::showMenu));
        analyzeButton = Ui.wide(I18n.t("review.analyze"), "fth-activity", "btn-primary", "btn-lg");
        analyzeButton.setOnAction(e -> startFullAnalysis());
        build();
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        mainController.facingBlackProperty().addListener((obs, o, n) -> {
            if (reviewChessBoard != null) {
                reviewChessBoard.setFlipped(n);
            }
        });
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    // ================================================================== layout

    private void build() {
        Label analyzeHint = Ui.wrap(I18n.t("review.analyze.hint"), "t-small", "t-muted");
        analyzeBox.getChildren().addAll(analyzeButton, analyzeHint);

        Region track = new Region();
        track.getStyleClass().add("progress-track");
        progressFill.getStyleClass().add("progress-fill");
        progressFill.setMaxWidth(0);
        StackPane bar = new StackPane(track, progressFill);
        StackPane.setAlignment(progressFill, Pos.CENTER_LEFT);
        bar.widthProperty().addListener((obs, o, n) -> setProgress(lastProgress));
        progressBox.getChildren().addAll(percentLabel, bar, Ui.wrap(I18n.t("review.analyzing.hint"), "t-small",
                "t-muted"));
        progressBox.getStyleClass().add("card");

        accuracyWrapper.getChildren().addAll(accuracyCard(true, whiteAccuracyLabel), accuracyCard(false,
                blackAccuracyLabel));
        for (Node n : accuracyWrapper.getChildren()) {
            HBox.setHgrow(n, Priority.ALWAYS);
            ((Region) n).setMaxWidth(Double.MAX_VALUE);
            ((Region) n).setPrefWidth(1);
        }
        summary.getChildren().addAll(analyzeBox, progressBox, accuracyWrapper);
        showSummary(analyzeBox);

        // board: drags step through the moves, taps try moves (variations)
        boardFrame.setOnMousePressed(e -> dragStartX = e.getX());
        boardFrame.setOnMouseReleased(e -> {
            if (!Double.isNaN(dragStartX)) {
                double dx = e.getX() - dragStartX;
                if (dx < -70) {
                    nextMove();
                } else if (dx > 70) {
                    previousMove();
                }
            }
            dragStartX = Double.NaN;
        });

        // current move card
        moveBadge.setMinSize(56, 56);
        moveBadge.setMaxSize(56, 56);
        VBox moveTexts = new VBox(4, moveTitle, moveSub);
        moveTexts.setMinWidth(0);
        HBox.setHgrow(moveTexts, Priority.ALWAYS);
        liveEval.getStyleClass().add("white-adv");
        liveEval.managedProperty().bind(liveEval.visibleProperty());
        liveEval.setMinWidth(Region.USE_PREF_SIZE);
        moveActions.managedProperty().bind(moveActions.visibleProperty());
        moveActions.setVisible(false);
        moveActions.setAlignment(Pos.CENTER_RIGHT);
        moveActions.setMinWidth(Region.USE_PREF_SIZE);
        // one row: tile, texts, then the actions (or the evaluation when the computer lines are hidden)
        moveCard.getChildren().addAll(moveBadge, moveTexts, moveActions, liveEval);
        moveCard.setAlignment(Pos.CENTER_LEFT);
        moveCard.getStyleClass().add("move-card");
        moveCard.setMinHeight(Region.USE_PREF_SIZE);
        linesBox.setMinHeight(Region.USE_PREF_SIZE);

        // computer lines
        linesBox.managedProperty().bind(linesBox.visibleProperty());
        followStatus.managedProperty().bind(followStatus.visibleProperty());
        followStatus.setVisible(false);

        // graph
        evaluationGraph.setOnMoveSelected(index -> goTo(index + 1));
        evaluationGraph.setMinHeight(88);
        evaluationGraph.setPrefHeight(88);
        evaluationGraph.managedProperty().bind(evaluationGraph.visibleProperty());

        // tabs: moves | summary
        ToggleButton movesTab = new ToggleButton(I18n.t("review.tab.moves"));
        ToggleButton summaryTab = new ToggleButton(I18n.t("review.tab.summary"));
        HBox tabBar = Ui.segmented(tabs, List.of(movesTab, summaryTab));
        movesTab.setSelected(true);
        tabs.selectedToggleProperty().addListener((obs, o, n) -> showTab());

        moveList.setCellFactory(list -> new MoveRowCell());
        moveList.setFixedCellSize(80);
        moveList.setPrefHeight(160);
        moveList.setFocusTraversable(false);
        moveList.setPlaceholder(Ui.label(I18n.t("moves.empty"), "t-body", "t-faint"));
        tabContent.getChildren().addAll(moveList, Ui.scroll(breakdown));
        VBox.setVgrow(tabContent, Priority.ALWAYS);
        tabContent.setMinHeight(120);
        showTab();

        VBox body = new VBox(12, summary, boardFrame, followStatus, moveCard, linesBox, evaluationGraph, tabBar,
                tabContent);
        for (Node n : new Node[] { summary, followStatus, moveCard, linesBox, evaluationGraph, tabBar, tabContent }) {
            VBox.setMargin(n, new Insets(0, 24, 0, 24));
        }

        // navigation
        firstButton = navButton("fth-chevrons-left", I18n.t("review.first"), this::firstMove);
        prevButton = navButton("fth-chevron-left", I18n.t("review.previous"), this::previousMove);
        nextButton = navButton("fth-chevron-right", I18n.t("review.next"), this::nextMove);
        lastButton = navButton("fth-chevrons-right", I18n.t("review.last"), this::lastMove);
        prevButton.getStyleClass().add("main");
        nextButton.getStyleClass().add("main");
        HBox nav = new HBox(12, firstButton, prevButton, nextButton, lastButton);
        nav.getStyleClass().add("nav-bar");
        HBox.setHgrow(prevButton, Priority.ALWAYS);
        HBox.setHgrow(nextButton, Priority.ALWAYS);
        prevButton.setMaxWidth(Double.MAX_VALUE);
        nextButton.setMaxWidth(Double.MAX_VALUE);
        firstButton.setMinWidth(112);
        lastButton.setMinWidth(112);
        HBox plyRow = new HBox(plyLabel);
        plyRow.setAlignment(Pos.CENTER);
        VBox bottom = new VBox(0, plyRow, nav);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        this.body = body;
        this.bottomBar = bottom;
        this.tabBarNode = tabBar;
        root.setTop(header);
        root.setCenter(body);
        root.setBottom(bottom);
    }

    private VBox body;
    private VBox bottomBar;
    private Node tabBarNode;

    /** Portrait: everything in one column. Wide: board | card, lines, graph, navigation | moves and summary. */
    @Override
    public void setWide(boolean wide) {
        body.getChildren().clear();
        // Portrait: the board never shrinks below its full width. Wide: it fits the height instead.
        boardFrame.setMinHeight(wide ? 64 : Region.USE_PREF_SIZE);
        if (!wide) {
            root.setTop(header);
            body.getChildren().addAll(summary, boardFrame, followStatus, moveCard, linesBox, evaluationGraph,
                    tabBarNode, tabContent);
            root.setCenter(body);
            root.setBottom(bottomBar);
            return;
        }
        root.setBottom(null);
        root.setTop(null);
        body.getChildren().addAll(header, summary, moveCard, evaluationGraph, Ui.vgrow(), bottomBar);
        body.setPrefWidth(620);
        body.setMinWidth(480);
        VBox moves = new VBox(12, followStatus, linesBox, tabBarNode, tabContent);
        moves.setPadding(new Insets(24, 0, 24, 0));
        moves.setPrefWidth(520);
        moves.setMinWidth(380);
        HBox.setHgrow(boardFrame, Priority.ALWAYS);
        HBox columns = new HBox(8, boardFrame, body, moves);
        root.setCenter(columns);
    }

    private VBox accuracyCard(boolean white, Label value) {
        Region dot = new Region();
        dot.getStyleClass().addAll("avatar", white ? "white" : "black");
        dot.setStyle("-fx-min-width: 28; -fx-min-height: 28; -fx-max-width: 28; -fx-max-height: 28;"
                + " -fx-background-radius: 9; -fx-border-radius: 9;");
        HBox head = new HBox(10, dot, Ui.label(I18n.t("review.accuracy.of",
                I18n.t(white ? "review.accuracy.white" : "review.accuracy.black")), "t-small", "t-muted"));
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(0, head, value);
        card.getStyleClass().add("accuracy-card");
        return card;
    }

    private Button navButton(String icon, String text, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.of(icon, 40));
        b.getStyleClass().setAll("nav-btn");
        b.setAccessibleText(text);
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void showSummary(Node shown) {
        for (Node n : summary.getChildren()) {
            n.setVisible(n == shown);
            n.setManaged(n == shown);
        }
    }

    private double lastProgress;

    private void setProgress(double fraction) {
        lastProgress = fraction;
        double width = ((Region) progressFill.getParent()).getWidth();
        progressFill.setMaxWidth(Math.max(12, width * fraction));
        progressFill.setMinWidth(Math.max(12, width * fraction));
    }

    private void showTab() {
        boolean moves = tabs.getSelectedToggle() == null || tabs.getToggles().indexOf(tabs.getSelectedToggle()) == 0;
        tabContent.getChildren().get(0).setVisible(moves);
        tabContent.getChildren().get(1).setVisible(!moves);
    }

    // ================================================================== opening a game

    /** Opens an archived game in the review screen (back goes to the screen it came from). */
    public static void open(MainController main, ArchivedGame game) {
        ReviewController review = (ReviewController) main.getController("REVIEW");
        review.backTarget = main.getCurrentViewName() == null ? "HOME" : main.getCurrentViewName();
        main.navigateTo("REVIEW");
        review.loadGame(game.movesAsUciString(), game.initialFen(), game.whiteRating(), game.blackRating());
        review.setGameInfo(ArchiveController.describe(game), ArchiveController.outcomeLine(game));
        review.archivedGame = game;
        review.restoreSavedReview();
    }

    /** Opens moves just played (end of a game). */
    public static void openMoves(MainController main, String uciMoves, String initialFen, String title) {
        ReviewController review = (ReviewController) main.getController("REVIEW");
        review.backTarget = "HOME";
        main.navigateTo("REVIEW");
        review.loadGame(uciMoves, initialFen);
        review.setGameInfo(title, "");
        review.restoreSavedReview();
    }

    /** The archived game shown, or null for moves just played that are not (yet) in the archive. */
    private ArchivedGame archivedGame;

    private String reviewKey() {
        return archivedGame != null ? ReviewStore.key(archivedGame)
                : ReviewStore.key(currentInitialFen, uciMoves, currentWhiteRating, currentBlackRating);
    }

    /** A game already analysed opens with its labels and accuracy at once (read off the FX thread). */
    private void restoreSavedReview() {
        int generation = analysisGeneration.get();
        String key = reviewKey();
        int plies = uciMoves.size();
        analyzeButton.setDisable(true);
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            java.util.Optional<GameReview> saved;
            try {
                saved = ReviewStore.get().find(key).filter(r -> r.moves().size() == plies);
            } catch (RuntimeException e) {
                LOG.warn("Saved review unreadable", e);
                saved = java.util.Optional.empty();
            }
            java.util.Optional<GameReview> found = saved;
            Platform.runLater(() -> {
                if (generation != analysisGeneration.get()) {
                    return; // another game was opened meanwhile
                }
                analyzeButton.setDisable(false);
                found.ifPresent(r -> showAnalysis(GameAnalyzer.toMoveAnalysis(r), r, r.whiteAccuracy(),
                        r.blackAccuracy()));
            });
        });
    }

    private void setGameInfo(String title, String detail) {
        gameTitle = title == null ? "" : title;
        gameDetail = detail == null ? "" : detail;
        refreshHeader();
    }

    private void refreshHeader() {
        String opening = session == null ? "" : session.openingProperty().get();
        String sub = gameTitle.isEmpty() ? (opening.isEmpty() ? I18n.t("review.subtitle") : opening)
                : gameTitle + (gameDetail.isBlank() ? "" : " · " + gameDetail);
        header.setSubtitle(sub);
    }

    public void loadGame(String pgn) {
        loadGame(pgn, START_FEN);
    }

    public void loadGame(String pgn, String initialFen) {
        loadGame(pgn, initialFen, 0, 0);
    }

    /** Loads a game whose players' ratings are known (0 = unknown): the review labels take them into account. */
    public void loadGame(String pgn, String initialFen, int whiteRating, int blackRating) {
        this.currentWhiteRating = whiteRating;
        this.currentBlackRating = blackRating;
        this.currentPgn = pgn;
        cancelFullAnalysis(); // the review of the previous game would keep six engines busy for nothing
        analysisGeneration.incrementAndGet();
        this.currentInitialFen = initialFen == null || initialFen.isBlank() ? START_FEN : initialFen;
        if (arduinoController == null) {
            Thread.ofVirtual().start(() -> arduinoController = ArduinoController.getInstance());
        }
        closeSession();

        uciMoves = pgn == null || pgn.isBlank() ? List.of()
                : Arrays.stream(pgn.trim().split("\\s+")).filter(t -> t.matches("[a-h][1-8][a-h][1-8][qrbnQRBN]?"))
                        .toList();
        sanMoves = Notation.toSan(this.currentInitialFen, uciMoves, 2000).stream().map(Notation::italian).toList();
        if (sanMoves.size() < uciMoves.size()) {
            uciMoves = uciMoves.subList(0, sanMoves.size()); // stop at the first illegal move
        }

        reviewChessBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 70);
        reviewChessBoard.setFitToParent(true);
        reviewChessBoard.setOverlaysEnabled(false);
        reviewChessBoard.setFlipped(mainController != null && mainController.isFacingBlack());
        boardFrame.setBoard(reviewChessBoard);
        evalBar.updateEvaluation(0);

        gameTitle = "";
        gameDetail = "";
        archivedGame = null;
        currentAnalysis = null;
        currentReview = null;
        evaluationGraph.setData(null);
        evaluationGraph.setVisible(false);
        showSummary(analyzeBox);
        analyzeButton.setDisable(false);
        whiteAccuracyLabel.setText("0%");
        blackAccuracyLabel.setText("0%");
        whiteCounts = new int[MoveClassification.values().length];
        blackCounts = new int[MoveClassification.values().length];
        List<Integer> rows = new ArrayList<>();
        int rowCount = (sanMoves.size() + (blackStarts() ? 2 : 1)) / 2;
        for (int i = 0; i < rowCount; i++) {
            rows.add(i);
        }
        moveList.getItems().setAll(rows);
        shownPly = -1;
        openSession();
        rebuildBreakdown();
    }

    /** One view-model per game: navigation, variations, computer lines, opening, physical board. */
    private void openSession() {
        session = new AnalysisSession(currentInitialFen, uciMoves);
        AnalysisSession s = session;
        reviewChessBoard.setMoveInput(new ChessBoardUI.MoveInput() {
            @Override
            public Board position() {
                Board b = new Board();
                b.loadFromFen(s.fenProperty().get());
                return b;
            }

            @Override
            public boolean enabled() {
                return s == session;
            }

            @Override
            public void play(String uci) {
                s.play(uci);
            }
        });
        listen(s.positionProperty(), (obs, o, n) -> onPosition(o, n));
        listen(s.insightProperty(), (obs, o, n) -> {
            refreshMoveCard();
            drawArrows();
        });
        listen(s.inVariationProperty(), (obs, o, n) -> refreshMoveCard());
        listen(s.openingProperty(), (obs, o, n) -> {
            refreshHeader();
            refreshMoveCard();
        });
        EngineLines lines = s.lines();
        listen(lines.linesProperty(), (obs, o, n) -> refreshLines());
        listen(lines.statusProperty(), (obs, o, n) -> refreshLines());
        listen(lines.whitePawnsProperty(), (obs, o, n) -> evalBar.updateEvaluation(n.doubleValue()));
        listen(lines.evalTextProperty(), (obs, o, n) -> refreshEvalChip());
        listen(lines.bestMoveProperty(), (obs, o, n) -> drawArrows());
        BoardFollower follower = s.boardFollower();
        if (follower != null) {
            listen(follower.stateProperty(), (obs, o, n) -> refreshFollow());
            listen(follower.messageProperty(), (obs, o, n) -> refreshFollow());
        }
        onPosition(null, s.positionProperty().get());
        refreshLines();
        refreshFollow();
        refreshHeader();
    }

    private <T> void listen(javafx.beans.value.ObservableValue<T> value, ChangeListener<? super T> listener) {
        value.addListener(listener);
        unbind.add(() -> value.removeListener(listener));
    }

    private void closeSession() {
        unbind.forEach(Runnable::run);
        unbind.clear();
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private boolean blackStarts() {
        String[] parts = currentInitialFen.split(" ");
        return parts.length > 1 && "b".equals(parts[1]);
    }

    private int firstMoveNumber() {
        try {
            return Integer.parseInt(currentInitialFen.split(" ")[5]);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    // ================================================================== full analysis

    /** Full-game review; its engine budget comes from the engine profile. */
    private void startFullAnalysis() {
        if (currentPgn == null) {
            return;
        }
        analyzeButton.setDisable(true);
        showSummary(progressBox);
        setProgress(0);
        percentLabel.setText(I18n.t("review.analyzing", 0));

        GameAnalyzer analyzer = new GameAnalyzer();
        String key = reviewKey();
        String pgn = String.join(" ", uciMoves);
        int generation = analysisGeneration.get();
        String fenToAnalyze = currentInitialFen;
        int whiteRating = currentWhiteRating;
        int blackRating = currentBlackRating;
        int totalMoves = uciMoves.size();
        cancelFullAnalysis();
        analysisThread = Thread.ofPlatform().daemon().name("game-analysis").start(() -> {
            try {
                analyzer.review(pgn, fenToAnalyze, whiteRating, blackRating, progress -> Platform.runLater(() -> {
                    if (generation == analysisGeneration.get()) {
                        setProgress(progress);
                        percentLabel.setText(I18n.t("review.analyzing", Math.round(progress * 100)));
                    }
                }), partial -> Platform.runLater(() -> {
                    if (generation == analysisGeneration.get()) {
                        currentAnalysis = partial;
                        evaluationGraph.setVisible(true);
                        evaluationGraph.setData(partial, totalMoves);
                        moveList.refresh();
                        refreshMoveCard();
                    }
                }));
                List<MoveAnalysis> analysis = analyzer.lastAnalysis();
                if (Thread.currentThread().isInterrupted() || generation != analysisGeneration.get()) {
                    return; // cancelled: the screen was left or another game was opened
                }
                if (analysis.isEmpty() && !pgn.isBlank()) {
                    throw new IllegalStateException("il motore non ha risposto");
                }
                double whiteAccuracy = analyzer.calculateAccuracy(analysis, true);
                double blackAccuracy = analyzer.calculateAccuracy(analysis, false);
                GameReview review = analyzer.lastReview();
                if (review != null) {
                    ReviewStore.get().save(key, review); // next time the game opens already analysed
                }
                Platform.runLater(() -> {
                    if (generation == analysisGeneration.get()) {
                        showAnalysis(analysis, review, whiteAccuracy, blackAccuracy);
                    }
                });
            } catch (RuntimeException | Error e) {
                LOG.error("Game analysis failed", e);
                if (generation != analysisGeneration.get()) {
                    return;
                }
                Platform.runLater(() -> {
                    showSummary(analyzeBox);
                    analyzeButton.setDisable(false);
                });
                io.github.hardin22.javachess.Utils.ErrorReporter.showError(I18n.t("review.title"),
                        "Analisi non riuscita: " + io.github.hardin22.javachess.Utils.ErrorReporter.userMessage(e)
                                + "\nControlla che Stockfish sia installato (Impostazioni).");
            }
        });
    }

    private void showAnalysis(List<MoveAnalysis> analysis, GameReview review, double whiteAccuracy,
                              double blackAccuracy) {
        this.currentAnalysis = analysis;
        this.currentReview = review;
        whiteAccuracyLabel.setText(accuracyText(whiteAccuracy));
        blackAccuracyLabel.setText(accuracyText(blackAccuracy));
        showSummary(accuracyWrapper);
        countClassifications(analysis);
        evaluationGraph.setVisible(true);
        evaluationGraph.setData(analysis);
        analyzeButton.setDisable(false);
        if (session != null && review != null) {
            session.attachReview(review);
        }
        rebuildBreakdown();
        moveList.refresh();
        scrollMoveListToCurrent();
        refreshMoveCard();
        drawArrows();
    }

    /** "87,4%", or a dash for a side that made no counted move. */
    private static String accuracyText(double accuracy) {
        return Double.isNaN(accuracy) ? "–" : String.format(Locale.ITALIAN, "%.1f%%", accuracy);
    }

    private void countClassifications(List<MoveAnalysis> analysis) {
        whiteCounts = new int[MoveClassification.values().length];
        blackCounts = new int[MoveClassification.values().length];
        for (MoveAnalysis move : analysis) {
            if (move.getClassification() == null) {
                continue;
            }
            int ordinal = move.getClassification().ordinal();
            if (move.isWhiteMove()) {
                whiteCounts[ordinal]++;
            } else {
                blackCounts[ordinal]++;
            }
        }
    }

    // ================================================================== summary tab

    /** Label counts (White | label | Black), accuracy by phase and the key moments (tap = go there). */
    private void rebuildBreakdown() {
        breakdown.getChildren().clear();
        if (currentAnalysis == null || whiteCounts.length == 0 || currentReview == null) {
            breakdown.getChildren().add(Ui.wrap(I18n.t("review.summary.empty"), "t-body", "t-muted"));
            return;
        }
        addReplayButton();
        GridPane grid = threeColumns();
        grid.add(centered(Ui.label(I18n.t("common.white"), "t-caption", "t-muted")), 0, 0);
        grid.add(centered(Ui.label(I18n.t("common.black"), "t-caption", "t-muted")), 2, 0);
        int r = 1;
        for (MoveClassification c : ReviewLabels.ORDER) {
            int w = whiteCounts[c.ordinal()];
            int b = blackCounts[c.ordinal()];
            if (c == MoveClassification.FORCED && w + b == 0) {
                continue;
            }
            Label wl = Ui.label(String.valueOf(w), "breakdown-count");
            Label bl = Ui.label(String.valueOf(b), "breakdown-count");
            if (w == 0) {
                wl.getStyleClass().add("t-faint");
            }
            if (b == 0) {
                bl.getStyleClass().add("t-faint");
            }
            HBox name = new HBox(16, ReviewLabels.tile(c, 40), Ui.label(ReviewLabels.name(c), "t-body-m"));
            name.getStyleClass().add("breakdown-row");
            grid.add(centered(wl), 0, r);
            grid.add(name, 1, r);
            grid.add(centered(bl), 2, r);
            r++;
        }
        breakdown.getChildren().add(grid);

        // accuracy by phase
        ReviewInsights.PhaseSummary phases = ReviewInsights.phases(currentReview);
        GridPane phaseGrid = threeColumns();
        int row = 0;
        for (ReviewInsights.Phase phase : ReviewInsights.Phase.values()) {
            ReviewInsights.PhaseScore w = phases.white().stream().filter(p -> p.phase() == phase).findFirst()
                    .orElse(null);
            ReviewInsights.PhaseScore b = phases.black().stream().filter(p -> p.phase() == phase).findFirst()
                    .orElse(null);
            if (w == null && b == null) {
                continue;
            }
            phaseGrid.add(centered(phaseValue(w)), 0, row);
            HBox name = new HBox(Ui.label(phase.italian(), "t-body-m"));
            name.getStyleClass().add("breakdown-row");
            phaseGrid.add(name, 1, row);
            phaseGrid.add(centered(phaseValue(b)), 2, row);
            row++;
        }
        breakdown.getChildren().addAll(Ui.sectionLabel(I18n.t("review.phases.title")), phaseGrid);

        // key moments
        breakdown.getChildren().add(Ui.sectionLabel(I18n.t("review.moments.title")));
        List<ReviewInsights.KeyMoment> moments = ReviewInsights.keyMoments(currentReview, null);
        if (moments.isEmpty()) {
            breakdown.getChildren().add(Ui.wrap(I18n.t("review.moments.empty"), "t-body", "t-muted"));
        }
        for (ReviewInsights.KeyMoment m : moments) {
            Label move = Ui.label(m.moveText(), "review-move-san");
            Label best = Ui.label(m.bestText().isEmpty() ? ReviewLabels.name(m.label())
                    : I18n.t("review.moments.best", m.bestText()), "t-small", "t-muted");
            best.setTextOverrun(OverrunStyle.ELLIPSIS);
            VBox texts = new VBox(2, move, best);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            Label swing = Ui.label(m.swingText(), "t-body-m", "t-danger");
            HBox line = new HBox(16, ReviewLabels.tile(m.label(), 40), texts, swing);
            line.getStyleClass().add("review-move");
            line.setMinHeight(80);
            line.setOnMouseClicked(e -> goTo(m.ply() + 1));
            breakdown.getChildren().add(line);
        }
    }

    /** "Rigioca i tuoi errori": the positions where the player went wrong, to find the better move. */
    private void addReplayButton() {
        java.util.Optional<Boolean> mine = archivedGame == null ? java.util.Optional.empty()
                : io.github.hardin22.javachess.Stats.PlayerStats.localSide(archivedGame);
        List<MistakeTrainer.Exercise> white = MistakeTrainer.exercises(currentReview, true, false);
        List<MistakeTrainer.Exercise> black = MistakeTrainer.exercises(currentReview, false, false);
        boolean any = mine.map(w -> !(w ? white : black).isEmpty()).orElse(!white.isEmpty() || !black.isEmpty());
        if (!any) {
            return;
        }
        int count = mine.map(w -> (w ? white : black).size()).orElse(white.size() + black.size());
        Button replay = Ui.wide(I18n.t("review.retry.mistakes"), "fth-target", "btn-primary", "btn-lg");
        replay.setOnAction(e -> {
            if (mine.isPresent()) {
                openTrainer(mine.get() ? white : black);
            } else {
                chooseTrainerSide(white, black);
            }
        });
        Label hint = Ui.wrap(I18n.t("review.retry.mistakes.count", count), "t-small", "t-muted");
        VBox box = new VBox(8, replay, hint);
        box.setPadding(new Insets(0, 0, 12, 0));
        breakdown.getChildren().add(box);
    }

    /** A game between two people: whose mistakes? */
    private void chooseTrainerSide(List<MistakeTrainer.Exercise> white, List<MistakeTrainer.Exercise> black) {
        VBox content = new VBox(14);
        for (boolean w : new boolean[] { true, false }) {
            List<MistakeTrainer.Exercise> list = w ? white : black;
            Button b = Ui.wide(I18n.t(w ? "trainer.side.white" : "trainer.side.black", list.size()), null,
                    "btn-outline", "btn-lg");
            b.setDisable(list.isEmpty());
            b.setOnAction(e -> {
                mainController.closeSheet();
                openTrainer(list);
            });
            content.getChildren().add(b);
        }
        mainController.showSheet(I18n.t("trainer.side.question"), content);
    }

    private void openTrainer(List<MistakeTrainer.Exercise> exercises) {
        if (session != null) {
            session.setBoardFollowing(false); // the trainer guides the board itself
        }
        TrainerController.open(mainController, exercises, session == null ? null : session.boardFollower());
    }

    private static GridPane threeColumns() {
        GridPane grid = new GridPane();
        grid.setVgap(6);
        ColumnConstraints side = new ColumnConstraints(96);
        ColumnConstraints mid = new ColumnConstraints();
        mid.setHgrow(Priority.ALWAYS);
        ColumnConstraints side2 = new ColumnConstraints(96);
        grid.getColumnConstraints().addAll(side, mid, side2);
        return grid;
    }

    private static Node phaseValue(ReviewInsights.PhaseScore score) {
        if (score == null) {
            return Ui.label("—", "breakdown-count", "t-faint");
        }
        Label value = Ui.label(score.accuracyText(), "breakdown-count");
        switch (score.grade()) {
            case EXCELLENT -> value.getStyleClass().add("t-ok");
            case POOR -> value.getStyleClass().add("t-danger");
            case FAIR -> value.getStyleClass().add("t-warn");
            default -> {
            }
        }
        return value;
    }

    private static Node centered(Node node) {
        HBox box = new HBox(node);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ================================================================== menu

    /** "⋯": number of computer lines, lines on/off, physical board, export with variations. */
    private void showMenu() {
        if (session == null) {
            return;
        }
        EngineLines lines = session.lines();
        Stepper count = new Stepper(1, EngineLines.MAX_LINES, 1, lines.lineCountProperty().get());
        count.format(String::valueOf, I18n.t("analysis.lines.unit"));
        count.valueProperty().addListener((obs, o, n) -> lines.setLineCount(n.intValue()));
        ToggleButton show = Ui.toggleSwitch(lines.enabledProperty().get());
        show.setOnAction(e -> {
            lines.setEnabled(show.isSelected());
            refreshLines();
        });
        VBox content = new VBox(14,
                switchRow(I18n.t("analysis.lines.show"), I18n.t("analysis.lines.count.description"), show),
                Ui.label(I18n.t("analysis.lines.count"), "row-title"), count);
        BoardFollower follower = session.boardFollower();
        if (follower != null) {
            ToggleButton follow = Ui.toggleSwitch(follower.isOn());
            follow.setOnAction(e -> session.setBoardFollowing(follow.isSelected()));
            content.getChildren().add(switchRow(I18n.t("analysis.board.follow"),
                    I18n.t("analysis.board.follow.description"), follow));
        }
        if (session.hasVariationsProperty().get()) {
            Button export = Ui.wide(I18n.t("analysis.export.variations"), "fth-download", "btn-outline");
            export.setOnAction(e -> {
                mainController.closeSheet();
                exportWithVariations();
            });
            content.getChildren().add(export);
        }
        mainController.showSheet(I18n.t("review.menu"), content);
    }

    private static HBox switchRow(String title, String description, ToggleButton toggle) {
        HBox row = new HBox(16, Ui.texts(title, description, "row-title", "row-sub"), toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(96);
        return row;
    }

    private void exportWithVariations() {
        String movetext = session == null ? "" : session.movetext();
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            try {
                java.nio.file.Path file = io.github.hardin22.javachess.Utils.AppPaths.exportDir()
                        .resolve("javachess-analisi-" + System.currentTimeMillis() + ".pgn");
                String pgn = "[Event \"javaChess analysis\"]\n"
                        + (START_FEN.equals(currentInitialFen) ? "" : "[SetUp \"1\"]\n[FEN \"" + currentInitialFen
                        + "\"]\n") + "\n" + movetext + " *\n";
                java.nio.file.Files.writeString(file, pgn, java.nio.charset.StandardCharsets.UTF_8);
                mainController.showToast(I18n.t("archive.exported", file));
            } catch (java.io.IOException | RuntimeException ex) {
                io.github.hardin22.javachess.Utils.ErrorReporter.showError(I18n.t("review.title"),
                        io.github.hardin22.javachess.Utils.ErrorReporter.userMessage(ex));
            }
        });
    }

    // ================================================================== navigation

    private void back() {
        closeSession();
        if (arduinoController != null) {
            arduinoController.getBoardStateManager().stopGameMode();
        }
        mainController.navigateTo(backTarget == null || "REVIEW".equals(backTarget) ? "HOME" : backTarget);
    }

    @Override
    public boolean onBack() {
        back();
        return true;
    }

    @Override
    public void onNavigatedFrom() {
        // do not keep the engine (or the physical board) busy for a screen that is not shown
        if (session != null) {
            session.lines().stop();
            session.setBoardFollowing(false);
        }
        if (cancelFullAnalysis()) {
            analysisGeneration.incrementAndGet(); // its results are no longer wanted
            showSummary(analyzeBox);
            analyzeButton.setDisable(false);
        }
    }

    /** Stops the running full-game review, if any (its engines are closed). True when one was running. */
    private boolean cancelFullAnalysis() {
        Thread running = analysisThread;
        analysisThread = null;
        if (running != null && running.isAlive()) {
            running.interrupt();
            return true;
        }
        return false;
    }

    @Override
    public void onNavigatedTo() {
        if (session != null) {
            session.lines().show(session.fenProperty().get());
        }
    }

    @Override
    public void onPreviousMove() {
        Platform.runLater(this::previousMove);
    }

    @Override
    public void onNextMove() {
        Platform.runLater(this::nextMove);
    }

    private void previousMove() {
        if (session != null) {
            session.previous();
        }
    }

    private void nextMove() {
        if (session != null) {
            session.next();
        }
    }

    private void firstMove() {
        if (session != null) {
            session.first();
        }
    }

    private void lastMove() {
        if (session != null) {
            session.last();
        }
    }

    /** Jumps to the position after {@code ply} half-moves of the game. Public for DevOptions. */
    public void goTo(int ply) {
        if (session != null) {
            session.goToPly(ply);
        }
    }

    /** Plays the best line instead of the current move and steps once into it (DevOptions demos). */
    /** True once the game is analysed (just now or restored from the saved review). */
    public boolean hasReview() {
        return currentReview != null;
    }

    public MainController mainControllerForDemo() {
        return mainController;
    }

    /** For demos: the summary tab. */
    public void devShowSummary() {
        tabs.getToggles().get(1).setSelected(true);
    }

    /** For demos: the trainer on White's mistakes (else Black's) once the review is there; false before. */
    public boolean devOpenTrainer() {
        if (currentReview == null) {
            return false;
        }
        List<MistakeTrainer.Exercise> white = MistakeTrainer.exercises(currentReview, true, false);
        openTrainer(white.isEmpty() ? MistakeTrainer.exercises(currentReview, false, false) : white);
        return true;
    }

    public void devShowBest() {
        if (session != null && session.showBestLine()) {
            session.next();
        }
    }

    /** Runs the full-game analysis (engine budget from the profile). Public for DevOptions and tests. */
    public void analyze() {
        startFullAnalysis();
    }

    /** A new position from the view-model: board (sliding one step forward), list, graph, card, labels. */
    private void onPosition(AnalysisSession.Position before, AnalysisSession.Position now) {
        if (now == null || reviewChessBoard == null || session == null) {
            return;
        }
        boolean oneStepForward = before != null && now.ply() == before.ply() + 1 && now.lastMove() != null;
        reviewChessBoard.clearArrows();
        reviewChessBoard.clearIcons();
        reviewChessBoard.showPosition(now.fen(), now.lastMove(), oneStepForward);
        int ply = now.mainPly();
        int total = uciMoves.size();
        plyLabel.setText(now.inVariation() ? I18n.t("review.ply.variation", ply, total)
                : ply == 0 ? I18n.t("review.start") : I18n.t("review.ply", ply, total));
        prevButton.setDisable(!session.canGoBackProperty().get());
        firstButton.setDisable(!session.canGoBackProperty().get());
        nextButton.setDisable(!session.canGoForwardProperty().get());
        lastButton.setDisable(!session.canGoForwardProperty().get() && !now.inVariation());
        evaluationGraph.setHighlightMove(currentAnalysis != null ? ply - 1 : -1);
        if (ply != shownPly) {
            shownPly = ply;
            moveList.refresh();
            scrollMoveListToCurrent();
        }
        refreshMoveCard();
        refreshEvalChip();
        drawArrows();
    }

    /** Keeps the current move visible in the list (again after layout: the list may not have its height yet). */
    private void scrollMoveListToCurrent() {
        if (shownPly <= 0) {
            return;
        }
        int row = Math.max(0, rowOfPly(shownPly) - 1);
        moveList.scrollTo(row);
        Platform.runLater(() -> moveList.scrollTo(row));
    }

    private int rowOfPly(int ply) {
        int offset = blackStarts() ? 1 : 0;
        return (ply - 1 + offset) / 2;
    }

    /** Label tile on the destination square, and an arrow: the better move after a mistake, else the best one. */
    private void drawArrows() {
        if (reviewChessBoard == null || session == null) {
            return;
        }
        reviewChessBoard.clearArrows();
        reviewChessBoard.clearIcons();
        ReviewInsights.MoveInsight insight = session.insightProperty().get();
        if (insight != null && insight.label() != null) {
            String played = insight.played();
            if (played != null && played.length() >= 4) {
                reviewChessBoard.drawLabelOnSquare(played.charAt(2) - 'a', '8' - played.charAt(3), insight.label());
            }
        }
        String arrow = insight != null && insight.showBest() ? insight.best() : session.lines().bestMoveProperty().get();
        if (arrow != null && arrow.length() >= 4) {
            reviewChessBoard.drawArrowOnBoard(arrow.charAt(0) - 'a', '8' - arrow.charAt(1), arrow.charAt(2) - 'a',
                    '8' - arrow.charAt(3), javafx.scene.paint.Color.web("#55B45E"));
        }
    }

    private void refreshEvalChip() {
        if (session == null) {
            return;
        }
        String eval = session.lines().evalTextProperty().get();
        liveEval.setText(eval == null || eval.isBlank() ? "…" : eval);
        liveEval.getStyleClass().removeAll("white-adv", "black-adv");
        liveEval.getStyleClass().add(eval != null && (eval.startsWith("−") || eval.startsWith("-") || eval.equals("0-1"))
                ? "black-adv" : "white-adv");
    }

    /** The card under the board: the move and its label, a variation, or the starting position. */
    private void refreshMoveCard() {
        if (session == null) {
            return;
        }
        moveActions.getChildren().clear();
        String opening = session.openingProperty().get();
        if (session.inVariationProperty().get()) {
            moveBadge.getChildren().setAll(Icons.of("fth-git-branch", 30));
            moveTitle.setText(I18n.t("analysis.variation"));
            moveSub.setText(session.variationTextProperty().get());
            Button back = Ui.button(I18n.t("analysis.variation.back"), "fth-corner-up-left", "btn-inverse", "btn-md");
            back.setOnAction(e -> session.backToGame());
            Button delete = Ui.iconButton("fth-trash-2", I18n.t("analysis.variation.delete"),
                    () -> session.deleteVariation());
            moveActions.getChildren().addAll(back, delete);
        } else if (session.mainPlyProperty().get() == 0) {
            moveBadge.getChildren().setAll(Icons.of("fth-flag", 32));
            moveTitle.setText(I18n.t("review.start"));
            moveSub.setText(opening.isEmpty() ? I18n.t("review.start.hint") : opening);
        } else {
            ReviewInsights.MoveInsight insight = session.insightProperty().get();
            int ply = session.mainPlyProperty().get();
            String title = session.titleProperty().get();
            if (insight == null || insight.label() == null) {
                MoveAnalysis partial = currentAnalysis != null && ply - 1 < currentAnalysis.size()
                        ? currentAnalysis.get(ply - 1) : null;
                if (partial != null && partial.getClassification() != null) {
                    moveBadge.getChildren().setAll(ReviewLabels.tile(partial.getClassification(), 56));
                    moveTitle.setText(ReviewLabels.sentence(partial.getClassification(), title));
                } else {
                    moveBadge.getChildren().setAll(Icons.of("fth-circle", 28));
                    moveTitle.setText(title);
                }
                moveSub.setText(session.bookMoveProperty().get() && !opening.isEmpty() ? opening
                        : currentAnalysis == null ? I18n.t("review.move.analyze") : I18n.t("review.move.pending"));
            } else {
                MoveClassification c = insight.label();
                moveBadge.getChildren().setAll(ReviewLabels.tile(c, 56));
                // with a button beside it the sentence would not fit on one line: move and label instead
                moveTitle.setText(insight.showBest() ? insight.moveText() + " · " + ReviewLabels.name(c).toLowerCase(
                        Locale.ITALIAN) : ReviewLabels.sentence(c, insight.moveText()));
                if (insight.book() && !opening.isEmpty()) {
                    moveSub.setText(opening);
                } else if (!insight.bestText().isEmpty() && ReviewLabels.bad(c)) {
                    moveSub.setText(I18n.t("analysis.best.was", insight.bestText()));
                } else {
                    moveSub.setText(ReviewLabels.name(c));
                }
                if (insight.showBest()) {
                    Button best = Ui.button(I18n.t("analysis.best.show.short"), "fth-eye", "btn-outline", "btn-md");
                    best.setOnAction(e -> session.showBestLine());
                    moveActions.getChildren().add(best);
                }
            }
        }
        moveActions.setVisible(!moveActions.getChildren().isEmpty());
        liveEval.setVisible(moveActions.getChildren().isEmpty() && !session.lines().enabledProperty().get());
    }

    /** Computer lines: evaluation chip, the line on one row, depth; a tap plays the line as a variation. */
    private void refreshLines() {
        if (session == null) {
            return;
        }
        EngineLines lines = session.lines();
        linesBox.getChildren().clear();
        linesBox.setVisible(lines.enabledProperty().get());
        List<EngineLines.Line> list = lines.linesProperty().get();
        EngineLines.Status status = lines.statusProperty().get();
        if (list == null || list.isEmpty()) {
            String message = lines.messageProperty().get();
            Label text = Ui.label(status == EngineLines.Status.SEARCHING || message == null || message.isBlank()
                    ? I18n.t("analysis.lines.searching") : message, "t-small", "t-muted");
            HBox placeholder = new HBox(text);
            placeholder.getStyleClass().add("coach-line");
            placeholder.setMinHeight(64);
            linesBox.getChildren().add(placeholder);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            EngineLines.Line line = list.get(i);
            Label eval = Ui.label(line.eval(), "eval-chip", line.whiteBetter() ? "white-adv" : "black-adv");
            Label text = Ui.label(line.text(), "t-body-m");
            text.setTextOverrun(OverrunStyle.ELLIPSIS);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMaxWidth(Double.MAX_VALUE);
            Label depth = Ui.label(I18n.t("analysis.lines.depth", line.depth()), "t-small", "t-faint");
            depth.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = new HBox(14, eval, text, depth);
            row.getStyleClass().addAll("coach-line", "row-press");
            row.setMinHeight(64);
            row.setPrefHeight(64);
            int index = i;
            row.setOnMouseClicked(e -> session.playLine(index));
            linesBox.getChildren().add(row);
        }
    }

    private void refreshFollow() {
        BoardFollower follower = session == null ? null : session.boardFollower();
        boolean on = follower != null && follower.stateProperty().get() != BoardFollower.State.OFF;
        followStatus.setVisible(on);
        followStatus.setText(on ? follower.messageProperty().get() : "");
    }

    // ================================================================== move list

    /** One row: number, White's move, Black's move; each with its label tile once the game is analysed. */
    private final class MoveRowCell extends ListCell<Integer> {
        private final Label number = Ui.label("", "move-number", "t-body");
        private final HBox white = new HBox(10);
        private final HBox black = new HBox(10);
        private final HBox row;

        MoveRowCell() {
            number.setMinWidth(64);
            for (HBox cell : new HBox[] { white, black }) {
                cell.getStyleClass().add("review-move");
                cell.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(cell, Priority.ALWAYS);
                cell.setPrefWidth(1);
            }
            row = new HBox(8, number, white, black);
            row.getStyleClass().add("review-move-row");
        }

        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            int offset = blackStarts() ? 1 : 0;
            int whitePly = item * 2 + 1 - offset;   // 1-based ply of White's move in this row
            number.setText((firstMoveNumber() + item) + ".");
            fill(white, whitePly);
            fill(black, whitePly + 1);
            setGraphic(row);
        }

        private void fill(HBox cell, int ply) {
            cell.getChildren().clear();
            cell.getStyleClass().remove("current");
            cell.setOnMouseClicked(null);
            if (ply < 1 || ply > sanMoves.size()) {
                return;
            }
            MoveAnalysis a = currentAnalysis != null && ply - 1 < currentAnalysis.size()
                    ? currentAnalysis.get(ply - 1) : null;
            if (a != null && a.getClassification() != null) {
                cell.getChildren().add(ReviewLabels.tile(a.getClassification(), 30));
            }
            cell.getChildren().add(Ui.label(sanMoves.get(ply - 1), "review-move-san"));
            if (shownPly == ply) {
                cell.getStyleClass().add("current");
            }
            cell.setOnMouseClicked(e -> goTo(ply));
        }
    }
}
