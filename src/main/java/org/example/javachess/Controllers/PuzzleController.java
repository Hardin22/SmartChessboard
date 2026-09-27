package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import org.example.javachess.Oggetti.EvalBar;
import org.example.javachess.Oggetti.Puzzle;
import org.example.javachess.Oggetti.PuzzleGame;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Services.PuzzleService;
import org.example.javachess.Utils.ConfigManager;
import java.util.List;

public class PuzzleController implements NavigationAware {

    private MainController mainController;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    private HBox puzzleBoardContainer;
    @FXML
    private Label puzzleInfoLabel;
    @FXML
    private Label statusLabel;
    @FXML
    private Label turnLabel;
    @FXML
    private Label instructionLabel;
    @FXML
    private Button nextButton;
    @FXML
    private Button hintButton;

    private PuzzleGame puzzleGame;
    private ChessBoardUI chessBoardUI;
    private EvalBar evalBar;
    private int hintStage = 0;

    // Criteria for fetching next puzzle
    private int currentTargetRating = 1500;
    private List<String> currentThemes = null;

    @FXML
    public void initialize() {
        // Initialize Components
        String boardStyle = ConfigManager.getProperty("theme.board", "Marghiacciato.png");
        String pieceStyle = ConfigManager.getProperty("theme.piece", "Classico");

        chessBoardUI = new ChessBoardUI(boardStyle, pieceStyle, 85); // Match ActiveGameController
        evalBar = new EvalBar(50, 640);
        evalBar.setPrefHeight(640);

        puzzleBoardContainer.getChildren().add(chessBoardUI);
        // We might want to add evalBar here too if we want it shown

        puzzleGame = new PuzzleGame(chessBoardUI, evalBar, instructionLabel);

        // Listen to status updates from game
        // In a real implementation, we'd use property binding or listener
    }

    public void setPuzzle(Puzzle puzzle) {
        // Default only
        setPuzzle(puzzle, puzzle.getRating(), java.util.Collections.singletonList("Tutti"));
    }

    public void setPuzzle(Puzzle puzzle, int targetRating, List<String> themes) {
        this.currentTargetRating = targetRating;
        this.currentThemes = themes;

        puzzleInfoLabel.setText("Puzzle #" + puzzle.getId() + " • Rating " + puzzle.getRating());
        statusLabel.setText("Trova la mossa!");
        nextButton.setVisible(false);
        nextButton.setManaged(false);

        // Reset Hint System
        hintStage = 0;
        hintButton.setText("SUGGERIMENTO");
        hintButton.setManaged(true);
        hintButton.setVisible(true);

        updateTurnIndicator(puzzle.getFen());

        puzzleGame.setOnTurnChange(() -> {
            javafx.application.Platform.runLater(() -> {
                updateTurnIndicator(chessBoardUI.getFen());
            });
        });

        puzzleGame.startPuzzle(puzzle);
    }

    private void updateTurnIndicator(String fen) {
        String[] parts = fen.split(" ");
        if (parts.length > 1) {
            boolean isWhite = parts[1].equals("w");
            turnLabel.setText(isWhite ? "Tocca al BIANCO" : "Tocca al NERO");
            turnLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: "
                    + (isWhite ? "#FFFFFF" : "#000000") + ";");
            // Add a background or border if needed for contrast
            if (!isWhite) {
                turnLabel.setStyle(turnLabel.getStyle() + " -fx-effect: dropshadow(one-pass-box, white, 2, 2, 0, 0);");
            }
        }
    }

    @FXML
    public void handleHint() {
        if (puzzleGame != null) {
            int level = puzzleGame.toggleHint();
            if (level == 1) {
                hintButton.setText("SOLUZIONE");
            } else if (level == 2) {
                // Keep showing solution or hide? User usually wants to see it.
                // hintButton.setVisible(false);
            }
        }
    }

    @FXML
    public void handleSolution() {
        if (puzzleGame != null) {
            puzzleGame.giveUp();
            nextButton.setVisible(true);
            nextButton.setManaged(true);
        }
    }

    @FXML
    public void handleNewPuzzle() {
        // Fetch next puzzle based on criteria
        PuzzleService service = PuzzleService.getInstance();
        List<Puzzle> candidates = service.getPuzzlesByThemeAndRating(currentThemes, currentTargetRating, 200);
        Puzzle nextPuzzle = service.getRandomPuzzle(candidates);

        if (nextPuzzle != null) {
            setPuzzle(nextPuzzle, currentTargetRating, currentThemes);

        } else {
            statusLabel.setText("Nessun altro puzzle trovato.");
        }
    }

    @FXML
    public void handleBack() {
        if (puzzleGame != null) {
            puzzleGame.endGame("Menu", false);
        }
        if (mainController != null) {
            // Ensure Dashboard is loaded
            mainController.loadView("PUZZLE_DASHBOARD", "/UI/PuzzleDashboardView.fxml");
            mainController.navigateTo("PUZZLE_DASHBOARD");
        }
    }
}
