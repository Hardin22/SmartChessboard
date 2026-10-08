package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Side;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardFrame;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.PuzzleThemes;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.StatusCard;
import io.github.hardin22.javachess.Components.StatusCard.Tone;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Oggetti.PuzzleGame;
import io.github.hardin22.javachess.Play.PuzzleReview;
import io.github.hardin22.javachess.Play.PuzzleRush;
import io.github.hardin22.javachess.Services.PuzzleService;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Puzzle screen, turned towards the solver. Presentation only; PuzzleGame owns the puzzle logic. */
public class PuzzleController implements Screen {

    private static final Pattern SQUARES = Pattern.compile("([a-hA-H][1-8])\\s*([a-hA-H][1-8])?");

    private MainController mainController;
    private final VBox root = new VBox();
    private final ScreenHeader header;
    private final EvalBar evalBar = new EvalBar(22, 600);
    private final BoardFrame boardFrame = new BoardFrame(evalBar);
    private final StatusCard status = new StatusCard();
    private final FlowPane themeChips = new FlowPane(10, 10);
    private final HBox progress = new HBox(28);
    private final Label instructionLabel = new Label();
    private final Button hintButton;
    private final Button solutionButton;
    private final Button nextButton;

    private PuzzleGame puzzleGame;
    private ChessBoardUI chessBoardUI;
    private String boardStyle;
    private int currentTargetRating = 1500;
    private Puzzle currentPuzzle;
    private List<String> currentThemes;
    private boolean solverWhite = true;
    private boolean finished;

    /** Normal puzzles, the review of failed ones, or a timed series. */
    enum Mode { NORMAL, REVIEW, RUSH }

    private Mode mode = Mode.NORMAL;
    private PuzzleRush rush;
    private final List<Runnable> rushUnbind = new java.util.ArrayList<>();
    private final Label rushTime = Ui.label("", "rush-time");
    private final Label rushScore = Ui.label("0", "t-number");
    private final HBox rushLives = new HBox(8);
    private final Label rushBest = Ui.label("", "t-small", "t-muted");
    private final Button skipButton;
    private final Button stopButton;

