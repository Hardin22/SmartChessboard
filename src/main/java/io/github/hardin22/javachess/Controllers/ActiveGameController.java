package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.MoveBackup;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.EnginePicker;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Notation;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.RotateButton;
import io.github.hardin22.javachess.Components.StatusCard;
import io.github.hardin22.javachess.Components.StatusCard.Tone;
import io.github.hardin22.javachess.Components.Stepper;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.EngineProfile;
import io.github.hardin22.javachess.Engine.EngineSelection;
import io.github.hardin22.javachess.Engine.EngineStatus;
import io.github.hardin22.javachess.Engine.MoveCoach;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Oggetti.AbstractGame;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;
import io.github.hardin22.javachess.Oggetti.OnlineGame;
import io.github.hardin22.javachess.Oggetti.PvcGame;
import io.github.hardin22.javachess.Oggetti.PvpGame;
import io.github.hardin22.javachess.Play.BotLevels;
import io.github.hardin22.javachess.Play.GameClock;
import io.github.hardin22.javachess.Play.GameSnapshot;
import io.github.hardin22.javachess.Play.HintAdvisor;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.EngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Game screen. Presentation only: the game logic lives in the AbstractGame subclasses.
 *
 * <ul>
 *   <li>Against the computer / online: one person, the screen faces them ({@link GameSoloView}); the status card
 *       says what to do now — above all the computer's move to make on the physical board.</li>
 *   <li>Two players: one half of the screen each, turned towards them ({@link GameDuelView}).</li>
 * </ul>
 */
public class ActiveGameController implements Screen, GameDuelView.Actions {

