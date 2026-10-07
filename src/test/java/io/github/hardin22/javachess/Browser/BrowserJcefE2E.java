package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Services.VisionService;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.BotMover;
import io.github.hardin22.javachess.Vision.PieceClassifier;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * End-to-end with the real browser engine: Chromium (JCEF) shows a local page that imitates the markup of lichess
 * and chess.com ({@code browser/e2e/board.html}) and, like them, only accepts trusted clicks. Checks the probe, the
 * DevTools screenshots read by the vision model, the watcher and BotMover's moves (including a promotion and a
 * board scrolled out of view).
 *
 * <p>Opt-in ({@code -DskipJcefE2E=false}, surefire execution "jcef-e2e"): it needs a display and the JCEF bundle,
 * downloaded on first use (set {@code -Djavachess.jcef.dir} to reuse one).</p>
 */
class BrowserJcefE2E {

    private static CefApp app;
    private static CefBrowser browser;
    private static JFrame frame;
    private static CdpPageDriver page;
    private static String base;

    @BeforeAll
    static void startChromium() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        URL url = BrowserJcefE2E.class.getResource("/browser/e2e/board.html");
        assertNotNull(url);
        base = url.toExternalForm();
        app = JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
            }

            @Override
            public void installing() {
            }
        }).get(15, TimeUnit.MINUTES);
        boolean osr = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
        SwingUtilities.invokeAndWait(() -> {
            CefClient client = app.createClient();
            browser = client.createBrowser(base + "?site=lichess", osr, false);
            frame = new JFrame("javaChess JCEF test");
            frame.add(browser.getUIComponent());
            frame.setBounds(40, 40, 720, 900);
            frame.setVisible(true);
        });
        page = new CdpPageDriver(browser);
        waitFor(s -> s.board() != null, "the test page");
    }

    @AfterAll
    static void stop() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(() -> {
                browser.close(true);
                frame.dispose();
            });
        }
        JcefRuntime.disposeIfStarted();
    }

    private static BoardSnapshot waitFor(Predicate<BoardSnapshot> condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        BoardSnapshot last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                last = BoardProbe.read(page).get(5, TimeUnit.SECONDS);
                if (condition.test(last)) {
                    return last;
                }
            } catch (Exception e) {
                // page loading
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what + " (last: " + last + ")");
    }

    private static BoardSnapshot open(String query) throws Exception {
        SwingUtilities.invokeAndWait(() -> browser.loadURL(base + query));
        String expectedSite = query.contains("chesscom") ? "chesscom" : "lichess";
        return waitFor(s -> s.url().endsWith(query) && s.board() != null && s.board().placement() != null
                && s.site() == ChessSite.fromProbe(expectedSite), query);
    }

    @Test
    void probeReadsBothMarkupsAndOrientations() throws Exception {
        for (String site : List.of("lichess", "chesscom")) {
            for (boolean black : List.of(false, true)) {
                String fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R";
                BoardSnapshot s = open("?site=" + site + "&orientation=" + (black ? "black" : "white") + "&fen=" + fen);
                assertEquals(fen, s.board().placement(), site + " " + black);
                assertEquals(black, s.board().flipped(), site);
                assertEquals(640, s.board().rect().w(), 1, site);
            }
        }
    }

    @Test
    void botMoverPlaysWithTrustedClicks() throws Exception {
        for (String site : List.of("lichess", "chesscom")) {
            for (boolean black : List.of(false, true)) {
                open("?site=" + site + "&orientation=" + (black ? "black" : "white"));
                new BotMover(page).play("g1f3").get(10, TimeUnit.SECONDS);
                BoardSnapshot s = waitFor(x -> x.board() != null && x.board().placement() != null
                        && x.board().placement().contains("5N2"), site + " Nf3");
                assertEquals("rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R", s.board().placement());
                assertEquals(List.of("g1", "f3"), s.board().lastMove(), site);
            }
        }
    }

    @Test
    void promotionThroughTheSitesMenu() throws Exception {
        open("?site=lichess&fen=8/1P4k1/8/8/8/8/6K1/8");
        new BotMover(page).play("b7b8n").get(10, TimeUnit.SECONDS);
        BoardSnapshot s = waitFor(x -> x.board().placement().startsWith("1N"), "underpromotion to a knight");
        assertEquals("1N6/6k1/8/8/8/8/6K1/8", s.board().placement());
    }

    @Test
    void aBoardScrolledOutOfViewIsBroughtBackBeforeMoving() throws Exception {
        BoardSnapshot s = open("?site=chesscom&top=1400");
        assertFalse(s.board().rect().inside(s.viewportWidth(), s.viewportHeight()));
        new BotMover(page).play("e2e4").get(10, TimeUnit.SECONDS);
        BoardSnapshot after = waitFor(x -> x.board().placement().contains("4P3"), "e4 after scrolling");
        assertTrue(after.board().rect().inside(after.viewportWidth(), after.viewportHeight()));
    }

    @Test
    void visionReadsTheScreenshotOfTheBoard() throws Exception {
        PieceClassifier classifier = new PieceClassifier(PieceClassifier.DEFAULT_MODEL);
        try {
            for (boolean black : List.of(false, true)) {
                String fen = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R";
                BoardSnapshot s = open("?site=lichess&orientation=" + (black ? "black" : "white") + "&fen=" + fen);
                BufferedImage img = page.screenshot(s.board().rect()).get(10, TimeUnit.SECONDS);
                assertTrue(img.getWidth() >= 640, "device pixels: " + img.getWidth());
                java.io.File dir = new java.io.File("target/jcef-e2e");
                dir.mkdirs();
                javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, "board-" + black + ".png"));
                BoardReading r = classifier.read(img, black, new java.io.File(dir, "board-" + black + "-debug.png")
                        .getPath()).withPlacementRules();
                System.out.printf("[jcef-e2e] vision black=%s: %s (min confidence %.2f)%n", black, r.placement(),
                        r.minConfidence());
                assertEquals(fen, r.placement(), "vision on Chromium's own picture, black=" + black);
            }
        } finally {
            classifier.close();
        }
    }

    @Test
    void theWatcherReportsMovesFromVisionAndPage() throws Exception {
        open("?site=chesscom");
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        List<BoardWatcher.PositionUpdate> updates = new CopyOnWriteArrayList<>();
        BoardWatcher w = new BoardWatcher(page, new VisionService(), exec, new BoardWatcher.Listener() {
            @Override
            public void onSnapshot(BoardSnapshot snapshot) {
            }

            @Override
            public void onPosition(BoardWatcher.PositionUpdate update) {
                updates.add(update);
            }

            @Override
            public void onProblem(BoardWatcher.Problem problem) {
            }
        }, BoardWatcher.ReadMode.VISION);
        try {
            w.setIntervalMs(150);
            w.start();
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && (updates.isEmpty()
                    || updates.get(updates.size() - 1).vision() == null)) {
                Thread.sleep(100);
            }
            new BotMover(page).play("d2d4").get(10, TimeUnit.SECONDS);
            String target = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR";
            while (System.currentTimeMillis() < deadline && !updates.isEmpty()
                    && !(target.equals(placement(updates.get(updates.size() - 1).vision()))
                    && target.equals(placement(updates.get(updates.size() - 1).page())))) {
                Thread.sleep(100);
            }
            BoardWatcher.PositionUpdate last = updates.get(updates.size() - 1);
            assertEquals(target, placement(last.vision()), "vision");
            assertEquals(target, placement(last.page()), "page");
            long withTarget = updates.stream().filter(u -> target.equals(placement(u.vision()))).count();
            assertTrue(withTarget >= 1);
        } finally {
            w.stop();
            exec.shutdownNow();
        }
    }

    private static String placement(BoardReading r) {
        return r == null ? null : r.placement();
    }
}