    public PuzzleController() {
        header = new ScreenHeader(I18n.t("puzzle.title"), this::handleBack);
        hintButton = Ui.toolButton(I18n.t("puzzle.hint"), "fth-help-circle", this::handleHint);
        solutionButton = Ui.toolButton(I18n.t("puzzle.solution"), "fth-eye", this::handleSolution);
        nextButton = Ui.toolButton(I18n.t("puzzle.next"), "fth-skip-forward", this::handleNewPuzzle);
        skipButton = Ui.toolButton(I18n.t("puzzle.rush.skip"), "fth-skip-forward", () -> {
            if (rush != null) {
                rush.failed();
            }
        });
        stopButton = Ui.toolButton(I18n.t("puzzle.rush.stop"), "fth-square", () -> {
            if (rush != null) {
                rush.stop();
            }
        });
        stopButton.setId("rush-stop");
        for (Button b : new Button[] { skipButton, stopButton }) {
            HBox.setHgrow(b, javafx.scene.layout.Priority.ALWAYS);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setPrefWidth(1);
        }
        evalBar.setVisible(false);
        build();
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

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        mainController.facingBlackProperty().addListener((obs, o, n) -> {
            if (chessBoardUI != null) {
                chessBoardUI.setFlipped(n);
            }
        });
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    private void build() {
        VBox boardBox = new VBox(boardFrame);
        boardBox.setPadding(new Insets(4, 0, 0, 0));
        VBox lower = new VBox(16, status, themeChips, Ui.gap(8), progress);
        progress.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        lower.setPadding(new Insets(16, 24, 0, 24));
        HBox tools = Ui.equalRow(12, hintButton, solutionButton, nextButton);
        tools.setPadding(new Insets(12, 24, 28, 24));
        this.boardBox = boardBox;
        this.lower = lower;
        this.tools = tools;
        root.getChildren().addAll(header, boardBox, lower, Ui.vgrow(), tools);
    }

    private VBox boardBox;
    private VBox lower;
    private HBox tools;

    @Override
    public void setWide(boolean wide) {
        root.getChildren().clear();
        if (!wide) {
            VBox.setVgrow(boardFrame, javafx.scene.layout.Priority.NEVER);
            boardBox.getChildren().setAll(boardFrame);
            root.getChildren().addAll(header, boardBox, lower, Ui.vgrow(), tools);
            return;
        }
        VBox.setVgrow(boardFrame, javafx.scene.layout.Priority.ALWAYS);
        VBox panel = new VBox(header, lower, Ui.vgrow(), tools);
        panel.setPrefWidth(Ui.COLUMN);
        panel.setMinWidth(560);
        HBox.setHgrow(boardBox, javafx.scene.layout.Priority.ALWAYS);
        HBox columns = new HBox(8, boardBox, panel);
        VBox.setVgrow(columns, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().add(columns);
    }

    /** (Re)creates board and game, e.g. when the board or piece style changed since the last puzzle. */
    private void createBoard() {
        boardStyle = BoardThemes.currentBoard() + "|" + BoardThemes.currentPieces();
        chessBoardUI = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 80);
        chessBoardUI.setFitToParent(true);
        chessBoardUI.setOverlaysEnabled(false);
        chessBoardUI.setFlipped(mainController != null && mainController.isFacingBlack());
        boardFrame.setBoard(chessBoardUI);
        puzzleGame = new PuzzleGame(chessBoardUI, evalBar, instructionLabel);
        PuzzleGame game = puzzleGame;
        chessBoardUI.setMoveInput(new ChessBoardUI.MoveInput() {
            @Override
            public com.github.bhlangonijr.chesslib.Board position() {
                return game.getBoard();
            }

            @Override
            public boolean enabled() {
                // without a physical board the puzzle is solved on the screen
                return !finished && game.isAwaitingHumanMove() && !io.github.hardin22.javachess.Controllers
                        .ArduinoController.getInstance().getBoardStateManager().isHardwareConnected();
            }

            @Override
            public void play(String uci) {
                game.handleMoveInput(uci);
            }

            @Override
            public void choosePromotion(String from, String to, boolean white, java.util.function.Consumer<String> done) {
                PromotionPicker.show(mainController, white, piece -> done.accept(from + to + piece));
            }
        });
        puzzleGame.setStatusCallback(this::showStatus); // setup progress, wrong move, hint, solution
    }

    private void showStatus(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ITALIAN);
        String side = I18n.t(solverWhite ? "puzzle.turn.white" : "puzzle.turn.black");
        StatusCard.Content content;
        if ((lower.contains("complet") || lower.contains("complimenti")) && mode == Mode.RUSH) {
            finished = true;
            content = StatusCard.Content.of(Tone.DONE, I18n.t("puzzle.done.kicker"), I18n.t("puzzle.done"),
                    I18n.t("puzzle.rush.loading"));
        } else if (lower.contains("complet") || lower.contains("complimenti")) {
            finished = true;
            Button next = Ui.button(I18n.t("puzzle.next"), "fth-skip-forward", "btn-inverse", "btn-md");
            next.setOnAction(e -> handleNewPuzzle());
            content = new StatusCard.Content(Tone.DONE, I18n.t("puzzle.done.kicker"), I18n.t("puzzle.done"), null,
                    I18n.t("puzzle.done.detail"), List.of(next));
            showNext();
        } else if (lower.contains("errat")) {
            content = StatusCard.Content.of(Tone.ERROR, side, I18n.t("puzzle.wrong"),
                    lower.contains("indietro") ? I18n.t("puzzle.wrong.back") : I18n.t("puzzle.wrong.retry"));
        } else if (lower.startsWith("suggerimento")) {
            Matcher m = SQUARES.matcher(text);
            String square = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
            content = new StatusCard.Content(Tone.ACTION, I18n.t("puzzle.hint"), I18n.t("puzzle.hint.title"),
                    square, I18n.t("puzzle.hint.detail"), List.of());
        } else if (lower.startsWith("soluzione")) {
            Matcher m = SQUARES.matcher(text.substring(text.indexOf(':') + 1));
            String move = m.find() ? m.group(1).toLowerCase(Locale.ROOT)
                    + (m.group(2) != null ? " → " + m.group(2).toLowerCase(Locale.ROOT) : "") : null;
            content = new StatusCard.Content(Tone.ACTION, I18n.t("puzzle.solution"), I18n.t("puzzle.solution.title"),
                    move, I18n.t("puzzle.solution.detail"), List.of());
        } else if (lower.startsWith("configura") || lower.startsWith("posiziona")) {
            content = ActiveGameController.setupCard(text,
                    io.github.hardin22.javachess.Oggetti.AnalysisPanel.prettify(text));
        } else if (lower.contains("tocca a te") || lower.contains("pronta")) {
            content = StatusCard.Content.of(Tone.TURN, side, I18n.t("puzzle.find"), I18n.t("puzzle.find.detail"));
        } else if (lower.contains("avversario")) {
            content = StatusCard.Content.of(Tone.PLAIN, I18n.t("puzzle.opponent.kicker"),
                    I18n.t("puzzle.opponent"), null);
        } else {
            content = StatusCard.Content.of(Tone.PLAIN, side, io.github.hardin22.javachess.Oggetti.AnalysisPanel
                    .prettify(text), null);
        }
        if (finished && content.tone() != Tone.DONE && content.tone() != Tone.ACTION) {
            return; // the solved card stays until the next puzzle
        }
        status.show(content);
    }