    private static final Logger LOG = LoggerFactory.getLogger(ActiveGameController.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String DUEL_BOARD_KEY = "ui.duel.board";

    private enum Mode { PVC, PVP, ONLINE }

    private MainController mainController;
    private AbstractGame currentGame;
    private ChessBoardUI chessBoard;
    private final EvalBar soloEvalBar = new EvalBar(22, 600);
    private final EvalBar duelEvalBar = new EvalBar(22, 560);
    private final StackPane root = new StackPane();
    private final GameSoloView solo;
    private final GameDuelView duel;
    private volatile ArduinoController arduinoController;

    /** The games write the opening name here. */
    private final Label openingNameLabel = new Label();
    /** The two-player game's clocks write here (ChessTimer); the clock faces follow them. */
    private final Label whiteClock = new Label();
    private final Label blackClock = new Label();

    private Mode mode = Mode.PVC;
    private boolean humanWhite = true;
    private String opponentName = "";
    private String title = "";
    private int pvpSeconds = 600;
    private int pvpIncrement;
    private boolean showEvaluation = true;
    private boolean showBestMoves = true;
    private int analysisDepth = Prefs.integer("game.depth", 18);
    private double currentEvaluation;
    private GameStatus status = GameStatus.none();
    private List<String> san = List.of();
    private List<String> uciPlayed = List.of();
    private Side drawOfferBy;
    private boolean ended;
    private String endMessage = "";
    private String opponentElo;

    public ActiveGameController() {
        solo = new GameSoloView(this, soloEvalBar);
        duel = new GameDuelView(this, duelEvalBar);
        root.getChildren().add(solo);
        openingNameLabel.textProperty().addListener((obs, o, n) -> onOpeningChanged());
        whiteClock.textProperty().addListener((obs, o, n) -> duel.half(Side.WHITE).clock.setTime(n));
        blackClock.textProperty().addListener((obs, o, n) -> duel.half(Side.BLACK).clock.setTime(n));
        whiteClock.getStyleClass().addListener((javafx.collections.ListChangeListener<String>) c -> refreshClocks());
        blackClock.getStyleClass().addListener((javafx.collections.ListChangeListener<String>) c -> refreshClocks());

        EngineSelection engines = EngineSelection.get();
        engines.activeProfileProperty().addListener((obs, o, n) -> runFx(this::showEngineState));
        engines.statusProperty().addListener((obs, o, n) -> runFx(() -> {
            showEngineState();
            if (n != null && n.state() == EngineStatus.State.ERROR && !n.message().isBlank()
                    && mainController != null && root.getScene() != null) {
                mainController.showToast(n.message());
            }
        }));
    }

    private static void runFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        mainController.facingBlackProperty().addListener((obs, o, n) -> applyOrientation());
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

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void setWide(boolean wide) {
        solo.setWide(wide);
        duel.setWide(wide);
    }

    // ================================================================== start

    public void startPvP(int duration, int increment) {
        startPvPSeconds(duration * 60, increment);
    }

    /** Two-player game with {@code seconds} on each clock (also used by the end-to-end tests: short clocks). */
    public void startPvPSeconds(int seconds, int increment) {
        setupBoard();
        beginPvp(new PvpGame(chessBoard, duelEvalBar, openingNameLabel, whiteClock, blackClock, seconds, increment),
                seconds, increment);
    }

    private void beginPvp(PvpGame game, int seconds, int increment) {
        mode = Mode.PVP;
        unbindPvcExtras();
        pvpSeconds = seconds;
        pvpIncrement = increment;
        int minutes = Math.max(1, seconds / 60);
        title = I18n.t("game.vs.player") + " · " + minutes + " + " + increment;
        root.getChildren().setAll(duel);
        duel.boardFrame.setBoard(chessBoard);
        duel.near.reset();
        duel.far.reset();
        duel.setPaused(false);
        duel.setBoardVisible(Prefs.bool(DUEL_BOARD_KEY, true));
        drawOfferBy = null;
        prepareCoach();
        currentGame = game;
        setupGameCallbacks();
        if (mainController != null) {
            mainController.face(Side.WHITE); // both halves are already turned towards their players
        }
        applyOrientation();
        updateStockfishState();
        currentGame.startGame();
        refreshAll();
    }

    /** Old-style game against Stockfish skill 0..20 or a Maia network (kept for scripts and tests). */
    public void startPvC(int difficulty, boolean isPlayerWhite, EngineService.EngineType botType) {
        setupBoard();
        beginPvc(new PvcGame(chessBoard, soloEvalBar, openingNameLabel, isPlayerWhite, difficulty, botType),
                isPlayerWhite, botName(botType, difficulty), null, botType != null && botType.name().startsWith("MAIA"));
    }

    /** Game against a level of the ladder (approximate Elo), with a clock or without. */
    public void startPvC(BotLevels.Level level, boolean isPlayerWhite, TimeControl timeControl) {
        setupBoard();
        beginPvc(new PvcGame(chessBoard, soloEvalBar, openingNameLabel, isPlayerWhite, level, timeControl),
                isPlayerWhite, level.name(), level.eloText(), level.isMaia());
    }

    /** A game interrupted by a restart or a power cut (Home, "Partita interrotta"). */
    public void resumeSnapshot(GameSnapshot snapshot) {
        setupBoard();
        if (snapshot.mode() == GameSnapshot.Mode.PVP) {
            TimeControl tc = snapshot.timeControl();
            beginPvp(PvpGame.fromSnapshot(snapshot, chessBoard, duelEvalBar, openingNameLabel, whiteClock, blackClock),
                    tc.isUnlimited() ? 0 : tc.initialSeconds(), tc.incrementSeconds());
            return;
        }
        PvcGame game = PvcGame.fromSnapshot(snapshot, chessBoard, soloEvalBar, openingNameLabel);
        BotLevels.Level level = game.getLevel();
        beginPvc(game, snapshot.humanWhite(), level != null ? level.name() : I18n.t("game.computer"),
                level != null ? level.eloText() : null, level != null && level.isMaia());
    }

    private void beginPvc(PvcGame game, boolean isPlayerWhite, String name, String elo, boolean maia) {
        mode = Mode.PVC;
        humanWhite = isPlayerWhite;
        opponentName = name;
        opponentElo = elo;
        title = elo == null ? name : name + " · " + elo;
        root.getChildren().setAll(solo);
        solo.boardFrame.setBoard(chessBoard);
        prepareCoach();
        currentGame = game;
        solo.header.setTitle(I18n.t("game.vs.computer"));
        solo.header.setSubtitle(title);
        solo.row(!isPlayerWhite).setIcon(maia ? "fth-user" : "fth-cpu");
        solo.row(isPlayerWhite).setIcon(null);
        solo.setPlayers(isPlayerWhite ? I18n.t("game.you") : name,
                I18n.t("common.white"), isPlayerWhite ? name : I18n.t("game.you"), I18n.t("common.black"));
        bindPvcExtras(game);
        setupGameCallbacks();
        if (mainController != null) {
            mainController.face(isPlayerWhite ? Side.WHITE : Side.BLACK);
        }
        applyOrientation();
        updateStockfishState();
        currentGame.startGame();
        refreshAll();
    }

    // ================================================================== play extras (clock, takeback, hint, draw)

    private final List<Runnable> pvcUnbind = new ArrayList<>();

    /** Clock in the player rows and the hint advisor (both observable, changed on the FX thread). */
    private void bindPvcExtras(PvcGame game) {
        unbindPvcExtras();
        GameClock clock = game.getClock();
        if (clock != null) {
            InvalidationListener update = o -> refreshPvcClock(game);
            for (Observable p : List.of(clock.whiteTextProperty(), clock.blackTextProperty(),
                    clock.runningProperty(), clock.whiteLowProperty(), clock.blackLowProperty())) {
                p.addListener(update);
                pvcUnbind.add(() -> p.removeListener(update));
            }
        }
        refreshPvcClock(game);
        HintAdvisor hints = game.hints();
        InvalidationListener hintUpdate = o -> refreshHint(game);
        for (Observable p : List.of(hints.levelProperty(), hints.textProperty(), hints.canAskMoreProperty())) {
            p.addListener(hintUpdate);
            pvcUnbind.add(() -> p.removeListener(hintUpdate));
        }
    }

    private void unbindPvcExtras() {
        pvcUnbind.forEach(Runnable::run);
        pvcUnbind.clear();
        solo.row(true).setClock(null, false, false);
        solo.row(false).setClock(null, false, false);
    }

    private void refreshPvcClock(PvcGame game) {
        GameClock clock = game.getClock();
        if (clock == null || currentGame != game) {
            solo.row(true).setClock(null, false, false);
            solo.row(false).setClock(null, false, false);
            return;
        }
        Side running = clock.runningProperty().get();
        solo.row(true).setClock(clock.whiteTextProperty().get(), running == Side.WHITE, clock.whiteLowProperty().get());
        solo.row(false).setClock(clock.blackTextProperty().get(), running == Side.BLACK, clock.blackLowProperty().get());
    }

    /** A hint asked for: the sentence in the coach line, the piece's square (then the move) on the screen board. */
    private void refreshHint(PvcGame game) {
        if (currentGame != game) {
            return;
        }
        HintAdvisor hints = game.hints();
        HintAdvisor.Level level = hints.levelProperty().get();
        if (level == HintAdvisor.Level.NONE) {
            solo.setCoach(showEvaluation || showBestMoves, null, false, null);
        } else {
            solo.setHint(hints.textProperty().get());
            String from = hints.fromSquareProperty().get();
            if (level == HintAdvisor.Level.PIECE && from != null && from.length() == 2) {
                chessBoard.highlightSquare(from.charAt(0) - 'a', '8' - from.charAt(1), HINT_SQUARE);
            }
            String move = hints.moveProperty().get();
            if (level == HintAdvisor.Level.MOVE && move != null && move.length() >= 4) {
                chessBoard.drawArrowOnBoard(move.charAt(0) - 'a', '8' - move.charAt(1), move.charAt(2) - 'a',
                        '8' - move.charAt(3), HINT_ARROW);
            }
        }
        refreshPvcButtons();
    }

    private static final javafx.scene.paint.Color HINT_SQUARE = javafx.scene.paint.Color.rgb(79, 157, 255, 0.45);
    private static final javafx.scene.paint.Color HINT_ARROW = javafx.scene.paint.Color.rgb(79, 157, 255, 0.85);

    /** Takeback, hint and draw follow the game's own rules (re-read after every move and every message). */
    private void refreshPvcButtons() {
        if (mode != Mode.PVC || !(currentGame instanceof PvcGame pvc)) {
            for (Button b : new Button[] { solo.undoButton, solo.hintButton, solo.drawButton }) {
                b.setDisable(true);
            }
            solo.resignButton.setDisable(mode == Mode.ONLINE || ended);
            return;
        }
        boolean running = pvc.isRunning() && !ended;
        solo.undoButton.setDisable(!running || !pvc.canTakeBack());
        HintAdvisor hints = pvc.hints();
        HintAdvisor.Level level = hints.levelProperty().get();
        solo.hintButton.setText(I18n.t(level == HintAdvisor.Level.PIECE ? "game.hint.more" : "game.hint"));
        solo.hintButton.setDisable(!running || !pvc.isAwaitingHumanMove() || level == HintAdvisor.Level.THINKING
                || !hints.canAskMoreProperty().get());
        solo.drawButton.setDisable(!running || !pvc.canOfferDraw());
        solo.resignButton.setDisable(!running);
    }

    /** "Annulla": the player's last move and the computer's answer; the LEDs show which pieces to put back. */
    void takeBack() {
        if (currentGame instanceof PvcGame pvc && pvc.canTakeBack() && pvc.takeBack()) {
            refreshAll();
        }
    }

    /** First tap: which piece to move. Second tap: the move itself. */
    void requestHint() {
        if (currentGame instanceof PvcGame pvc && pvc.isAwaitingHumanMove()) {
            pvc.requestHint();
            refreshPvcButtons();
        }
    }

    /** "Patta": the computer answers with a ready sentence (accepts, too early, it thinks it is better). */
    void offerBotDraw() {
        if (!(currentGame instanceof PvcGame pvc) || !pvc.canOfferDraw()) {
            return;
        }
        solo.drawButton.setDisable(true);
        pvc.offerDraw().thenAccept(decision -> runFx(() -> {
            if (mainController != null) {
                mainController.showToast(decision.message());
            }
            refreshAll();
        }));
    }

    public void startOnlineGame(String gameId) {
        setupBoard();
        mode = Mode.ONLINE;
        humanWhite = true;
        opponentName = "Lichess";
        title = I18n.t("game.online");
        root.getChildren().setAll(solo);
        solo.boardFrame.setBoard(chessBoard);
        showEvaluation = false;
        showBestMoves = false;
        prepareCoach();
        arduino().getBoardStateManager().setEvaluationEnabled(false);
        MoveCoach.get().setEnabled(false);
        currentGame = new OnlineGame(chessBoard, soloEvalBar, gameId);
        solo.header.setTitle(title);
        solo.row(true).setIcon(null);
        solo.row(false).setIcon("fth-globe");
        solo.setPlayers(I18n.t("common.white"), "Lichess", I18n.t("common.black"), "Lichess");
        opponentElo = null;
        unbindPvcExtras();
        setupGameCallbacks();
        applyOrientation();
        currentGame.startGame();
        refreshAll();
    }

    private static String botName(EngineService.EngineType type, int level) {
        return switch (type) {
            case MAIA_1100 -> "Maia 1100";
            case MAIA_1500 -> "Maia 1500";
            case MAIA_1900 -> "Maia 1900";
            default -> I18n.t("pvc.bot.name", level);
        };
    }

    private void setupBoard() {
        if (currentGame != null) {
            stopAndSaveGame();
        }
        showEvaluation = Prefs.bool("game.evaluation", true);
        showBestMoves = Prefs.bool("game.suggestions", true);
        chessBoard = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 70);
        chessBoard.setFitToParent(true);
        chessBoard.setOverlaysEnabled(false); // the status card / the halves show the result
        chessBoard.setOnPositionChanged(this::onPositionChanged);
        chessBoard.setMoveInput(new ChessBoardUI.MoveInput() {
            @Override
            public Board position() {
                return currentGame.getBoard();
            }

            @Override
            public boolean enabled() {
                return screenMovesAllowed();
            }

            @Override
            public void play(String uci) {
                if (currentGame != null) {
                    currentGame.handleMoveInput(uci);
                }
            }

            @Override
            public void choosePromotion(String from, String to, boolean white, java.util.function.Consumer<String> done) {
                PromotionPicker.show(mainController, white, piece -> done.accept(from + to + piece));
            }
        });
        chessBoard.resetBoard();
        soloEvalBar.updateEvaluation(0);
        duelEvalBar.updateEvaluation(0);
        currentEvaluation = 0.0;
        openingNameLabel.setText("");
        status = GameStatus.none();
        san = List.of();
        uciPlayed = List.of();
        ended = false;
        endMessage = "";
        drawOfferBy = null;
        arduino().getBoardStateManager().setEvaluationEnabled(showBestMoves);
        MoveCoach.get().setEnabled(showBestMoves); // LED verdicts follow the suggestions toggle
    }

