package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Board;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Controllers.ArchiveController;
import io.github.hardin22.javachess.Controllers.MainController;
import io.github.hardin22.javachess.Controllers.PuzzleController;
import io.github.hardin22.javachess.Controllers.ReviewController;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Engine.EngineSelection;
import io.github.hardin22.javachess.Oggetti.AbstractGame;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Oggetti.PuzzleGame;
import io.github.hardin22.javachess.Services.EngineService;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Services.PuzzleProgressService;
import io.github.hardin22.javachess.Utils.ConfigManager;
import io.github.hardin22.javachess.Utils.ErrorReporter;
import io.github.hardin22.javachess.Utils.PgnCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests of the real application: the real views, controllers, game classes, engine manager, archive
 * and puzzle progress, in a temporary data folder, with the board in "off" mode (no Arduino) and a deterministic
 * UCI engine ({@link ScriptedUciEngine}) started as a child process in place of Stockfish and lc0.
 *
 * <p>They need a display: skipped when JavaFX cannot start (CI runs them under {@code xvfb-run}). They run in a
 * separate JVM (surefire execution "e2e") because they configure global singletons.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppEndToEndTest {

    private static final long TIMEOUT_MS = 30_000;
    private static Path home;
    private static Path script;
    private static Stage stage;
    private static MainController main;

    @BeforeAll
    static void startApp() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "engine wrapper is a shell script");
        home = Files.createTempDirectory("javachess-e2e");
        System.setProperty("javachess.home", home.toString());
        System.setProperty("javachess.legacyDir", Files.createDirectories(home.resolve("legacy")).toString());
        System.setProperty("javachess.exportDir", home.resolve("exports").toString());
        System.setProperty("javachess.board", "off");
        ErrorReporter.setDialogsEnabled(false);

        script = home.resolve("engine-script.txt");
        Files.writeString(script, "");
        Path engine = home.resolve("scripted-engine.sh");
        Files.writeString(engine, "#!/bin/sh\nexec \"" + Path.of(System.getProperty("java.home"), "bin", "java")
                + "\" -Xshare:auto -XX:TieredStopAtLevel=1 -cp \"" + System.getProperty("java.class.path")
                + "\" " + ScriptedUciEngine.class.getName() + " \"" + script + "\"\n");
        Files.setPosixFilePermissions(engine, PosixFilePermissions.fromString("rwxr-xr-x"));

        ConfigManager.reload();
        ConfigManager.setProperty("stockfish.path", engine.toString());
        ConfigManager.setProperty("lc0.path", engine.toString());
        ConfigManager.setProperty("game.bot.movetime", "100");
        GameArchiveService.resetInstance();
        PuzzleProgressService.resetInstance();
        EngineManager.get().refreshProfiles();

        E2eHarness.configureHeadlessIfRequested();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        } catch (Throwable noDisplay) {
            assumeTrue(false, "JavaFX cannot start here (no display): " + noDisplay);
        }
        assumeTrue(started.await(15, TimeUnit.SECONDS), "JavaFX did not start");
        Platform.setImplicitExit(false);
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(AppEndToEndTest.class.getResource("/UI/MainLayout.fxml"));
            Parent root = loader.load();
            stage = new Stage();
            stage.setScene(new Scene(root, 720, 1280));
            stage.show();
            main = loader.getController();
            return null;
        });
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (main != null) {
            fx(() -> {
                main.navigateTo("HOME"); // ends (and saves) any game still running
                stage.close();
                return null;
            });
        }
        EngineManager.get().shutdown();
    }

    /** Saves are asynchronous (storage thread): the game a previous test left must be stored before counting. */
    @org.junit.jupiter.api.BeforeEach
    void storedGamesSettled() throws Exception {
        E2eHarness.awaitStorage();
    }

    // ================================================================== scenarios

    @Test
    @Order(1)
    void pvcGameUntilMateIsArchived() throws Exception {
        Files.writeString(script, "e7e5\nd8h4\n"); // the bot answers 1.f3 e5 2.g4 Qh4#
        int before = archive().size();
        ActiveGameController game = startPvc(true);
        play(game, "f2f3", 2);
        play(game, "g2g4", 4);
        waitFor("mate", () -> fxGet(() -> currentGame(game).getBoard().isMated()));
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("0-1", saved.result());
        assertEquals("Scaccomatto", saved.termination());
        assertEquals(List.of("f2f3", "e7e5", "g2g4", "d8h4"), saved.movesUci());
        assertEquals("Giocatore", saved.white());
        assertEquals(ArchivedGame.GameMode.PVC, saved.mode());
    }

    @Test
    @Order(2)
    void engineCanBeSwitchedDuringAGame() throws Exception {
        Files.writeString(script, "");
        fx(() -> {
            EngineSelection.get().select(EngineManager.STOCKFISH);
            return null;
        });
        ActiveGameController game = startPvc(true);
        play(game, "e2e4", 2);
        assumeTrue(EngineManager.get().profiles().stream()
                .anyMatch(p -> p.id().equals(EngineManager.MAIA_1500) && p.available()), "Maia weights not found");
        fx(() -> {
            EngineSelection.get().select(EngineManager.MAIA_1500); // hot switch: same game continues
            return null;
        });
        waitFor("profile switched", () -> EngineManager.MAIA_1500.equals(EngineManager.get().activeProfile().id()));
        play(game, "d2d4", 4);
        fx(() -> {
            EngineSelection.get().select(EngineManager.STOCKFISH);
            main.navigateTo("HOME");
            return null;
        });
    }

    @Test
    @Order(3)
    void pvpFlagFallEndsAndArchivesTheGame() throws Exception {
        int before = archive().size();
        ActiveGameController game = fx(() -> {
            ActiveGameController g = (ActiveGameController) main.getController("GAME");
            g.startPvPSeconds(3, 0);
            main.navigateTo("GAME");
            return g;
        });
        for (String move : List.of("e2e4", "e7e5", "g1f3", "b8c6")) {
            fx(() -> {
                currentGame(game).handleMoveInput(move);
                return null;
            });
        }
        assertEquals(4, (int) fxGet(() -> currentGame(game).getBoard().getHistory().size() - 1));
        ArchivedGame saved = waitForArchived(before + 1); // white's 3 s run out
        assertEquals("0-1", saved.result());
        assertEquals("Tempo", saved.termination());
        assertEquals(ArchivedGame.GameMode.PVP, saved.mode());
        assertEquals(4, saved.movesUci().size());
    }

    @Test
    @Order(4)
    void puzzlesSolvedCleanlyOrWithAMistakeAreRecorded() throws Exception {
        PuzzleController puzzles = fx(() -> {
            PuzzleController c = (PuzzleController) main.getController("PUZZLE_GAME");
            main.navigateTo("PUZZLE_GAME");
            return c;
        });
        // Black moves Ra7-a6, then White mates with Rd1-d8.
        String fen = "6k1/r4ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1";
        solve(puzzles, new Puzzle("e2eA", fen, List.of("a7a6", "d1d8"), 1500, 80, 90, 100,
                List.of("mateIn1", "backRankMate"), "", ""), List.of("d1d8"));
        waitFor("first attempt stored", () -> PuzzleProgressService.getInstance().getStats().attempts() >= 1);
        Thread.sleep(500);
        assertEquals(1, PuzzleProgressService.getInstance().getStats().attempts(), "one record per puzzle");
        assertEquals(1, PuzzleProgressService.getInstance().getStats().solved());

        solve(puzzles, new Puzzle("e2eB", fen, List.of("a7a6", "d1d8"), 1500, 80, 90, 100,
                List.of("mateIn1"), "", ""), List.of("d1d2", "d1d8")); // wrong first, then right
        waitFor("second attempt stored", () -> PuzzleProgressService.getInstance().getStats().attempts() >= 2);
        Thread.sleep(500); // a duplicate record would show up here
        assertEquals(2, PuzzleProgressService.getInstance().getStats().attempts(), "one record per puzzle");
        PuzzleProgressService.Stats stats = PuzzleProgressService.getInstance().getStats();
        assertEquals(1, stats.solved(), "a puzzle with a mistake is not solved cleanly");
        assertTrue(PuzzleProgressService.getInstance().isSolved("e2eA"));
        assertFalse(PuzzleProgressService.getInstance().isSolved("e2eB"));
        assertArrayEquals(new int[]{2, 1}, stats.byTheme().get("mateIn1"));
        fx(() -> {
            main.navigateTo("HOME");
            return null;
        });
    }

    @Test
    @Order(5)
    void archivedGameIsReviewedWithAccuracy() throws Exception {
        ArchivedGame game = archive().add(new ArchivedGame(0, ArchivedGame.GameMode.PVP, "Player vs Player",
                "Bianco", "Nero", "*", "", "", "", LocalDateTime.now(), PgnCodec.START_FEN, "",
                List.of("e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7")));
        ReviewController review = fx(() -> {
            ReviewController r = (ReviewController) main.getController("REVIEW");
            main.navigateTo("REVIEW");
            r.loadGame(game.movesAsUciString(), game.initialFen());
            r.analyze();
            return r;
        });
        waitFor("full analysis", () -> fxGet(() -> {
            // the provisional results of the moves reviewed so far come first: wait for the accuracy as well
            List<?> analysis = (List<?>) field(review, "currentAnalysis");
            return analysis != null && analysis.size() == 7
                    && field(review, "currentReview") != null; // set with the accuracy when the review is done
        }));
        String white = fxGet(() -> ((Label) field(review, "whiteAccuracyLabel")).getText());
        String black = fxGet(() -> ((Label) field(review, "blackAccuracyLabel")).getText());
        assertTrue(white.matches("\\d+[.,]\\d%?"), "white accuracy shown: " + white);
        assertTrue(black.matches("\\d+[.,]\\d%?"), "black accuracy shown: " + black);
        assertTrue(parse(black) < parse(white), "black blundered into mate: " + white + " vs " + black);
        fx(() -> {
            main.navigateTo("HOME");
            return null;
        });
    }

    @Test
    @Order(6)
    void archiveScreenOpensExportsAndDeletesAGame() throws Exception {
        ArchiveController archiveView = fx(() -> {
            ArchiveController c = (ArchiveController) main.getController("ARCHIVE");
            main.navigateTo("ARCHIVE");
            return c;
        });
        @SuppressWarnings("unchecked")
        ListView<ArchiveController.Row> list = (ListView<ArchiveController.Row>) field(archiveView, "archiveListView");
        int stored = archive().size();
        waitFor("archive list", () -> fxGet(() -> list.getItems().size()) == stored);
        ArchiveController.Row first = fxGet(() -> list.getItems().get(0));

        // export
        actions(archiveView, first);
        fireButton(I18n.t("archive.export"));
        Path exported = home.resolve("exports").resolve("javachess-partita-" + first.id() + ".pgn");
        waitFor("exported PGN", () -> Files.exists(exported));
        assertEquals(1, PgnCodec.parsePgn(Files.readString(exported)).size());

        // open in review
        actions(archiveView, first);
        fireButton(I18n.t("archive.open"));
        waitFor("review opened", () -> "REVIEW".equals(fxGet(main::getCurrentViewName)));

        // delete (with confirmation)
        fx(() -> {
            main.navigateTo("ARCHIVE");
            return null;
        });
        actions(archiveView, first);
        fireButton(I18n.t("archive.delete"));   // asks for confirmation
        fireButton(I18n.t("archive.delete"));   // confirms
        waitFor("deleted", () -> archive().get(first.id()).isEmpty());
        waitFor("list refreshed", () -> fxGet(() -> list.getItems().size()) == stored - 1);
    }

    @Test
    @Order(7)
    void botMoveIsRetriedWhenTheEngineCrashesOrHangs() throws Exception {
        for (String failure : List.of("!crash", "!hang")) {
            if (failure.equals("!hang")) {
                // a new bot process for the second case: the crashes above used up the restart budget
                fx(() -> {
                    EngineSelection.get().select(EngineManager.STOCKFISH_LITE);
                    return null;
                });
                waitFor("lite profile", () -> EngineManager.STOCKFISH_LITE.equals(EngineManager.get().activeProfile().id()));
            }
            Files.writeString(script, failure + "\n");
            ActiveGameController game = startPvc(true);
            fx(() -> {
                currentGame(game).handleMoveInput("e2e4");
                return null;
            });
            waitFor("engine failure reported (" + failure + ")",
                    () -> fxGet(() -> currentGame(game).lastStatus()).startsWith("Motore non disponibile"));
            assertEquals(1, (int) fxGet(() -> currentGame(game).getBoard().getHistory().size() - 1));
            assertFalse(fxGet(() -> currentGame(game).isAwaitingHumanMove()), "still the bot's turn");
            Files.writeString(script, "e7e5\n"); // the engine works again
            waitFor("bot move after the retry (" + failure + ")",
                    () -> fxGet(() -> currentGame(game).getBoard().getHistory().size() - 1) >= 2);
            assertTrue(fxGet(() -> currentGame(game).isAwaitingHumanMove()));
            fx(() -> {
                main.navigateTo("HOME");
                return null;
            });
        }
        fx(() -> {
            EngineSelection.get().select(EngineManager.STOCKFISH);
            return null;
        });
    }

    @Test
    @Order(8)
    void leavingTheReviewStopsTheFullAnalysisAndItsEngines() throws Exception {
        Files.writeString(script, "!slow\n");
        long baseline = liveChildProcesses();
        ArchivedGame game = archive().add(new ArchivedGame(0, ArchivedGame.GameMode.PVP, "Player vs Player",
                "Bianco", "Nero", "*", "", "", "", LocalDateTime.now(), PgnCodec.START_FEN, "",
                List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1", "f8e7", "f1e1",
                        "b7b5", "a4b3", "d7d6", "c2c3", "e8g8", "h2h3", "c6b8", "d2d4", "b8d7", "c3c4", "c7c6",
                        "c4b5", "a6b5", "b1c3", "c8b7", "c1g5", "b5b4", "c3b1", "h7h6", "g5h4", "c6c5",
                        "d4e5", "f6e4", "h4e7", "d8e7", "b1d2", "d7e5", "d2e4", "b7e4")));
        // 40 plies at 0.8 s per search: the review alone would need well over 5 s
        ReviewController review = fx(() -> {
            ReviewController r = (ReviewController) main.getController("REVIEW");
            main.navigateTo("REVIEW");
            r.loadGame(game.movesAsUciString(), game.initialFen());
            r.analyze();
            return r;
        });
        waitFor("review engines started", () -> liveChildProcesses() > baseline);
        Thread.sleep(500);
        long left = System.currentTimeMillis();
        fx(() -> {
            main.navigateTo("HOME");
            return null;
        });
        waitFor("review engines closed after leaving", () -> liveChildProcesses() <= baseline);
        long closedAfter = System.currentTimeMillis() - left;
        assertTrue(closedAfter < 3000, "engines closed " + closedAfter + " ms after leaving the review");
        Thread analysis = (Thread) field(review, "analysisThread");
        assertTrue(analysis == null || !analysis.isAlive(), "the review thread stopped");
        Thread.sleep(1000);
        assertNull(fxGet(() -> field(review, "currentReview")), "no final results from a cancelled review");
        Files.writeString(script, "");
    }

    @Test
    @Order(9)
    void withoutABoardTheGameIsPlayedByTappingTheScreen() throws Exception {
        Files.writeString(script, "e7e5\nd8h4\n"); // 1.f3 e5 2.g4 Qh4#
        int before = archive().size();
        ActiveGameController game = startPvc(true);
        waitFor("screen moves accepted", () -> fxGet(() -> currentGame(game).isAwaitingHumanMove()));
        tapMove(game, "f2f3");
        waitFor("bot reply", () -> fxGet(() -> currentGame(game).getBoard().getHistory().size() - 1) >= 2);
        tapMove(game, "g2g4");
        waitFor("bot reply 2", () -> fxGet(() -> currentGame(game).getBoard().getHistory().size() - 1) >= 4);
        assertEquals(List.of("f2f3", "e7e5", "g2g4", "d8h4"), fxGet(() -> currentGame(game).getBoard().getBackup()
                .stream().map(b -> b.getMove().toString()).toList()));
        // a tap on a piece of the bot does nothing
        tapSquare(game, "a7");
        tapSquare(game, "a5");
        Thread.sleep(300);
        assertEquals(4, (int) fxGet(() -> currentGame(game).getBoard().getHistory().size() - 1));
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("f2f3 e7e5 g2g4 d8h4", saved.movesAsUciString());
        assertEquals("0-1", saved.result());
        fx(() -> {
            main.navigateTo("HOME");
            return null;
        });
    }

    @Test
    @Order(10)
    void everyScreenOpensRotatesAndSwitchesThemeWithoutErrors() throws Exception {
        List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> capture =
                new ch.qos.logback.core.AppenderBase<>() {
                    @Override
                    protected void append(ch.qos.logback.classic.spi.ILoggingEvent e) {
                        if (e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)) {
                            errors.add(e.getLoggerName() + ": " + e.getFormattedMessage()
                                    + (e.getThrowableProxy() != null ? " (" + e.getThrowableProxy().getClassName()
                                    + ": " + e.getThrowableProxy().getMessage() + ")" : ""));
                        }
                    }
                };
        capture.start();
        root.addAppender(capture);
        try {
            List<String> views = List.of("HOME", "PVC_SETUP", "PVP_SETUP", "LICHESS_SETUP", "ARCHIVE", "REVIEW",
                    "PUZZLE_DASHBOARD", "PUZZLE_GAME", "THEME", "SETTINGS", "GAME");
            for (String view : views) {
                fx(() -> {
                    main.navigateTo(view);
                    return null;
                });
                waitFor(view, () -> view.equals(fxGet(main::getCurrentViewName)));
                Thread.sleep(250); // background loads of the screen (archive, statistics...)
                fx(() -> {
                    main.rotateScreen();
                    io.github.hardin22.javachess.Components.ThemeManager.get().setMode(
                            io.github.hardin22.javachess.Components.ThemeManager.Mode.LIGHT);
                    return null;
                });
                Thread.sleep(150);
                fx(() -> {
                    main.rotateScreen();
                    io.github.hardin22.javachess.Components.ThemeManager.get().setMode(
                            io.github.hardin22.javachess.Components.ThemeManager.Mode.DARK);
                    return null;
                });
                Thread.sleep(150);
            }
            fx(() -> {
                main.navigateTo("HOME");
                return null;
            });
            Thread.sleep(300);
        } finally {
            root.detachAppender(capture);
        }
        assertTrue(errors.isEmpty(), "errors while visiting the screens: " + errors);
    }

    @Test
    @Order(11)
    void aGameAgainstTheBotWithAClockKeepsItsTimeControlInTheArchive() throws Exception {
        Files.writeString(script, "e7e5\n");
        int before = archive().size();
        ActiveGameController game = fx(() -> {
            ActiveGameController g = (ActiveGameController) main.getController("GAME");
            main.navigateTo("GAME");
            g.startPvC(io.github.hardin22.javachess.Play.BotLevels.byId(
                    io.github.hardin22.javachess.Play.BotLevels.DEFAULT_ID).orElseThrow(), true,
                    io.github.hardin22.javachess.Play.TimeControl.minutes(3, 2));
            return g;
        });
        play(game, "e2e4", 2);
        fx(() -> {
            java.lang.reflect.Method resign = ActiveGameController.class.getDeclaredMethod("requestResign");
            resign.setAccessible(true);
            resign.invoke(game);
            return null;
        });
        fireButton(I18n.t("game.resign.confirm.ok"));
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("3+2", saved.timeControl(), "the clock of the game is in the archive (and its PGN)");
        assertEquals("0-1", saved.result());
        assertTrue(GameArchiveService.toPgn(saved).contains("[TimeControl \"180+2\"]"));
        fx(() -> {
            main.navigateTo("HOME");
            return null;
        });
    }

    /** Taps the from-square then the to-square of {@code uci} on the game board (no physical board). */
    private static void tapMove(ActiveGameController game, String uci) throws Exception {
        tapSquare(game, uci.substring(0, 2));
        tapSquare(game, uci.substring(2, 4));
    }

    private static void tapSquare(ActiveGameController game, String square) throws Exception {
        fx(() -> {
            javafx.scene.Node board = (javafx.scene.Node) field(game, "chessBoard");
            int tile = (int) field(board, "TILE_SIZE");
            boolean flipped = (boolean) field(board, "flipped");
            int file = square.charAt(0) - 'a';
            int rank = square.charAt(1) - '1';
            double x = ((flipped ? 7 - file : file) + 0.5) * tile;
            double y = ((flipped ? rank : 7 - rank) + 0.5) * tile;
            javafx.geometry.Point2D scene = board.localToScene(x, y); // the dispatch recomputes x/y from it
            javafx.event.Event.fireEvent(board, new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, scene.getX(), scene.getY(), scene.getX(),
                    scene.getY(), javafx.scene.input.MouseButton.PRIMARY,
                    1, false, false, false, false, true, false, false, true, false, true, null));
            return null;
        });
    }

    private static long liveChildProcesses() {
        return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
    }

    // ================================================================== helpers

    private static ActiveGameController startPvc(boolean white) throws Exception {
        ActiveGameController game = fx(() -> {
            ActiveGameController g = (ActiveGameController) main.getController("GAME");
            g.startPvC(5, white, EngineService.EngineType.STOCKFISH);
            main.navigateTo("GAME");
            return g;
        });
        waitFor("game ready", () -> fxGet(() -> currentGame(game) != null));
        return game;
    }

    /** Plays a human move and waits until the position has {@code plies} moves (bot reply included). */
    private static void play(ActiveGameController game, String uci, int plies) throws Exception {
        waitFor("human to move before " + uci, () -> fxGet(() -> {
            Board b = currentGame(game).getBoard();
            return PgnCodec.fromUci(b, uci) != null;
        }));
        fx(() -> {
            currentGame(game).handleMoveInput(uci);
            return null;
        });
        waitFor(plies + " plies after " + uci,
                () -> fxGet(() -> currentGame(game).getBoard().getHistory().size() - 1 >= plies));
    }

    private static void solve(PuzzleController puzzles, Puzzle puzzle, List<String> moves) throws Exception {
        fx(() -> {
            puzzles.setPuzzle(puzzle, puzzle.getRating(), puzzle.getThemes());
            return null;
        });
        PuzzleGame game = (PuzzleGame) field(puzzles, "puzzleGame");
        waitFor("puzzle ready", () -> fxGet(() -> (Boolean) field(game, "isSolving")));
        for (String move : moves) {
            fx(() -> {
                game.handleMoveInput(move);
                return null;
            });
        }
    }

    private static void actions(ArchiveController view, ArchiveController.Row row) throws Exception {
        fx(() -> {
            Method m = ArchiveController.class.getDeclaredMethod("showActions", ArchiveController.Row.class);
            m.setAccessible(true);
            m.invoke(view, row);
            return null;
        });
    }

    /** Fires the last visible button with this text (the newest sheet). */
    private static void fireButton(String text) throws Exception {
        waitFor("button " + text, () -> fxGet(() -> !buttons(text).isEmpty()));
        fx(() -> {
            List<Button> found = buttons(text);
            found.get(found.size() - 1).fire();
            return null;
        });
    }

    private static List<Button> buttons(String text) {
        List<Button> out = new ArrayList<>();
        collect(stage.getScene().getRoot(), text, out);
        return out;
    }

    private static void collect(Node node, String text, List<Button> out) {
        if (node instanceof Button b && text.equals(b.getText()) && b.isVisible() && !b.isDisabled()
                && b.getScene() != null) {
            out.add(b);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, text, out);
            }
        }
    }

    private static GameArchiveService archive() {
        return GameArchiveService.getInstance();
    }

    private static ArchivedGame waitForArchived(int count) throws Exception {
        waitFor("archived game", () -> archive().size() >= count);
        return archive().list().stream().max(java.util.Comparator.comparingInt(ArchivedGame::id)).orElseThrow();
    }

    private static AbstractGame currentGame(ActiveGameController c) {
        return (AbstractGame) field(c, "currentGame");
    }

    private static Object field(Object target, String name) {
        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field f = type.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            throw new IllegalArgumentException("no field " + name);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double parse(String accuracy) {
        return Double.parseDouble(accuracy.replace("%", "").replace(',', '.'));
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static <T> T fxGet(Callable<T> action) {
        try {
            return fx(action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // state not ready yet
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + what);
    }

}
