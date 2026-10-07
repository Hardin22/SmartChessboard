package org.example.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.javachess.Components.BoardThemes;
import org.example.javachess.Components.I18n;
import org.example.javachess.Components.MoveListView;
import org.example.javachess.Components.PageHeader;
import org.example.javachess.Oggetti.AnalysisPanel;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Oggetti.EvalBar;
import org.example.javachess.Oggetti.EvaluationGraph;
import org.example.javachess.Oggetti.MoveAnalysis;
import org.example.javachess.Engine.AnalysisUpdate;
import org.example.javachess.Engine.OpeningExplorer;
import org.example.javachess.Engine.PositionAnalyzer;
import org.example.javachess.Services.GameAnalyzer;
import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Utils.ImageCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Review screen: replays an archived game with live engine lines, full-game analysis, accuracy and graph. */
public class ReviewController implements NavigationAware, GameNavigationListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final int DEBOUNCE_DELAY = 150;

    private MainController mainController;
    @FXML
    private VBox stockfishControls;
    @FXML
    private StockfishControlsController stockfishControlsController;
    @FXML
    private PageHeader header;
    @FXML
    private org.example.javachess.Components.GameLayout reviewView;
    @FXML
    private StackPane reviewHbox;
    @FXML
    private Label plyLabel;
    @FXML
    private VBox analysisContainer;
    @FXML
    private MoveListView moveList;
    @FXML
    private Button analyzeButton;
    @FXML
    private Label analyzeHint;
    @FXML
    private HBox analyzeRow;
    @FXML
    private StackPane graphContainer;
    @FXML
    private VBox progressContainer;
    @FXML
    private ProgressBar analysisProgressIndicator;
    @FXML
    private Label percentLabel;
    @FXML
    private VBox accuracyWrapper;
    @FXML
    private HBox accuracyContainer;
    @FXML
    private Label whiteAccuracyLabel;
    @FXML
    private Label blackAccuracyLabel;

    private int analysisDepth = 18;
    private int analysisMultiPV = 1;
    private final boolean analysisEnabled = true;

    private ChessBoardUI reviewChessBoard;
    private final EvalBar reviewEvalBar = new EvalBar(8, 400);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "review-debounce");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> debounceHandle;
    private ArduinoController arduinoController;
    private AnalysisPanel evaluationPanel;
    private EvaluationGraph evaluationGraph;
    private List<MoveAnalysis> currentAnalysis;
    private String currentPgn;
    private String currentInitialFen = START_FEN;
    /** Incremented when another game is loaded: a running full analysis of the previous game is discarded. */
    private final java.util.concurrent.atomic.AtomicInteger analysisGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private int[] whiteCounts = new int[0];
    private int[] blackCounts = new int[0];

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        if (stockfishControlsController != null) {
            stockfishControlsController.setOnParamsChanged((depth, multiPv) -> {
                this.analysisDepth = depth;
                this.analysisMultiPV = multiPv;
                triggerAnalysisDebounced();
            });
        }
        evaluationGraph = new EvaluationGraph(600, 132);
        evaluationGraph.setOnMoveSelected(index -> goTo(index + 1));
        graphContainer.getChildren().add(evaluationGraph);

        evaluationPanel = new AnalysisPanel();
        analysisContainer.getChildren().add(evaluationPanel);
        reviewView.setEvalBar(reviewEvalBar);
        moveList.setOnPlySelected(this::goTo);
    }

    @FXML
    private void toggleSettings() {
        mainController.showSheet(I18n.t("analysis.title"), stockfishControlsController.getRoot());
    }

    public void loadGame(String pgn) {
        loadGame(pgn, START_FEN);
    }

    public void loadGame(String pgn, String initialFen) {
        this.currentPgn = pgn;
        analysisGeneration.incrementAndGet();
        this.currentInitialFen = initialFen == null ? START_FEN : initialFen;
        if (arduinoController == null) {
            Thread.ofVirtual().start(() -> arduinoController = ArduinoController.getInstance());
        }

        reviewChessBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 80);
        reviewChessBoard.setFitToParent(true);
        reviewHbox.getChildren().setAll(reviewChessBoard);
        reviewChessBoard.loadPgn(pgn, this.currentInitialFen);
        reviewEvalBar.updateEvaluation(0);

        moveList.setMoves(this.currentInitialFen, toMoves(reviewChessBoard.getMoveList(), this.currentInitialFen));
        header.setSubtitle(I18n.t("review.subtitle"));

        // Reset analysis state
        progressContainer.setVisible(false);
        progressContainer.setManaged(false);
        analyzeRow.setVisible(true);
        analyzeRow.setManaged(true);
        analyzeButton.setDisable(false);
        accuracyWrapper.setVisible(false);
        accuracyWrapper.setManaged(false);
        currentAnalysis = null;
        evaluationGraph.setData(null);
        evaluationPanel.clear();
        whiteAccuracyLabel.setText("0%");
        blackAccuracyLabel.setText("0%");

        handleMoveUpdate();
    }

    /** UCI strings of a PGN to chesslib moves (for SAN conversion); stops at the first unparsable token. */
    private static List<Move> toMoves(List<String> uci, String initialFen) {
        List<Move> result = new ArrayList<>();
        Side side = initialFen.split(" ").length > 1 && initialFen.split(" ")[1].equals("b") ? Side.BLACK : Side.WHITE;
        for (String token : uci) {
            try {
                result.add(new Move(token, side));
            } catch (RuntimeException e) {
                break;
            }
            side = side.flip();
        }
        return result;
    }

    @FXML
    private void startFullAnalysis() {
        // Full-game analysis depth comes from Settings; the sheet only tunes the live lines.
        startFullAnalysis(ConfigManager.getIntProperty("analysis.depth", 12));
    }

    private void startFullAnalysis(int depth) {
        if (currentPgn == null) {
            return;
        }
        analyzeButton.setDisable(true);
        analyzeRow.setVisible(false);
        analyzeRow.setManaged(false);
        progressContainer.setVisible(true);
        progressContainer.setManaged(true);
        analysisProgressIndicator.setProgress(0);
        percentLabel.setText(I18n.t("review.analyzing", 0));

        GameAnalyzer analyzer = new GameAnalyzer();
        String pgn = currentPgn;
        int generation = analysisGeneration.get();
        String fenToAnalyze = currentInitialFen;
        Thread.ofPlatform().daemon().name("game-analysis").start(() -> {
            try {
                List<MoveAnalysis> analysis = analyzer.analyzeGame(pgn, fenToAnalyze, depth, progress -> Platform.runLater(() -> {
                    if (generation == analysisGeneration.get()) { // progress of an older game is ignored
                        analysisProgressIndicator.setProgress(progress);
                        percentLabel.setText(I18n.t("review.analyzing", Math.round(progress * 100)));
                    }
                }));
                if (analysis.isEmpty() && !pgn.isBlank()) {
                    // GameAnalyzer returns nothing when the engine fails mid-way: report it, do not show 0%
                    throw new IllegalStateException("il motore non ha risposto");
                }
                double whiteAccuracy = analyzer.calculateAccuracy(analysis, true);
                double blackAccuracy = analyzer.calculateAccuracy(analysis, false);
                Platform.runLater(() -> {
                    if (generation == analysisGeneration.get()) { // another game may have been opened meanwhile
                        showAnalysis(analysis, whiteAccuracy, blackAccuracy);
                    }
                });
            } catch (RuntimeException | Error e) {
                LOG.error("Game analysis failed", e);
                if (generation != analysisGeneration.get()) {
                    return; // the user already moved to another game
                }
                Platform.runLater(() -> {
                    progressContainer.setVisible(false);
                    progressContainer.setManaged(false);
                    analyzeRow.setVisible(true);
                    analyzeRow.setManaged(true);
                    analyzeButton.setDisable(false);
                });
                org.example.javachess.Utils.ErrorReporter.showError("Analisi",
                        "Analisi non riuscita: " + org.example.javachess.Utils.ErrorReporter.userMessage(e)
                                + "\nControlla che Stockfish sia installato (Impostazioni).");
            }
        });
    }

    private void showAnalysis(List<MoveAnalysis> analysis, double whiteAccuracy, double blackAccuracy) {
        this.currentAnalysis = analysis;
        progressContainer.setVisible(false);
        progressContainer.setManaged(false);
        accuracyWrapper.setVisible(true);
        accuracyWrapper.setManaged(true);
        whiteAccuracyLabel.setText(String.format(Locale.ITALIAN, "%.1f%%", whiteAccuracy));
        blackAccuracyLabel.setText(String.format(Locale.ITALIAN, "%.1f%%", blackAccuracy));
        countClassifications(analysis);
        evaluationGraph.setData(analysis);
        analyzeButton.setDisable(false);
        updateAnalysisUI();
    }

    private void countClassifications(List<MoveAnalysis> analysis) {
        whiteCounts = new int[MoveAnalysis.MoveClassification.values().length];
        blackCounts = new int[MoveAnalysis.MoveClassification.values().length];
        for (MoveAnalysis move : analysis) {
            if (move.getClassification() == null) {
                continue;
            }
            boolean isWhite = move.isWhiteMove();
            int ordinal = move.getClassification().ordinal();
            if (isWhite) {
                whiteCounts[ordinal]++;
            } else {
                blackCounts[ordinal]++;
            }
        }
    }

    /** Breakdown of the move classifications (white | icon + name | black) in a sheet. */
    @FXML
    private void toggleBreakdown() {
        if (currentAnalysis == null) {
            return;
        }
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        Label whiteHead = new Label(I18n.t("review.accuracy.white"));
        Label blackHead = new Label(I18n.t("review.accuracy.black"));
        whiteHead.getStyleClass().add("stat-label");
        blackHead.getStyleClass().add("stat-label");
        grid.add(whiteHead, 0, 0);
        grid.add(blackHead, 2, 0);
        Object[][] rows = {
                { MoveAnalysis.MoveClassification.BRILLIANT, "brilliant.png", "review.brilliant" },
                { MoveAnalysis.MoveClassification.GREAT, "great.png", "review.great" },
                { MoveAnalysis.MoveClassification.BOOK_MOVE, "book.png", "review.book" },
                { MoveAnalysis.MoveClassification.BEST, "best.png", "review.best" },
                { MoveAnalysis.MoveClassification.EXCELLENT, "excellent.png", "review.excellent" },
                { MoveAnalysis.MoveClassification.GOOD, "good.png", "review.good" },
                { MoveAnalysis.MoveClassification.INACCURACY, "inaccuracy.png", "review.inaccuracy" },
                { MoveAnalysis.MoveClassification.MISTAKE, "mistake.png", "review.mistake" },
                { MoveAnalysis.MoveClassification.MISS, "missed_win.png", "review.miss" },
                { MoveAnalysis.MoveClassification.BLUNDER, "blunder.png", "review.blunder" } };
        int r = 1;
        for (Object[] row : rows) {
            int ordinal = ((MoveAnalysis.MoveClassification) row[0]).ordinal();
            Label w = new Label(String.valueOf(whiteCounts[ordinal]));
            Label b = new Label(String.valueOf(blackCounts[ordinal]));
            w.getStyleClass().add("breakdown-count");
            b.getStyleClass().add("breakdown-count");
            ImageView icon = new ImageView(ImageCache.getInstance().getImage("/images/analysis/" + row[1], 22, 22));
            Label name = new Label(I18n.t((String) row[2]), icon);
            name.setGraphicTextGap(10);
            name.getStyleClass().add("breakdown-name");
            HBox middle = new HBox(name);
            middle.setAlignment(Pos.CENTER_LEFT);
            middle.getStyleClass().add("breakdown-row");
            grid.add(w, 0, r);
            grid.add(middle, 1, r);
            grid.add(b, 2, r);
            r++;
        }
        javafx.scene.layout.ColumnConstraints side = new javafx.scene.layout.ColumnConstraints(64);
        javafx.scene.layout.ColumnConstraints mid = new javafx.scene.layout.ColumnConstraints();
        mid.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.ColumnConstraints side2 = new javafx.scene.layout.ColumnConstraints(64);
        grid.getColumnConstraints().addAll(side, mid, side2);
        mainController.showSheet(I18n.t("review.breakdown"), grid);
    }

    @Override
    public void onNavigatedFrom() {
        PositionAnalyzer.get().stop(); // do not keep the engine busy for a screen that is not shown
    }

    @FXML
    private void backToArchive() {
        PositionAnalyzer.get().stop();
        if (arduinoController != null) {
            arduinoController.getBoardStateManager().stopGameMode();
        }
        mainController.navigateTo("ARCHIVE");
    }

    @Override
    public void onPreviousMove() {
        Platform.runLater(this::previousMove);
    }

    @Override
    public void onNextMove() {
        Platform.runLater(this::nextMove);
    }

    @FXML
    private void previousMove() {
        if (reviewChessBoard != null && reviewChessBoard.hasPreviousMove()) {
            reviewChessBoard.clearArrows();
            reviewChessBoard.clearIcons();
            reviewChessBoard.previousMove();
            handleMoveUpdate();
        }
    }

    @FXML
    private void nextMove() {
        if (reviewChessBoard != null && reviewChessBoard.hasNextMove()) {
            reviewChessBoard.clearArrows();
            reviewChessBoard.clearIcons();
            reviewChessBoard.nextMove();
            handleMoveUpdate();
        }
    }

    @FXML
    private void firstMove() {
        goTo(0);
    }

    @FXML
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

    /** Runs the full-game analysis at the given depth. Public for DevOptions. */
    public void analyze(int depth) {
        startFullAnalysis(depth);
    }

    private void handleMoveUpdate() {
        int index = reviewChessBoard.getCurrentMoveIndex();
        int total = reviewChessBoard.getMoveCount();
        plyLabel.setText(index == 0 ? I18n.t("review.start") : I18n.t("review.ply", index, total));
        moveList.setCurrentPly(index);
        triggerAnalysisDebounced();
        updateAnalysisUI();
    }

    private void updateAnalysisUI() {
        if (currentAnalysis == null || reviewChessBoard == null) {
            return;
        }
        int analysisIndex = reviewChessBoard.getCurrentMoveIndex() - 1;
        evaluationGraph.setHighlightMove(analysisIndex >= -1 && analysisIndex < currentAnalysis.size()
                ? analysisIndex : -1);
        if (analysisIndex < 0 || analysisIndex >= currentAnalysis.size()) {
            return;
        }
        MoveAnalysis analysis = currentAnalysis.get(analysisIndex);
        String iconName = getIconForClassification(analysis.getClassification());
        if (iconName != null) {
            int squareIndex = analysis.getToSquareIndex();
            reviewChessBoard.drawIconOnSquare(squareIndex % 8, 7 - (squareIndex / 8), iconName);
        }
        if (analysis.getClassification() == MoveAnalysis.MoveClassification.BLUNDER
                || analysis.getClassification() == MoveAnalysis.MoveClassification.MISTAKE) {
            String best = analysis.getBestMove();
            if (best != null && best.length() >= 4) {
                reviewChessBoard.drawArrowOnBoard(best.charAt(0) - 'a', '8' - best.charAt(1), best.charAt(2) - 'a',
                        '8' - best.charAt(3), javafx.scene.paint.Color.web("#3FB950"));
            }
        }
    }

    private String getIconForClassification(MoveAnalysis.MoveClassification classification) {
        if (classification == null) {
            return null;
        }
        return switch (classification) {
            case BRILLIANT -> "brilliant.png";
            case BEST -> "best.png";
            case GREAT -> "great.png";
            case EXCELLENT -> "excellent.png";
            case BOOK_MOVE -> "book.png";
            case GOOD -> "good.png";
            case INACCURACY -> "inaccuracy.png";
            case MISS -> "missed_win.png";
            case MISTAKE -> "mistake.png";
            case BLUNDER -> "blunder.png";
            case FORCED -> "forced.png";
            default -> null;
        };
    }

    /** Engine event thread: eval bar, best-move arrow and analysis lines for the reviewed position. */
    private void onReviewAnalysisUpdate(AnalysisUpdate update) {
        int n = Math.max(1, update.lines().size());
        String[] lines = new String[n];
        String[] evals = new String[n];
        for (int i = 0; i < n; i++) {
            lines[i] = update.formatLine(i);
            evals[i] = update.evalText(i);
        }
        String best = update.bestMove();
        reviewEvalBar.updateEvaluation(update.whitePawns());
        Platform.runLater(() -> {
            if (reviewChessBoard == null || !update.fen().equals(reviewChessBoard.getFen())) {
                return; // the user already moved on
            }
            reviewChessBoard.clearArrows();
            if (best != null && best.length() >= 4) {
                reviewChessBoard.drawArrowOnBoard(best.charAt(0) - 'a', '8' - best.charAt(1),
                        best.charAt(2) - 'a', '8' - best.charAt(3), javafx.scene.paint.Color.rgb(156, 204, 101, 0.7));
            }
            if (reviewChessBoard.getBoard().isMated()) {
                evaluationPanel.showResult(reviewChessBoard.getBoard().getSideToMove() == Side.WHITE ? "0-1" : "1-0");
            } else {
                for (int i = 0; i < n; i++) {
                    evaluationPanel.updateAnalysis(i, lines[i], evals[i]);
                }
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
        debounceHandle = scheduler.schedule(() -> {
            // Opening name: asynchronous, cached, never blocks.
            OpeningExplorer.lookup(currentFen).thenAccept(openingName -> Platform.runLater(() -> {
                if (openingName.isPresent()) {
                    header.setSubtitle(openingName.get());
                } else if (reviewChessBoard.getCurrentMoveIndex() <= 1) {
                    header.setSubtitle(I18n.t("review.subtitle"));
                }
            }));
            Platform.runLater(() -> {
                if (reviewChessBoard.getBoard().isMated()) {
                    evaluationPanel.showResult(
                            reviewChessBoard.getBoard().getSideToMove() == Side.WHITE ? "0-1" : "1-0");
                }
            });
            if (analysisEnabled) {
                PositionAnalyzer.get().analyze(currentFen, analysisDepth, analysisMultiPV, this::onReviewAnalysisUpdate);
            }
        }, DEBOUNCE_DELAY, TimeUnit.MILLISECONDS);
    }
}
