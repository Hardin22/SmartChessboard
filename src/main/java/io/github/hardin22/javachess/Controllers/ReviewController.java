package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
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
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardFrame;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Notation;
import io.github.hardin22.javachess.Components.ReviewLabels;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.OpeningExplorer;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Oggetti.AnalysisPanel;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;
import io.github.hardin22.javachess.Oggetti.EvaluationGraph;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import io.github.hardin22.javachess.Services.GameAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Review of a game. Accuracy of both players, the board with the live evaluation, the label of the current move
 * ("Cf3 è la mossa migliore"), the evaluation graph with the notable moves, the move list with label tiles and a
 * summary; huge navigation buttons at the bottom and horizontal drags on the board to step through the moves.
 */
public class ReviewController implements Screen, GameNavigationListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final int DEBOUNCE_DELAY = 150;

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final ScreenHeader header;
    private final EvalBar evalBar = new EvalBar(22, 560);
    private final BoardFrame boardFrame = new BoardFrame(evalBar);
    private final EvaluationGraph evaluationGraph = new EvaluationGraph(672, 140);

    // summary area: analyse button, progress, accuracy
    private final StackPane summary = new StackPane();
    private final VBox analyzeBox = new VBox(14);
    private final VBox progressBox = new VBox(12);
    private final Region progressFill = new Region();
    private final Label percentLabel = Ui.label("", "t-body-m");
    private final HBox accuracyBox = new HBox(14);
    /** Accuracy texts ("82,5%"), read by the end-to-end tests. */
    private final Label whiteAccuracyLabel = Ui.label("0%", "accuracy-value");
    private final Label blackAccuracyLabel = Ui.label("0%", "accuracy-value");
    private final Button analyzeButton;

    // current move card
    private final HBox moveCard = new HBox();
    private final StackPane moveBadge = new StackPane();
    private final Label moveTitle = Ui.wrap("", "move-card-title");
    private final Label moveSub = Ui.wrap("", "move-card-sub");
    private final Label liveEval = Ui.label("", "eval-chip");

    // tabs
    private final ToggleGroup tabs = new ToggleGroup();
    private final ListView<Integer> moveList = new ListView<>();
    private final VBox breakdown = new VBox(4);
    private final StackPane tabContent = new StackPane();

    // navigation
    private final Label plyLabel = Ui.label("", "ply-label");
    private Button prevButton;
    private Button nextButton;
    private Button firstButton;
    private Button lastButton;

    private int analysisDepth = 18;
    private ChessBoardUI reviewChessBoard;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "review-debounce");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> debounceHandle;
    private ArduinoController arduinoController;
    private List<MoveAnalysis> currentAnalysis;
    private String currentPgn;
    private String currentInitialFen = START_FEN;
    private List<String> sanMoves = List.of();
    private int currentWhiteRating;
    private int currentBlackRating;
    private String backTarget = "ARCHIVE";
    private String gameTitle = "";
    private String opening = "";
    /** Incremented when another game is loaded: a running full analysis of the previous game is discarded. */
    private final java.util.concurrent.atomic.AtomicInteger analysisGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private int[] whiteCounts = new int[0];
    private int[] blackCounts = new int[0];
    private double dragStartX = Double.NaN;

    public ReviewController() {
        header = new ScreenHeader(I18n.t("review.title"), () -> back());
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
        // --- summary: analyse / progress / accuracy
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

        accuracyBox.getChildren().addAll(accuracyCard(true, whiteAccuracyLabel), accuracyCard(false,
                blackAccuracyLabel));
        for (Node n : accuracyBox.getChildren()) {
            HBox.setHgrow(n, Priority.ALWAYS);
            ((Region) n).setMaxWidth(Double.MAX_VALUE);
            ((Region) n).setPrefWidth(1);
        }
        summary.getChildren().addAll(analyzeBox, progressBox, accuracyBox);
        showSummary(analyzeBox);

        // --- board with drag navigation
        boardFrame.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
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

        // --- current move card
        moveCard.getStyleClass().add("move-card");
        moveBadge.setMinSize(56, 56);
        moveBadge.setMaxSize(56, 56);
        VBox moveTexts = new VBox(4, moveTitle, moveSub);
        moveTexts.setMinWidth(0);
        HBox.setHgrow(moveTexts, Priority.ALWAYS);
        liveEval.getStyleClass().add("white-adv");
        moveCard.getChildren().addAll(moveBadge, moveTexts, liveEval);

        // --- graph
        evaluationGraph.setOnMoveSelected(index -> goTo(index + 1));
        evaluationGraph.setMinHeight(104);
        evaluationGraph.setPrefHeight(104);
        evaluationGraph.managedProperty().bind(evaluationGraph.visibleProperty());

        // --- tabs: moves | summary
        ToggleButton movesTab = new ToggleButton(I18n.t("review.tab.moves"));
        ToggleButton summaryTab = new ToggleButton(I18n.t("review.tab.summary"));
        HBox tabBar = Ui.segmented(tabs, List.of(movesTab, summaryTab));
        tabBar.getChildren().forEach(n -> ((Region) n).setMinHeight(72));
        movesTab.setSelected(true);
        tabs.selectedToggleProperty().addListener((obs, o, n) -> showTab());

        moveList.setCellFactory(list -> new MoveRowCell());
        moveList.setFixedCellSize(80);
        moveList.setPrefHeight(160);
        moveList.setFocusTraversable(false);
        moveList.setPlaceholder(Ui.label(I18n.t("moves.empty"), "t-body", "t-faint"));
        tabContent.getChildren().addAll(moveList, Ui.scroll(breakdown));
        VBox.setVgrow(tabContent, Priority.ALWAYS);
        tabContent.setMinHeight(160);
        showTab();

        VBox body = new VBox(14, summary, boardFrame, moveCard, evaluationGraph, tabBar, tabContent);
        for (Node n : new Node[] { summary, moveCard, evaluationGraph, tabBar, tabContent }) {
            VBox.setMargin(n, new Insets(0, 24, 0, 24));
        }
        body.setPadding(new Insets(0, 0, 0, 0));

        // --- navigation
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
        bottom.setPadding(new Insets(12, 0, 0, 0));

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

    /** Portrait: everything in one column. Wide: the board on the left, the rest in a column on the right. */
    @Override
    public void setWide(boolean wide) {
        body.getChildren().clear();
        // Portrait: the board never shrinks below its full width. Wide: it fits the height instead.
        boardFrame.setMinHeight(wide ? 64 : javafx.scene.layout.Region.USE_PREF_SIZE);
        if (!wide) {
            root.setTop(header);
            body.getChildren().addAll(summary, boardFrame, moveCard, evaluationGraph, tabBarNode, tabContent);
            root.setCenter(body);
            root.setBottom(bottomBar);
            return;
        }
        root.setBottom(null);
        root.setTop(null);
        // Board | header, accuracy, current move, graph, navigation | move list and summary.
        body.getChildren().addAll(header, summary, moveCard, evaluationGraph, Ui.vgrow(), bottomBar);
        body.setPrefWidth(620);
        body.setMinWidth(480);
        VBox moves = new VBox(12, tabBarNode, tabContent);
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
    }

    /** Opens moves just played (end of a game). */
    public static void openMoves(MainController main, String uciMoves, String initialFen, String title) {
        ReviewController review = (ReviewController) main.getController("REVIEW");
        review.backTarget = "HOME";
        main.navigateTo("REVIEW");
        review.loadGame(uciMoves, initialFen);
        review.setGameInfo(title, "");
    }

    private void setGameInfo(String title, String detail) {
        gameTitle = title == null ? "" : title;
        header.setSubtitle(gameTitle + (detail == null || detail.isBlank() ? "" : " · " + detail));
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
        analysisGeneration.incrementAndGet();
        this.currentInitialFen = initialFen == null || initialFen.isBlank() ? START_FEN : initialFen;
        if (arduinoController == null) {
            Thread.ofVirtual().start(() -> arduinoController = ArduinoController.getInstance());
        }
        reviewChessBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 70);
        reviewChessBoard.setFitToParent(true);
        reviewChessBoard.setOverlaysEnabled(false);
        reviewChessBoard.setFlipped(mainController != null && mainController.isFacingBlack());
        boardFrame.setBoard(reviewChessBoard);
        reviewChessBoard.loadPgn(pgn, this.currentInitialFen);
        evalBar.updateEvaluation(0);
        sanMoves = Notation.toSan(this.currentInitialFen, reviewChessBoard.getMoveList(), 2000).stream()
                .map(Notation::italian).toList();
        gameTitle = "";
        opening = "";
        header.setSubtitle(I18n.t("review.subtitle"));

        currentAnalysis = null;
        evaluationGraph.setData(null);
        evaluationGraph.setVisible(false);
        showSummary(analyzeBox);
        analyzeButton.setDisable(false);
        whiteAccuracyLabel.setText("0%");
        blackAccuracyLabel.setText("0%");
        whiteCounts = new int[MoveClassification.values().length];
        blackCounts = new int[MoveClassification.values().length];
        rebuildBreakdown();
        List<Integer> rows = new ArrayList<>();
        int rowCount = (sanMoves.size() + (blackStarts() ? 2 : 1)) / 2;
        for (int i = 0; i < rowCount; i++) {
            rows.add(i);
        }
        moveList.getItems().setAll(rows);
        handleMoveUpdate();
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
        String pgn = currentPgn;
        int generation = analysisGeneration.get();
        String fenToAnalyze = currentInitialFen;
        int whiteRating = currentWhiteRating;
        int blackRating = currentBlackRating;
        int totalMoves = reviewChessBoard.getMoveList().size();
        Thread.ofPlatform().daemon().name("game-analysis").start(() -> {
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
                        updateAnalysisUI();
                    }
                }));
                List<MoveAnalysis> analysis = analyzer.lastAnalysis();
                if (analysis.isEmpty() && !pgn.isBlank()) {
                    throw new IllegalStateException("il motore non ha risposto");
                }
                double whiteAccuracy = analyzer.calculateAccuracy(analysis, true);
                double blackAccuracy = analyzer.calculateAccuracy(analysis, false);
                Platform.runLater(() -> {
                    if (generation == analysisGeneration.get()) {
                        showAnalysis(analysis, whiteAccuracy, blackAccuracy);
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

    private void showAnalysis(List<MoveAnalysis> analysis, double whiteAccuracy, double blackAccuracy) {
        this.currentAnalysis = analysis;
        whiteAccuracyLabel.setText(String.format(Locale.ITALIAN, "%.1f%%", whiteAccuracy));
        blackAccuracyLabel.setText(String.format(Locale.ITALIAN, "%.1f%%", blackAccuracy));
        showSummary(accuracyBox);
        countClassifications(analysis);
        rebuildBreakdown();
        evaluationGraph.setVisible(true);
        evaluationGraph.setData(analysis);
        analyzeButton.setDisable(false);
        moveList.refresh();
        updateAnalysisUI();
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

    /** Summary tab: white count | tile + name | black count, one row per label. */
    private void rebuildBreakdown() {
        breakdown.getChildren().clear();
        if (currentAnalysis == null || whiteCounts.length == 0) {
            breakdown.getChildren().add(Ui.wrap(I18n.t("review.summary.empty"), "t-body", "t-muted"));
            return;
        }
        GridPane grid = new GridPane();
        grid.setVgap(6);
        ColumnConstraints side = new ColumnConstraints(88);
        ColumnConstraints mid = new ColumnConstraints();
        mid.setHgrow(Priority.ALWAYS);
        ColumnConstraints side2 = new ColumnConstraints(88);
        grid.getColumnConstraints().addAll(side, mid, side2);
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
    }

    private static Node centered(Node node) {
        HBox box = new HBox(node);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ================================================================== navigation

    private void back() {
        PositionAnalyzer.get().stop();
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
        PositionAnalyzer.get().stop(); // do not keep the engine busy for a screen that is not shown
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
        if (reviewChessBoard != null && reviewChessBoard.hasPreviousMove()) {
            reviewChessBoard.clearArrows();
            reviewChessBoard.clearIcons();
            reviewChessBoard.previousMove();
            handleMoveUpdate();
        }
    }

    private void nextMove() {
        if (reviewChessBoard != null && reviewChessBoard.hasNextMove()) {
            reviewChessBoard.clearArrows();
            reviewChessBoard.clearIcons();
            reviewChessBoard.nextMove();
            handleMoveUpdate();
        }
    }

    private void firstMove() {
        goTo(0);
    }

    private void lastMove() {
        if (reviewChessBoard != null) {
            goTo(reviewChessBoard.getMoveCount());
        }
    }

    /** Jumps to the position after {@code ply} half-moves. Public for DevOptions. */
    public void goTo(int ply) {
        if (reviewChessBoard == null) {
            return;
        }
        reviewChessBoard.clearArrows();
        reviewChessBoard.clearIcons();
        reviewChessBoard.goToMove(ply);
        handleMoveUpdate();
    }

    /** Runs the full-game analysis (engine budget from the profile). Public for DevOptions and tests. */
    public void analyze() {
        startFullAnalysis();
    }

    private void handleMoveUpdate() {
        int index = reviewChessBoard.getCurrentMoveIndex();
        int total = reviewChessBoard.getMoveCount();
        plyLabel.setText(index == 0 ? I18n.t("review.start") : I18n.t("review.ply", index, total));
        prevButton.setDisable(index == 0);
        firstButton.setDisable(index == 0);
        nextButton.setDisable(index >= total);
        lastButton.setDisable(index >= total);
        moveList.refresh();
        if (index > 0) {
            int row = rowOfPly(index);
            Platform.runLater(() -> moveList.scrollTo(Math.max(0, row - 1)));
        }
        triggerAnalysisDebounced();
        updateAnalysisUI();
    }

    private int rowOfPly(int ply) {
        int offset = blackStarts() ? 1 : 0;
        return (ply - 1 + offset) / 2;
    }

    /** Current move card, label tile on the board, best-move arrow on mistakes, graph cursor. */
    private void updateAnalysisUI() {
        if (reviewChessBoard == null) {
            return;
        }
        int ply = reviewChessBoard.getCurrentMoveIndex();
        int analysisIndex = ply - 1;
        evaluationGraph.setHighlightMove(currentAnalysis != null && analysisIndex >= -1
                && analysisIndex < currentAnalysis.size() ? analysisIndex : -1);
        String san = ply > 0 && ply <= sanMoves.size() ? sanMoves.get(ply - 1) : null;
        MoveAnalysis analysis = currentAnalysis != null && analysisIndex >= 0 && analysisIndex < currentAnalysis.size()
                ? currentAnalysis.get(analysisIndex) : null;
        if (san == null) {
            moveBadge.getChildren().setAll(Icons.of("fth-flag", 32));
            moveTitle.setText(I18n.t("review.start"));
            moveSub.setText(opening.isEmpty() ? I18n.t("review.start.hint") : opening);
        } else if (analysis == null || analysis.getClassification() == null) {
            moveBadge.getChildren().setAll(Icons.of("fth-circle", 28));
            moveTitle.setText(moveLabel(ply, san));
            moveSub.setText(currentAnalysis == null ? I18n.t("review.move.analyze") : I18n.t("review.move.pending"));
        } else {
            MoveClassification c = analysis.getClassification();
            moveBadge.getChildren().setAll(ReviewLabels.tile(c, 56));
            moveTitle.setText(ReviewLabels.sentence(c, moveLabel(ply, san)));
            String best = analysis.getBestMove();
            String bestSan = best == null || best.isBlank() ? null
                    : Notation.italian(firstSan(analysis.getFen(), best));
            moveSub.setText(ReviewLabels.bad(c) && bestSan != null ? I18n.t("review.move.best", bestSan)
                    : ReviewLabels.name(c));
            int square = analysis.getToSquareIndex();
            reviewChessBoard.drawLabelOnSquare(square % 8, 7 - (square / 8), c);
            if (ReviewLabels.bad(c) && best != null && best.length() >= 4) {
                reviewChessBoard.drawArrowOnBoard(best.charAt(0) - 'a', '8' - best.charAt(1), best.charAt(2) - 'a',
                        '8' - best.charAt(3), javafx.scene.paint.Color.web("#55B45E"));
            }
        }
    }

    private String moveLabel(int ply, String san) {
        int fullMove = firstMoveNumber() + (ply - 1 + (blackStarts() ? 1 : 0)) / 2;
        boolean whiteMove = (ply - 1 + (blackStarts() ? 1 : 0)) % 2 == 0;
        return fullMove + (whiteMove ? ". " : "… ") + san;
    }

    private static String firstSan(String fen, String uci) {
        List<String> san = Notation.toSan(fen, List.of(uci), 1);
        return san.isEmpty() ? uci : san.get(0);
    }

    /** Engine event thread: eval bar, best-move arrow and evaluation for the reviewed position. */
    private void onReviewAnalysisUpdate(AnalysisUpdate update) {
        String eval = AnalysisPanel.formatScore(update.evalText(0));
        String best = update.bestMove();
        evalBar.updateEvaluation(update.whitePawns());
        Platform.runLater(() -> {
            if (reviewChessBoard == null || !update.fen().equals(reviewChessBoard.getFen())) {
                return; // the user already moved on
            }
            liveEval.setText(eval);
            liveEval.getStyleClass().removeAll("white-adv", "black-adv");
            liveEval.getStyleClass().add(eval.startsWith("−") ? "black-adv" : "white-adv");
            if (currentAnalysis == null && best != null && best.length() >= 4) {
                reviewChessBoard.clearArrows();
                reviewChessBoard.drawArrowOnBoard(best.charAt(0) - 'a', '8' - best.charAt(1),
                        best.charAt(2) - 'a', '8' - best.charAt(3), javafx.scene.paint.Color.rgb(85, 180, 94, 0.8));
            }
        });
    }

    private void triggerAnalysisDebounced() {
        if (debounceHandle != null && !debounceHandle.isDone()) {
            debounceHandle.cancel(false);
        }
        if (reviewChessBoard == null) {
            return;
        }
        String currentFen = reviewChessBoard.getFen(); // read on the FX thread, which owns the board
        boolean mated = reviewChessBoard.getBoard().isMated();
        Side toMove = reviewChessBoard.getBoard().getSideToMove();
        if (mated) {
            liveEval.setText(toMove == Side.WHITE ? "0-1" : "1-0");
        }
        debounceHandle = scheduler.schedule(() -> {
            OpeningExplorer.lookup(currentFen).thenAccept(name -> Platform.runLater(() -> {
                if (name.isPresent()) {
                    opening = name.get();
                    if (gameTitle.isEmpty()) {
                        header.setSubtitle(opening);
                    }
                }
            }));
            if (!mated) {
                PositionAnalyzer.get().analyze(currentFen, analysisDepth, 1, this::onReviewAnalysisUpdate);
            }
        }, DEBOUNCE_DELAY, TimeUnit.MILLISECONDS);
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
            if (reviewChessBoard != null && reviewChessBoard.getCurrentMoveIndex() == ply) {
                cell.getStyleClass().add("current");
            }
            cell.setOnMouseClicked(e -> goTo(ply));
        }
    }
}