    public void setPuzzle(Puzzle puzzle) {
        setPuzzle(puzzle, puzzle.getRating(), java.util.Collections.singletonList("Tutti"));
    }

    /** A puzzle chosen on the dashboard (leaves the review or a series). */
    public void startPuzzle(Puzzle puzzle, int targetRating, List<String> themes) {
        leaveRush();
        setPuzzle(puzzle, targetRating, themes);
    }

    public void setPuzzle(Puzzle puzzle, int targetRating, List<String> themes) {
        this.currentTargetRating = targetRating;
        this.currentThemes = themes;
        if (!boardStyle.equals(BoardThemes.currentBoard() + "|" + BoardThemes.currentPieces())) {
            puzzleGame.endGame("Menu", false);
            createBoard();
        }
        configureGame();
        currentPuzzle = puzzle;
        finished = false;
        String[] fen = puzzle.getFen().split(" ");
        // The first move of a Lichess puzzle is the opponent's: the solver plays the other colour.
        solverWhite = fen.length > 1 && "b".equals(fen[1]);
        if (mainController != null) {
            mainController.face(solverWhite ? Side.WHITE : Side.BLACK);
        }
        header.setSubtitle(mode == Mode.RUSH ? rush.mode().italian() + " · " + I18n.t("puzzle.rating.value",
                puzzle.getRating()) : mode == Mode.REVIEW ? I18n.t("puzzle.review.subtitle", puzzle.getRating())
                : I18n.t("puzzle.info", puzzle.getId(), puzzle.getRating()));
        status.show(StatusCard.Content.of(Tone.PLAIN, I18n.t(solverWhite ? "puzzle.turn.white" : "puzzle.turn.black"),
                I18n.t("puzzle.loading.position"), null));
        hintButton.setText(I18n.t("puzzle.hint"));
        hintButton.setDisable(false);
        solutionButton.setDisable(false);
        themeChips.getChildren().clear();
        if (puzzle.getThemes() != null) {
            for (String tag : puzzle.getThemes()) {
                themeChips.getChildren().add(Ui.label(PuzzleThemes.label(tag), "pill"));
            }
        }
        puzzleGame.startPuzzle(puzzle);
        if (mode != Mode.RUSH) {
            refreshProgress();
        }
    }

    // ================================================================== review of failed puzzles

    /** "Ripassa": the failed puzzles again, one after the other, until they are solved without help. */
    public void startReview() {
        leaveRush();
        mode = Mode.REVIEW;
        nextFromReview();
    }

