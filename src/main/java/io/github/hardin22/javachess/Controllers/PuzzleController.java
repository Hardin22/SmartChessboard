package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.PageHeader;
import io.github.hardin22.javachess.Components.PuzzleThemes;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Oggetti.PuzzleGame;
import io.github.hardin22.javachess.Services.PuzzleService;

import java.util.List;

/** Puzzle screen. Presentation only; PuzzleGame owns the puzzle logic. */
public class PuzzleController implements NavigationAware {

    private MainController mainController;

    @FXML
    private PageHeader header;
    @FXML
    private StackPane puzzleBoardContainer;
    @FXML
    private Label statusLabel;
    @FXML
    private Label turnLabel;
    @FXML
    private Region turnAvatar;
    @FXML
    private Label ratingBadge;
    @FXML
    private Label instructionLabel;
    @FXML
    private Button nextButton;
    @FXML
    private Button hintButton;
    @FXML
    private Button giveUpButton;
    @FXML
    private FlowPane themeChips;

    private PuzzleGame puzzleGame;
    private ChessBoardUI chessBoardUI;
    private int currentTargetRating = 1500;
    private Puzzle currentPuzzle;
    /** Hint, wrong move or give-up on the current puzzle: it no longer counts as solved cleanly. */
    private List<String> currentThemes = null;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    private String boardStyle;

    @FXML
    public void initialize() {
        createBoard();
        // PuzzleGame reports progress through instructionLabel (sometimes off the FX thread).
        instructionLabel.textProperty().addListener((obs, o, n) -> {
            if (n != null && !n.isBlank()) {
                if (Platform.isFxApplicationThread()) {
                    showStatus(n);
                } else {
                    Platform.runLater(() -> showStatus(n));
                }
            }
        });
    }

    /** (Re)creates board and game, e.g. when the board or piece style changed since the last puzzle. */
    private void createBoard() {
        boardStyle = BoardThemes.currentBoard() + "|" + BoardThemes.currentPieces();
        chessBoardUI = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 80);
        chessBoardUI.setFitToParent(true);
        puzzleBoardContainer.getChildren().setAll(chessBoardUI);
        puzzleGame = new PuzzleGame(chessBoardUI, new EvalBar(8, 400), instructionLabel);
        puzzleGame.setStatusCallback(this::showStatus); // setup progress, wrong move, hint, solution
    }

    private void showStatus(String text) {
        String pretty = text.equals(text.toUpperCase()) && text.length() > 3
                ? Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase() : text;
        statusLabel.setText(pretty);
        // Progress (solved / mistakes / hints / give up) is recorded once by PuzzleGame itself.
        if (text.toUpperCase().startsWith("COMPLIMENTI")) {
            showNext();
        }
    }

    public void setPuzzle(Puzzle puzzle) {
        setPuzzle(puzzle, puzzle.getRating(), java.util.Collections.singletonList("Tutti"));
    }

    public void setPuzzle(Puzzle puzzle, int targetRating, List<String> themes) {
        this.currentTargetRating = targetRating;
        this.currentThemes = themes;
        if (!boardStyle.equals(BoardThemes.currentBoard() + "|" + BoardThemes.currentPieces())) {
            puzzleGame.endGame("Menu", false);
            createBoard();
        }

        currentPuzzle = puzzle;
        header.setSubtitle(I18n.t("puzzle.info", puzzle.getId(), puzzle.getRating()));
        ratingBadge.setText(String.valueOf(puzzle.getRating()));
        statusLabel.setText(I18n.t("puzzle.find"));
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        giveUpButton.setVisible(true);
        giveUpButton.setManaged(true);
        hintButton.setText(I18n.t("puzzle.hint"));
        hintButton.setManaged(true);
        hintButton.setVisible(true);

        themeChips.getChildren().clear();
        if (puzzle.getThemes() != null) {
            for (String tag : puzzle.getThemes()) {
                Label chip = new Label(PuzzleThemes.label(tag));
                chip.getStyleClass().add("badge");
                themeChips.getChildren().add(chip);
            }
        }

        updateTurnIndicator(puzzle.getFen());
        puzzleGame.setOnTurnChange(() -> Platform.runLater(() -> updateTurnIndicator(chessBoardUI.getFen())));
        puzzleGame.startPuzzle(puzzle);
    }

    private void updateTurnIndicator(String fen) {
        String[] parts = fen.split(" ");
        if (parts.length > 1) {
            boolean isWhite = parts[1].equals("w");
            turnLabel.setText(I18n.t(isWhite ? "puzzle.turn.white" : "puzzle.turn.black"));
            turnAvatar.getStyleClass().removeAll("white", "black");
            turnAvatar.getStyleClass().add(isWhite ? "white" : "black");
        }
    }

    @FXML
    public void handleHint() {
        if (puzzleGame != null) {
            int level = puzzleGame.toggleHint();
            if (level == 1) {
                hintButton.setText(I18n.t("puzzle.solution"));
            }
        }
    }

    @FXML
    public void handleSolution() {
        if (puzzleGame != null) {
            puzzleGame.giveUp();
            showNext();
        }
    }

    private void showNext() {
        nextButton.setVisible(true);
        nextButton.setManaged(true);
        giveUpButton.setVisible(false);
        giveUpButton.setManaged(false);
        hintButton.setVisible(false);
        hintButton.setManaged(false);
    }

    @FXML
    public void handleNewPuzzle() {
        nextButton.setDisable(true);
        int rating = currentTargetRating;
        List<String> themes = currentThemes;
        // The search reads the puzzle database: off the FX thread.
        PuzzleService.getInstance().findPuzzleAsync(rating, 200, themes)
                .thenAccept(next -> Platform.runLater(() -> {
                    nextButton.setDisable(false);
                    if (next != null) {
                        setPuzzle(next, rating, themes);
                    } else {
                        mainController.showToast(I18n.t("puzzle.none"));
                    }
                }));
    }

    @Override
    public void onNavigatedFrom() {
        if (puzzleGame != null) {
            puzzleGame.endGame("Menu", false); // leave setup mode and release the board listener
        }
    }

    @FXML
    public void handleBack() {
        if (puzzleGame != null) {
            puzzleGame.endGame("Menu", false);
        }
        mainController.navigateTo("PUZZLE_DASHBOARD");
    }
}
