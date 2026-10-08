package io.github.hardin22.javachess.e2e;

import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Oggetti.AbstractGame;
import io.github.hardin22.javachess.Services.GameArchiveService;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.hardin22.javachess.e2e.E2eHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Monkey test of the whole interface, the way a person taps it: at every step, one random button, toggle, row or
 * square that is visible and enabled on the screen now (only the open sheet's, when a sheet is open) is tapped. Every
 * screen is reached through the app's own buttons, new screens included without changing this test.
 *
 * <p>Fails on an error in the log, an uncaught exception, a frozen FX thread, or a dead end (a screen with nothing
 * to tap). The network to the outside is cut (the app must cope with it: Lichess, chess.com, the opening explorer,
 * the online import) and the integrated browser has no Chromium bundle, so it shows its "not available" state; the
 * "USB drives" are a folder of the temporary home with one PGN file. A failure prints the seed and the trail to
 * replay it: {@code -De2e.tap.seed=N -De2e.tap.steps=M}.</p>
 */
class TapWalkEndToEndTest {

    private static E2eHarness app;
    private static final List<String> ERRORS = new CopyOnWriteArrayList<>();
    private static final List<String> NETWORK = new CopyOnWriteArrayList<>();
    private static ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> capture;
    private static Thread.UncaughtExceptionHandler previousHandler;

    /**
     * Errors expected in this setup: the browser cannot be installed without the network (the user sees
     * "Browser non disponibile" with Riprova / Home, which this test taps too).
     */
    private static boolean expected(ch.qos.logback.classic.spi.ILoggingEvent e) {
        return e.getLoggerName().endsWith("BrowserController")
                && e.getFormattedMessage().startsWith("Cannot start the integrated browser");
    }

    @BeforeAll
    static void startApp() throws Exception {
        setUp("off");
    }

    /** Starts the app with {@code board} (off, sim), offline, with a "USB drive" and the error capture. */
    static void setUp(String board) throws Exception {
        // a person taps; nothing may open the computer's own web browser
        System.setProperty("java.awt.headless", "true");
        cutTheNetwork();
        app = E2eHarness.start(board);
        Path drive = Files.createDirectories(app.home.resolve("usb").resolve("PENNA"));
        Files.writeString(drive.resolve("torneo.pgn"), """
                [White "Rossi"]
                [Black "Bianchi"]
                [Result "1-0"]

                1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0

                [White "Verdi"]
                [Black "Neri"]
                [Result "1/2-1/2"]

                1. d4 d5 2. c4 e6 3. Nc3 Nf6 1/2-1/2
                """);
        // a few puzzles in the Lichess CSV format, so that the puzzle screens open (the real database is 300 MB)
        Files.writeString(app.home.resolve("puzzles.csv"), """
                PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags
                tapA,6k1/r4ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1,a7a6 d1d8,1200,80,90,100,mateIn1 endgame short,,
                tapB,r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR b KQkq - 3 3,g8f6 f3f7,1100,80,90,100,mateIn1 opening short,,
                tapC,6k1/5ppp/8/8/8/8/r4PPP/1R4K1 b - - 0 1,a2a1 b1a1,900,80,90,100,short,,
                """);
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        capture = new ch.qos.logback.core.AppenderBase<>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent e) {
                if (e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR) && !expected(e)) {
                    ERRORS.add(e.getLoggerName() + ": " + e.getFormattedMessage() + (e.getThrowableProxy() == null
                            ? "" : " (" + e.getThrowableProxy().getClassName() + ": "
                            + e.getThrowableProxy().getMessage() + ")"));
                }
            }
        };
        capture.start();
        root.addAppender(capture);
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> ERRORS.add("uncaught in " + t.getName() + ": " + e));
        fx(() -> {
            Thread.currentThread().setUncaughtExceptionHandler(
                    (t, e) -> ERRORS.add("uncaught on the FX thread: " + e + " at " + top(e)));
            return null;
        });
    }

    private static String top(Throwable e) {
        StackTraceElement[] st = e.getStackTrace();
        return st.length == 0 ? "?" : st[0].toString();
    }

    /** Every connection to a host other than this computer goes to a closed port: no network, as on a Pi offline. */
    private static void cutTheNetwork() {
        Proxy dead = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9));
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri.getHost() == null ? "" : uri.getHost();
                if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")) {
                    return List.of(Proxy.NO_PROXY);
                }
                NETWORK.add(uri.getScheme() + "://" + host);
                return List.of(dead);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        kong.unirest.Unirest.config().proxy("127.0.0.1", 9);
    }

    @AfterAll
    static void stopApp() throws Exception {
        tearDown();
    }

    static void tearDown() throws Exception {
        if (capture != null) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                    .detachAppender(capture);
        }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void tappingAroundEveryScreenLogsNoErrorsAndNeverDeadEnds() throws Exception {
        walk("tap walk", Long.getLong("e2e.tap.seed", 20261008L), Integer.getInteger("e2e.tap.steps", 600));
    }

    /** The walk itself: {@code steps} taps (or moves) from a random generator seeded with {@code seed}. */
    static void walk(String name, long seed, int steps) throws Exception {
        Random random = new Random(seed);
        List<String> trail = new ArrayList<>();
        Map<String, Integer> views = new TreeMap<>();
        ActiveGameController game = (ActiveGameController) fxGet(() -> app.main.getController("GAME"));
        int empty = 0;
        try {
            for (int step = 0; step < steps && ERRORS.isEmpty(); step++) {
                String view = fxGet(app.main::getCurrentViewName);
                views.merge(view, 1, Integer::sum);
                String action = step(random, game, view);
                if (action == null) {
                    if (++empty >= 8) {
                        fail("dead end: nothing to tap on " + view + " for " + empty + " steps");
                    }
                    Thread.sleep(150);
                    continue;
                }
                empty = 0;
                trail.add(view + ": " + action);
                Thread.sleep(15 + random.nextInt(50));
                fx(() -> null); // the FX thread answers: nothing is stuck
            }
            fx(() -> {
                app.main.closeSheet();
                app.main.navigateTo("HOME");
                return null;
            });
            awaitStorage();
            Thread.sleep(300);
        } catch (Exception | AssertionError e) {
            fail(name + " (seed " + seed + ") broke after " + trail.size() + " taps: " + e + "\nlast taps: "
                    + tail(trail), e);
        }
        assertTrue(ERRORS.isEmpty(), name + " (seed " + seed + "): errors " + ERRORS + "\nlast taps: " + tail(trail));
        System.out.println("[" + name + "] seed " + seed + ", " + trail.size() + " taps, screens " + views + ", "
                + archive().size() + " games archived, outside hosts tried " + NETWORK.stream().distinct().toList());
        GameArchiveService reread = new GameArchiveService(archive().getFile(), null,
                archive().getFile().resolveSibling("backups"));
        assertEquals(archive().size(), reread.size());
        assertNull(reread.getLoadProblem());
    }

    private static List<String> tail(List<String> trail) {
        return trail.subList(Math.max(0, trail.size() - 40), trail.size());
    }

    /** One tap (or a move in a game, so games also reach their end); null when there is nothing to tap. */
    private static String step(Random random, ActiveGameController game, String view) throws Exception {
        if ("GAME".equals(view) && random.nextInt(100) < 45) {
            AbstractGame current = fxGet(() -> E2eHarness.game(game));
            boolean sheet = fxGet(app.main::isSheetOpen);
            boolean byScreen = !fxGet(() -> io.github.hardin22.javachess.Hardware.Hardware.boardState()
                    .isHardwareConnected()); // with a board the player moves the pieces
            if (byScreen && current != null && !sheet && fxGet(current::isRunning)
                    && fxGet(current::isAwaitingHumanMove)) {
                String uci = fxGet(() -> {
                    var legal = current.getBoard().legalMoves();
                    return legal.isEmpty() ? null : legal.get(random.nextInt(legal.size())).toString();
                });
                if (uci != null) {
                    fx(() -> {
                        current.handleMoveInput(uci);
                        return null;
                    });
                    return "move " + uci;
                }
            }
        }
        return fxGet(() -> tapSomething(random));
    }

    /** FX thread: taps one random tappable node; returns what was tapped. */
    private static String tapSomething(Random random) {
        Parent root = app.stage.getScene().getRoot();
        StackPane sheets = (StackPane) field(app.main, "sheetLayer");
        Parent area = sheets != null && sheets.isVisible() && !sheets.getChildren().isEmpty() ? sheets : root;
        List<Node> targets = new ArrayList<>();
        collect(area, targets);
        if (area == sheets && random.nextInt(12) == 0) {
            app.main.closeSheet(); // a tap outside the sheet, or the system back
            return "close sheet";
        }
        if (targets.isEmpty()) {
            return null;
        }
        Node target = targets.get(random.nextInt(targets.size()));
        String name = describe(target);
        if (target instanceof ButtonBase b) {
            b.fire();
        } else {
            Bounds bounds = target.getLayoutBounds();
            double x = bounds.getMinX() + random.nextDouble() * Math.max(1, bounds.getWidth());
            double y = bounds.getMinY() + random.nextDouble() * Math.max(1, bounds.getHeight());
            Point2D scene = target.localToScene(x, y);
            Point2D screen = target.localToScreen(x, y);
            double sx = screen == null ? scene.getX() : screen.getX();
            double sy = screen == null ? scene.getY() : screen.getY();
            for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                target.fireEvent(new MouseEvent(type, scene.getX(), scene.getY(), sx, sy, MouseButton.PRIMARY, 1,
                        false, false, false, false, true, false, false, true, false, true, null));
            }
        }
        return name;
    }

    /** Buttons, toggles and nodes with a tap handler that a person could reach now (visible, enabled, laid out). */
    private static void collect(Node node, List<Node> out) {
        if (!node.isVisible() || node.isDisabled() || node.getOpacity() == 0 || node.isMouseTransparent()) {
            return;
        }
        if (node instanceof ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), out); // skin-independent: scrolling can bring any row into view
        }
        Bounds b = node.getLayoutBounds();
        boolean sized = b.getWidth() > 1 && b.getHeight() > 1;
        if (sized && (node instanceof ButtonBase || node.getOnMouseClicked() != null
                || node.getOnMouseReleased() != null)) {
            out.add(node);
            if (node instanceof ButtonBase) {
                return; // its graphic is part of it
            }
        }
        if (node instanceof Parent p && !(node instanceof ScrollPane)) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private static String describe(Node n) {
        String text = n instanceof javafx.scene.control.Labeled l && l.getText() != null && !l.getText().isBlank()
                ? l.getText() : n instanceof javafx.scene.control.Labeled l2 && l2.getTooltip() != null
                ? l2.getTooltip().getText() : n.getAccessibleText();
        if (text == null || text.isBlank()) {
            text = firstLabel(n);
        }
        return n.getClass().getSimpleName() + (text == null ? "" : " \"" + text.replace('\n', ' ') + "\"");
    }

    private static String firstLabel(Node n) {
        if (n instanceof javafx.scene.control.Labeled l && l.getText() != null && !l.getText().isBlank()) {
            return l.getText();
        }
        if (n instanceof javafx.scene.text.Text t && !t.getText().isBlank()) {
            return t.getText();
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                String s = firstLabel(c);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }
}
