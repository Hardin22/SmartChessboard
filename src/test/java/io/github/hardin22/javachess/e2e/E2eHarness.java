package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.MoveBackup;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Controllers.MainController;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Hardware.SimulatedBoard;
import io.github.hardin22.javachess.Oggetti.AbstractGame;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Services.BoardStateManager;
import io.github.hardin22.javachess.Services.EngineService;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Services.PuzzleProgressService;
import io.github.hardin22.javachess.Utils.ConfigManager;
import io.github.hardin22.javachess.Utils.ErrorReporter;
import io.github.hardin22.javachess.Utils.PgnCodec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real application (main layout, views, controllers, games, engine manager, archive) in a temporary data
 * folder, with {@link ScriptedUciEngine} in place of Stockfish and lc0, for the end-to-end tests that play on the
 * simulated board. Each test class runs in its own JVM (surefire execution "e2e", reuseForks=false).
 */
final class E2eHarness {

    static final long TIMEOUT_MS = 30_000;

    final Path home;
    final Path script;
    Stage stage;
    MainController main;

    private E2eHarness(Path home, Path script) {
        this.home = home;
        this.script = script;
    }

    /** Starts JavaFX and the main layout; skips the test class when there is no display. */
    static E2eHarness start(String boardMode) throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "engine wrapper is a shell script");
        Path home = Files.createTempDirectory("javachess-e2e");
        System.setProperty("javachess.home", home.toString());
        System.setProperty("javachess.legacyDir", Files.createDirectories(home.resolve("legacy")).toString());
        System.setProperty("javachess.exportDir", home.resolve("exports").toString());
        System.setProperty("javachess.board", boardMode);
        ErrorReporter.setDialogsEnabled(false);

        Path script = home.resolve("engine-script.txt");
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
        E2eHarness h = new E2eHarness(home, script);
        fx(() -> {
            FXMLLoader loader = new FXMLLoader(E2eHarness.class.getResource("/UI/MainLayout.fxml"));
            Parent root = loader.load();
            h.stage = new Stage();
            h.stage.setScene(new Scene(root, 720, 1280));
            h.stage.show();
            h.main = loader.getController();
            return null;
        });
        return h;
    }

    void stop() throws Exception {
        if (main != null) {
            fx(() -> {
                main.navigateTo("HOME"); // ends (and saves) any game still running
                stage.close();
                return null;
            });
        }
        EngineManager.get().shutdown();
    }

    void bot(String... moves) throws Exception {
        Files.writeString(script, String.join("\n", moves) + "\n");
    }

    // ------------------------------------------------------------------ games

    ActiveGameController startPvc(boolean white) throws Exception {
        ActiveGameController game = fx(() -> {
            ActiveGameController g = (ActiveGameController) main.getController("GAME");
            main.navigateTo("GAME");
            g.startPvC(5, white, EngineService.EngineType.STOCKFISH);
            return g;
        });
        waitFor("game ready", () -> fxGet(() -> game(game) != null));
        return game;
    }

    ActiveGameController startPvp(int seconds, int increment) throws Exception {
        ActiveGameController game = fx(() -> {
            ActiveGameController g = (ActiveGameController) main.getController("GAME");
            main.navigateTo("GAME");
            g.startPvPSeconds(seconds, increment);
            return g;
        });
        waitFor("game ready", () -> fxGet(() -> game(game) != null));
        return game;
    }

    static AbstractGame game(ActiveGameController c) {
        return (AbstractGame) field(c, "currentGame");
    }

    static int plies(ActiveGameController c) {
        return fxGet(() -> game(c).getBoard().getBackup().size());
    }

    // ------------------------------------------------------------------ simulated board

    static SimulatedBoard sim() {
        return Hardware.simulator();
    }

    static BoardStateManager boardState() {
        return Hardware.boardState();
    }

    /** Waits for the board manager to be in {@code mode}. */
    static void waitForMode(BoardStateManager.Mode mode) throws InterruptedException {
        waitFor("board mode " + mode, () -> boardState().mode() == mode);
    }

    /** Lifts and places the pieces of {@code uci} on the simulated board, as a player does. */
    static void physicalMove(String uci) {
        Board logical = new Board();
        logical.loadFromFen(boardState().logicalFen());
        Move move = PgnCodec.fromUci(logical, uci);
        if (move == null) {
            fail(uci + " is not legal in " + logical.getFen());
        }
        sim().playMove(logical, move);
    }

    /** Plays {@code uci} on the board and waits until the game has {@code plies} half-moves. */
    static void playOnBoard(ActiveGameController c, String uci, int plies) throws InterruptedException {
        waitFor("board ready for " + uci, () -> {
            BoardStateManager.Mode mode = boardState().mode();
            return mode == BoardStateManager.Mode.PLAY && fxGet(() -> game(c).isAwaitingHumanMove());
        });
        physicalMove(uci);
        waitFor(plies + " plies after " + uci, () -> plies(c) >= plies);
    }

    /**
     * Reproduces on the board the last move of the game (the opponent's), the way a player follows the LEDs:
     * captured piece off first, then the moving piece (and the rook when castling).
     */
    static void reproduceLastMove(ActiveGameController c) throws InterruptedException {
        waitForMode(BoardStateManager.Mode.REPLICATE);
        List<MoveBackup> history = fxGet(() -> new ArrayList<>(game(c).getBoard().getBackup()));
        Board before = new Board(); // games of these tests start from the initial position
        for (int i = 0; i < history.size() - 1; i++) {
            before.doMove(history.get(i).getMove());
        }
        sim().playMove(before, history.get(history.size() - 1).getMove());
        waitForMode(BoardStateManager.Mode.PLAY);
    }

    /** Puts the pieces exactly as in the game position (setup or resync done by hand). */
    static void arrangeAsLogical() {
        arrangeAs(boardState().logicalFen());
    }

    /** Puts the pieces as in {@code fen} (e.g. the set-up target of a puzzle). */
    static void arrangeAs(String fen) {
        Board position = new Board();
        position.loadFromFen(fen);
        long target = BoardStateManager.occupancy(position);
        SimulatedBoard sim = sim();
        for (long bits = sim.occupancy() & ~target; bits != 0; bits &= bits - 1) {
            sim.lift(Long.numberOfTrailingZeros(bits));
        }
        for (long bits = target & ~sim.occupancy(); bits != 0; bits &= bits - 1) {
            sim.place(Long.numberOfTrailingZeros(bits));
        }
    }

    // ------------------------------------------------------------------ archive

    static GameArchiveService archive() {
        return GameArchiveService.getInstance();
    }

    static ArchivedGame waitForArchived(int count) throws InterruptedException {
        waitFor("archived game " + count, () -> archive().size() >= count);
        return archive().list().stream().max(java.util.Comparator.comparingInt(ArchivedGame::id)).orElseThrow();
    }

    // ------------------------------------------------------------------ UI helpers

    /** Fires the last visible, enabled button with this text (the newest sheet). */
    void fireButton(String text) throws Exception {
        waitFor("button " + text, () -> fxGet(() -> !buttons(text).isEmpty()));
        fx(() -> {
            List<Button> found = buttons(text);
            found.get(found.size() - 1).fire();
            return null;
        });
    }

    private List<Button> buttons(String text) {
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

    static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    static Object field(Object target, String name) {
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

    static <T> T fx(Callable<T> action) throws Exception {
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

    static <T> T fxGet(Callable<T> action) {
        try {
            return fx(action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // state not ready yet
            }
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }
}
