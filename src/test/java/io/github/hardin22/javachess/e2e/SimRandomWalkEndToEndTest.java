package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.MoveBackup;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Controllers.PuzzleController;
import io.github.hardin22.javachess.Oggetti.AbstractGame;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Oggetti.PuzzleGame;
import io.github.hardin22.javachess.Oggetti.PvcGame;
import io.github.hardin22.javachess.Oggetti.PvpGame;
import io.github.hardin22.javachess.Play.BotLevels;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.hardin22.javachess.e2e.E2eHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Monkey test on the simulated board: a seeded random walk where every move is made on the sensors and the board
 * is followed like a player would (set-up, reproducing the opponent's moves, putting pieces back), mixed with
 * cable unplugged and plugged back (sometimes with a move made meanwhile), take-backs, pauses with a move during
 * the pause, resignations, leaving and puzzles. Fails on any ERROR in the log, a stuck FX thread, or a board that
 * cannot be brought back to the game. {@code -De2e.simwalk.seed=N -De2e.simwalk.steps=M}.
 */
class SimRandomWalkEndToEndTest {

    private static E2eHarness app;
    private static final List<String> ERRORS = new CopyOnWriteArrayList<>();
    private static ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> capture;

    @BeforeAll
    static void startApp() throws Exception {
        app = E2eHarness.start("sim");
        boardState().setTimings(20, 300, 150);
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        capture = new ch.qos.logback.core.AppenderBase<>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent e) {
                if (e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)) {
                    ERRORS.add(e.getLoggerName() + ": " + e.getFormattedMessage() + (e.getThrowableProxy() == null ? ""
                            : " (" + e.getThrowableProxy().getClassName() + ": " + e.getThrowableProxy().getMessage()
                            + ")"));
                }
            }
        };
        capture.start();
        root.addAppender(capture);
    }

    @AfterAll
    static void stopApp() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .detachAppender(capture);
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void randomWalkOnThePhysicalBoardLogsNoErrorsAndNeverGetsStuck() throws Exception {
        long seed = Long.getLong("e2e.simwalk.seed", 8102026L);
        int steps = Integer.getInteger("e2e.simwalk.steps", 250);
        Random random = new Random(seed);
        List<String> trail = new ArrayList<>();
        ActiveGameController game = (ActiveGameController) fxGet(() -> app.main.getController("GAME"));
        PuzzleController puzzles = (PuzzleController) fxGet(() -> app.main.getController("PUZZLE_GAME"));
        try {
            for (int step = 0; step < steps && ERRORS.isEmpty(); step++) {
                trail.add(step(random, game, puzzles));
                Thread.sleep(40);
                fx(() -> null);
            }
            fx(() -> {
                app.main.navigateTo("HOME");
                return null;
            });
            waitForMode(BoardStateManager.Mode.IDLE);
            awaitStorage();
        } catch (Exception | AssertionError e) {
            fail("sim random walk (seed " + seed + ") broke after " + trail.size() + " actions: " + e + "\ntrail: "
                    + trail.subList(Math.max(0, trail.size() - 40), trail.size()), e);
        }
        assertTrue(ERRORS.isEmpty(), "sim random walk (seed " + seed + "): errors " + ERRORS + "\ntrail: " + trail);
        java.util.Map<String, Long> counts = trail.stream().collect(java.util.stream.Collectors.groupingBy(
                a -> a.split(" ")[0], java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        System.out.println("[sim random walk] seed " + seed + ", " + trail.size() + " actions " + counts + ", "
                + archive().size() + " games archived");
    }

    private static String step(Random random, ActiveGameController game, PuzzleController puzzles) throws Exception {
        String view = fxGet(app.main::getCurrentViewName);
        AbstractGame current = "PUZZLE_GAME".equals(view) ? (AbstractGame) field(puzzles, "puzzleGame")
                : "GAME".equals(view) ? fxGet(() -> game(game)) : null;
        BoardStateManager.Mode mode = boardState().mode();
        boolean running = current != null && !(current instanceof PuzzleGame) && fxGet(current::isRunning);
        // a puzzle is going on while it is set up or followed on the board, or waits for the solver's move
        boolean puzzleActive = current instanceof PuzzleGame && (mode == BoardStateManager.Mode.SETUP
                || mode == BoardStateManager.Mode.REPLICATE || mode == BoardStateManager.Mode.RESYNC
                || fxGet(current::isAwaitingHumanMove) || fxGet(() -> current.getBoard().getBackup().isEmpty()));
        if (!running && !puzzleActive) {
            return start(random, game, puzzles);
        }
        int dice = random.nextInt(100);
        if (mode == BoardStateManager.Mode.SETUP || mode == BoardStateManager.Mode.RESYNC) {
            if (current instanceof PuzzleGame && fxGet(() -> current.getBoard().getBackup().isEmpty())) {
                Thread.sleep(100); // the opponent's first move is still being shown
                return "puzzle: wait for the first move";
            }
            arrangeAs(fxGet(() -> current.getBoard().getFen()));
            return "arrange (" + mode + ")";
        }
        if (mode == BoardStateManager.Mode.REPLICATE) {
            List<MoveBackup> history = fxGet(() -> new ArrayList<>(current.getBoard().getBackup()));
            Board after = fxGet(() -> current.getBoard().clone());
            reproduce(history.get(history.size() - 1).getMove(), after);
            Thread.sleep(80);
            if (boardState().mode() == BoardStateManager.Mode.REPLICATE) {
                // e.g. a move reproduced while unplugged: put everything as on the screen and touch the target
                arrangeAs(after.getFen());
                int to = history.get(history.size() - 1).getMove().getTo().ordinal();
                sim().lift(to);
                sim().place(to);
            }
            return "reproduce " + history.get(history.size() - 1).getMove();
        }
        if (dice < 8) {
            return unplugged(random, current);
        }
        if (dice < 14 && current instanceof PuzzleGame) {
            fx(() -> {
                puzzles.handleSolution(); // give up: the next step starts something else
                return null;
            });
            fx(() -> {
                app.main.navigateTo("HOME");
                return null;
            });
            return "puzzle given up";
        }
        if (dice < 12 && current instanceof PvcGame pvc) {
            fx(pvc::takeBack);
            return "takeBack";
        }
        if (dice < 16 && current instanceof PvpGame pvp) {
            fx(() -> {
                game.togglePause();
                return null;
            });
            if (fxGet(pvp::isClockPaused) && fxGet(() -> current.getBoard().legalMoves().size()) > 0) {
                physicalMove(randomMove(random, boardState().logicalFen())); // refused: pieces go back
                return "pause + move during the pause";
            }
            return "resume";
        }
        if (dice < 19 && !(current instanceof PuzzleGame)) {
            fx(() -> invoke(game, "requestLeave"));
            Thread.sleep(60);
            app.fireButtonIfPresent(I18n.t("game.leave"));
            return "leave";
        }
        if (dice < 21 && current instanceof PvcGame) {
            fx(() -> invoke(game, "requestResign"));
            Thread.sleep(60);
            app.fireButtonIfPresent(I18n.t("game.resign.confirm.ok"));
            return "resign";
        }
        if (mode == BoardStateManager.Mode.PLAY && fxGet(current::isAwaitingHumanMove)) {
            String uci = randomMove(random, boardState().logicalFen());
            if (uci == null) {
                return "no move";
            }
            int before = fxGet(() -> current.getBoard().getBackup().size());
            physicalMove(uci);
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && fxGet(() -> current.getBoard().getBackup().size()) == before
                    && boardState().mode() == BoardStateManager.Mode.PLAY) {
                Thread.sleep(20);
            }
            return "move " + uci;
        }
        Thread.sleep(60);
        return "wait";
    }

    private static String start(Random random, ActiveGameController game, PuzzleController puzzles) throws Exception {
        sim().setConnected(true);
        int dice = random.nextInt(10);
        if (dice < 5) {
            boolean white = random.nextBoolean();
            List<BotLevels.Level> levels = BotLevels.available().isEmpty() ? BotLevels.ALL : BotLevels.available();
            BotLevels.Level level = levels.get(random.nextInt(levels.size()));
            TimeControl tc = random.nextBoolean() ? TimeControl.UNLIMITED : TimeControl.minutes(5, 3);
            fx(() -> {
                app.main.navigateTo("GAME");
                game.startPvC(level, white, tc);
                return null;
            });
            return "pvc " + level.id() + " " + (white ? "white" : "black");
        }
        if (dice < 8) {
            fx(() -> {
                app.main.navigateTo("GAME");
                game.startPvPSeconds(600, 2);
                return null;
            });
            return "pvp";
        }
        Puzzle p = random.nextBoolean()
                ? new Puzzle("simwalkA", "6k1/r4ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1", List.of("a7a6", "d1d8"), 1200, 80,
                        90, 100, List.of("mateIn1"), "", "")
                : new Puzzle("simwalkB", "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR b KQkq - 3 3",
                        List.of("g8f6", "f3f7"), 1100, 80, 90, 100, List.of("mateIn1"), "", "");
        fx(() -> {
            app.main.navigateTo("PUZZLE_GAME");
            puzzles.setPuzzle(p, p.getRating(), List.of("Tutti"));
            return null;
        });
        return "puzzle " + p.getId();
    }

    /** Cable out; sometimes a legal move made meanwhile; cable back in. */
    private static String unplugged(Random random, AbstractGame current) throws Exception {
        sim().setConnected(false);
        Thread.sleep(60);
        String note = "unplug";
        if (random.nextBoolean() && fxGet(current::isAwaitingHumanMove)) {
            String uci = randomMove(random, boardState().logicalFen());
            if (uci != null) {
                physicalMove(uci);
                note += " + move " + uci + " offline";
            }
        }
        sim().setConnected(true);
        Thread.sleep(150);
        return note + " + replug";
    }

    private static String randomMove(Random random, String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        List<Move> legal = b.legalMoves();
        return legal.isEmpty() ? null : legal.get(random.nextInt(legal.size())).toString();
    }

    @SuppressWarnings("unused")
    private static Side sideOf(boolean white) {
        return white ? Side.WHITE : Side.BLACK;
    }
}
