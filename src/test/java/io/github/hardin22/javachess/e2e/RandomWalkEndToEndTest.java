package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.ThemeManager;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Controllers.PuzzleController;
import io.github.hardin22.javachess.Controllers.ReviewController;
import io.github.hardin22.javachess.Oggetti.AbstractGame;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Oggetti.PuzzleGame;
import io.github.hardin22.javachess.Oggetti.PvcGame;
import io.github.hardin22.javachess.Oggetti.PvpGame;
import io.github.hardin22.javachess.Play.BotLevels;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.GameArchiveService;
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
 * Monkey test: a seeded random walk through the real app (no board: moves from the screen) — screens, games against
 * the bot and between two players, moves, take-backs, hints, draws, pauses, resignations, leaving, reviews, puzzles,
 * rotation and theme — looking for anything that logs an error or leaves the app unusable. A failure prints the
 * seed and the trail of actions to replay it ({@code -De2e.walk.seed=N -De2e.walk.steps=M}).
 */
class RandomWalkEndToEndTest {

    private static E2eHarness app;
    private static final List<String> ERRORS = new CopyOnWriteArrayList<>();
    private static ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> capture;

    @BeforeAll
    static void startApp() throws Exception {
        app = E2eHarness.start("off");
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
        org.slf4j.LoggerFactory.getLogger("qa.walk").error("capture check");
        assertEquals(1, ERRORS.size(), "the error capture works");
        ERRORS.clear();
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
    void randomWalkThroughTheAppLogsNoErrors() throws Exception {
        long seed = Long.getLong("e2e.walk.seed", 20261008L);
        int steps = Integer.getInteger("e2e.walk.steps", 300);
        Random random = new Random(seed);
        List<String> trail = new ArrayList<>();
        ActiveGameController game = (ActiveGameController) fxGet(() -> app.main.getController("GAME"));
        PuzzleController puzzles = (PuzzleController) fxGet(() -> app.main.getController("PUZZLE_GAME"));
        try {
            for (int step = 0; step < steps; step++) {
                String action = step(random, game, puzzles);
                trail.add(action);
                Thread.sleep(20 + random.nextInt(60));
                fx(() -> null); // the FX thread answers: nothing is stuck
                if (!ERRORS.isEmpty()) {
                    break;
                }
            }
            fx(() -> {
                app.main.navigateTo("HOME");
                return null;
            });
            awaitStorage();
            Thread.sleep(500);
        } catch (Exception | AssertionError e) {
            fail("random walk (seed " + seed + ") broke after " + trail.size() + " actions: " + e + "\ntrail: "
                    + trail, e);
        }
        assertTrue(ERRORS.isEmpty(), "random walk (seed " + seed + "): errors " + ERRORS + "\ntrail: " + trail);
        java.util.Map<String, Long> counts = trail.stream().collect(java.util.stream.Collectors.groupingBy(
                a -> a.split(" ")[0], java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        System.out.println("[random walk] seed " + seed + ", " + trail.size() + " actions " + counts + ", "
                + archive().size() + " games archived");
        // the archive written along the way reads back
        GameArchiveService reread = new GameArchiveService(archive().getFile(), null,
                archive().getFile().resolveSibling("backups"));
        assertEquals(archive().size(), reread.size());
        assertNull(reread.getLoadProblem());
    }

    /** One random action; returns its description for the trail. */
    private static String step(Random random, ActiveGameController game, PuzzleController puzzles) throws Exception {
        String view = fxGet(app.main::getCurrentViewName);
        AbstractGame current = fxGet(() -> game(game));
        int dice = random.nextInt(100);
        if ("GAME".equals(view) && current != null && fxGet(current::isRunning) && dice < 88) {
            return gameAction(random, game, current);
        }
        if ("PUZZLE_GAME".equals(view) && dice < 50) {
            return puzzleAction(random, puzzles);
        }
        if ("REVIEW".equals(view) && dice < 40) {
            ReviewController review = (ReviewController) fxGet(() -> app.main.getController("REVIEW"));
            int ply = random.nextInt(60);
            fx(() -> {
                review.goTo(ply);
                if (random.nextInt(6) == 0) {
                    review.analyze();
                }
                return null;
            });
            return "review goTo " + ply;
        }
        return switch (random.nextInt(11)) {
            case 0, 1 -> {
                boolean white = random.nextBoolean();
                List<BotLevels.Level> levels = BotLevels.available().isEmpty() ? BotLevels.ALL : BotLevels.available();
                BotLevels.Level level = levels.get(random.nextInt(levels.size()));
                TimeControl tc = random.nextBoolean() ? TimeControl.UNLIMITED
                        : TimeControl.PRESETS.get(random.nextInt(TimeControl.PRESETS.size()));
                fx(() -> {
                    app.main.navigateTo("GAME");
                    game.startPvC(level, white, tc);
                    return null;
                });
                yield "pvc " + level.id() + " " + (white ? "white" : "black") + " " + tc.storage();
            }
            case 2 -> {
                int seconds = 60 + random.nextInt(600);
                int inc = random.nextInt(6);
                fx(() -> {
                    app.main.navigateTo("GAME");
                    game.startPvPSeconds(seconds, inc);
                    return null;
                });
                yield "pvp " + seconds + "+" + inc;
            }
            case 3 -> {
                List<String> views = registeredScreens();
                String target = views.get(random.nextInt(views.size()));
                fx(() -> {
                    app.main.navigateTo(target);
                    return null;
                });
                yield "view " + target;
            }
            case 4 -> {
                List<ArchivedGame> games = archive().list();
                if (games.isEmpty()) {
                    yield "review: archive empty";
                }
                ArchivedGame g = games.get(random.nextInt(games.size()));
                fx(() -> {
                    ReviewController.open(app.main, g);
                    return null;
                });
                yield "review #" + g.id();
            }
            case 5 -> {
                String fen = random.nextBoolean() ? "6k1/r4ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1"
                        : "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR b KQkq - 3 3";
                Puzzle p = fen.startsWith("6k1") ? new Puzzle("walkA", fen, List.of("a7a6", "d1d8"), 1200, 80, 90,
                        100, List.of("mateIn1"), "", "")
                        : new Puzzle("walkB", fen, List.of("g8f6", "f3f7"), 1100, 80, 90, 100, List.of("mateIn1"), "", "");
                fx(() -> {
                    app.main.navigateTo("PUZZLE_GAME");
                    puzzles.setPuzzle(p, p.getRating(), List.of("Tutti"));
                    return null;
                });
                yield "puzzle " + p.getId();
            }
            case 6 -> {
                fx(() -> {
                    app.main.rotateScreen();
                    return null;
                });
                yield "rotate";
            }
            case 7 -> {
                ThemeManager.Mode mode = ThemeManager.Mode.values()[random.nextInt(ThemeManager.Mode.values().length)];
                fx(() -> {
                    ThemeManager.get().setMode(mode);
                    return null;
                });
                yield "theme " + mode;
            }
            default -> {
                fx(() -> {
                    app.main.navigateTo("HOME");
                    return null;
                });
                yield "home";
            }
        };
    }

    private static String gameAction(Random random, ActiveGameController game, AbstractGame current) throws Exception {
        int dice = random.nextInt(100);
        if (dice < 80) {
            if (!fxGet(current::isAwaitingHumanMove)) {
                Thread.sleep(60); // the bot is thinking
                return "wait (not our move)";
            }
            String uci = fxGet(() -> randomMove(random, current.getBoard()));
            if (uci == null) {
                return "no legal move";
            }
            fx(() -> {
                current.handleMoveInput(uci);
                return null;
            });
            return "move " + uci;
        }
        if (current instanceof PvcGame pvc) {
            if (dice < 85) {
                fx(pvc::takeBack);
                return "takeBack";
            }
            if (dice < 89) {
                fx(() -> {
                    pvc.requestHint();
                    return null;
                });
                return "hint";
            }
            if (dice < 92) {
                fx(pvc::offerDraw);
                return "offer draw to the bot";
            }
            if (dice < 96) {
                fx(() -> invoke(game, "requestResign"));
                confirmIfAsked(I18n.t("game.resign.confirm.ok"));
                return "resign";
            }
        } else if (current instanceof PvpGame) {
            Side side = random.nextBoolean() ? Side.WHITE : Side.BLACK;
            if (dice < 86) {
                fx(() -> {
                    game.togglePause();
                    return null;
                });
                return "pause/resume";
            }
            if (dice < 92) {
                boolean accept = random.nextBoolean();
                fx(() -> {
                    game.offerDraw(side);
                    game.answerDraw(accept);
                    return null;
                });
                return "pvp draw offer, accepted=" + accept;
            }
            if (dice < 96) {
                fx(() -> {
                    game.requestResign(side);
                    return null;
                });
                confirmIfAsked(I18n.t("game.resign.confirm.ok"));
                return "pvp resign " + side;
            }
        }
        fx(() -> invoke(game, "requestLeave"));
        confirmIfAsked(I18n.t("game.leave"));
        return "leave";
    }

    private static String puzzleAction(Random random, PuzzleController puzzles) throws Exception {
        PuzzleGame pg = (PuzzleGame) field(puzzles, "puzzleGame");
        int dice = random.nextInt(10);
        if (dice < 6) {
            if (!fxGet(pg::isAwaitingHumanMove)) {
                return "puzzle: wait";
            }
            String uci = fxGet(() -> randomMove(random, pg.getBoard()));
            if (uci == null) {
                return "puzzle: no move";
            }
            fx(() -> {
                pg.handleMoveInput(uci);
                return null;
            });
            return "puzzle move " + uci;
        }
        if (dice < 8) {
            fx(() -> {
                puzzles.handleHint();
                return null;
            });
            return "puzzle hint";
        }
        fx(() -> {
            puzzles.handleSolution();
            return null;
        });
        return "puzzle solution";
    }

    /** Every screen of the app except the integrated browser (JCEF), also screens added later. */
    private static List<String> registeredScreens() throws Exception {
        java.lang.reflect.Field registry = io.github.hardin22.javachess.Controllers.MainController.class
                .getDeclaredField("VIEWS");
        registry.setAccessible(true);
        return ((java.util.Map<?, ?>) registry.get(null)).keySet().stream().map(String::valueOf)
                .filter(v -> !v.equals("BROWSER")).toList();
    }

    /** A random legal move in UCI, with a random promotion piece when it promotes; null when there is none. */
    private static String randomMove(Random random, Board board) {
        List<Move> legal = board.legalMoves();
        if (legal.isEmpty()) {
            return null;
        }
        Move m = legal.get(random.nextInt(legal.size()));
        return m.toString();
    }

    /** Fires the confirmation button when a confirmation sheet or prompt appeared. */
    private static void confirmIfAsked(String text) throws Exception {
        Thread.sleep(60);
        try {
            app.fireButtonIfPresent(text);
        } catch (RuntimeException ignored) {
            // nothing to confirm
        }
    }
}
