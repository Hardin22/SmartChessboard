package io.github.hardin22.javachess.Application;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.util.Duration;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Controllers.MainController;
import io.github.hardin22.javachess.Controllers.PuzzleController;
import io.github.hardin22.javachess.Controllers.ReviewController;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Play.BotLevels;
import io.github.hardin22.javachess.Play.GameSnapshot;
import io.github.hardin22.javachess.Play.GameSnapshotStore;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.EngineService;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Services.PuzzleService;
import io.github.hardin22.javachess.Utils.AppPaths;
import io.github.hardin22.javachess.Utils.PgnCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Developer demos used by {@link DevOptions} ({@code -Djavachess.demo=NAME}) to reach every screen in a realistic
 * state for screenshots. Demo runs never write the user's settings or archive (except {@code demo.seed}, which only
 * works in a data folder given with {@code -Djavachess.home}).
 */
final class DevDemos {

    private static final Logger LOG = LoggerFactory.getLogger(DevDemos.class);
    private static final String DEFAULT_MOVES = "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6";

    private DevDemos() {
    }

    static void run(MainController main, String demo) {
        try {
            seedArchiveIfAsked();
            switch (demo) {
                case "game", "pvp" -> pvp(main, null);
                case "pvp-draw" -> pvp(main, game -> game.offerDraw(com.github.bhlangonijr.chesslib.Side.WHITE));
                case "pvp-resign" -> pvp(main, game -> game.requestResign(com.github.bhlangonijr.chesslib.Side.BLACK));
                case "pvp-pause" -> pvp(main, ActiveGameController::togglePause);
                case "pvp-end" -> pvp(main, game -> game.devResign(com.github.bhlangonijr.chesslib.Side.BLACK));
                case "pvp-menu" -> pvp(main, game -> game.showDuelMenu(com.github.bhlangonijr.chesslib.Side.BLACK));
                case "pvc" -> pvc(main, true, null);
                case "pvc-black" -> pvc(main, false, null);
                case "pvc-replicate" -> pvc(main, true, game ->
                        game.devStatus("Muovi l'avversario: solleva da F8 posiziona su C5"));
                case "pvc-setup" -> pvc(main, true, game -> game.devStatus("Posiziona i pezzi: mancano 6"));
                case "pvc-error" -> pvc(main, true, game -> game.devStatus("ERRORE: Controlla E4"));
                case "pvc-status" -> pvc(main, true, game -> game.devStatus(System.getProperty("javachess.demo.status", "")));
                case "pvp-status" -> pvp(main, game -> game.devStatus(System.getProperty("javachess.demo.status", "")));
                case "pvc-menu" -> pvc(main, true, game -> lookupFire(main, "game-menu"));
                case "pvc-hint" -> pvc(main, true, game -> fireWhenEnabled(main, "game-hint", 60, () -> {
                    if (Boolean.getBoolean("javachess.demo.hintMove")) {
                        later(0.5, () -> fireWhenEnabled(main, "game-hint", 150, null));
                    }
                }));
                case "pvc-end" -> pvc(main, true, game -> game.devResign(com.github.bhlangonijr.chesslib.Side.BLACK));
                case "pvc-draw" -> pvc(main, true, game -> fireWhenEnabled(main, "game-draw", 60, null));
                case "pvc-undo" -> pvc(main, true, game -> fireWhenEnabled(main, "game-undo", 60, null));
                case "home-resume" -> {
                    GameSnapshotStore.get().save(demoSnapshot());
                    later(0.8, () -> main.navigateTo("HOME"));
                }
                case "pvc-select" -> pvc(main, true, game -> tapSquare(main, System.getProperty("javachess.demo.square", "f1")));
                case "review" -> review(main);
                case "puzzle" -> puzzle(main);
                case "puzzle-rush" -> {
                    // A series on the fixed demo puzzle (no puzzle database needed)
                    Puzzle puzzle = demoPuzzle();
                    PuzzleController controller = (PuzzleController) main.getController("PUZZLE_GAME");
                    main.navigateTo("PUZZLE_GAME");
                    controller.startRush(new io.github.hardin22.javachess.Play.PuzzleRush(
                            io.github.hardin22.javachess.Play.PuzzleRush.Mode.valueOf(
                                    System.getProperty("javachess.demo.rush", "THREE_MINUTES")), 1500,
                            target -> java.util.concurrent.CompletableFuture.completedFuture(puzzle),
                            () -> new io.github.hardin22.javachess.Play.GameClock(TimeControl.minutes(3, 0)),
                            Platform::runLater));
                    if (Boolean.getBoolean("javachess.demo.rushEnd")) {
                        later(2, () -> lookupFire(main, "rush-stop"));
                    }
                }
                case "browser-cycle" -> browserCycle(main);
                case "online" -> {
                    main.navigateTo("HOME");
                    later(1, () -> lookupFire(main, "home-online"));
                }
                case "archive-preview" -> {
                    main.navigateTo("ARCHIVE");
                    later(1.2, () -> lookupFire(main, "archive-row"));
                }
                case "position-editor", "position-odds" -> {
                    main.navigateTo("PVC_SETUP");
                    later(1, () -> lookupFire(main, "setup-position"));
                    if (demo.equals("position-editor")) {
                        later(1.6, () -> lookupFire(main, "setup-position-editor"));
                    }
                }
                case "archive-transfer" -> {
                    main.navigateTo("ARCHIVE");
                    later(1.2, () -> lookupFire(main, "archive-transfer"));
                }
                case "archive-import" -> {
                    main.navigateTo("ARCHIVE");
                    later(1.2, () -> ((io.github.hardin22.javachess.Controllers.ArchiveController)
                            main.getController("ARCHIVE")).devOpenImport());
                }
                case "archive-search" -> {
                    main.navigateTo("ARCHIVE");
                    later(1.2, () -> lookupFire(main, "archive-search"));
                }
                case "opening" -> {
                    var opening = io.github.hardin22.javachess.Training.OpeningCatalog.byId(
                            System.getProperty("javachess.demo.opening", "italian")).orElse(
                            io.github.hardin22.javachess.Training.OpeningCatalog.all().get(0));
                    main.navigateTo("OPENINGS");
                    later(0.5, () -> io.github.hardin22.javachess.Controllers.OpeningTrainerController.open(main,
                            opening));
                    String moves = System.getProperty("javachess.demo.moves.training", "");
                    if (!moves.isBlank()) {
                        later(1.5, () -> lookupPlay(main, "OPENING_TRAINER", moves));
                    }
                    if (Boolean.getBoolean("javachess.demo.hint")) {
                        later(2.2, () -> lookupFire(main, "opening-hint"));
                    }
                }
                case "analysis" -> {
                    main.navigateTo("TRAINING");
                    later(0.5, () -> io.github.hardin22.javachess.Controllers.ReviewController.openPosition(main,
                            System.getProperty("javachess.demo.fen",
                                    "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3")));
                }
                case "puzzle-daily" -> {
                    main.navigateTo("PUZZLE_DASHBOARD");
                    later(2.0, () -> lookupFire(main, "puzzle-daily"));
                }
                case "drill" -> {
                    var drill = io.github.hardin22.javachess.Training.EndgameDrills.byId(
                            System.getProperty("javachess.demo.drill", "")).orElse(
                            io.github.hardin22.javachess.Training.EndgameDrills.all().get(0));
                    main.navigateTo("ENDGAMES");
                    later(0.5, () -> io.github.hardin22.javachess.Controllers.DrillController.open(main, drill));
                }
                case "coordinates-find", "coordinates-name" -> {
                    main.navigateTo("COORDINATES");
                    later(0.6, () -> lookupFire(main, demo.equals("coordinates-find") ? "coordinates-find"
                            : "coordinates-name"));
                    if (Boolean.getBoolean("javachess.demo.go")) {
                        later(1.2, () -> fireText(main, "Via!"));
                    }
                }
                case "browser" -> {
                    // the start-up view of the integrated browser in a given state, without starting Chromium
                    main.navigateTo("BROWSER");
                    var state = io.github.hardin22.javachess.Browser.BrowserStatus.State.valueOf(
                            System.getProperty("javachess.demo.state", "STARTING"));
                    double progress = Double.parseDouble(System.getProperty("javachess.demo.progress", "-2"));
                    List<io.github.hardin22.javachess.Browser.BrowserStatus.Action> actions = new ArrayList<>();
                    for (String a : System.getProperty("javachess.demo.actions", "").split(",")) {
                        if (!a.isBlank()) {
                            actions.add(io.github.hardin22.javachess.Browser.BrowserStatus.Action.valueOf(a.trim()));
                        }
                    }
                    Object[] args = System.getProperty("javachess.demo.args", "").isEmpty() ? new Object[0]
                            : System.getProperty("javachess.demo.args").split("\\|");
                    var status = io.github.hardin22.javachess.Browser.BrowserStatus.of(state, progress, actions, args);
                    later(0.5, () -> ((io.github.hardin22.javachess.Controllers.BrowserController)
                            main.getController("BROWSER")).devShow(status));
                }
                case "settings-online", "settings-login" -> {
                    main.navigateTo("SETTINGS");
                    io.github.hardin22.javachess.Controllers.SettingsController settings =
                            (io.github.hardin22.javachess.Controllers.SettingsController) main.getController("SETTINGS");
                    later(0.6, () -> {
                        javafx.scene.Node group = main.getMainContainer().getScene().lookup("#online-login-chess_com");
                        if (group != null) {
                            scrollIntoView(group);
                        }
                        if (demo.equals("settings-login")) {
                            settings.openLoginForm(System.getProperty("javachess.demo.site", "chess_com"));
                        }
                    });
                }
                case "settings-advanced" -> {
                    main.navigateTo("SETTINGS");
                    ((io.github.hardin22.javachess.Controllers.SettingsController) main.getController("SETTINGS"))
                            .openAdvanced();
                }
                default -> main.navigateTo(demo.toUpperCase());
            }
        } catch (RuntimeException e) {
            LOG.error("Demo {} failed", demo, e);
        }
    }

