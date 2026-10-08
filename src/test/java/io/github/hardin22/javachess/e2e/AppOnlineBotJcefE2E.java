package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Browser.BrowserSession;
import io.github.hardin22.javachess.Browser.BrowserStatus;
import io.github.hardin22.javachess.Browser.BrowserWindow;
import io.github.hardin22.javachess.Browser.JcefRuntime;
import io.github.hardin22.javachess.Browser.PageDriver;
import io.github.hardin22.javachess.Browser.trial.BotGameTrial;
import io.github.hardin22.javachess.Controllers.BrowserController;
import io.github.hardin22.javachess.Services.BoardStateManager;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static io.github.hardin22.javachess.e2e.E2eHarness.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The user's real flow, end to end: the whole app with the simulated board, the integrated browser on the real
 * site, Play > Computer > a bot > Play (chess.com) or "play against the computer" (lichess), and a whole game: the
 * test plays the user's moves on the simulated board (chosen by a weak Stockfish, as typed in the simulator's
 * field) and reproduces the bot's moves the LEDs ask for. It fails when the app does not move on for 45 s
 * (e.g. "Leggo la scacchiera..." for good, the bug of 8 October 2026 after picking the bot).
 *
 * <p>Only against the sites' computer opponents, never people, as an anonymous guest (fresh browser profile in the
 * test's own data folder). Opt-in: {@code -DskipJcefE2E=false -Dtest=AppOnlineBotJcefE2E -De2e.site=chesscom|lichess
 * -De2e.stockfish=/path}; meant for the Pi box (720x1920 screen). The page's readings at each step are saved in
 * {@code target/e2e-online/} (fixtures for BoardProbeTest).</p>
 */
class AppOnlineBotJcefE2E {

    private static final long STALL_MS = 45_000;
    private static E2eHarness app;
    private static final Path OUT = Path.of("target", "e2e-online");