    private void nextFromReview() {
        nextButton.setDisable(true);
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            java.util.Optional<Puzzle> next = PuzzleReview.get().next();
            Platform.runLater(() -> {
                nextButton.setDisable(false);
                if (next.isPresent()) {
                    setPuzzle(next.get(), next.get().getRating(), List.of("Tutti"));
                } else {
                    mainController.showToast(I18n.t("puzzle.review.empty"));
                    handleBack();
                }
            });
        });
    }

    // ================================================================== timed series

    /** A timed series (3 or 5 minutes, or survival): unrated, one wrong move fails the puzzle. */
    public void startRush(PuzzleRush.Mode rushMode) {
        int rating = io.github.hardin22.javachess.Services.PuzzleProgressService.getInstance().getStats().rating();
        startRush(new PuzzleRush(rushMode, rating));
    }

    public void startRush(PuzzleRush series) {
        leaveRush();
        mode = Mode.RUSH;
        rush = series;
        javafx.beans.InvalidationListener bar = o -> refreshRushBar();
        for (javafx.beans.Observable p : List.of(series.timeTextProperty(), series.timeLowProperty(),
                series.scoreProperty(), series.failuresProperty(), series.bestProperty(), series.stateProperty())) {
            p.addListener(bar);
            rushUnbind.add(() -> p.removeListener(bar));
        }
        javafx.beans.value.ChangeListener<Puzzle> next = (obs, o, p) -> {
            if (p != null) {
                setPuzzle(p, p.getRating(), List.of("Tutti"));
            }
        };
        series.currentProperty().addListener(next);
        rushUnbind.add(() -> series.currentProperty().removeListener(next));
        javafx.beans.InvalidationListener end = o -> {
            if (series.stateProperty().get() == PuzzleRush.State.FINISHED) {
                Platform.runLater(() -> showRushEnd(series)); // the final message is set right after the state
            } else if (series.stateProperty().get() == PuzzleRush.State.LOADING && currentPuzzle == null) {
                status.show(StatusCard.Content.of(Tone.PLAIN, series.mode().italian(),
                        I18n.t("puzzle.rush.loading"), null));
            }
        };
        series.stateProperty().addListener(end);
        rushUnbind.add(() -> series.stateProperty().removeListener(end));
        currentPuzzle = null;
        layoutForMode();
        refreshRushBar();
        series.start();
    }

    private void showRushEnd(PuzzleRush series) {
        finished = true;
        if (puzzleGame != null) {
            puzzleGame.endGame("Menu", false);
        }
        Button again = Ui.button(I18n.t("puzzle.rush.again"), "fth-rotate-ccw", "btn-inverse", "btn-md");
        again.setOnAction(e -> startRush(new PuzzleRush(series.mode(), io.github.hardin22.javachess.Services
                .PuzzleProgressService.getInstance().getStats().rating())));
        Button exit = Ui.button(I18n.t("puzzle.rush.exit"), "fth-arrow-left", "btn-outline", "btn-md");
        exit.setOnAction(e -> handleBack());
        status.show(new StatusCard.Content(Tone.DONE, I18n.t("puzzle.rush.over"), series.messageProperty().get(),
                null, series.newRecordProperty().get() ? I18n.t("puzzle.rush.record.new") : null,
                List.of(again, exit)));
        tools.setVisible(false);
    }

    private void refreshRushBar() {
        if (rush == null) {
            return;
        }
        boolean timed = rush.mode().seconds() > 0;
        rushTime.setText(timed ? rush.timeTextProperty().get() : rush.mode().italian());
        rushTime.getStyleClass().remove("low");
        if (timed && rush.timeLowProperty().get()) {
            rushTime.getStyleClass().add("low");
        }
        rushScore.setText(String.valueOf(rush.scoreProperty().get()));
        rushLives.getChildren().clear();
        for (int i = 0; i < PuzzleRush.MAX_FAILURES; i++) {
            boolean lost = i < rush.failuresProperty().get();
            var icon = io.github.hardin22.javachess.Components.Icons.of(lost ? "fth-x" : "fth-heart", 30);
            icon.getStyleClass().add(lost ? "rush-life-lost" : "rush-life");
            rushLives.getChildren().add(icon);
        }
        int best = rush.bestProperty().get();
        rushBest.setText(best > 0 ? I18n.t("puzzle.rush.best", best) : I18n.t("puzzle.rush.nobest"));
    }

    /** The bar under the board: solver stats normally, time / score / lives in a series. */
    private void layoutForMode() {
        boolean inRush = mode == Mode.RUSH;
        if (inRush) {
            VBox score = new VBox(0, rushScore, Ui.label(I18n.t("puzzle.rush.score"), "t-small", "t-muted"));
            VBox lives = new VBox(6, rushLives, rushBest);
            progress.getChildren().setAll(rushTime, score, lives);
            themeChips.setVisible(false);
            themeChips.setManaged(false);
            tools.getChildren().setAll(skipButton, stopButton);
        } else {
            themeChips.setVisible(true);
            themeChips.setManaged(true);
            tools.getChildren().setAll(hintButton, solutionButton, nextButton);
        }
        tools.setVisible(true);
    }

    /** The puzzle game follows the mode (a new game object is created when the board style changes). */
    private void configureGame() {
        if (puzzleGame == null) {
            return;
        }
        boolean inRush = mode == Mode.RUSH && rush != null;
        puzzleGame.setRated(!inRush);
        PuzzleRush series = rush;
        puzzleGame.setResultListener(!inRush ? null : new PuzzleGame.ResultListener() {
            @Override
            public void finished(Puzzle puzzle, boolean solved, boolean clean) {
                Platform.runLater(() -> {
                    if (solved) {
                        series.solved();
                    } else {
                        series.failed();
                    }
                });
            }

            @Override
            public void wrongMove(Puzzle puzzle) {
                Platform.runLater(series::failed);
            }
        });
    }

    private void leaveRush() {
        rushUnbind.forEach(Runnable::run);
        rushUnbind.clear();
        if (rush != null) {
            rush.stop();
            rush = null;
        }
        mode = Mode.NORMAL;
        configureGame();
        layoutForMode();
    }

    /** The solver's rating, streak and solved count under the puzzle. */
    private void refreshProgress() {
        io.github.hardin22.javachess.Utils.AppExecutors.io().execute(() -> {
            var stats = io.github.hardin22.javachess.Services.PuzzleProgressService.getInstance().getStats();
            Platform.runLater(() -> progress.getChildren().setAll(
                    stat(String.valueOf(stats.rating()), I18n.t("puzzle.stats.rating")),
                    stat(String.valueOf(stats.currentStreak()), I18n.t("puzzle.stats.streak")),
                    stat(stats.solved() + "/" + stats.attempts(), I18n.t("puzzle.stats.solved"))));
        });
    }

    private static VBox stat(String value, String caption) {
        return new VBox(0, Ui.label(value, "t-number"), Ui.label(caption, "t-small", "t-muted"));
    }

    public void handleHint() {
        if (puzzleGame != null && !finished) {
            int level = puzzleGame.toggleHint();
            if (level == 1) {
                hintButton.setText(I18n.t("puzzle.hint.more"));
            }
        }
    }

    public void handleSolution() {
        if (puzzleGame != null && !finished) {
            puzzleGame.giveUp();
            showNext();
        }
    }

    private void showNext() {
        hintButton.setDisable(true);
        solutionButton.setDisable(true);
    }

    public void handleNewPuzzle() {
        if (mode == Mode.REVIEW) {
            nextFromReview();
            return;
        }
        if (mode == Mode.RUSH) {
            return; // the series loads the next puzzle itself
        }
        nextButton.setDisable(true);
        int rating = currentTargetRating;
        List<String> themes = currentThemes == null ? List.of("Tutti") : currentThemes;
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

    public void handleBack() {
        leaveRush();
        if (puzzleGame != null) {
            puzzleGame.endGame("Menu", false);
        }
        mainController.navigateTo("PUZZLE_DASHBOARD");
    }

    /** For DevOptions demos. */
    Puzzle currentPuzzle() {
        return currentPuzzle;
    }
}