    private void prepareCoach() {
        soloEvalBar.setVisible(showEvaluation);
        duelEvalBar.setVisible(showEvaluation);
        solo.evalToggle.setSelected(showEvaluation);
        solo.hintsToggle.setSelected(showBestMoves);
        solo.setCoach(showEvaluation || showBestMoves, null, false, null);
    }

    private void setupGameCallbacks() {
        currentGame.setAnalysisCallback(this::onAnalysis);
        currentGame.setStatusCallback(message -> runFx(() -> onStatus(message)));
        AbstractGame game = currentGame;
        game.setAnalysisParams(analysisDepth, 1);
    }

    /**
     * Moves can be made by tapping the screen when no physical board is connected (otherwise a move on the screen
     * would put the sensors out of step): in a two-player game always, against the computer on the human's turn.
     */
    private boolean screenMovesAllowed() {
        if (currentGame == null || !currentGame.isRunning() || ended || mode == Mode.ONLINE) {
            return false;
        }
        if (arduinoController != null && arduinoController.getBoardStateManager().isHardwareConnected()) {
            return false;
        }
        if (currentGame instanceof PvpGame pvp && pvp.isClockPaused()) {
            return false;
        }
        return currentGame.isAwaitingHumanMove();
    }

    /** Boards on screen match the physical board as seen by whoever the interface faces. */
    private void applyOrientation() {
        boolean black = mainController != null && mainController.isFacingBlack();
        if (mode == Mode.PVP) {
            duel.setNearSide(black ? Side.BLACK : Side.WHITE);
            refreshAll();
        } else {
            solo.setFlipped(black);
        }
    }