    @BeforeAll
    static void start() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        assumeTrue(System.getProperty("e2e.stockfish") != null, "-De2e.stockfish=PATH");
        app = E2eHarness.start("sim");
        System.clearProperty("javachess.jcef.dir");
        assumeTrue(Files.isDirectory(JcefRuntime.installDir()), "no JCEF bundle on this computer");
        Files.createDirectories(OUT);
        fx(() -> { // the board's screen: the whole first screen (the Pi box: 720x1920)
            Rectangle2D b = Screen.getPrimary().getBounds();
            app.stage.setX(b.getMinX());
            app.stage.setY(b.getMinY());
            app.stage.setWidth(b.getWidth());
            app.stage.setHeight(b.getHeight());
            return null;
        });
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) {
            app.stop();
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

    @SuppressWarnings("unchecked")
    private static int plies() {
        return ((List<String>) field(field(session(), "sync"), "moves")).size();
    }

    @Test
    void aWholeGameAgainstTheComputer() throws Exception {
        String site = System.getProperty("e2e.site", "chesscom").toLowerCase(Locale.ROOT);
        boolean chesscom = site.startsWith("chess");
        fx(() -> {
            app.main.openBrowser(chesscom ? "https://www.chess.com/play/computer" : "https://lichess.org/");
            return null;
        });
        waitFor("browser shown", () -> window() != null && window().isShowing());
        PageDriver page = window().page();
        List<String> states = new ArrayList<>();
        Runnable note = () -> {
            BrowserStatus s = session().status();
            String line = s.state() + " " + s.title() + " | " + s.detail();
            if (states.isEmpty() || !states.get(states.size() - 1).equals(line)) {
                states.add(line);
                System.out.println("[online] " + java.time.LocalTime.now() + " " + line);
            }
        };

        // the user's clicks on the site: cookies, intro, a bot / the computer, white, Play
        Thread.sleep(4000);
        save(page, site + "-start");
        if (chesscom) {
            // as the user did: the board of /play/computer is read first, then a bot is picked from the list, which
            // changes the address to /play/computer/<bot> on the same page
            waitFor("the board of /play/computer read", () -> {
                note.run();
                BrowserStatus.State st = session().status().state();
                return st == BrowserStatus.State.YOUR_TURN || st == BrowserStatus.State.SETUP;
            });
            if (session().status().state() == BrowserStatus.State.SETUP) {
                arrangeAsLogical();
            }
            String bot = System.getProperty("e2e.bot", "Martin");
            for (int attempt = 0; attempt < 20 && !page.url().contains("-BOT"); attempt++) {
                String r = page.evaluate(PICK_BOT.replace("NAME", bot)).get(10, TimeUnit.SECONDS);
                System.out.println("[online] pick " + bot + ": " + r + " at " + page.url());
                Thread.sleep(1500);
            }
            assertTrue(page.url().contains("-BOT"), "the bot's address, as in the user's flow: " + page.url());
            save(page, site + "-bot-chosen");
            Thread.sleep(3000);
            note.run();
        }
        String startScript = chesscom ? BotGameTrial.CHESSCOM_START.replace("COLOUR", "white")
                : BotGameTrial.LICHESS_START.replace("COLOUR", "white");
        boolean started = false;
        for (int attempt = 0; attempt < 60 && !started; attempt++) {
            String r;
            try {
                r = page.evaluate(startScript).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                r = "no answer " + e;
            }
            System.out.println("[online] start: " + r + " at " + page.url());
            if (page.url().contains("-BOT")) {
                save(page, site + "-bot-chosen");
            }
            started = r.contains("started");
            note.run();
            Thread.sleep(1500);
        }
        assertTrue(started, "the game against the computer did not start; states " + states);
        save(page, site + "-game");

        try (BotGameTrial.Engine engine = new BotGameTrial.Engine(System.getProperty("e2e.stockfish"))) {
            int maxPlies = Integer.getInteger("e2e.plies", 300);
            long lastProgress = System.currentTimeMillis();
            String lastKey = "";
            boolean over = false;
            while (!over) {
                note.run();
                BrowserStatus.State state = session().status().state();
                int plies = plies();
                String key = state + "/" + plies;
                if (!key.equals(lastKey)) {
                    lastKey = key;
                    lastProgress = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastProgress > STALL_MS) {
                    save(page, site + "-stalled");
                    fail("no progress for " + STALL_MS / 1000 + " s in " + state + " after " + plies
                            + " plies; states " + states + "\n" + diagnostics());
                }
                BoardStateManager.Mode mode = boardState().mode();
                switch (state) {
                    case SETUP -> {
                        if (mode == BoardStateManager.Mode.SETUP) {
                            arrangeAsLogical();
                        }
                    }
                    case YOUR_TURN -> {
                        if (mode == BoardStateManager.Mode.PLAY) {
                            String fen = boardState().logicalFen();
                            String uci = engine.bestMove(fen);
                            System.out.println("[online] my move " + uci);
                            physicalMove(uci);
                            waitFor("my move taken", () -> plies() > plies
                                    || session().status().state() != BrowserStatus.State.YOUR_TURN);
                        }
                    }
                    case REPLICATE -> {
                        if (mode == BoardStateManager.Mode.REPLICATE) {
                            Object sync = field(session(), "sync");
                            String from = (String) field(sync, "lastFrom");
                            String to = (String) field(sync, "lastTo");
                            Board after = new Board();
                            after.loadFromFen(boardState().logicalFen());
                            System.out.println("[online] bot moved " + from + to + ", reproduced on the board");
                            reproduce(new Move(Square.valueOf(from.toUpperCase(Locale.ROOT)),
                                    Square.valueOf(to.toUpperCase(Locale.ROOT))), after);
                            waitFor("reproduced", () -> boardState().mode() != BoardStateManager.Mode.REPLICATE);
                        }
                    }
                    case GAME_OVER -> over = true;
                    default -> {
                    }
                }
                if (plies >= maxPlies) {
                    over = true;
                }
                Thread.sleep(300);
            }
            note.run();
            System.out.println("[online] RESULT site=" + site + " plies=" + plies() + " final="
                    + session().status().title() + " states=" + states.size());
            assertTrue(plies() >= 10, "a real game was played: " + plies() + " plies; states " + states);
            assertTrue(states.stream().noneMatch(s -> s.startsWith("NOT_ACCEPTED")), "moves not accepted: " + states);
        }
    }

    /** chess.com's bot list: the bot whose name contains NAME (Martin is the first, weakest one). */
    private static final String PICK_BOT = "(() => {"
            + " const visible = (e) => e && e.getBoundingClientRect().width > 0;"
            + " const reject = [...document.querySelectorAll('button')].filter(visible)"
            + "   .find(b => /^(reject all|rifiuta tutto)$/i.test((b.textContent || '').trim()));"
            + " if (reject) { reject.click(); return 'cookies rejected'; }"
            + " const named = [...document.querySelectorAll('[data-bot-selection-name], [data-bot-name], [aria-label], img[alt]')]"
            + "   .filter(visible).find(e => new RegExp('NAME', 'i').test(e.getAttribute('data-bot-selection-name')"
            + "   || e.getAttribute('data-bot-name') || e.getAttribute('aria-label') || e.getAttribute('alt') || ''));"
            + " if (named) { (named.closest('button, a, li, [role=button]') || named).click(); return 'clicked ' + (named.tagName); }"
            + " return 'no bot named NAME';"
            + "})()";

    /** The page's own reading (probe answer) at this step, as a fixture. */
    private static void save(PageDriver page, String name) {
        try {
            String json = page.evaluate(io.github.hardin22.javachess.Browser.BoardProbe.SCRIPT).get(10, TimeUnit.SECONDS);
            Files.writeString(OUT.resolve(name + ".json"), json);
            System.out.println("[online] saved " + name + " (" + page.url() + ")");
        } catch (Exception e) {
            System.out.println("[online] could not save " + name + ": " + e);
        }
    }
}
