package org.example.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.MoveBackup;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.javachess.Components.BoardThemes;
import org.example.javachess.Components.EnginePicker;
import org.example.javachess.Components.HardwareStatus;
import org.example.javachess.Components.I18n;
import org.example.javachess.Components.Icons;
import org.example.javachess.Components.MoveListView;
import org.example.javachess.Components.PageHeader;
import org.example.javachess.Components.StatusChip;
import org.example.javachess.Oggetti.AbstractGame;
import org.example.javachess.Oggetti.AnalysisPanel;
import org.example.javachess.Oggetti.ChessBoardUI;
import org.example.javachess.Oggetti.EvalBar;
import org.example.javachess.Oggetti.OnlineGame;
import org.example.javachess.Oggetti.PvcGame;
import org.example.javachess.Oggetti.PvpGame;
import org.example.javachess.Oggetti.UCIEngine;
import org.example.javachess.Services.EngineService;
import org.example.javachess.Utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Game screen (PvC, PvP, Lichess). Presentation only: the game logic lives in the AbstractGame subclasses. */
public class ActiveGameController implements NavigationAware {

    private static final Logger LOG = LoggerFactory.getLogger(ActiveGameController.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private MainController mainController;
    private AbstractGame currentGame;
    private ChessBoardUI chessBoard;
    private final EvalBar evalBar = new EvalBar(8, 400);
    private volatile ArduinoController arduinoController;

    @FXML
    private org.example.javachess.Components.GameLayout gameView;
    @FXML
    private PageHeader header;
    @FXML
    private StatusChip boardStatus;
    @FXML
    private StackPane boardContainer;
    @FXML
    private Label topLabel; // Black clock
    @FXML
    private Label bottomLabel; // White clock
    @FXML
    private HBox topPlayerInfo;
    @FXML
    private Label topName;
    @FXML
    private Label topMeta;
    @FXML
    private Label bottomName;
    @FXML
    private Label bottomMeta;
    @FXML
    private VBox analysisContainer;
    @FXML
    private MoveListView moveList;
    @FXML
    private ToggleButton evalToggle;
    @FXML
    private ToggleButton hintsToggle;
    @FXML
    private Button engineButton;
    @FXML
    private VBox stockfishControls;
    @FXML
    private StockfishControlsController stockfishControlsController;

    /** Opening name: the games write it here; shown as the header subtitle. */
    private final Label openingNameLabel = new Label();
    private AnalysisPanel evaluationPanel;
    private boolean showEvaluation = true;
    private boolean showBestMoves = true;
    private double currentEvaluation = 0.0;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        // Opening the serial port can block: never do it on the FX thread.
        Thread.ofVirtual().name("arduino-init").start(() -> {
            ArduinoController arduino = ArduinoController.getInstance();
            arduino.getBoardStateManager().setEvaluationProvider(() -> this.currentEvaluation);
            this.arduinoController = arduino;
        });
    }

    private ArduinoController arduino() {
        if (arduinoController == null) {
            arduinoController = ArduinoController.getInstance();
            arduinoController.getBoardStateManager().setEvaluationProvider(() -> this.currentEvaluation);
        }
        return arduinoController;
    }

    @FXML
    public void initialize() {
        if (stockfishControlsController != null) {
            stockfishControlsController.setOnParamsChanged((depth, multiPv) -> updateAnalysisParams());
        }
        evaluationPanel = new AnalysisPanel();
        analysisContainer.getChildren().add(evaluationPanel);
        gameView.setEvalBar(evalBar);
        header.subtitleProperty().bind(openingNameLabel.textProperty());
        moveList.setFocusTraversable(false);
    }

    @Override
    public void onNavigatedTo() {
        HardwareStatus.bind(boardStatus);
    }

    @FXML
    private void toggleSettings() {
        mainController.showSheet(I18n.t("analysis.title"), stockfishControlsController.getRoot());
    }

    @FXML
    private void showEngines() {
        EnginePicker picker = new EnginePicker();
        picker.setOnPicked(mainController::closeSheet);
        mainController.showSheet(I18n.t("engine.title"), picker);
    }

    private void updateAnalysisParams() {
        if (currentGame != null && stockfishControlsController != null) {
            currentGame.setAnalysisParams(stockfishControlsController.getDepth(),
                    stockfishControlsController.getMultiPv());
        }
    }

