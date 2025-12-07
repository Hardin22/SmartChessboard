package org.example.javachess.Controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.javachess.Oggetti.*;
import org.example.javachess.Services.GameAnalyzer;
import org.example.javachess.Services.StockfishService;
import org.example.javachess.Utils.ConfigManager;

import java.util.List;
import java.util.concurrent.Executors;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReviewController implements NavigationAware, GameNavigationListener {

    private MainController mainController;
    @FXML private javafx.scene.layout.VBox stockfishControls;
    @FXML private StockfishControlsController stockfishControlsController;
    
    private int analysisDepth = 18;
    private int analysisMultiPV = 1;
    private boolean analysisEnabled = true;

    private ChessBoardUI reviewChessBoard;
    private EvalBar reviewEvalBar;
    private Stockfish stockfish;
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> debounceHandle = null;
    private final int DEBOUNCE_DELAY = 150;
    private AtomicBoolean isCalculating = new AtomicBoolean(false);
    private ArduinoController arduinoController;

    @FXML private HBox reviewHbox;
    @FXML private Label openingReview;
    @FXML private Label evalScoreReview;
    @FXML private Label move1LabelReview;
    @FXML private Label move2LabelReview;
    @FXML private Label move3LabelReview;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        this.arduinoController = ArduinoController.getInstance();
        this.arduinoController.setNavigationListener(this);
    }

    @FXML private Button analyzeButton;
    @FXML private VBox graphContainer;
    @FXML private VBox progressContainer;
    @FXML private javafx.scene.control.ProgressBar analysisProgressBar;
    
    private EvaluationGraph evaluationGraph;
    private List<MoveAnalysis> currentAnalysis;
    private String currentPgn;

    @FXML
    public void initialize() {
        if (stockfishControlsController != null) {
            stockfishControlsController.setOnParamsChanged((depth, multiPv) -> {
                this.analysisDepth = depth;
                this.analysisMultiPV = multiPv;
                triggerAnalysisDebounced();
            });
            
            stockfishControlsController.setOnMasterSwitchChanged(enabled -> {
                this.analysisEnabled = enabled;
                if (!enabled) {
                    if (stockfish != null) stockfish.stopCalculating();
                    if (reviewChessBoard != null) reviewChessBoard.clearArrows();
                } else {
                    triggerAnalysisDebounced();
                }
            });
            
            stockfishControlsController.setOnClose(() -> stockfishControls.setVisible(false));
        }
        
        // Initialize Graph
        evaluationGraph = new EvaluationGraph(600, 150);
        graphContainer.getChildren().add(evaluationGraph);
        
        // Fix Layout Shift for Stockfish Lines
        setupLabel(move1LabelReview);
        setupLabel(move2LabelReview);
        setupLabel(move3LabelReview);
    }
    
    private void setupLabel(Label label) {
        if (label != null) {
            label.setWrapText(false);
            label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            label.setMaxWidth(Double.MAX_VALUE); // Allow it to grow but constrained by parent
        }
    }
    
    @FXML
    private void toggleSettings() {
        stockfishControls.setVisible(!stockfishControls.isVisible());
    }

    public void loadGame(String pgn) {
        this.currentPgn = pgn;
        stockfish = StockfishService.getInstance(); // Or new instance if we want separate
        
        String boardStyle = ConfigManager.getProperty("theme.board", "Marghiacciato.png");
        String pieceStyle = ConfigManager.getProperty("theme.piece", "Classico");
        
        reviewChessBoard = new ChessBoardUI(boardStyle, pieceStyle, 85);
        reviewEvalBar = new EvalBar(20, 400);

        reviewHbox.getChildren().clear();
        reviewHbox.getChildren().addAll(reviewChessBoard, reviewEvalBar);
        
        reviewChessBoard.loadPgn(pgn);
        
        // Reset analysis state
        graphContainer.setVisible(false);
        graphContainer.setManaged(false);
        progressContainer.setVisible(false);
        progressContainer.setManaged(false);
        analyzeButton.setDisable(false);
        analyzeButton.setDisable(false);
        currentAnalysis = null;
        currentAnalysis = null;
        // currentMoveIndex is managed by reviewChessBoard now
        
        handleMoveUpdate();
    }
    
    @FXML
    private void startFullAnalysis() {
        analyzeButton.setDisable(true);
        progressContainer.setVisible(true);
        progressContainer.setManaged(true);
        
        GameAnalyzer analyzer = new GameAnalyzer();
        
        new Thread(() -> {
            List<MoveAnalysis> analysis = analyzer.analyzeGame(currentPgn, analysisDepth, progress -> {
                Platform.runLater(() -> analysisProgressBar.setProgress(progress));
            });
            
            Platform.runLater(() -> {
                this.currentAnalysis = analysis;
                progressContainer.setVisible(false);
                progressContainer.setManaged(false);
                graphContainer.setVisible(true);
                graphContainer.setManaged(true);
                evaluationGraph.setData(analysis);
                analyzeButton.setDisable(false); // Or keep disabled?
            });
        }).start();
    }

    @FXML
    private void backToArchive() {
        if (stockfish != null) {
            stockfish.stopCalculating();
        }
        if (arduinoController != null) {
            arduinoController.setNavigationListener(null);
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

    // private int currentMoveIndex = 0; // REMOVED: Use reviewChessBoard.getCurrentMoveIndex()

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
            // currentMoveIndex 0 = Start position (no move made yet) -> No analysis to show?
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
                         
                         reviewChessBoard.drawArrowOnBoard(fromCol, fromRow, toCol, toRow, javafx.scene.paint.Color.web("#ccff00")); // Neon Yellow for best move suggestion
                     }
                }
            }
        }
    }

    private String getIconForClassification(MoveAnalysis.MoveClassification classification) {
        switch (classification) {
            case BEST: return "best.png";
            case GREAT: return "great.png";
            case EXCELLENT: return "excellent.png";
            case BOOK_MOVE: return "book.png";
            case GOOD: return "good.png"; 
            case INACCURACY: return "inaccuracy.png";
            case MISSED_WIN: return "missed_win.png";
            case MISTAKE: return "mistake.png";
            case BLUNDER: return "blunder.png";
            default: return null;
        }
    }

    private void triggerAnalysisDebounced() {
        if (debounceHandle != null && !debounceHandle.isDone()) {
            debounceHandle.cancel(false);
        }

        debounceHandle = scheduler.schedule(() -> {
            if (reviewChessBoard != null && reviewEvalBar != null && stockfish != null) {
                String currentFen = reviewChessBoard.getFen();
                
                // Fetch Opening Name (Async)
                String openingName = stockfish.getOpeningName(currentFen);
                Platform.runLater(() -> {
                    if (openingName != null && !openingName.equals("Unknown Opening") && !openingName.equals("Error in API Call")) {
                        openingReview.setText(openingName);
                    } else {
                        // Keep previous name or clear? 
                        // Usually we want to keep the name if we are deep in the game, 
                        // but if we are at start and it's unknown, maybe clear.
                        // Better: Only update if valid, or if we are at start (move 0/1) and it's unknown.
                        if (reviewChessBoard.getCurrentMoveIndex() <= 1) {
                             openingReview.setText("Analisi Partita");
                        }
                    }
                });

                if (analysisEnabled) {
                    Platform.runLater(() -> {
                        stockfish.startAnalysis(currentFen, analysisDepth, analysisMultiPV, move1LabelReview, move2LabelReview, move3LabelReview, reviewChessBoard, evalScoreReview, reviewEvalBar, true);
                    });
                }
            }
        }, DEBOUNCE_DELAY, TimeUnit.MILLISECONDS);
    }
}

