package org.example.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Square;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.javachess.Oggetti.*;
import org.example.javachess.Utils.ConfigManager;

public class ActiveGameController implements NavigationAware {

    private MainController mainController;
    private AbstractGame currentGame;
    private ChessBoardUI chessBoard;
    private EvalBar evalBar;
    private ArduinoController arduinoController;

    @FXML private HBox boardContainer;
    @FXML private Label topLabel; // Black or Computer
    @FXML private Label bottomLabel; // White or Player
    @FXML private Label openingNameLabel;
    @FXML private TextField moveInputField;
    @FXML private Label evaluationLabel;
    @FXML private Label move1Label;
    @FXML private Label move2Label;
    @FXML private Label move3Label;
    @FXML private TextField legalMovesInput;

    private String boardStyle = "Marghiacciato.png"; // Default, should be configurable
    private String pieceStyle = "Classico"; // Default

    @FXML private javafx.scene.layout.VBox stockfishControls; // The included root
    @FXML private StockfishControlsController stockfishControlsController; // The controller injected by FXML
    
    private boolean isMasterEnabled = true;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        this.arduinoController = ArduinoController.getInstance();
    }
    
    @FXML
    public void initialize() {
        // Configure Stockfish Controls
        if (stockfishControlsController != null) {
            stockfishControlsController.setOnParamsChanged((depth, multiPv) -> updateAnalysisParams());
            stockfishControlsController.setOnMasterSwitchChanged(enabled -> {
                isMasterEnabled = enabled;
                updateStockfishState();
            });
            stockfishControlsController.setOnClose(() -> stockfishControls.setVisible(false));
        }
    }
    
    @FXML
    private void toggleSettings() {
        stockfishControls.setVisible(!stockfishControls.isVisible());
    }
    
    private void updateAnalysisParams() {
        if (currentGame != null && stockfishControlsController != null) {
            currentGame.setAnalysisParams(stockfishControlsController.getDepth(), stockfishControlsController.getMultiPv());
        }
    }

    public void startPvP(int duration, int increment) {
        setupBoard();
        currentGame = new PvpGame(chessBoard, evaluationLabel, evalBar, move1Label, move2Label, move3Label, openingNameLabel, bottomLabel, topLabel, duration * 60, increment);
        updateAnalysisParams(); // Apply initial spinner values
        currentGame.startGame();
    }

    public void startPvC(int difficulty, boolean isPlayerWhite) {
        setupBoard();
        currentGame = new PvcGame(chessBoard, evaluationLabel, evalBar, move1Label, move2Label, move3Label, openingNameLabel, isPlayerWhite, difficulty);
        updateAnalysisParams(); // Apply initial spinner values
        currentGame.startGame();
    }

    private void setupBoard() {
        String boardStyle = ConfigManager.getProperty("theme.board", "Marghiacciato.png");
        String pieceStyle = ConfigManager.getProperty("theme.piece", "Classico");
        
        chessBoard = new ChessBoardUI(boardStyle, pieceStyle, 85);
        evalBar = new EvalBar(20, 400);
        boardContainer.getChildren().clear();
        boardContainer.getChildren().addAll(chessBoard, evalBar);
        chessBoard.resetBoard();
    }

    @FXML
    private void handleMoveInput() {
        String input = moveInputField.getText().trim();
        if (!input.isEmpty() && currentGame != null) {
            currentGame.handleMoveInput(input);
            moveInputField.clear();
        }
    }

    @FXML
    private void handleLegalMoves() {
        String input = legalMovesInput.getText().trim();
        chessBoard.clearHighlights();
        if (input != null && !input.isEmpty() && currentGame instanceof PvpGame) {
             try {
                Square selectedSquare = Square.valueOf(input.toUpperCase());
                Stockfish currentstockfish = ((PvpGame) currentGame).getStockfish();
                currentstockfish.highlightLegalMovesWithEvaluation(currentGame.getBoard(), selectedSquare, chessBoard, getEvaluationFromLabel(evaluationLabel.getText()));
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid square");
            }
        }
    }

    @FXML
    private void endGame() {
        if (currentGame != null) {
            boolean save = false;
            if (currentGame instanceof PvpGame) save = ((PvpGame) currentGame).isSaveGame();
            else if (currentGame instanceof PvcGame) save = ((PvcGame) currentGame).isSaveGame();
            
            currentGame.endGame("Partita interrotta", save);
            currentGame = null;
        }
        mainController.navigateTo("HOME");
    }

    @FXML
    private void toggleEvaluation() {
        boolean visible = !evalBar.isVisible();
        evalBar.setVisible(visible);
        evaluationLabel.setVisible(visible);
        updateStockfishState();
    }

    @FXML
    private void toggleBestMoves() {
        boolean visible = !move1Label.isVisible();
        move1Label.setVisible(visible);
        move2Label.setVisible(visible);
        move3Label.setVisible(visible);
        updateStockfishState();
        
        // Hide arrows immediately if suggestions are turned off
        if (!visible && currentGame != null) {
             currentGame.clearArrows();
        }
    }
    
    private void updateStockfishState() {
        boolean isEvalVisible = evaluationLabel.isVisible();
        boolean isMovesVisible = move1Label.isVisible();
        
        if (currentGame != null) {
            // Master switch overrides everything
            if (!isMasterEnabled) {
                currentGame.setAnalysisEnabled(false);
                return;
            }
            
            // Disable if BOTH are hidden
            boolean enabled = isEvalVisible || isMovesVisible;
            currentGame.setAnalysisEnabled(enabled);
            
            // If enabled but moves hidden, ensure arrows are cleared (though Stockfish might send them, we can filter)
            currentGame.setShowArrows(isMovesVisible);
        }
    }
    
    private double getEvaluationFromLabel(String evalText) {
        // ... (Logic from GlobalController)
        try {
            if (evalText.contains("#")) {
                String[] parts = evalText.split(" ");
                for (String part : parts) {
                    if (part.startsWith("#")) {
                        int mateIn = Integer.parseInt(part.substring(1));
                        return mateIn > 0 ? 100.00 : -100.00;
                    }
                }
            } else {
                String[] parts = evalText.split(" ");
                for (String part : parts) {
                    part = part.replace(",", ".");
                    if (part.matches("-?\\d+(\\.\\d+)?")) {
                        return Double.parseDouble(part);
                    }
                }
            }
        } catch (NumberFormatException e) {
            return 0.0;
        }
        return 0.0;
    }
}