    public void startPvP(int duration, int increment) {
        setupBoard();
        arduino().getBoardStateManager().setEvaluationEnabled(showBestMoves);
        currentGame = new PvpGame(chessBoard, evalBar, openingNameLabel, bottomLabel, topLabel, duration * 60,
                increment);
        header.setTitle(I18n.t("game.vs.player") + " · " + duration + " + " + increment);
        setPlayers(I18n.t("game.black"), I18n.t("game.black"), I18n.t("game.white"), I18n.t("game.white"), true);
        setupGameCallbacks();
        updateAnalysisParams();
        // The opponent sits on the other side of the board: turn their bar towards them.
        topPlayerInfo.setRotate(180);
        currentGame.startGame();
    }

    public void startPvC(int difficulty, boolean isPlayerWhite, EngineService.EngineType botType) {
        setupBoard();
        arduino().getBoardStateManager().setEvaluationEnabled(showBestMoves);
        currentGame = new PvcGame(chessBoard, evalBar, openingNameLabel, isPlayerWhite, difficulty, botType);
        header.setTitle(I18n.t("game.vs.computer"));
        String bot = botName(botType, difficulty);
        if (isPlayerWhite) {
            setPlayers(bot, I18n.t("game.black"), I18n.t("game.you"), I18n.t("game.white"), false);
        } else {
            setPlayers(I18n.t("game.you"), I18n.t("game.black"), bot, I18n.t("game.white"), false);
        }
        setupGameCallbacks();
        updateAnalysisParams();
        topPlayerInfo.setRotate(0);
        currentGame.startGame();
    }

    public void startOnlineGame(String gameId) {
        setupBoard();
        arduino().getBoardStateManager().setEvaluationEnabled(false);
        currentGame = new OnlineGame(chessBoard, evalBar, gameId);
        header.setTitle(I18n.t("game.online"));
        setPlayers(I18n.t("game.black"), "Lichess", I18n.t("game.white"), "Lichess", false);
        setupGameCallbacks();
        topPlayerInfo.setRotate(0);
        currentGame.startGame();
    }

    private static String botName(EngineService.EngineType type, int level) {
        return switch (type) {
            case MAIA_1100 -> "Maia 1100";
            case MAIA_1500 -> "Maia 1500";
            case MAIA_1900 -> "Maia 1900";
            default -> I18n.t("pvc.bot.name", level);
        };
    }

    private void setPlayers(String topNameText, String topMetaText, String bottomNameText, String bottomMetaText,
            boolean clocks) {
        topName.setText(topNameText);
        topMeta.setText(topMetaText);
        bottomName.setText(bottomNameText);
        bottomMeta.setText(bottomMetaText);
        for (Label clock : new Label[] { topLabel, bottomLabel }) {
            clock.setVisible(clocks);
            clock.setManaged(clocks);
            clock.getStyleClass().remove("stopwatch-active");
        }
    }

    private void setupGameCallbacks() {
        if (currentGame != null) {
            currentGame.setAnalysisCallback(createAnalysisCallback());
            currentGame.setStatusCallback(message -> Platform.runLater(() -> updateStatusUI(message)));
        }
    }

    private UCIEngine.AnalysisUpdateCallback createAnalysisCallback() {
        return (pv, bestMove, fullLine, score, moveEvaluations) -> Platform
                .runLater(() -> updateAnalysisUI(pv, bestMove, fullLine, score, moveEvaluations));
    }

    private void setupBoard() {
        if (currentGame != null) {
            stopAndSaveGame();
        }
        this.showEvaluation = ConfigManager.getBooleanProperty("game.evaluation", true);
        this.showBestMoves = ConfigManager.getBooleanProperty("game.suggestions", true);

        chessBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 80);
        chessBoard.setFitToParent(true);
        chessBoard.setOnPositionChanged(this::onPositionChanged);
        boardContainer.getChildren().setAll(chessBoard);
        evalBar.updateEvaluation(0);
        evalBar.setVisible(showEvaluation);
        evalBar.setManaged(showEvaluation);
        chessBoard.resetBoard();
        this.currentEvaluation = 0.0;
        openingNameLabel.setText("");
        moveList.clear();

