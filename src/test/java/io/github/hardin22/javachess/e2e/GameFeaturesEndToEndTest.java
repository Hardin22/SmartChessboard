package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;
import io.github.hardin22.javachess.Oggetti.PvcGame;
import io.github.hardin22.javachess.Play.BotDrawPolicy;
import io.github.hardin22.javachess.Play.BotLevels;
import io.github.hardin22.javachess.Play.GameResume;
import io.github.hardin22.javachess.Play.GameSnapshot;
import io.github.hardin22.javachess.Play.GameSnapshotStore;
import io.github.hardin22.javachess.Play.HintAdvisor;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;
import io.github.hardin22.javachess.Utils.ErrorReporter;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The game-against-the-computer features end to end, on the real game class, engine manager, archive and saved-game
 * store, with the deterministic {@link ScriptedUciEngine} as Stockfish and no board ("off"): levels in Elo, clock
 * with increment, take-back, resuming after a restart, flag fall, hints and draw offers. No controller is involved,
 * so the test does not depend on the screens being redesigned.
 */
class GameFeaturesEndToEndTest {

    private static final long TIMEOUT_MS = 30_000;
    private static Path home;
    private static Path script;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "engine wrapper is a shell script");
        home = Files.createTempDirectory("javachess-features-e2e");
        System.setProperty("javachess.home", home.toString());
        System.setProperty("javachess.legacyDir", Files.createDirectories(home.resolve("legacy")).toString());
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
        GameSnapshotStore.resetInstance();
        EngineManager.get().shutdown(); // processes of another test class use another script
        EngineManager.get().refreshProfiles();
        EngineManager.get().select(EngineManager.STOCKFISH);

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
    }

    @AfterAll
    static void stop() {
        EngineManager.get().setBotStrength(null);
        EngineManager.get().shutdown();
    }

    private PvcGame game;

    @AfterEach
    void endGame() throws Exception {
        if (game != null) {
            fx(() -> {
                game.endGame("Partita interrotta", false);
                return null;
            });
        }
        GameSnapshotStore.get().clear();
        flushStorage();
    }

    // ================================================================== scenarios

    @Test
    void levelClockTakeBackAndResume() throws Exception {
        Files.writeString(script, "e7e5\nb8c6\n");
        BotLevels.Level club = BotLevels.byId("club").orElseThrow();
        game = newGame(true, club, TimeControl.minutes(5, 3));
        fx(() -> {
            game.startGame();
            return null;
        });
        waitFor("white clock running", () -> fxGet(() -> game.getClock().runningProperty().get() == Side.WHITE));
        assertEquals(1320, EngineManager.get().botStrength().uciElo(), "the level plays at its Elo");

        play("e2e4", 2);
        assertTrue(game.getClock().remainingMillis(Side.WHITE) > 301_000, "increment added after the move");
        waitFor("white clock again", () -> fxGet(() -> game.getClock().runningProperty().get() == Side.WHITE));
        assertTrue(EngineManager.get().botStrength().movetimeMs() <= 100, "bot time within the configured time");

        flushStorage();
        GameSnapshot saved = GameSnapshotStore.get().load().orElseThrow();
        assertEquals(List.of("e2e4", "e7e5"), saved.moves());
        assertEquals("club", saved.botLevelId());
        assertEquals(TimeControl.minutes(5, 3), saved.timeControl());

        // take-back: the player's move and the bot's answer go
        assertTrue(fxGet(() -> game.canTakeBack()));
        assertTrue(fxGet(() -> game.takeBack()));
        assertEquals(List.of(), fxGet(() -> game.getMovesUci()));
        assertEquals(1, (int) fxGet(() -> game.getTakebacks()));
        assertFalse(fxGet(() -> game.canTakeBack()));
        play("d2d4", 2);
        assertEquals(List.of("d2d4", "e7e5"), fxGet(() -> game.getMovesUci()), "the bot answers again");

        // the app restarts: the game comes back from disk with its clocks and counters
        flushStorage();
        long whiteLeft = game.getClock().remainingMillis(Side.WHITE);
        fx(() -> {
            game.endGame("Partita interrotta", false);
            return null;
        });
        GameSnapshot resumable = GameResume.available().orElseThrow();
        assertEquals(1, resumable.takebacks());
        game = fxGet(() -> PvcGame.fromSnapshot(resumable, board(), new EvalBar(10, 100), new Label()));
        assertEquals(List.of("d2d4", "e7e5"), fxGet(() -> game.getMovesUci()));
        assertEquals("club", game.getLevel().id());
        assertEquals(1, (int) fxGet(() -> game.getTakebacks()));
        assertTrue(Math.abs(game.getClock().remainingMillis(Side.WHITE) - whiteLeft) < 1_500, "clock restored");
        fx(() -> {
            game.startGame();
            return null;
        });
        play("g1f3", 4);
        assertEquals("b8c6", fxGet(() -> game.getMovesUci().get(3)));

        // too early for a draw
        BotDrawPolicy.Decision d = fxGet(() -> game.offerDraw()).get(10, TimeUnit.SECONDS);
        assertFalse(d.accepted());
        assertFalse(fxGet(() -> game.canOfferDraw()), "not again right away");
    }

    @Test
    void botMovesFirstFromAPositionWhenThePlayerIsBlack() throws Exception {
        Files.writeString(script, "e2e4\n");
        game = newGame(false, BotLevels.byId("intermediate").orElseThrow(), TimeControl.UNLIMITED);
        fx(() -> {
            game.setStartPosition("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
            game.startGame();
            return null;
        });
        waitFor("bot moved first", () -> fxGet(() -> game.getMovesUci().size() == 1));
        assertEquals("e2e4", fxGet(() -> game.getMovesUci().get(0)));
        assertEquals(null, game.getClock());
        assertFalse(fxGet(() -> game.takeBack()), "the bot's first move alone cannot be taken back");
    }

    @Test
    void flagFallIsArchivedAsALossOnTime() throws Exception {
        Files.writeString(script, "");
        int before = GameArchiveService.getInstance().size();
        game = newGame(true, BotLevels.byId("club").orElseThrow(), new TimeControl(2, 0));
        fx(() -> {
            game.startGame();
            return null;
        });
        play("e2e4", 2); // a game without moves is not archived
        waitFor("archived", () -> archivedLater(before));
        flushStorage();
        assertTrue(GameSnapshotStore.get().load().isEmpty(), "a finished game is not resumable");
        ArchivedGame g = GameArchiveService.getInstance().list().get(0);
        assertEquals("0-1", g.result());
        assertEquals("Tempo", g.termination());
        assertEquals("Stockfish (1350)", g.black());
        game = null;
    }

    @Test
    void hintGivesThePieceThenTheMove() throws Exception {
        Files.writeString(script, "g1f3\n");
        game = newGame(true, BotLevels.byId("club").orElseThrow(), TimeControl.UNLIMITED);
        fx(() -> {
            game.startGame();
            game.requestHint();
            return null;
        });
        waitFor("hint", () -> fxGet(() -> game.hints().levelProperty().get() == HintAdvisor.Level.PIECE));
        assertEquals("g1", fxGet(() -> game.hints().fromSquareProperty().get()));
        fx(() -> {
            game.requestHint();
            return null;
        });
        assertEquals("g1f3", fxGet(() -> game.hints().moveProperty().get()));
        play("e2e4", 2);
        assertEquals(HintAdvisor.Level.NONE, fxGet(() -> game.hints().levelProperty().get()), "gone after the move");
        assertEquals(1, (int) fxGet(() -> game.hints().usedProperty().get()));
    }

    // ================================================================== helpers

    private boolean archivedLater(int before) {
        return GameArchiveService.getInstance().size() > before;
    }

    private PvcGame newGame(boolean white, BotLevels.Level level, TimeControl tc) throws Exception {
        return fxGet(() -> new PvcGame(board(), new EvalBar(10, 100), new Label(), white, level, tc));
    }

    private static ChessBoardUI board() {
        return new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 40);
    }

    /** Plays a move for the player and waits until the game has {@code plies} moves (the bot answered). */
    private void play(String uci, int plies) throws Exception {
        fx(() -> {
            game.handleMoveInput(uci);
            return null;
        });
        waitFor("bot answer after " + uci, () -> fxGet(() -> game.getMovesUci().size() >= plies));
    }

    private static void flushStorage() throws Exception {
        AppExecutors.storage().submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(action.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static <T> T fxGet(Callable<T> action) throws Exception {
        return fx(action);
    }

    /** A condition that may throw (FX round trips): an exception counts as "not yet". */
    @FunctionalInterface
    private interface Check {
        boolean get() throws Exception;
    }

    private static void waitFor(String what, Check condition) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.get()) {
                    return;
                }
            } catch (Exception e) {
                // not yet
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
