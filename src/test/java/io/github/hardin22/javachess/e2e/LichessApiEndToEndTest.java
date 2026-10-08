package io.github.hardin22.javachess.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.OnlineGame;
import io.github.hardin22.javachess.Utils.ConfigManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.github.hardin22.javachess.e2e.E2eHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Lichess Board API flow (Settings → Advanced → Lichess API) against a local fake Lichess: a game in progress is
 * resumed, left and resumed again, then resigned from the screen while playing Black (QA-011). No real account, no
 * network.
 */
class LichessApiEndToEndTest {

    private static E2eHarness app;
    private static HttpServer lichess;
    private static final List<String> commands = new CopyOnWriteArrayList<>();
    private static volatile BlockingQueue<String> stream;
    private static volatile String moves = "e2e4 e7e5 g1f3";
    private static volatile String status = "started";

    @BeforeAll
    static void start() throws Exception {
        lichess = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lichess.createContext("/", LichessApiEndToEndTest::handle);
        lichess.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        lichess.start();
        System.setProperty("javachess.lichess.url", "http://127.0.0.1:" + lichess.getAddress().getPort());
        app = E2eHarness.start("off");
        ConfigManager.setProperty(ConfigManager.LICHESS_TOKEN, "fake-token-for-the-fake-server");
        ConfigManager.setProperty("lichess.username", "");
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) {
            app.stop();
        }
        if (lichess != null) {
            lichess.stop(0);
        }
    }

    private static String state() {
        return "{\"type\":\"gameState\",\"moves\":\"" + moves + "\",\"wtime\":600000,\"btime\":600000,\"status\":\""
                + status + "\"" + ("resign".equals(status) ? ",\"winner\":\"white\"" : "") + "}";
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (method.equals("POST")) {
            commands.add(path);
        }
        if (path.equals("/api/account")) {
            reply(ex, "{\"id\":\"me\",\"username\":\"Me\"}");
        } else if (path.equals("/api/account/playing")) {
            reply(ex, "resign".equals(status) ? "{\"nowPlaying\":[]}"
                    : "{\"nowPlaying\":[{\"gameId\":\"g1\",\"fullId\":\"g1xxxx\",\"color\":\"black\"}]}");
        } else if (path.equals("/api/board/game/stream/g1")) {
            BlockingQueue<String> events = new LinkedBlockingQueue<>();
            stream = events;
            ex.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                write(os, "{\"type\":\"gameFull\",\"id\":\"g1\",\"initialFen\":\"startpos\",\"speed\":\"rapid\","
                        + "\"white\":{\"id\":\"opp\",\"name\":\"Opp\",\"rating\":1500},"
                        + "\"black\":{\"id\":\"me\",\"name\":\"Me\",\"rating\":1500},\"state\":" + state() + "}");
                while (true) {
                    String event = events.poll(200, TimeUnit.MILLISECONDS);
                    if (event == null) {
                        write(os, ""); // keep-alive, like Lichess
                        continue;
                    }
                    write(os, event);
                    if (event.contains("\"resign\"")) {
                        return;
                    }
                }
            } catch (IOException | InterruptedException closedByTheApp) {
                // the app left the game: the stream is closed
            }
        } else if (path.equals("/api/board/game/g1/resign")) {
            status = "resign";
            reply(ex, "{\"ok\":true}");
            BlockingQueue<String> events = stream;
            if (events != null) {
                events.add(state());
            }
        } else if (path.startsWith("/api/board/game/g1/move/")) {
            moves = moves + " " + path.substring(path.lastIndexOf('/') + 1);
            reply(ex, "{\"ok\":true}");
            BlockingQueue<String> events = stream;
            if (events != null) {
                events.add(state());
            }
        } else {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        }
    }

    private static void write(OutputStream os, String line) throws IOException {
        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void aGameLeftOnLichessIsResumedAndResignedWithTheRightResult() throws Exception {
        ActiveGameController game = (ActiveGameController) fxGet(() -> app.main.getController("GAME"));
        // Settings → Advanced → "Lichess API": the game in progress opens
        fx(() -> {
            app.main.openLichess();
            return null;
        });
        waitFor("online game connected", () -> fxGet(() -> E2eHarness.game(game) instanceof OnlineGame o
                && o.isRunning() && !o.isPlayingWhite() && o.getBoard().getBackup().size() == 3));
        // the player's move on the screen goes to Lichess
        fx(() -> {
            E2eHarness.game(game).handleMoveInput("b8c6");
            return null;
        });
        waitFor("move sent", () -> commands.contains("/api/board/game/g1/move/b8c6"));
        waitFor("move shown", () -> fxGet(() -> E2eHarness.game(game).getBoard().getBackup().size() == 4));

        // leaving the screen does not end the game on Lichess; the same entry resumes it
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
        assertFalse(commands.stream().anyMatch(c -> c.endsWith("/resign") || c.endsWith("/abort")));
        fx(() -> {
            app.main.openLichess();
            return null;
        });
        waitFor("game resumed", () -> fxGet(() -> "GAME".equals(app.main.getCurrentViewName())
                && E2eHarness.game(game) instanceof OnlineGame o && o.isRunning()
                && o.getBoard().getBackup().size() == 4));

        // Resign (enabled for online games now), confirmed: sent to Lichess, the end comes from the stream
        assertFalse(fxGet(() -> ((javafx.scene.control.Button) field(field(game, "solo"), "resignButton"))
                .isDisabled()), "Resign is available in an online game");
        fx(() -> invoke(game, "requestResign"));
        app.fireButton(I18n.t("game.resign.confirm.ok"));
        waitFor("resignation sent", () -> commands.contains("/api/board/game/g1/resign"));
        waitFor("game over", () -> fxGet(() -> !E2eHarness.game(game).isRunning()));

        ArchivedGame archived = waitForArchived(1);
        assertEquals(ArchivedGame.GameMode.LICHESS, archived.mode());
        assertEquals("1-0", archived.result(), "White wins: the player (Black) resigned");
        assertEquals(List.of("e2e4", "e7e5", "g1f3", "b8c6"), archived.movesUci());
        // the end card speaks to the player who had Black
        assertEquals(Boolean.FALSE, fxGet(() -> field(game, "humanWhite")));
        String end = fxGet(() -> (String) field(game, "endMessage"));
        assertTrue(end.contains("1-0"), end);
    }
}