        evalToggle.setSelected(showEvaluation);
        hintsToggle.setSelected(showBestMoves);
        evaluationPanel.clear();
        evaluationPanel.setStatusMessage("");
        evaluationPanel.setShowEvaluation(showEvaluation);
        evaluationPanel.setShowBestMoves(showBestMoves);
    }

    /** Rebuilds the move list from the game's board history when a move is shown on screen. */
    private void onPositionChanged(Move lastMove) {
        if (lastMove == null || currentGame == null) {
            return;
        }
        Board board = currentGame.getBoard();
        List<Move> played = new ArrayList<>();
        try {
            for (MoveBackup backup : new ArrayList<>(board.getBackup())) {
                played.add(backup.getMove());
            }
        } catch (RuntimeException e) {
            // The game thread changed the history while we copied it; the next move refreshes the list.
            LOG.debug("Move history busy", e);
            return;
        }
        moveList.setMoves(START_FEN, played);
    }

    @FXML
    private void endGame() {
        Board board = currentGame == null ? null : currentGame.getBoard();
        boolean finished = board == null || board.isMated() || board.isDraw();
        if (finished) {
            leaveGame();
            return;
        }
        Label detail = new Label(I18n.t("game.end.confirm.detail"));
        detail.getStyleClass().add("card-description");
        detail.setWrapText(true);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.getStyleClass().addAll("btn", "btn-secondary", "btn-lg");
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> mainController.closeSheet());
        Button confirm = new Button(I18n.t("game.end.confirm.ok"));
        confirm.getStyleClass().addAll("btn", "btn-danger", "btn-lg");
        confirm.setGraphic(Icons.of("fth-flag", 18));
        confirm.setMaxWidth(Double.MAX_VALUE);
        confirm.setOnAction(e -> {
            mainController.closeSheet();
            leaveGame();
        });
        HBox buttons = new HBox(12, cancel, confirm);
        HBox.setHgrow(cancel, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(confirm, javafx.scene.layout.Priority.ALWAYS);
        buttons.setAlignment(Pos.CENTER);
        VBox content = new VBox(20, detail, buttons);
        mainController.showSheet(I18n.t("game.end.confirm"), content);
    }

    private void leaveGame() {
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
            if (currentGame instanceof PvpGame pvp) {
                save = pvp.isSaveGame();
            } else if (currentGame instanceof PvcGame pvc) {
                save = pvc.isSaveGame();
            }
            currentGame.endGame("Partita interrotta", save);
            currentGame = null;
        }
        if (arduinoController != null) {
            arduinoController.getBoardStateManager().stopGameMode();
        }
    }

    @FXML
    private void toggleEvaluation() {
        showEvaluation = evalToggle.isSelected();
        evalBar.setVisible(showEvaluation);
        evalBar.setManaged(showEvaluation);
        evaluationPanel.setShowEvaluation(showEvaluation);
        updateStockfishState();
    }

    @FXML
    private void toggleBestMoves() {
        showBestMoves = hintsToggle.isSelected();
        evaluationPanel.setShowBestMoves(showBestMoves);
        updateStockfishState();
        if (!showBestMoves && currentGame != null) {
            currentGame.clearArrows();
        }
    }

    private void updateStockfishState() {
        if (currentGame != null) {
            boolean enabled = showEvaluation || showBestMoves;
            currentGame.setAnalysisEnabled(enabled);
            if (!enabled) {
                evaluationPanel.clear();
            }
            currentGame.setShowArrows(showBestMoves);
            arduino().getBoardStateManager().setEvaluationEnabled(showBestMoves);
        }
    }

    private void updateStatusUI(String message) {
        evaluationPanel.setStatusMessage(message);
        if (currentGame != null && currentGame.getBoard().isMated()) {
            evaluationPanel.showResult(currentGame.getBoard().getSideToMove() == Side.WHITE ? "0-1" : "1-0");
        }
    }

    private void updateAnalysisUI(int pv, String bestMove, String fullLine, double score, String[] moveEvaluations) {
        if (pv == 0) {
            this.currentEvaluation = score;
            if (arduinoController != null) {
                arduinoController.getBoardStateManager().setBestMove(bestMove);
            }
        }
        String evalText = (moveEvaluations != null && moveEvaluations.length > 0) ? moveEvaluations[0] : "0.0";
        if (currentGame != null) {
            if (currentGame.getBoard().isMated()) {
                evaluationPanel.showResult(currentGame.getBoard().getSideToMove() == Side.WHITE ? "0-1" : "1-0");
            } else {
                evaluationPanel.updateAnalysis(pv, fullLine, evalText);
            }
        }
    }

    // ------------------------------------------------------------------ dev helpers (DevOptions)

    /** Plays moves (UCI, e.g. "e2e4") as if made on the board, one by one. Used by DevOptions demos. */
    public void devPlayMoves(List<String> uciMoves, long delayMillis) {
        AbstractGame game = currentGame;
        if (game == null) {
            return;
        }
        Thread.ofVirtual().name("dev-moves").start(() -> {
            for (String move : uciMoves) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                game.handleMoveInput(move);
            }
        });
    }
}
