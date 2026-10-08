package io.github.hardin22.javachess.Services;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Commands of a Lichess Board API game, against a recording client (no network). */
class LichessGameManagerTest {

    /** Records the commands instead of sending them. */
    private static final class RecordingClient extends LichessClient {
        final List<String> calls = new CopyOnWriteArrayList<>();

        RecordingClient() {
            super("http://127.0.0.1:9", () -> "");
        }

        @Override
        public void resign(String gameId) {
            calls.add("resign " + gameId);
        }

        @Override
        public void abort(String gameId) {
            calls.add("abort " + gameId);
        }
    }

    private static JSONObject gameFull(String moves) {
        return new JSONObject("{\"type\":\"gameFull\",\"id\":\"g1\",\"initialFen\":\"startpos\","
                + "\"white\":{\"id\":\"me\",\"name\":\"Me\"},\"black\":{\"aiLevel\":2},"
                + "\"state\":{\"type\":\"gameState\",\"moves\":\"" + moves + "\",\"status\":\"started\"}}");
    }

    private static List<String> callsAfterGiveUp(String moves) throws Exception {
        RecordingClient client = new RecordingClient();
        LichessGameManager manager = new LichessGameManager("g1", null, client);
        manager.processEvent(gameFull(moves));
        manager.resignOrAbort();
        manager.stop(); // queued commands still go out after stop (leaving the screen right after resigning)
        for (int i = 0; i < 50 && client.calls.isEmpty(); i++) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return client.calls;
    }

    @Test
    void givingUpBeforeBothSidesMovedAbortsTheGame() throws Exception {
        // QA-011: Lichess refuses a resignation before both players moved; only an abort ends the game
        assertEquals(List.of("abort g1"), callsAfterGiveUp(""));
        assertEquals(List.of("abort g1"), callsAfterGiveUp("e2e4"));
    }

    @Test
    void givingUpLaterResigns() throws Exception {
        assertEquals(List.of("resign g1"), callsAfterGiveUp("e2e4 e7e5"));
    }
}