    // ================================================================== game events

    /** Engine thread: best line and evaluation for the coach line, LED evaluation. */
    private void onAnalysis(AnalysisUpdate update) {
        String line = update.lines().isEmpty() ? "" : Notation.italianLine(update.fen(), update.lines().get(0).pv(), 6);
        String eval = io.github.hardin22.javachess.Oggetti.AnalysisPanel.formatScore(update.evalText(0));
        double score = update.whitePawns();
        String best = update.bestMove();
        boolean whiteToMove = update.whiteToMove();
        Platform.runLater(() -> {
            if (currentGame == null || !update.fen().equals(currentGame.getBoard().getFen())) {
                return;
            }
            currentEvaluation = score;
            if (arduinoController != null) {
                arduinoController.getBoardStateManager().setBestMove(best);
            }
            if (mode != Mode.PVP) {
                boolean humanToMove = whiteToMove == humanWhite;
                boolean blackAhead = eval.startsWith("−") || eval.startsWith("-");
                String text = showBestMoves && humanToMove ? line : showBestMoves ? I18n.t("game.coach.wait") : "";
                solo.setCoach(showEvaluation || showBestMoves, showEvaluation ? eval : null, blackAhead,
                        showBestMoves ? text : I18n.t("game.coach.eval"));
            }
        });
    }

    private void onStatus(String message) {
        if (currentGame == null) {
            return;
        }
        status = GameStatus.parse(message, currentGame.isRunning());
        if (status.kind() == GameStatus.Kind.END) {
            ended = true;
            endMessage = status.text();
        }
        if (status.kind() == GameStatus.Kind.ERROR && status.to() != null && chessBoard != null) {
            chessBoard.highlightErrorSquare(status.to());
        }
        refreshAll();
    }

    /** Rebuilds the move list from the game's board history when a move is shown on screen. */
    private void onPositionChanged(Move lastMove) {
        if (currentGame == null) {
            return;
        }
        Board board = currentGame.getBoard();
        List<Move> played = new ArrayList<>();
        try {
            for (MoveBackup backup : new ArrayList<>(board.getBackup())) {
                played.add(backup.getMove());
            }
        } catch (RuntimeException e) {
            LOG.debug("Move history busy", e);
            return;
        }
        List<String> uci = played.stream().map(Move::toString).toList();
        if (!uci.equals(uciPlayed)) {
            uciPlayed = uci;
            san = Notation.toSan(currentGame.getInitialFen(), uci, 2000).stream().map(Notation::italian).toList();
            if (drawOfferBy != null) {
                // A move answers a draw offer with "no" (as over the board).
                drawOfferBy = null;
                duel.near.clearPrompt();
                duel.far.clearPrompt();
            }
        }
        if (!currentGame.isRunning() && !ended) {
            ended = board.isMated() || board.isDraw();
        }
        refreshAll();
    }

    private void onOpeningChanged() {
        if (mode != Mode.PVP) {
            String opening = openingNameLabel.getText();
            solo.header.setSubtitle(opening == null || opening.isBlank() ? opponentName : opening);
        }
    }

    // ================================================================== rendering

    private void refreshAll() {
        if (currentGame == null) {
            return;
        }
        String fen = chessBoard.getFen();
        if (mode == Mode.PVP) {
            duel.setPosition(fen);
            refreshDuel();
        } else {
            solo.setPosition(fen);
            String startFen = currentGame.getInitialFen();
            String[] parts = startFen.split(" ");
            int first = 1;
            try {
                first = Integer.parseInt(parts[5]);
            } catch (RuntimeException ignored) {
                // FEN without counters
            }
            solo.setMoves(san, first, parts.length > 1 && "b".equals(parts[1]));
            refreshSolo();
        }
    }

    private Side sideToMove() {
        return currentGame.getBoard().getSideToMove();
    }