    private static void later(double seconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(e -> action.run());
        pause.play();
    }

    /** Plays UCI moves (space separated) through a training screen's play method, one every 0.4 s. */
    private static void lookupPlay(MainController main, String view, String moves) {
        Object controller = main.getController(view);
        String[] list = moves.trim().split("[\\s_]+");
        for (int i = 0; i < list.length; i++) {
            String uci = list[i];
            later(0.4 * i, () -> {
                try {
                    java.lang.reflect.Field f = controller.getClass().getDeclaredField("trainer");
                    f.setAccessible(true);
                    Object t = f.get(controller);
                    t.getClass().getMethod("play", String.class).invoke(t, uci);
                } catch (ReflectiveOperationException e) {
                    LOG.warn("demo move {} failed: {}", uci, e.toString());
                }
            });
        }
    }

    /** Fires the first visible button with this text. */
    private static void fireText(MainController main, String text) {
        List<Button> found = new ArrayList<>();
        collectButtons(main.getMainContainer().getScene().getRoot(), text, found);
        if (!found.isEmpty()) {
            found.get(found.size() - 1).fire();
        } else {
            LOG.warn("No button '{}' for the demo", text);
        }
    }

    private static void collectButtons(Node node, String text, List<Button> out) {
        if (node instanceof Button b && text.equals(b.getText()) && b.isVisible()) {
            out.add(b);
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collectButtons(child, text, out);
            }
        }
    }

    /** Scrolls the enclosing scroll pane so that the node is near the top (screenshots of long pages). */
    private static void scrollIntoView(Node node) {
        Node p = node.getParent();
        while (p != null && !(p instanceof javafx.scene.control.ScrollPane)) {
            p = p.getParent();
        }
        if (p instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            double contentHeight = scroll.getContent().getBoundsInLocal().getHeight();
            double viewport = scroll.getViewportBounds().getHeight();
            javafx.geometry.Bounds b = scroll.getContent().sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double target = Math.max(0, b.getMinY() - 160);
            scroll.setVvalue(contentHeight <= viewport ? 0 : Math.min(1, target / (contentHeight - viewport)));
        }
    }

    /** Fires the node with this id (buttons) or simulates a tap on it. */
    private static void lookupFire(MainController main, String id) {
        Node node = main.getMainContainer().getScene().lookup("#" + id);
        if (node instanceof Button b) {
            b.fire();
        } else if (node != null) {
            node.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true,
                    false, false, null));
        } else {
            LOG.warn("No node #{} for the demo", id);
        }
    }

    /** Taps a button as soon as it is enabled (the computer may still be thinking), then runs {@code then}. */
    private static void fireWhenEnabled(MainController main, String id, int tries, Runnable then) {
        Node node = main.getMainContainer().getScene().lookup("#" + id);
        if (node instanceof Button b && !b.isDisabled()) {
            LOG.info("demo: tapping #{}", id);
            b.fire();
            if (then != null) {
                then.run();
            }
        } else if (tries > 0) {
            later(0.3, () -> fireWhenEnabled(main, id, tries - 1, then));
        } else {
            LOG.warn("Button #{} never enabled for the demo", id);
        }
    }

    /** Simulates a finger on a square of the board shown on the screen (scene coordinates, flip-aware). */
    private static void tapSquare(MainController main, String square) {
        if (!(main.getMainContainer().lookup(".chess-board")
                instanceof io.github.hardin22.javachess.Oggetti.ChessBoardUI board)) {
            LOG.warn("No board on screen for the demo");
            return;
        }
        int file = square.charAt(0) - 'a';
        int rank = square.charAt(1) - '1';
        double tile = board.getTileSize();
        int col = board.isFlipped() ? 7 - file : file;
        int row = board.isFlipped() ? rank : 7 - rank;
        javafx.geometry.Point2D p = board.localToScene((col + 0.5) * tile, (row + 0.5) * tile);
        board.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, p.getX(),
                p.getY(), 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false,
                false, true, false, false, null));
    }

    private static List<String> demoMoves() {
        return Arrays.asList(System.getProperty("javachess.demo.moves", DEFAULT_MOVES).trim().split("\\s+"));
    }

    private static void pvp(MainController main, java.util.function.Consumer<ActiveGameController> then) {
        ActiveGameController game = (ActiveGameController) main.getController("GAME");
        main.navigateTo("GAME");
        game.startPvP(Integer.getInteger("javachess.demo.minutes", 10), 5);
        List<String> moves = demoMoves();
        game.devPlayMoves(moves, 120);
        if (then != null) {
            later(0.2 + moves.size() * 0.13, () -> then.accept(game));
        }
    }

    private static void pvc(MainController main, boolean white, java.util.function.Consumer<ActiveGameController> then) {
        ActiveGameController game = (ActiveGameController) main.getController("GAME");
        main.navigateTo("GAME");
        if (System.getProperty("javachess.demo.fen") != null) {
            game.setNextStartPosition(System.getProperty("javachess.demo.fen").replace('_', ' '));
        }
        if (Integer.getInteger("javachess.demo.level") != null) {
            game.startPvC(Integer.getInteger("javachess.demo.level"), white, EngineService.EngineType.STOCKFISH);
        } else {
            game.startPvC(BotLevels.byId(System.getProperty("javachess.demo.bot", BotLevels.DEFAULT_ID)).orElseThrow(),
                    white, TimeControl.parseStorage(System.getProperty("javachess.demo.time", "")).orElse(TimeControl.UNLIMITED));
        }
        List<String> moves = demoMoves();
        // Against the computer only the human moves are played; the engine answers.
        if (Boolean.parseBoolean(System.getProperty("javachess.demo.autoplay", "true"))) {
            game.devPlayHumanMoves(moves, white, 900);
        }
        if (then != null) {
            later(Double.parseDouble(System.getProperty("javachess.demo.thenAfter", "2.5")), () -> then.accept(game));
        }
    }

    /** An interrupted game for the Home card: the first moves of the demo game against "Circolo", 10 + 5. */
    private static GameSnapshot demoSnapshot() {
        List<String> all = demoMoves();
        List<String> moves = all.subList(0, Math.min(23, all.size()));
        return new GameSnapshot(GameSnapshot.Mode.PVC, null, moves, true, BotLevels.DEFAULT_ID, null, 0,
                TimeControl.minutes(10, 5), 312_000, 401_000, 1, 2, LocalDateTime.now().minusHours(3),
                LocalDateTime.now().minusHours(2));
    }

    private static void review(MainController main) {
        var archive = GameArchiveService.getInstance();
        int wanted = Integer.getInteger("javachess.demo.game", -1);
        var chosen = wanted >= 0 ? archive.get(wanted)
                : archive.list().stream()
                        .filter(g -> !"*".equals(g.result()) && g.movesUci().size() >= 40)
                        .findFirst();
        if (chosen.isEmpty()) {
            LOG.warn("No archived game for the review demo");
            main.navigateTo("REVIEW");
            return;
        }
        ReviewController.open(main, chosen.get());
        ReviewController review = (ReviewController) main.getController("REVIEW");
        review.goTo(Integer.getInteger("javachess.demo.ply", 20));
        if (Boolean.getBoolean("javachess.demo.analyze")) {
            later(1.5, () -> {
                if (!review.hasReview()) { // a saved review opens already analysed
                    review.analyze();
                }
            });
        }
        if (Boolean.getBoolean("javachess.demo.summary")) {
            later(2, review::devShowSummary);
        }
        if (Boolean.getBoolean("javachess.demo.trainer")) {
            openTrainerWhenReady(review, 240);
        }
        double variationAfter = Double.parseDouble(System.getProperty("javachess.demo.variationAfter", "0"));
        if (variationAfter > 0) {
            later(variationAfter, review::devShowBest);
        }
    }

    private static void openTrainerWhenReady(ReviewController review, int tries) {
        later(1, () -> {
            if (review.devOpenTrainer()) {
                String attempt = System.getProperty("javachess.demo.trainerTry");
                if (attempt != null) {
                    later(1, () -> ((io.github.hardin22.javachess.Controllers.TrainerController)
                            review.mainControllerForDemo().getController("TRAINER")).devAttempt(attempt));
                }
            } else if (tries > 0) {
                openTrainerWhenReady(review, tries - 1);
            }
        });
    }

    private static Puzzle demoPuzzle() {
        return new Puzzle("demo", "6k1/r4ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1", List.of("a7a6", "d1d8"),
                1520, 80, 90, 100, List.of("mateIn1", "backRankMate", "short"), "", "");
    }

    private static void puzzle(MainController main) {
        if (!Boolean.getBoolean("javachess.demo.puzzledb")) {
            // A fixed back-rank puzzle when there is no puzzle database (or one is asked for).
            Puzzle puzzle = demoPuzzle();
            PuzzleController controller = (PuzzleController) main.getController("PUZZLE_GAME");
            main.navigateTo("PUZZLE_GAME");
            controller.setPuzzle(puzzle, 1500, List.of("Tutti"));
            return;
        }
        PuzzleService.getInstance().findPuzzleAsync(1500, 200, List.of("Tutti")).thenAccept(puzzle ->
                Platform.runLater(() -> {
                    PuzzleController controller = (PuzzleController) main.getController("PUZZLE_GAME");
                    main.navigateTo("PUZZLE_GAME");
                    if (puzzle != null) {
                        controller.setPuzzle(puzzle, 1500, List.of("Tutti"));
                    }
                }));
    }

    // ================================================================== demo archive

    private static final String[][] LICHESS_NAMES = { { "anna_rossi", "1640" }, { "TorreNera", "1820" },
            { "marco_b", "1490" }, { "Gambetto87", "1710" }, { "lucia.scacchi", "1580" } };

    /**
     * {@code -Djavachess.demo.seed=N}: fills an empty archive with N games made from public-domain game scores, with
     * every mode, result and a date spread over the last months. Only in a data folder chosen with
     * {@code -Djavachess.home} (never in the user's real archive).
     */
    private static void seedArchiveIfAsked() {
        int count = Integer.getInteger("javachess.demo.seed", 0);
        if (count <= 0) {
            return;
        }
        if (System.getProperty("javachess.home") == null && System.getenv("JAVACHESS_HOME") == null) {
            LOG.warn("javachess.demo.seed ignored: pass -Djavachess.home=<empty folder> (data folder {})",
                    AppPaths.dataDir());
            return;
        }
        GameArchiveService archive = GameArchiveService.getInstance();
        if (archive.size() > 0) {
            return;
        }
        List<String[]> scores = readScores();
        if (scores.isEmpty()) {
            return;
        }
        Random rnd = new Random(42);
        LocalDateTime when = LocalDateTime.now().withSecond(0).withNano(0).minusMinutes(20);
        for (int i = 0; i < count; i++) {
            String[] score = scores.get(i % scores.size());
            List<String> uci = toUci(score[4]);
            if (uci.isEmpty()) {
                continue;
            }
            String result = score[3];
            int kind = i % 10;
            boolean unfinished = kind == 7;
            if (unfinished) {
                uci = uci.subList(0, Math.max(6, uci.size() * 2 / 3));
                result = "*";
            }
            ArchivedGame.GameMode mode;
            String label;
            String white;
            String black;
            String termination = "*".equals(result) ? "Interrotta" : terminationOf(uci, result, rnd);
            String time = "";
            if (kind <= 3) {
                mode = ArchivedGame.GameMode.PVC;
                int level = new int[] { 5, 8, 10, 12, 15, 20 }[rnd.nextInt(6)];
                String bot = kind == 3 ? "Maia " + new int[] { 1100, 1500, 1900 }[rnd.nextInt(3)]
                        : "Stockfish livello " + level;
                label = "Player vs " + bot;
                boolean humanWhite = rnd.nextBoolean();
                white = humanWhite ? "Giocatore" : bot;
                black = humanWhite ? bot : "Giocatore";
            } else if (kind <= 6) {
                mode = ArchivedGame.GameMode.PVP;
                label = "Player vs Player";
                white = "Bianco";
                black = "Nero";
                time = new String[] { "10+5", "5+3", "15+10", "3+2" }[rnd.nextInt(4)];
            } else if (kind == 8) {
                mode = ArchivedGame.GameMode.LICHESS;
                String[] a = LICHESS_NAMES[rnd.nextInt(LICHESS_NAMES.length)];
                String[] b = LICHESS_NAMES[(rnd.nextInt(LICHESS_NAMES.length - 1) + 1) % LICHESS_NAMES.length];
                label = "";
                white = a[0];
                black = b[0].equals(a[0]) ? "scacco_matto99" : b[0];
                time = "10+0";
            } else {
                mode = ArchivedGame.GameMode.BROWSER;
                label = "Online";
                white = "?";
                black = "?";
            }
            Board board = new Board();
            for (String m : uci) {
                board.doMove(new Move(m, board.getSideToMove()));
            }
            archive.add(new ArchivedGame(0, mode, label, white, black, result, termination, "", time, when,
                    PgnCodec.START_FEN, board.getFen(), uci));
            when = when.minusMinutes(25 + rnd.nextInt(50));
            if (rnd.nextInt(3) == 0) {
                when = when.minusDays(1 + rnd.nextInt(3)).withHour(15 + rnd.nextInt(6));
            }
        }
        LOG.info("Demo archive: {} games", archive.size());
    }

    private static String terminationOf(List<String> uci, String result, Random rnd) {
        Board board = new Board();
        for (String m : uci) {
            board.doMove(new Move(m, board.getSideToMove()));
        }
        if (board.isMated()) {
            return "Scaccomatto";
        }
        if ("1/2-1/2".equals(result)) {
            return "Patta";
        }
        return rnd.nextInt(4) == 0 ? "Tempo" : "Abbandono";
    }

    private static List<String[]> readScores() {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                DevDemos.class.getResourceAsStream("/demo/famous-games.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    String[] parts = line.split("\t");
                    if (parts.length == 5) {
                        out.add(parts);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Demo games not readable: {}", e.toString());
        }
        return out;
    }

    private static List<String> toUci(String san) {
        try {
            MoveList list = new MoveList();
            list.loadFromSan(san.replaceAll("\\d+\\.", " ").replaceAll("\\s+", " ").trim());
            return list.stream().map(Move::toString).toList();
        } catch (Exception e) {
            LOG.warn("Demo game not parsed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * {@code -Djavachess.demo=browser-cycle}: opens the integrated browser, goes back Home and opens it again, like a
     * user switching between the sites ({@code javachess.demo.urls}, comma-separated, used in turn;
     * {@code javachess.demo.rounds}, default 5). Logs "Browser cycle round N" and "Browser cycle done", then quits
     * with {@code javachess.snapshot.exit=true}. Used to check the full-screen window on macOS (the app crashed on
     * the second opening on 8 October 2026).
     */
    private static void browserCycle(MainController main) {
        String[] urls = System.getProperty("javachess.demo.urls", "https://www.chess.com/login,https://lichess.org")
                .split(",");
        int rounds = Integer.getInteger("javachess.demo.rounds", 5);
        double openSeconds = Double.parseDouble(System.getProperty("javachess.demo.openSeconds", "8"));
        double homeSeconds = Double.parseDouble(System.getProperty("javachess.demo.homeSeconds", "4"));
        var browser = (io.github.hardin22.javachess.Controllers.BrowserController) main.getController("BROWSER");
        Runnable[] round = new Runnable[1];
        int[] n = {0};
        round[0] = () -> {
            if (n[0] >= rounds) {
                LOG.info("Browser cycle done: {} rounds", rounds);
                if (Boolean.getBoolean("javachess.snapshot.exit")) {
                    later(1, Platform::exit);
                }
                return;
            }
            String url = urls[n[0] % urls.length].trim();
            n[0]++;
            LOG.info("Browser cycle round {}: {}", n[0], url);
            main.openBrowser(url);
            later(openSeconds, () -> {
                LOG.info("Browser cycle round {}: window showing {}, back Home", n[0], browser.devWindowShowing());
                browser.devPerform(io.github.hardin22.javachess.Browser.BrowserStatus.Action.BACK_HOME);
                later(homeSeconds, round[0]);
            });
        };
        later(2, round[0]);
    }
}
