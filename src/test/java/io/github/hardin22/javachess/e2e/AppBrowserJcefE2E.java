package io.github.hardin22.javachess.e2e;

import io.github.hardin22.javachess.Browser.BrowserSession;
import io.github.hardin22.javachess.Browser.BrowserStatus;
import io.github.hardin22.javachess.Browser.BrowserWindow;
import io.github.hardin22.javachess.Browser.JcefRuntime;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Controllers.BrowserController;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.hardin22.javachess.e2e.E2eHarness.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole app with the real integrated browser (Chromium) and the simulated board: the integrated browser and the
 * other screens share the physical board, the window and the archive. Opt-in like the other Chromium tests
 * ({@code -DskipJcefE2E=false -Dtest=AppBrowserJcefE2E}): needs a display and the JCEF bundle of this computer.
 *
 * <ol>
 *   <li>QA-034: the user leaves while Chromium is still starting and starts a game against the computer; when
 *       Chromium is ready its window must stay hidden and the game must keep the board.</li>
 *   <li>Home → browser on a local page imitating lichess → follow it with the board → set up → a move on the sensors
 *       is played on the page → Home (board released, window hidden) → a game against the computer on the board →
 *       the browser again (same page, window back).</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppBrowserJcefE2E {

    private static E2eHarness app;
    private static String page;
    private static final List<String> STATES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        app = E2eHarness.start("sim");
        System.clearProperty("javachess.jcef.dir"); // this test wants the real Chromium bundle of the computer
        assumeTrue(java.nio.file.Files.isDirectory(JcefRuntime.installDir()), "no JCEF bundle on this computer");
        boardState().setTimings(15, 300, 150);
        URL url = AppBrowserJcefE2E.class.getResource("/browser/e2e/board.html");
        assertNotNull(url);
        page = url.toExternalForm() + "?site=lichess";
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) {
            app.stop(); // Chromium ends with the JVM, as in the app
        }
    }

    private static BrowserController browser() {
        return (BrowserController) fxGet(() -> app.main.getController("BROWSER"));
    }

    private static BrowserSession session() {
        return (BrowserSession) field(browser(), "session");
    }

    private static BrowserWindow window() {
        return (BrowserWindow) field(browser(), "window");
    }

    private static BrowserStatus.State state() {
        BrowserStatus.State s = session().status().state();
        if (STATES.isEmpty() || !STATES.get(STATES.size() - 1).equals(s.name())) {
            STATES.add(s.name());
        }
        return s;
    }

    private static boolean showing() {
        BrowserWindow w = window();
        return w != null && w.isShowing();
    }

    /** Sets the pieces up and plays e2e4 on the board in a game against the computer; true when the game took it. */
    private static void playE4AgainstTheBot() throws Exception {
        app.bot("e7e5");
        sim().setOccupancy(0xFFFF_0000_0000_FFFFL);
        ActiveGameController game = app.startPvc(true);
        waitForMode(BoardStateManager.Mode.PLAY);
        playOnBoard(game, "e2e4", 1);
        reproduceLastMove(game); // the bot's e7e5 on the LEDs, reproduced
        assertEquals(2, plies(game));
    }

    @Test
    @Order(1)
    void leavingWhileChromiumStartsKeepsTheWindowHiddenAndTheBoardWithTheGame() throws Exception {
        assumeTrue(JcefRuntime.app() == null, "Chromium must start in this test");
        fx(() -> {
            app.main.openBrowser(page);
            return null;
        });
        BrowserController controller = browser();
        fx(() -> invoke(controller, "onBack")); // "Indietro" on the start-up view
        waitFor("back home", () -> "HOME".equals(fxGet(app.main::getCurrentViewName)));

        // the game against the computer starts while Chromium is still starting
        app.bot("e7e5");
        sim().setOccupancy(0xFFFF_0000_0000_FFFFL);
        ActiveGameController game = app.startPvc(true);
        waitForMode(BoardStateManager.Mode.PLAY);
        waitFor("Chromium ready", () -> JcefRuntime.app() != null && window() != null);
        Thread.sleep(2500); // the window would have been shown and the page read by now
        assertFalse(showing(), "the browser window stays hidden after the user left");
        assertEquals("GAME", fxGet(app.main::getCurrentViewName));
        playOnBoard(game, "e2e4", 1); // the board still belongs to the game
        reproduceLastMove(game);
        assertEquals(2, plies(game));
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
        waitForMode(BoardStateManager.Mode.IDLE);
    }

    @Test
    @Order(2)
    void theBrowserAndTheOtherScreensShareTheBoardAndTheWindow() throws Exception {
        sim().setOccupancy(0);
        fx(() -> {
            app.main.openBrowser(page);
            return null;
        });
        waitFor("browser window shown", AppBrowserJcefE2E::showing);
        waitFor("board found on the page, states " + STATES, () -> state() == BrowserStatus.State.BOARD_FOUND
                || state() == BrowserStatus.State.SETUP);
        if (state() == BrowserStatus.State.BOARD_FOUND) {
            session().perform(BrowserStatus.Action.SYNC_START); // "Collega"
        }
        waitFor("set-up asked, states " + STATES, () -> state() == BrowserStatus.State.SETUP);
        sim().setOccupancy(0xFFFF_0000_0000_FFFFL); // the pieces as the LEDs ask
        waitFor("your turn, states " + STATES, () -> state() == BrowserStatus.State.YOUR_TURN);
        waitForMode(BoardStateManager.Mode.PLAY);
        physicalMove("e2e4");
        // a board that is not a game (like the analysis board): either side moves next, so the state goes back to
        // "Tocca a te" once the page shows the move
        waitFor("move played on the page, states " + STATES, () -> ((List<?>) field(field(session(), "sync"),
                "moves")).size() == 1 && state() == BrowserStatus.State.YOUR_TURN);
        assertFalse(STATES.contains(BrowserStatus.State.NOT_ACCEPTED.name()), "states " + STATES);

        // Home from the bar above the page: board released, window hidden, the app in front
        session().perform(BrowserStatus.Action.BACK_HOME);
        waitFor("home", () -> "HOME".equals(fxGet(app.main::getCurrentViewName)) && !showing());
        waitForMode(BoardStateManager.Mode.IDLE);

        // the board serves a game against the computer as usual
        playE4AgainstTheBot();
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
        waitForMode(BoardStateManager.Mode.IDLE);

        // and the browser comes back on the same page
        fx(() -> {
            app.main.openBrowser(page);
            return null;
        });
        waitFor("browser window shown again", AppBrowserJcefE2E::showing);
        session().perform(BrowserStatus.Action.BACK_HOME);
        waitFor("home again", () -> "HOME".equals(fxGet(app.main::getCurrentViewName)) && !showing());
        System.out.println("[app browser] states " + STATES);
    }
}