    private void refreshSolo() {
        Side human = humanWhite ? Side.WHITE : Side.BLACK;
        boolean humanTurn = sideToMove() == human;
        String lastMove = san.isEmpty() ? null : san.get(san.size() - 1);
        boolean running = currentGame.isRunning() && !ended;

        solo.row(humanWhite).setMeta(I18n.t(humanWhite ? "common.white" : "common.black"));
        String opponentMeta = I18n.t(humanWhite ? "common.black" : "common.white");
        if (running && mode == Mode.PVC && !humanTurn && status.kind() != GameStatus.Kind.REPLICATE
                && status.kind() != GameStatus.Kind.SETUP) {
            opponentMeta = I18n.t("game.thinking");
        }
        if (opponentElo != null && !opponentMeta.equals(I18n.t("game.thinking"))) {
            opponentMeta = opponentMeta + " · " + opponentElo;
        }
        solo.row(!humanWhite).setMeta(opponentMeta);
        refreshPvcButtons();

        StatusCard.Content content;
        if (!running && ended) {
            content = endCard();
        } else if (status.kind() == GameStatus.Kind.READY && status.text().toLowerCase(Locale.ROOT).contains("allineata")) {
            content = StatusCard.Content.of(Tone.DONE, I18n.t("game.status.resync.kicker"),
                    I18n.t("game.status.aligned"), null);
        } else {
            content = switch (status.kind()) {
                case SETUP -> StatusCard.Content.of(Tone.ACTION, I18n.t("game.status.setup.kicker"),
                        I18n.t("game.status.setup"), pretty(status.text()));
                case RESYNC -> StatusCard.Content.of(Tone.ACTION, I18n.t("game.status.resync.kicker"),
                        I18n.t("game.status.resync"), resyncDetail(status.text()));
                case ENGINE -> engineCard();
                case REPLICATE -> new StatusCard.Content(Tone.ACTION,
                        I18n.t("game.status.replicate.kicker", opponentName), pieceAt(status.from()),
                        (status.from() == null ? "" : status.from() + " → ") + status.to(),
                        I18n.t("game.status.replicate.detail"), List.of());
                case ERROR -> StatusCard.Content.of(Tone.ERROR, I18n.t("game.status.error.kicker"),
                        status.to() != null ? I18n.t("game.status.error.square", status.to().toUpperCase(Locale.ROOT))
                                : pretty(status.text()), status.to() != null ? pretty(status.text()) : null);
                case INFO -> StatusCard.Content.of(Tone.PLAIN, "", pretty(status.text()), null);
                default -> turnCard(humanTurn, lastMove);
            };
        }
        solo.status.show(content);
    }

    private StatusCard.Content turnCard(boolean humanTurn, String lastMove) {
        if (humanTurn) {
            boolean boardConnected = arduinoController != null
                    && arduinoController.getBoardStateManager().isHardwareConnected();
            String detail = lastMove == null
                    ? I18n.t(boardConnected ? "game.status.first" : "game.status.first.screen")
                    : I18n.t("game.status.lastmove", opponentName, lastMove);
            return StatusCard.Content.of(Tone.TURN, I18n.t("game.status.turn.kicker"), I18n.t("game.status.turn"),
                    detail);
        }
        return StatusCard.Content.of(Tone.PLAIN, opponentName, I18n.t("game.status.thinking"),
                lastMove == null ? null : I18n.t("game.status.yourmove", lastMove));
    }

    /** "mancano 3, da togliere 1 (in rosso)" from the board's resync message. */
    private static String resyncDetail(String text) {
        int colon = text.indexOf(':');
        String rest = colon >= 0 ? text.substring(colon + 1).trim() : text;
        return rest.isEmpty() ? I18n.t("game.status.resync.detail")
                : Character.toUpperCase(rest.charAt(0)) + rest.substring(1) + ". " + I18n.t("game.status.resync.detail");
    }

    /** The engine does not answer: the game retries by itself; "Riprova" forces it now. */
    private StatusCard.Content engineCard() {
        Button retry = Ui.button(I18n.t("game.engine.retry"), "fth-refresh-cw", "btn-inverse", "btn-md");
        retry.setOnAction(e -> {
            if (currentGame instanceof PvcGame pvc) {
                pvc.retryBotMove();
            }
        });
        String text = status.text();
        int colon = text.indexOf(':');
        String detail = colon >= 0 ? text.substring(colon + 1).trim() : text;
        return new StatusCard.Content(Tone.ERROR, I18n.t("game.engine.kicker"), I18n.t("game.engine.down"), null,
                detail, List.of(retry));
    }

    private StatusCard.Content endCard() {
        Integer outcome = GameStatus.outcomeFor(endMessage, humanWhite);
        if (outcome == null && currentGame.getBoard().isMated()) {
            outcome = (currentGame.getBoard().getSideToMove() == Side.WHITE) == humanWhite ? -1 : 1;
        }
        String headline = outcome == null ? I18n.t("game.end.over") : outcome > 0 ? I18n.t("game.end.won")
                : outcome < 0 ? I18n.t("game.end.lost") : I18n.t("game.end.draw");
        String reason = GameStatus.reason(endMessage);
        if (reason.isEmpty() && currentGame.getBoard().isMated()) {
            reason = "per scacco matto";
        }
        Button review = Ui.button(I18n.t("duel.review"), "fth-bar-chart-2", "btn-inverse", "btn-md");
        review.setOnAction(e -> reviewGame());
        Button again = Ui.button(I18n.t("game.end.again"), "fth-repeat", "btn-outline", "btn-md");
        again.setOnAction(e -> mainController.navigateTo(mode == Mode.PVP ? "PVP_SETUP" : "PVC_SETUP"));
        List<Node> buttons = mode == Mode.ONLINE ? List.of(review) : List.of(review, again);
        String detail = capitalize(reason);
        String move = null;
        if (status.kind() == GameStatus.Kind.REPLICATE) {
            // the bot's last move still has to be made on the physical board (the LEDs show it)
            move = (status.from() == null ? "" : status.from() + " → ") + status.to();
            detail = (detail == null || detail.isEmpty() ? "" : detail + ". ") + I18n.t("game.end.replicate");
        }
        return new StatusCard.Content(outcome != null && outcome > 0 ? Tone.DONE : Tone.PLAIN,
                I18n.t("game.end.kicker"), headline, move, detail, buttons);
    }

