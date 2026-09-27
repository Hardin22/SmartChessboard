package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import org.example.javachess.Oggetti.Puzzle;
import org.example.javachess.Services.PuzzleService;

import java.util.List;

public class PuzzleDashboardController implements NavigationAware {

    private MainController mainController;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    private Slider ratingSlider;
    @FXML
    private Label ratingValueLabel;
    @FXML
    private FlowPane tacticsContainer;
    @FXML
    private FlowPane phaseContainer;
    @FXML
    private FlowPane specialContainer;
    @FXML
    private FlowPane mateContainer;

    @FXML
    private ToggleButton modeRated;
    @FXML
    private ToggleButton modeCasual;

    @FXML
    public void initialize() {
        // Bind slider value to label
        ratingSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            ratingValueLabel.setText(String.valueOf(newVal.intValue()));
        });

        // Setup ToggleGroup for Modes ONLY
        javafx.scene.control.ToggleGroup modeGroup = new javafx.scene.control.ToggleGroup();
        modeRated.setToggleGroup(modeGroup);
        modeCasual.setToggleGroup(modeGroup);

        // NO ToggleGroup for Themes -> Allows Multi-Select
    }

    @FXML
    public void handleStart() {
        int targetRating = (int) ratingSlider.getValue();
        List<String> selectedThemes = getSelectedThemes();

        PuzzleService service = PuzzleService.getInstance();
        Puzzle puzzle;

        List<Puzzle> candidates = service.getPuzzlesByThemeAndRating(selectedThemes, targetRating, 200);
        puzzle = service.getRandomPuzzle(candidates);

        if (puzzle != null) {
            navigateToPuzzleGame(puzzle, targetRating, selectedThemes);
        } else {
            System.out.println("No puzzle found matching criteria.");
        }
    }

    private List<String> getSelectedThemes() {
        List<String> themes = new java.util.ArrayList<>();

        collectThemes(tacticsContainer, themes);
        collectThemes(phaseContainer, themes);
        collectThemes(specialContainer, themes);
        collectThemes(mateContainer, themes);

        if (themes.isEmpty())
            themes.add("Tutti");
        return themes;
    }

    private void collectThemes(FlowPane container, List<String> themes) {
        if (container == null)
            return;
        for (javafx.scene.Node node : container.getChildren()) {
            if (node instanceof ToggleButton) {
                ToggleButton btn = (ToggleButton) node;
                if (btn.isSelected()) {
                    themes.add(mapLabelToTag(btn.getText()));
                }
            }
        }
    }

    private String mapLabelToTag(String label) {
        switch (label) {
            // Tattica
            case "Forchetta":
                return "fork";
            case "Inchiodatura":
                return "pin";
            case "Infilata":
                return "skewer";
            case "Attacco alla Scoperta":
                return "discoveredAttack";
            case "Sacrificio":
                return "sacrifice";
            case "Deviazione":
                return "deflection";
            case "Interferenza":
                return "interference";
            case "Pezzo in Presa":
                return "hangingPiece";
            case "Zugzwang":
                return "zugzwang";
            case "Mossa Tranquilla":
                return "quietMove";

            // Fase
            case "Apertura":
                return "opening";
            case "Medio Gioco":
                return "middlegame";
            case "Finale":
                return "endgame";
            case "Vantaggio":
                return "advantage";

            // Speciali
            case "Promozione":
                return "promotion";
            case "Sottopromozione":
                return "underPromotion";
            case "Arrocco":
                return "castling";
            case "En Passant":
                return "enPassant";

            // Matto
            case "Scacco Matto":
                return "mate";
            case "Matto Affogato":
                return "smotheredMate";
            case "Matto del Corridoio":
                return "backRankMate";
            case "Matto di Boden":
                return "bodenMate";

            default:
                return "Tutti";
        }
    }

    private void navigateToPuzzleGame(Puzzle puzzle, int rating, List<String> themes) {
        if (mainController != null) {
            mainController.loadView("PUZZLE_GAME", "/UI/PuzzleView.fxml");
            PuzzleController controller = (PuzzleController) mainController.getController("PUZZLE_GAME");
            if (controller != null) {
                controller.setPuzzle(puzzle, rating, themes);
                mainController.navigateTo("PUZZLE_GAME");
            }
        }
    }

    @FXML
    public void handleBack() {
        if (mainController != null) {
            mainController.navigateTo("HOME");
        }
    }
}
