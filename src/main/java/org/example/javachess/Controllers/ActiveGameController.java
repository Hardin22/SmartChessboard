package org.example.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Square;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.javachess.Oggetti.*;
import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Services.EngineService;

public class ActiveGameController implements NavigationAware {

    private MainController mainController;
    private AbstractGame currentGame;
    private ChessBoardUI chessBoard;
    private EvalBar evalBar;
    private ArduinoController arduinoController;

    @FXML
    private HBox boardContainer;
    @FXML
    private Label topLabel; // Black or Computer
    @FXML
    private Label bottomLabel; // White or Player
    @FXML
    private Label openingNameLabel;
    @FXML
    private TextField moveInputField;
    @FXML
    private VBox analysisContainer; // Container for dynamic AnalysisPanel
    private AnalysisPanel evaluationPanel; // Reusable component
    @FXML
    private TextField legalMovesInput;
    @FXML
    private VBox topPlayerInfo;

    @FXML
    private javafx.scene.layout.VBox stockfishControls; // The included root
    @FXML
    private StockfishControlsController stockfishControlsController; // The controller injected by FXML

    private boolean showEvaluation = true;
    private boolean showBestMoves = true;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        this.arduinoController = ArduinoController.getInstance();

        // Register Evaluation Provider
        this.arduinoController.getBoardStateManager().setEvaluationProvider(() -> {
            return this.currentEvaluation;
        });
    }

    @FXML
    public void initialize() {
        // Configure Stockfish Controls
        if (stockfishControlsController != null) {
            stockfishControlsController.setOnParamsChanged((depth, multiPv) -> updateAnalysisParams());
            stockfishControlsController.setOnClose(() -> stockfishControls.setVisible(false));
        }

        // Instantiate and add AnalysisPanel
        evaluationPanel = new AnalysisPanel();
        analysisContainer.getChildren().add(evaluationPanel);
        analysisContainer.setVisible(true);
        analysisContainer.setManaged(true);
    }

    @FXML
    private void toggleSettings() {
        stockfishControls.setVisible(!stockfishControls.isVisible());
    }

    private void updateAnalysisParams() {
        if (currentGame != null && stockfishControlsController != null) {
            currentGame.setAnalysisParams(stockfishControlsController.getDepth(),
                    stockfishControlsController.getMultiPv());
        }
    }

    public void startPvP(int duration, int increment) {
        setupBoard();
        arduinoController.getBoardStateManager().setEvaluationEnabled(showBestMoves);
        currentGame = new PvpGame(chessBoard, evalBar, openingNameLabel, bottomLabel, topLabel, duration * 60,
                increment);
        setupGameCallbacks();
        updateAnalysisParams(); // Apply initial spinner values
        topPlayerInfo.setRotate(180); // Rotate for opponent
        currentGame.startGame();
    }

    public void startPvC(int difficulty, boolean isPlayerWhite, EngineService.EngineType botType) {
        setupBoard();
        arduinoController.getBoardStateManager().setEvaluationEnabled(showBestMoves);
        currentGame = new PvcGame(chessBoard, evalBar, openingNameLabel, isPlayerWhite, difficulty, botType);
        setupGameCallbacks();
        updateAnalysisParams(); // Apply initial spinner values
        topPlayerInfo.setRotate(0); // Reset rotation
        currentGame.startGame();
    }

    public void startOnlineGame(String gameId) {
        setupBoard();
        arduinoController.getBoardStateManager().setEvaluationEnabled(false);
        currentGame = new OnlineGame(chessBoard, evalBar, gameId);
        setupGameCallbacks();
        // Disable analysis for online games by default
        stockfishControls.setVisible(false);
        topPlayerInfo.setRotate(0); // Reset rotation
        currentGame.startGame();
    }

    private void setupGameCallbacks() {
        if (currentGame != null) {
            currentGame.setAnalysisCallback(createAnalysisCallback());
            currentGame.setStatusCallback(createStatusCallback());
        }
    }

    private UCIEngine.AnalysisUpdateCallback createAnalysisCallback() {
        return (pv, bestMove, fullLine, score, moveEvaluations) -> {
            javafx.application.Platform.runLater(() -> {
                updateAnalysisUI(pv, bestMove, fullLine, score, moveEvaluations);
            });
        };
    }

    private java.util.function.Consumer<String> createStatusCallback() {
        return (message) -> {
            javafx.application.Platform.runLater(() -> {
                updateStatusUI(message);
            });
        };
    }

    private void setupBoard() {
        String boardStyle = ConfigManager.getProperty("theme.board", "Marghiacciato.png");
        String pieceStyle = ConfigManager.getProperty("theme.piece", "Classico");

        // Load Defaults from Settings
        this.showEvaluation = ConfigManager.getBooleanProperty("game.evaluation", true);
        this.showBestMoves = ConfigManager.getBooleanProperty("game.suggestions", true);

        chessBoard = new ChessBoardUI(boardStyle, pieceStyle, 85);
        evalBar = new EvalBar(20, 400); // Standard width
        evalBar.setMinWidth(20);
        evalBar.setMaxWidth(20);
        evalBar.setVisible(showEvaluation);
        evalBar.setManaged(showEvaluation);

        HBox.setMargin(evalBar, new javafx.geometry.Insets(0, 0, 0, 5)); // 5px Left Margin
        boardContainer.getChildren().clear();
        boardContainer.getChildren().addAll(chessBoard, evalBar);
        chessBoard.resetBoard();
        this.currentEvaluation = 0.0; // Reset evaluation for new game

        if (evaluationPanel != null) {
            evaluationPanel.setShowEvaluation(showEvaluation);
            evaluationPanel.setShowBestMoves(showBestMoves);
        }
    }

    @FXML
    private void handleMoveInput() {
        String input = moveInputField.getText().trim();
        if (!input.isEmpty() && currentGame != null) {
            currentGame.handleMoveInput(input);
            moveInputField.clear();
        }
    }

    private double currentEvaluation = 0.0;

    @FXML
    private void handleLegalMoves() {
        String input = legalMovesInput.getText().trim();
        chessBoard.clearHighlights();
        if (input != null && !input.isEmpty() && currentGame instanceof PvpGame) {
            try {
                Square selectedSquare = Square.valueOf(input.toUpperCase());
                UCIEngine currentstockfish = ((PvpGame) currentGame).getStockfish();
                currentstockfish.highlightLegalMovesWithEvaluation(currentGame.getBoard(), selectedSquare, chessBoard,
                        currentEvaluation);
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid square");
            }
        }
    }

    @FXML
    private void endGame() {
        stopAndSaveGame();
        mainController.navigateTo("HOME");
    }

    @Override
    public void onNavigatedFrom() {
        stopAndSaveGame();
    }

    private void stopAndSaveGame() {
        if (currentGame != null) {
            boolean save = false;
            if (currentGame instanceof PvpGame)
                save = ((PvpGame) currentGame).isSaveGame();
            else if (currentGame instanceof PvcGame)
                save = ((PvcGame) currentGame).isSaveGame();

            currentGame.endGame("Partita interrotta", save);
            currentGame = null;
        }
        arduinoController.getBoardStateManager().stopGameMode();
    }

    @FXML
    private void toggleEvaluation() {
        showEvaluation = !showEvaluation;
        evalBar.setVisible(showEvaluation);
        evalBar.setManaged(showEvaluation);
        evaluationPanel.setShowEvaluation(showEvaluation);
        updateStockfishState();
    }

    @FXML
    private void toggleBestMoves() {
        showBestMoves = !showBestMoves;
        evaluationPanel.setShowBestMoves(showBestMoves);
        updateStockfishState();

        // Hide arrows immediately if suggestions are turned off
        if (!showBestMoves && currentGame != null) {
            currentGame.clearArrows();
        }
    }

    private void updateStockfishState() {
        if (currentGame != null) {
            // Disable if BOTH are hidden (optimization)
            boolean enabled = showEvaluation || showBestMoves;
            currentGame.setAnalysisEnabled(enabled);

            if (!enabled) {
                evaluationPanel.clear();
            }

            // If enabled but moves hidden, ensure arrows are cleared (though Stockfish
            // might send them, we can filter)
            currentGame.setShowArrows(showBestMoves);

            // Link physical board evaluation to the suggestions toggle
            arduinoController.getBoardStateManager().setEvaluationEnabled(showBestMoves);
        }
    }

    // --- UI Update Logic ---

    private void updateStatusUI(String message) {
        evaluationPanel.setStatusMessage(message);
        if (currentGame != null && currentGame.getBoard().isMated()) {
            String result = currentGame.getBoard().getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE ? "0-1"
                    : "1-0";
            evaluationPanel.showResult(result);
        }
    }

    private void updateAnalysisUI(int pv, String bestMove, String fullLine, double score, String[] moveEvaluations) {
        if (pv == 0) {
            this.currentEvaluation = score;
            // Pass the best move to the physical board for LED highlighting
            arduinoController.getBoardStateManager().setBestMove(bestMove);
        }

        String evalText = (moveEvaluations != null && moveEvaluations.length > 0) ? moveEvaluations[0] : "0.0";
        if (currentGame != null) {
            if (currentGame.getBoard().isMated()) {
                String result = currentGame.getBoard().getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE
                        ? "0-1"
                        : "1-0";
                evaluationPanel.showResult(result);
            } else {
                evaluationPanel.updateAnalysis(pv, fullLine, evalText);
            }
        }
    }

}
