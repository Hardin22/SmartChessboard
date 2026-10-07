package org.example.javachess.Services;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Lichess client against a local fake server: no network needed. */
class LichessClientTest {

    private HttpServer server;
    private LichessClient client;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String authHeader;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            int status = 200;
            String body;
            if (path.equals("/api/account")) {
                body = "{\"id\":\"tester\",\"username\":\"Tester\"}";
            } else if (path.equals("/api/account/playing")) {
                body = "{\"nowPlaying\":[{\"gameId\":\"abc12345\",\"fullId\":\"abc12345wxyz\",\"color\":\"white\"}]}";
            } else if (path.equals("/api/board/game/stream/abc12345")) {
                body = String.join("\n",
                        "{\"type\":\"gameFull\",\"id\":\"abc12345\",\"initialFen\":\"startpos\","
                                + "\"white\":{\"id\":\"tester\",\"name\":\"Tester\",\"rating\":1500},"
                                + "\"black\":{\"aiLevel\":3},"
                                + "\"state\":{\"type\":\"gameState\",\"moves\":\"e2e4\",\"wtime\":600000,"
                                + "\"btime\":600000,\"status\":\"started\"}}",
                        "",
                        "{\"type\":\"gameState\",\"moves\":\"e2e4 e7e5\",\"status\":\"started\"}",
                        "not json",
                        "{\"type\":\"gameState\",\"moves\":\"e2e4 e7e5 d1h5\",\"status\":\"resign\","
                                + "\"winner\":\"white\"}") + "\n";
            } else if (path.endsWith("/move/e2e5")) {
                status = 400;
                body = "{\"error\":\"Not your turn, or game already over\"}";
            } else if (path.contains("/move/")) {
                body = "{\"ok\":true}";
            } else {
                status = 404;
                body = "{\"error\":\"Not found\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        client = new LichessClient("http://127.0.0.1:" + server.getAddress().getPort(), () -> "lip_test");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void accountAndOngoingGame() throws Exception {
        assertEquals("tester", client.getAccountId());
        assertEquals("abc12345", client.findOngoingGameId().orElseThrow());
        assertEquals("Bearer lip_test", authHeader);
    }

