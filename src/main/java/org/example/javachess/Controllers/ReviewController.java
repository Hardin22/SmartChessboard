package org.example.javachess.Controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.javachess.Engine.AnalysisUpdate;
import org.example.javachess.Engine.OpeningExplorer;
import org.example.javachess.Engine.PositionAnalyzer;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Oggetti.EvalBar;
import org.example.javachess.Oggetti.EvaluationGraph;
import org.example.javachess.Oggetti.MoveAnalysis;
import org.example.javachess.Oggetti.AnalysisPanel;
import org.example.javachess.Services.GameAnalyzer;
import org.example.javachess.Utils.ConfigManager;

import java.util.List;
import java.util.concurrent.Executors;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReviewController implements NavigationAware, GameNavigationListener {

    private MainController mainController;
    @FXML
    private javafx.scene.layout.VBox stockfishControls;
    @FXML
    private StockfishControlsController stockfishControlsController;

    private int analysisDepth = 18;
    private int analysisMultiPV = 1;
    private boolean analysisEnabled = true;

    private ChessBoardUI reviewChessBoard;
    private EvalBar reviewEvalBar;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "review-debounce");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> debounceHandle = null;
    private final int DEBOUNCE_DELAY = 150;
    private AtomicBoolean isCalculating = new AtomicBoolean(false);
    private ArduinoController arduinoController;

    @FXML
    private HBox reviewHbox;
    @FXML
    private Label openingReview;
    @FXML
    private VBox analysisContainer;
    private AnalysisPanel evaluationPanel;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        this.arduinoController = ArduinoController.getInstance();
        // arduinoController.setNavigationListener(this);
    }

    @FXML
    private Button analyzeButton;
    @FXML
    private VBox graphContainer;
    @FXML
    private VBox progressContainer;
    @FXML
    private javafx.scene.control.ProgressIndicator analysisProgressIndicator;
    @FXML
    private HBox accuracyContainer;
    @FXML
    private Label whiteAccuracyLabel;
    @FXML
    private Label blackAccuracyLabel;
    @FXML
    private Label percentLabel;

    private EvaluationGraph evaluationGraph;
    private List<MoveAnalysis> currentAnalysis;
    private String currentPgn;
    /** Incremented when another game is loaded: a running full analysis of the previous game is discarded. */
    private final java.util.concurrent.atomic.AtomicInteger analysisGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private String currentInitialFen;

    @FXML
    public void initialize() {
        if (stockfishControlsController != null) {
            stockfishControlsController.setOnParamsChanged((depth, multiPv) -> {
                this.analysisDepth = depth;
                this.analysisMultiPV = multiPv;
                triggerAnalysisDebounced();
            });

            stockfishControlsController.setOnClose(() -> stockfishControls.setVisible(false));
        }

        // Initialize Graph
        evaluationGraph = new EvaluationGraph(600, 150);
        graphContainer.getChildren().add(evaluationGraph);

        // Instantiate and add AnalysisPanel
        evaluationPanel = new AnalysisPanel();
        analysisContainer.getChildren().add(evaluationPanel);
        analysisContainer.setVisible(true);
        analysisContainer.setManaged(true);

        // Set initial text programmatically to avoid FXML resource key issues with %
        if (whiteAccuracyLabel != null)
            whiteAccuracyLabel.setText("0%");
        if (blackAccuracyLabel != null)
            blackAccuracyLabel.setText("0%");
        if (percentLabel != null)
            percentLabel.setText("%");

        // Load analysis depth from config
        this.analysisDepth = ConfigManager.getIntProperty("analysis.depth", 12);
    }

    @FXML
    private void toggleSettings() {
        stockfishControls.setVisible(!stockfishControls.isVisible());
    }

    public void loadGame(String pgn) {
        loadGame(pgn, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    public void loadGame(String pgn, String initialFen) {
        this.currentPgn = pgn;
        analysisGeneration.incrementAndGet();
        this.currentInitialFen = initialFen;

        String boardStyle = ConfigManager.getProperty("theme.board", "Marghiacciato.png");
        String pieceStyle = ConfigManager.getProperty("theme.piece", "Classico");

        reviewChessBoard = new ChessBoardUI(boardStyle, pieceStyle, 85);
        reviewEvalBar = new EvalBar(20, 400);
        reviewEvalBar.setMinWidth(20);
        reviewEvalBar.setMaxWidth(20);
        HBox.setMargin(reviewEvalBar, new javafx.geometry.Insets(0, 0, 0, 5));

        reviewHbox.getChildren().clear();
        reviewHbox.getChildren().addAll(reviewChessBoard, reviewEvalBar);

        reviewChessBoard.loadPgn(pgn, initialFen);

        // Reset analysis state
        graphContainer.setVisible(false);
        graphContainer.setManaged(false);
        progressContainer.setVisible(false);
        progressContainer.setManaged(false);
        analyzeButton.setDisable(false);
        currentAnalysis = null;
        accuracyContainer.setVisible(false);
        accuracyContainer.setManaged(false);
        whiteAccuracyLabel.setText("0%");
        blackAccuracyLabel.setText("0%");
        // currentMoveIndex is managed by reviewChessBoard now

        handleMoveUpdate();
    }

    @FXML
    private void startFullAnalysis() {
        analyzeButton.setDisable(true);
        progressContainer.setVisible(true);
        progressContainer.setManaged(true);

        String pgnToAnalyze = currentPgn;
        int generation = analysisGeneration.get();
        String fenToAnalyze = currentInitialFen;
        Thread analysisThread = new Thread(() -> {
            try {
                // Built here, not on the FX thread: starting the engine blocks until it answers.
                GameAnalyzer analyzer = new GameAnalyzer();
                // initial position matters for games that did not start from the standard position
                List<MoveAnalysis> analysis = analyzer.analyzeGame(pgnToAnalyze, fenToAnalyze, analysisDepth, progress -> {
                    Platform.runLater(() -> analysisProgressIndicator.setProgress(progress));
                });

                double whiteAccuracy = analyzer.calculateAccuracy(analysis, true);
                double blackAccuracy = analyzer.calculateAccuracy(analysis, false);

                Platform.runLater(() -> {
                    if (generation != analysisGeneration.get()) {
                        return; // another game was opened meanwhile
                    }
                    this.currentAnalysis = analysis;
                    progressContainer.setVisible(false);
                    progressContainer.setManaged(false);
                    graphContainer.setVisible(true);
                    graphContainer.setManaged(true);
                    accuracyWrapper.setVisible(true);
                    accuracyWrapper.setManaged(true);
                    accuracyContainer.setVisible(true);
                    accuracyContainer.setManaged(true);

                    whiteAccuracyLabel.setText(String.format("%.1f", whiteAccuracy));
                    blackAccuracyLabel.setText(String.format("%.1f", blackAccuracy));

                    updateBreakdownStats(analysis);

                    evaluationGraph.setData(analysis);
                    analyzeButton.setDisable(false);
                });
            } catch (RuntimeException | Error e) {
                org.slf4j.LoggerFactory.getLogger(ReviewController.class).error("Game analysis failed", e);
                Platform.runLater(() -> {
                    progressContainer.setVisible(false);
                    progressContainer.setManaged(false);
                    analyzeButton.setDisable(false);
                });
                org.example.javachess.Utils.ErrorReporter.showError("Analisi",
                        "Analisi non riuscita: " + org.example.javachess.Utils.ErrorReporter.userMessage(e)
                                + "\nControlla che Stockfish sia installato (Impostazioni).");
            }
        }, "game-analysis");
        analysisThread.setDaemon(true);
        analysisThread.start();
    }

    @FXML
    private VBox accuracyWrapper;
    @FXML
    private Button toggleBreakdownButton;
    @FXML
    private org.kordamp.ikonli.javafx.FontIcon breakdownIcon;
    @FXML
    private VBox breakdownStatsContainer;

    // Breakdown Labels
    @FXML
    private Label wBrilliant, bBrilliant;
    @FXML
    private Label wGreat, bGreat;
    @FXML
    private Label wBook, bBook;
    @FXML
    private Label wBest, bBest;
    @FXML
    private Label wExcellent, bExcellent;
    @FXML
    private Label wGood, bGood;
    @FXML
    private Label wInaccuracy, bInaccuracy;
    @FXML
    private Label wMistake, bMistake;
    @FXML
    private Label wMiss, bMiss;
    @FXML
    private Label wBlunder, bBlunder;

    @FXML
    private void toggleBreakdown() {
        boolean isVisible = breakdownStatsContainer.isVisible();
        breakdownStatsContainer.setVisible(!isVisible);
        breakdownStatsContainer.setManaged(!isVisible);

        if (!isVisible) {
            breakdownIcon.setIconLiteral("mdal-keyboard_arrow_up");
        } else {
            breakdownIcon.setIconLiteral("mdal-keyboard_arrow_down");
        }
    }

    private void updateBreakdownStats(List<MoveAnalysis> analysis) {
        int[] whiteCounts = new int[MoveAnalysis.MoveClassification.values().length];
        int[] blackCounts = new int[MoveAnalysis.MoveClassification.values().length];

        for (MoveAnalysis move : analysis) {
            if (move.getClassification() == null)
                continue;

            boolean isWhite = move.isWhiteMove();
            int ordinal = move.getClassification().ordinal();

            if (isWhite) {
                whiteCounts[ordinal]++;
            } else {
                blackCounts[ordinal]++;
            }
        }

        // Update Labels
        wBrilliant.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.BRILLIANT.ordinal()]));
        bBrilliant.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.BRILLIANT.ordinal()]));

        wGreat.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.GREAT.ordinal()]));
        bGreat.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.GREAT.ordinal()]));

        wBook.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.BOOK_MOVE.ordinal()]));
        bBook.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.BOOK_MOVE.ordinal()]));

        wBest.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.BEST.ordinal()]));
        bBest.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.BEST.ordinal()]));

        wExcellent.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.EXCELLENT.ordinal()]));
        bExcellent.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.EXCELLENT.ordinal()]));

        wGood.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.GOOD.ordinal()]));
        bGood.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.GOOD.ordinal()]));

        wInaccuracy.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.INACCURACY.ordinal()]));
        bInaccuracy.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.INACCURACY.ordinal()]));

        wMistake.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.MISTAKE.ordinal()]));
        bMistake.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.MISTAKE.ordinal()]));

        wMiss.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.MISS.ordinal()]));
        bMiss.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.MISS.ordinal()]));

        wBlunder.setText(String.valueOf(whiteCounts[MoveAnalysis.MoveClassification.BLUNDER.ordinal()]));
        bBlunder.setText(String.valueOf(blackCounts[MoveAnalysis.MoveClassification.BLUNDER.ordinal()]));
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
            // arduinoController.setNavigationListener(null);
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

    // private int currentMoveIndex = 0; // REMOVED: Use
    // reviewChessBoard.getCurrentMoveIndex()

    @FXML
    private void previousMove() {
        if (reviewChessBoard != null && reviewChessBoard.hasPreviousMove()) {
            reviewChessBoard.clearArrows();
            reviewChessBoard.clearIcons(); // Clear previous icons
            reviewChessBoard.previousMove();
            handleMoveUpdate();
        }
    }

    @FXML
    private void nextMove() {
        if (reviewChessBoard != null && reviewChessBoard.hasNextMove()) {
            reviewChessBoard.clearArrows();
            reviewChessBoard.clearIcons(); // Clear previous icons
            reviewChessBoard.nextMove();
            handleMoveUpdate();
        }
    }

    private void handleMoveUpdate() {
        triggerAnalysisDebounced();
        updateAnalysisUI();
    }

    private void updateAnalysisUI() {
        if (currentAnalysis != null && reviewChessBoard != null) {
            int currentMoveIndex = reviewChessBoard.getCurrentMoveIndex();

            // Update Graph Cursor
            // Analysis list is 0-indexed, corresponding to move 1, 2, 3...
            // currentMoveIndex 0 = Start position (no move made yet) -> No analysis to
            // show?
            // Actually, move 1 is index 0 in analysis list.
            // If currentMoveIndex is 0 (start), we highlight nothing or start.

            int analysisIndex = currentMoveIndex - 1;

            // Bounds check for graph cursor
            if (analysisIndex >= -1 && analysisIndex < currentAnalysis.size()) {
                evaluationGraph.setHighlightMove(analysisIndex);
            } else {
                // If out of bounds (shouldn't happen with strict nav), clear or clamp
                evaluationGraph.setHighlightMove(-1);
            }

            if (analysisIndex >= 0 && analysisIndex < currentAnalysis.size()) {
                MoveAnalysis analysis = currentAnalysis.get(analysisIndex);

                // Draw Icon based on classification
                String iconName = getIconForClassification(analysis.getClassification());
                if (iconName != null) {
                    int squareIndex = analysis.getToSquareIndex();
                    int col = squareIndex % 8;
                    int row = 7 - (squareIndex / 8);
                    reviewChessBoard.drawIconOnSquare(col, row, iconName);
                }

                // Show Best Move Arrow if Mistake/Blunder
                if (analysis.getClassification() == MoveAnalysis.MoveClassification.BLUNDER ||
                        analysis.getClassification() == MoveAnalysis.MoveClassification.MISTAKE ||
                        analysis.getClassification() == MoveAnalysis.MoveClassification.MISTAKE) {

                    String bestMoveUci = analysis.getBestMove();
                    if (bestMoveUci != null && !bestMoveUci.isEmpty()) {
                        // Draw arrow for best move (Green)
                        // We need to parse UCI string (e.g. "e2e4")
                        int fromCol = bestMoveUci.charAt(0) - 'a';
                        int fromRow = '8' - bestMoveUci.charAt(1);
                        int toCol = bestMoveUci.charAt(2) - 'a';
                        int toRow = '8' - bestMoveUci.charAt(3);

                        reviewChessBoard.drawArrowOnBoard(fromCol, fromRow, toCol, toRow,
                                javafx.scene.paint.Color.web("#ccff00")); // Neon Yellow for best move suggestion
                    }
                }
            }
        }
    }

    private String getIconForClassification(MoveAnalysis.MoveClassification classification) {
        switch (classification) {
            case BRILLIANT:
                return "brilliant.png";
            case BEST:
                return "best.png";
            case GREAT:
                return "great.png";
            case EXCELLENT:
                return "excellent.png";
            case BOOK_MOVE:
                return "book.png";
            case GOOD:
                return "good.png";
            case INACCURACY:
                return "inaccuracy.png";
            case MISS:
                return "missed_win.png";
            case MISTAKE:
                return "mistake.png";
            case BLUNDER:
                return "blunder.png";
            case FORCED:
                return "forced.png";
            default:
                return null;
        }
    }

    /** Engine event thread: eval bar, best-move arrow and analysis panel for the reviewed position. */
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
                return; // user already moved on
            }
            reviewChessBoard.clearArrows();
            if (best != null && best.length() >= 4) {
                reviewChessBoard.drawArrowOnBoard(best.charAt(0) - 'a', '8' - best.charAt(1),
                        best.charAt(2) - 'a', '8' - best.charAt(3), javafx.scene.paint.Color.rgb(156, 204, 101, 0.7));
            }
            if (reviewChessBoard.getBoard().isMated()) {
                String result = reviewChessBoard.getBoard()
                        .getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE ? "0-1" : "1-0";
                evaluationPanel.showResult(result);
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

        if (reviewChessBoard == null || reviewEvalBar == null) {
            return;
        }
        String currentFen = reviewChessBoard.getFen(); // read on the FX thread, which owns the board
        debounceHandle = scheduler.schedule(() -> {
            if (reviewChessBoard != null && reviewEvalBar != null) {

                // Opening name (asynchronous, cached, never blocks)
                OpeningExplorer.lookup(currentFen).thenAccept(openingName -> Platform.runLater(() -> {
                    if (openingName.isPresent()) {
                        openingReview.setText(openingName.get());
                    } else if (reviewChessBoard.getCurrentMoveIndex() <= 1) {
                        openingReview.setText("Analisi Partita");
                    }
                }));
                Platform.runLater(() -> {
                    // Immediate checkmate detection for UI
                    if (reviewChessBoard.getBoard().isMated()) {
                        String result = reviewChessBoard.getBoard()
                                .getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE ? "0-1" : "1-0";
                        evaluationPanel.showResult(result);
                    }
                });

                if (analysisEnabled) {
                    PositionAnalyzer.get().analyze(currentFen, analysisDepth, analysisMultiPV,
                            this::onReviewAnalysisUpdate);
                }
            }
        }, DEBOUNCE_DELAY, TimeUnit.MILLISECONDS);
    }
}