    private String pieceAt(String square) {
        if (square == null) {
            return I18n.t("game.status.replicate");
        }
        try {
            var piece = currentGame.getBoard().getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(
                    square.toUpperCase(Locale.ROOT)));
            if (piece == com.github.bhlangonijr.chesslib.Piece.NONE) {
                // the move is already on the logical board: the piece now stands on the destination
                piece = currentGame.getBoard().getPiece(com.github.bhlangonijr.chesslib.Square.valueOf(
                        status.to().toUpperCase(Locale.ROOT)));
            }
            String name = Notation.pieceName(piece.getFenSymbol().charAt(0));
            return I18n.t("game.status.replicate.piece", name);
        } catch (RuntimeException e) {
            return I18n.t("game.status.replicate");
        }
    }

    private static String pretty(String text) {
        return io.github.hardin22.javachess.Oggetti.AnalysisPanel.prettify(text == null ? "" : text);
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void refreshDuel() {
        boolean running = currentGame.isRunning() && !ended;
        int moveNumber = san.size() / 2 + 1;
        for (GameDuelView.Half half : new GameDuelView.Half[] { duel.near, duel.far }) {
            boolean turn = sideToMove() == half.side;
            half.setMoveNumber(san.isEmpty() ? "" : I18n.t("duel.move", moveNumber));
            if (!running) {
                if (ended) {
                    showDuelResult(half);
                }
                continue;
            }
            boolean board = status.kind() == GameStatus.Kind.SETUP || status.kind() == GameStatus.Kind.RESYNC;
            if (board) {
                // an instruction that blocks the game: in each half, not only in the small state line
                half.notice(I18n.t(status.kind() == GameStatus.Kind.RESYNC ? "game.status.resync" : "game.status.setup"),
                        status.kind() == GameStatus.Kind.RESYNC ? resyncDetail(status.text()) : pretty(status.text()));
            } else {
                half.clearNotice();
            }
            switch (status.kind()) {
                case SETUP, RESYNC -> half.setState(I18n.t("game.status.resync.kicker"), false);
                case ERROR -> half.setState(status.to() != null
                        ? I18n.t("game.status.error.square", status.to().toUpperCase(Locale.ROOT))
                        : pretty(status.text()), false);
                default -> {
                    String last = san.isEmpty() ? "" : " · " + I18n.t("duel.last", san.get(san.size() - 1));
                    half.setState(turn ? I18n.t("duel.turn") : I18n.t("duel.wait") + last, turn);
                }
            }
        }
        refreshClocks();
    }

    private void refreshClocks() {
        for (Side side : new Side[] { Side.WHITE, Side.BLACK }) {
            Label source = side == Side.WHITE ? whiteClock : blackClock;
            var clock = duel.half(side).clock;
            clock.setTime(source.getText());
            clock.setActive(source.getStyleClass().contains("stopwatch-active"));
        }
    }

    private void showDuelResult(GameDuelView.Half half) {
        Integer outcome = GameStatus.outcomeFor(endMessage, half.side == Side.WHITE);
        Board board = currentGame.getBoard();
        if (outcome == null && board.isMated()) {
            outcome = board.getSideToMove() == half.side ? -1 : 1;
        }
        if (outcome == null && board.isDraw()) {
            outcome = 0;
        }
        String headline = outcome == null ? I18n.t("game.end.over") : outcome > 0 ? I18n.t("game.end.won")
                : outcome < 0 ? I18n.t("game.end.lost") : I18n.t("game.end.draw");
        String reason = GameStatus.reason(endMessage);
        if (reason.isEmpty() && board.isMated()) {
            reason = "per scacco matto";
        }
        half.showResult(headline, capitalize(reason));
        duel.setPaused(false);
    }

    // ================================================================== two-player actions

    @Override
    public void togglePause() {
        if (!(currentGame instanceof PvpGame pvp) || !pvp.isRunning()) {
            return;
        }
        if (pvp.isClockPaused()) {
            pvp.resumeClock();
            duel.setPaused(false);
        } else {
            pvp.pauseClock();
            duel.setPaused(pvp.isClockPaused());
        }
        refreshClocks();
    }

    @Override
    public void requestResign(Side side) {
        if (currentGame == null || !currentGame.isRunning()) {
            return;
        }
        GameDuelView.Half half = duel.half(side);
        Button no = Ui.wide(I18n.t("common.cancel"), null, "btn-outline");
        no.setOnAction(e -> half.clearPrompt());
        Button yes = Ui.wide(I18n.t("game.resign.confirm.ok"), "fth-flag", "btn-danger-solid");
        yes.setOnAction(e -> resign(side));
        half.prompt(I18n.t("game.resign.confirm"), no, yes);
    }

    private void resign(Side side) {
        String winner = side == Side.WHITE ? "il Nero" : "il Bianco";
        String loser = side == Side.WHITE ? "Il Bianco" : "Il Nero";
        endWith(loser + " abbandona: vince " + winner);
    }

    private void endWith(String message) {
        endMessage = message;
        ended = true;
        currentGame.endGame(message, saveAllowed());
        refreshAll();
    }

    @Override
    public void offerDraw(Side side) {
        if (currentGame == null || !currentGame.isRunning()) {
            return;
        }
        drawOfferBy = side;
        GameDuelView.Half mine = duel.half(side);
        GameDuelView.Half theirs = duel.half(side.flip());
        Button withdraw = Ui.wide(I18n.t("duel.draw.withdraw"), "fth-x", "btn-outline");
        withdraw.setOnAction(e -> withdrawDraw());
        mine.prompt(I18n.t("duel.draw.sent"), withdraw);
        Button decline = Ui.wide(I18n.t("duel.draw.decline"), null, "btn-outline");
        decline.setOnAction(e -> answerDraw(false));
        Button accept = Ui.wide(I18n.t("duel.draw.accept"), "fth-check", "btn-inverse");
        accept.setOnAction(e -> answerDraw(true));
        theirs.prompt(I18n.t("duel.draw.offer", I18n.t(side == Side.WHITE ? "duel.the.white" : "duel.the.black")),
                decline, accept);
    }

    @Override
    public void answerDraw(boolean accepted) {
        drawOfferBy = null;
        duel.near.clearPrompt();
        duel.far.clearPrompt();
        if (accepted && currentGame != null && currentGame.isRunning()) {
            endWith("Patta d'accordo");
        }
    }

    @Override
    public void withdrawDraw() {
        answerDraw(false);
    }

    @Override
    public void showDuelMenu(Side side) {
        boolean far = duel.half(side) == duel.far;
        VBox content = new VBox(12,
                switchRow(I18n.t("game.eval"), I18n.t("game.eval.description"), showEvaluation,
                        this::setShowEvaluation),
                switchRow(I18n.t("game.hints"), I18n.t("game.hints.description"), showBestMoves,
                        this::setShowBestMoves),
                switchRow(I18n.t("duel.board"), I18n.t("duel.board.description"), duel.isBoardVisible(), on -> {
                    duel.setBoardVisible(on);
                    Prefs.set(DUEL_BOARD_KEY, on);
                }));
        Button rotate = Ui.wide(I18n.t("settings.rotate.now"), "fth-rotate-cw", "btn-outline", "btn-lg");
        rotate.setOnAction(e -> {
            mainController.closeSheet();
            mainController.rotateScreen();
        });
        Button leave = Ui.wide(I18n.t("game.leave"), "fth-log-out", "btn-danger", "btn-lg");
        leave.setOnAction(e -> confirmLeave(far));
        content.getChildren().addAll(Ui.gap(8), Ui.equalRow(12, rotate, leave));
        mainController.showSheetFor(far, I18n.t("game.menu"), content);
    }

    @Override
    public void reviewGame() {
        if (currentGame == null && uciPlayed.isEmpty()) {
            return;
        }
        String initialFen = currentGame == null ? START_FEN : currentGame.getInitialFen();
        List<String> moves = uciPlayed;
        String reviewTitle = title;
        mainController.closeSheet();
        ReviewController.openMoves(mainController, String.join(" ", moves), initialFen, reviewTitle);
    }

    @Override
    public void rematch() {
        startPvPSeconds(pvpSeconds, pvpIncrement);
    }

    @Override
    public void leaveToHome() {
        leaveGame();
    }

    // ================================================================== solo actions

    void requestLeave() {
        if (currentGame == null || !currentGame.isRunning()) {
            leaveGame();
            return;
        }
        confirmLeave(false);
    }

    private void confirmLeave(boolean far) {
        Label detail = Ui.wrap(I18n.t("game.leave.detail"), "t-body", "t-muted");
        Button stay = Ui.wide(I18n.t("game.leave.stay"), null, "btn-outline", "btn-lg");
        stay.setOnAction(e -> mainController.closeSheet());
        Button leave = Ui.wide(I18n.t("game.leave"), "fth-log-out", "btn-danger-solid", "btn-lg");
        leave.setOnAction(e -> {
            mainController.closeSheet();
            leaveGame();
        });
        VBox content = new VBox(24, detail, Ui.equalRow(14, stay, leave));
        mainController.showSheetFor(far, I18n.t("game.leave.title"), content);
    }

    void requestResign() {
        if (currentGame == null || !currentGame.isRunning()) {
            return;
        }
        Label detail = Ui.wrap(I18n.t("game.resign.detail"), "t-body", "t-muted");
        Button no = Ui.wide(I18n.t("common.cancel"), null, "btn-outline", "btn-lg");
        no.setOnAction(e -> mainController.closeSheet());
        Button yes = Ui.wide(I18n.t("game.resign.confirm.ok"), "fth-flag", "btn-danger-solid", "btn-lg");
        yes.setOnAction(e -> {
            mainController.closeSheet();
            resign(humanWhite ? Side.WHITE : Side.BLACK);
        });
        mainController.showSheet(I18n.t("game.resign.confirm"), new VBox(24, detail, Ui.equalRow(14, no, yes)));
    }

    /** "⋯" of the solo layout: toggles, analysis depth, engine, rotation, leave. */
    void showMenu(boolean far) {
        Stepper depth = new Stepper(8, 30, 1, analysisDepth);
        depth.format(String::valueOf, I18n.t("analysis.depth.unit"));
        depth.valueProperty().addListener((obs, o, n) -> {
            analysisDepth = n.intValue();
            if (currentGame != null) {
                currentGame.setAnalysisParams(analysisDepth, 1);
            }
        });
        Button engines = Ui.wide(I18n.t("game.engine.change"), "fth-cpu", "btn-outline");
        engines.setOnAction(e -> showEngines());
        Button leave = Ui.wide(I18n.t("game.leave"), "fth-log-out", "btn-danger");
        leave.setOnAction(e -> confirmLeave(far));
        VBox content = new VBox(12,
                switchRow(I18n.t("game.eval"), I18n.t("game.eval.description"), showEvaluation,
                        this::setShowEvaluation),
                switchRow(I18n.t("game.hints"), I18n.t("game.hints.description"), showBestMoves,
                        this::setShowBestMoves),
                Ui.label(I18n.t("analysis.depth"), "row-title"), depth,
                Ui.gap(4), Ui.equalRow(12, engines, leave));
        mainController.showSheet(I18n.t("game.menu"), content);
    }

    private HBox switchRow(String title, String description, boolean value, java.util.function.Consumer<Boolean> onChange) {
        ToggleButton toggle = Ui.toggleSwitch(value);
        toggle.setOnAction(e -> onChange.accept(toggle.isSelected()));
        HBox row = new HBox(16, Ui.texts(title, description, "row-title", "row-sub"), toggle);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.setMinHeight(96);
        return row;
    }

    void showEngines() {
        EnginePicker picker = new EnginePicker();
        picker.setOnPicked(mainController::closeSheet);
        mainController.showSheet(I18n.t("engine.title"), picker);
    }

    /** The "Motore" toolbar button shows the active engine, or that it is starting. */
    private void showEngineState() {
        EngineSelection engines = EngineSelection.get();
        EngineStatus engineStatus = engines.statusProperty().get();
        EngineProfile active = engines.activeProfileProperty().get();
        if (engineStatus != null && engineStatus.state() == EngineStatus.State.LOADING) {
            solo.engineButton.setText(I18n.t("engine.loading"));
        } else {
            solo.engineButton.setText(active == null ? I18n.t("game.engine") : shortName(active.displayName()));
        }
    }

    private static String shortName(String name) {
        return name.length() > 14 ? name.substring(0, 13) + "…" : name;
    }

    void setShowEvaluation(boolean show) {
        showEvaluation = show;
        solo.evalToggle.setSelected(show);
        soloEvalBar.setVisible(show);
        duelEvalBar.setVisible(show);
        updateStockfishState();
    }

    void setShowBestMoves(boolean show) {
        showBestMoves = show;
        solo.hintsToggle.setSelected(show);
        updateStockfishState();
        if (!show && currentGame != null) {
            currentGame.clearArrows();
        }
    }

    private void updateStockfishState() {
        if (currentGame == null) {
            return;
        }
        boolean enabled = (showEvaluation || showBestMoves) && mode != Mode.ONLINE;
        currentGame.setAnalysisEnabled(enabled);
        currentGame.setShowArrows(showBestMoves && mode != Mode.ONLINE);
        arduino().getBoardStateManager().setEvaluationEnabled(showBestMoves && mode != Mode.ONLINE);
        MoveCoach.get().setEnabled(showBestMoves && mode != Mode.ONLINE); // LED verdicts follow the suggestions
        solo.setCoach(enabled, null, false, null);
    }

    // ================================================================== leave / save

    private void leaveGame() {
        stopAndSaveGame();
        mainController.navigateTo("HOME");
    }

    @Override
    public void onNavigatedFrom() {
        stopAndSaveGame();
    }

    private boolean saveAllowed() {
        // demo and screenshot runs never write into the user's archive
        return System.getProperty("javachess.demo") == null && System.getProperty("javachess.snapshot") == null;
    }

    private void stopAndSaveGame() {
        if (currentGame != null) {
            boolean save = false;
            if (currentGame instanceof PvpGame pvp) {
                save = pvp.isSaveGame();
            } else if (currentGame instanceof PvcGame pvc) {
                save = pvc.isSaveGame();
            }
            if (!saveAllowed()) {
                save = false;
            }
            if (currentGame instanceof PvpGame pvp && pvp.isClockPaused()) {
                pvp.resumeClock();
            }
            currentGame.endGame("Partita interrotta", save);
            currentGame = null;
        }
        duel.setPaused(false);
        if (arduinoController != null) {
            arduinoController.getBoardStateManager().stopGameMode();
        }
        PositionAnalyzer.get().stop();
    }

    // ================================================================== queries

    /** True while a game is open and not finished (shown as "Partita in corso" on the home screen). */
    public boolean isGameInProgress() {
        return currentGame != null && currentGame.isRunning();
    }

    public String getTitle() {
        return title;
    }

    /** "Mossa 14 · Bianco" for the home card. */
    public String getSubtitle() {
        if (currentGame == null) {
            return "";
        }
        return I18n.t("duel.move", san.size() / 2 + 1);
    }

    public String getCurrentFen() {
        return chessBoard == null ? null : chessBoard.getFen();
    }

    // ================================================================== dev helpers (DevOptions)

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

    /** Resignation of {@code side} without confirmation (DevOptions demos). */
    public void devResign(Side side) {
        if (currentGame != null && currentGame.isRunning()) {
            resign(side);
        }
    }

    /**
     * Against the computer: plays the human's moves (the scripted one when legal, otherwise a legal move), each
     * after the engine has answered. DevOptions demos.
     */
    public void devPlayHumanMoves(List<String> uciMoves, boolean white, long delayMillis) {
        AbstractGame game = currentGame;
        if (game == null) {
            return;
        }
        Side human = white ? Side.WHITE : Side.BLACK;
        java.util.Random rnd = new java.util.Random(3);
        Thread.ofVirtual().name("dev-moves").start(() -> {
            int played = 0;
            int limit = Integer.getInteger("javachess.demo.humanMoves", 6);
            long deadline = System.currentTimeMillis() + 60_000;
            while (played < limit && System.currentTimeMillis() < deadline && game == currentGame && game.isRunning()) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                java.util.concurrent.CompletableFuture<String> pick = new java.util.concurrent.CompletableFuture<>();
                int index = played;
                Platform.runLater(() -> {
                    Board b = game.getBoard();
                    if (b.getSideToMove() != human) {
                        pick.complete(null);
                        return;
                    }
                    List<Move> legal = b.legalMoves();
                    String wanted = null;
                    for (int i = index * 2 + (white ? 0 : 1); i < uciMoves.size() && wanted == null; i += 2) {
                        String candidate = uciMoves.get(i);
                        if (legal.stream().anyMatch(m -> m.toString().equals(candidate))) {
                            wanted = candidate;
                        }
                        break;
                    }
                    pick.complete(wanted != null ? wanted : legal.get(rnd.nextInt(legal.size())).toString());
                });
                try {
                    String move = pick.get();
                    if (move != null) {
                        Platform.runLater(() -> game.handleMoveInput(move));
                        played++;
                    }
                } catch (Exception e) {
                    return;
                }
            }
        });
    }

    /** Shows a status message as the game classes would (DevOptions demos of the board instructions). */
    public void devStatus(String message) {
        onStatus(message);
    }
}