    @Test
    void streamsEventsSkippingKeepAlivesAndGarbage() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        client.streamGame("abc12345", new LichessClient.SeekHandle(), events::add);
        assertEquals(3, events.size());
        assertEquals("gameFull", events.get(0).getString("type"));
    }

    @Test
    void moveErrorsBecomeUserMessages() {
        LichessClient.LichessException e = assertThrows(LichessClient.LichessException.class,
                () -> client.makeMove("abc12345", "e2e5"));
        assertEquals(400, e.getStatus());
        assertEquals("Lichess: non è il tuo turno.", e.getMessage());
        assertDoesNotThrow(() -> client.makeMove("abc12345", "e2e4"));
        assertTrue(requests.contains("POST /api/board/game/abc12345/move/e2e4"));
    }

    @Test
    void missingTokenFailsBeforeAnyRequest() {
        LichessClient noToken = new LichessClient("http://127.0.0.1:" + server.getAddress().getPort(), () -> " ");
        LichessClient.LichessException e = assertThrows(LichessClient.LichessException.class, noToken::getAccountId);
        assertTrue(e.getMessage().startsWith("Token Lichess mancante"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void unreachableServerGivesReadableMessage() {
        LichessClient dead = new LichessClient("http://127.0.0.1:9", () -> "lip_test");
        LichessClient.LichessException e = assertThrows(LichessClient.LichessException.class, dead::getAccountId);
        assertEquals(0, e.getStatus());
        assertTrue(e.getMessage().startsWith("Lichess non raggiungibile"), e.getMessage());
    }

    @Test
    void gameManagerFollowsTheGameAndDetectsTheEnd() throws Exception {
        LichessGameManager manager = new LichessGameManager("abc12345", null, client);
        List<String> ended = new CopyOnWriteArrayList<>();
        manager.setUiCallback(new LichessGameManager.UiCallback() {
            public void onConnected() { }
            public void onStatusMessage(String message) { }
            public void onMoveMade(String lastMove) { }
            public void onBoardUpdated(String fen, String lastMove, String errorSquare) { }
            public void onBotMoveReplicated() { }
            public void onGameEnd(String result) { ended.add(result); }
            public void onError(String message) { fail(message); }
        });
        manager.startGame();
        long deadline = System.currentTimeMillis() + 5000;
        while (ended.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(List.of("1-0"), ended);
        assertTrue(manager.isWhite(), "tester is white");
        assertEquals(List.of("e2e4", "e7e5", "d1h5"), manager.getMovesUci());
        assertEquals("Abbandono", manager.getTermination());
        assertEquals("Tester (1500)", manager.getWhiteName());
        assertEquals("Stockfish livello 3", manager.getBlackName());
        manager.stop();
    }

    // ---------------------------------------------------------------- pure parsing

    @Test
    void parsesErrorBodies() {
        assertEquals("Not your turn", LichessClient.parseErrorMessage("{\"error\":\"Not your turn\"}"));
        assertEquals("Invalid time control", LichessClient.parseErrorMessage(
                "{\"error\":{\"time\":[\"Invalid time control\"]}}"));
        assertEquals("", LichessClient.parseErrorMessage(""));
        assertEquals("<html>oops</html>", LichessClient.parseErrorMessage("<html>oops</html>"));
        assertTrue(LichessClient.errorFor(400, "{\"error\":{\"time\":[\"Invalid time control\"]}}").getMessage()
                .contains("rapid o classiche"));
        assertTrue(LichessClient.errorFor(401, "").getMessage().contains("Token Lichess non valido"));
        assertTrue(LichessClient.errorFor(429, "").getMessage().contains("Troppe richieste"));
        assertTrue(LichessClient.errorFor(503, "").getMessage().contains("problema temporaneo"));
    }

    @Test
    void parsesPlayingList() {
        assertEquals(List.of(), LichessClient.parsePlayingGameIds("{\"nowPlaying\":[]}"));
        assertEquals(List.of(), LichessClient.parsePlayingGameIds("garbage"));
        assertEquals(List.of("a", "b"), LichessClient.parsePlayingGameIds(
                "{\"nowPlaying\":[{\"gameId\":\"a\"},{\"gameId\":\"b\"},{\"x\":1}]}"));
    }

    @Test
    void mapsGameStatusToResults() {
        assertEquals("*", state("started", null).result());
        assertFalse(state("started", null).isOver());
        assertEquals("1-0", state("mate", "white").result());
        assertEquals("Scaccomatto", state("mate", "white").termination());
        assertEquals("0-1", state("outoftime", "black").result());
        assertEquals("Tempo", state("outoftime", "black").termination());
        assertEquals("1/2-1/2", state("draw", null).result());
        assertEquals("1/2-1/2", state("stalemate", null).result());
        assertEquals("1/2-1/2", state("outoftime", null).result(), "flag with insufficient material");
        assertEquals("*", state("aborted", null).result());
        assertEquals("Annullata", state("aborted", null).termination());
    }

    private static LichessClient.GameState state(String status, String winner) {
        JSONObject o = new JSONObject().put("moves", "e2e4").put("status", status);
        if (winner != null) {
            o.put("winner", winner);
        }
        return LichessClient.parseGameState(o);
    }

    @Test
    void playerNames() {
        assertEquals("Stockfish livello 8", LichessClient.parsePlayer(new JSONObject("{\"aiLevel\":8}")).displayName());
        assertEquals("bob", LichessClient.parsePlayer(new JSONObject("{\"id\":\"bob\"}")).displayName());
        assertEquals("?", LichessClient.parsePlayer(null).displayName());
    }
}
