package org.example.javachess.Services;

import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Utils.ErrorReporter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Small client for the Lichess Board API (https://lichess.org/api#tag/Board).
 *
 * <ul>
 *   <li>The token is read from the settings on every request, so a new token works without restarting.</li>
 *   <li>Failures become a {@link LichessException} whose message is meant for the user (Italian), never a stack trace
 *       or raw JSON. The token is never logged.</li>
 *   <li>Streams ({@link #streamGame}) run on the caller's thread and stop when the returned handle is closed.</li>
 * </ul>
 * The JSON parsing helpers are static and pure so they can be unit-tested without network.
 */
public class LichessClient {

    private static final Logger log = LoggerFactory.getLogger(LichessClient.class);
    public static final String DEFAULT_BASE_URL = "https://lichess.org";

    private final HttpClient http;
    private final String baseUrl;
    private final Supplier<String> tokenSupplier;

    public LichessClient() {
        this(DEFAULT_BASE_URL, () -> ConfigManager.getProperty(ConfigManager.LICHESS_TOKEN));
    }

    public LichessClient(String baseUrl, Supplier<String> tokenSupplier) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokenSupplier = tokenSupplier;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** A failure with a message that can be shown to the user as is. */
    public static class LichessException extends Exception {
        private final int status;

        public LichessException(String userMessage, int status, Throwable cause) {
            super(userMessage, cause);
            this.status = status;
        }

        /** HTTP status, or 0 for network errors. */
        public int getStatus() {
            return status;
        }
    }

    // =================================================================== account

    /** Lichess id (lower-case username) of the token owner. */
    public String getAccountId() throws LichessException {
        JSONObject account = parseObject(send(get("/api/account"), Duration.ofSeconds(15)));
        String id = account.optString("id", "");
        if (id.isEmpty()) {
            throw new LichessException("Risposta di Lichess non valida (account senza id)", 200, null);
        }
        return id;
    }

    /** Id of the first game in progress for the token owner, if any. */
    public Optional<String> findOngoingGameId() throws LichessException {
        List<String> ids = findOngoingGameIds();
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** Ids of the games in progress for the token owner (most urgent first). */
    public List<String> findOngoingGameIds() throws LichessException {
        return parsePlayingGameIds(send(get("/api/account/playing?nb=20"), Duration.ofSeconds(15)));
    }

    // =================================================================== seek

    /**
     * Creates a real-time seek and blocks until an opponent accepts it (Lichess keeps the connection open and closes
     * it when the game starts) or {@code cancel} is closed. Returns the id of the new game.
     *
     * <p>The Board API only accepts rapid and classical time controls (roughly {@code minutes*60 + 40*increment >= 480}).
     */
    public String seek(int minutes, int incrementSeconds, boolean rated, String color, SeekHandle cancel)
            throws LichessException {
        String c = color == null ? "random" : color.toLowerCase(Locale.ROOT);
        if (!c.equals("white") && !c.equals("black")) {
            c = "random";
        }
        String form = "rated=" + rated + "&time=" + minutes + "&increment=" + incrementSeconds + "&color=" + c;
        HttpRequest request = authorized(baseUrl + "/api/board/seek")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        // Also validates the token before seeking.
        List<String> before = findOngoingGameIds();
        log.info("Lichess seek {}+{} rated={} color={}", minutes, incrementSeconds, rated, c);
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String body = readAll(response.body());
                throw errorFor(response.statusCode(), body);
            }
            cancel.attach(response.body());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        // Correspondence seeks answer {"id": seekId}; real-time ones only send keep-alives.
                        log.debug("Seek stream: {}", line);
                    }
                }
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new LichessException("Ricerca annullata", 0, e);
            }
            throw new LichessException("Connessione a Lichess interrotta: " + ErrorReporter.userMessage(e), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LichessException("Ricerca annullata", 0, e);
        }
        if (cancel.isCancelled()) {
            throw new LichessException("Ricerca annullata", 0, null);
        }
        // The seek connection closed: a game started. Find it (it may take a moment to appear).
        for (int attempt = 0; attempt < 5; attempt++) {
            for (String id : findOngoingGameIds()) {
                if (!before.contains(id)) {
                    return id;
                }
            }
            sleepQuietly(700);
        }
        throw new LichessException("Nessun avversario trovato. Riprova o scegli un altro tempo di gioco.", 0, null);
    }

    /** Cancels a running {@link #seek} from another thread. */
    public static class SeekHandle implements Closeable {
        private volatile InputStream stream;
        private volatile boolean cancelled;

        void attach(InputStream s) throws IOException {
            stream = s;
            if (cancelled) {
                s.close();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void close() {
            cancelled = true;
            InputStream s = stream;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }
    }

    // =================================================================== game

    /**
     * Streams the events of a game (NDJSON) to {@code onEvent} until the game ends, the server closes the stream or
     * {@code handle} is closed. Runs on the calling thread.
     */
    public void streamGame(String gameId, SeekHandle handle, Consumer<JSONObject> onEvent) throws LichessException {
        HttpRequest request = authorized(baseUrl + "/api/board/game/stream/" + encode(gameId)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw errorFor(response.statusCode(), readAll(response.body()));
            }
            handle.attach(response.body());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!handle.isCancelled() && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue; // keep-alive
                    }
                    try {
                        onEvent.accept(new JSONObject(line));
                    } catch (JSONException e) {
                        log.warn("Ignoring malformed Lichess event: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                throw new LichessException("Connessione alla partita persa: " + ErrorReporter.userMessage(e), 0, e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Plays a move (UCI, e.g. e2e4 or e7e8q). */
    public void makeMove(String gameId, String uci) throws LichessException {
        post("/api/board/game/" + encode(gameId) + "/move/" + encode(uci));
    }

    public void resign(String gameId) throws LichessException {
        post("/api/board/game/" + encode(gameId) + "/resign");
    }

    public void abort(String gameId) throws LichessException {
        post("/api/board/game/" + encode(gameId) + "/abort");
    }

    /** Offers or accepts a draw. */
    public void offerDraw(String gameId) throws LichessException {
        post("/api/board/game/" + encode(gameId) + "/draw/yes");
    }

    private void post(String path) throws LichessException {
        send(authorized(baseUrl + path).POST(HttpRequest.BodyPublishers.noBody()).build(), Duration.ofSeconds(15));
    }

    // =================================================================== HTTP plumbing

    private HttpRequest get(String path) throws LichessException {
        return authorized(baseUrl + path).GET().build();
    }

    private HttpRequest.Builder authorized(String url) throws LichessException {
        String token = tokenSupplier.get();
        if (token == null || token.isBlank()) {
            throw new LichessException(
                    "Token Lichess mancante: inseriscilo nelle Impostazioni (serve il permesso \"board:play\").",
                    401, null);
        }
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token.trim())
                .header("Accept", "application/json")
                .header("User-Agent", "javaChess smart board (+https://github.com/Hardin22/SmartChessboard)");
    }

    private String send(HttpRequest request, Duration timeout) throws LichessException {
        HttpRequest timed = HttpRequest.newBuilder(request, (k, v) -> true).timeout(timeout).build();
        try {
            HttpResponse<String> response = http.send(timed, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw errorFor(response.statusCode(), response.body());
            }
            return response.body();
        } catch (IOException e) {
            log.warn("Lichess request {} failed: {}", request.uri().getPath(), e.toString());
            throw new LichessException("Lichess non raggiungibile: " + ErrorReporter.userMessage(e), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LichessException("Richiesta interrotta", 0, e);
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =================================================================== parsing (pure, unit-tested)

    /** Turns an HTTP error into a user message. */
    static LichessException errorFor(int status, String body) {
        String detail = parseErrorMessage(body);
        log.warn("Lichess HTTP {}: {}", status, detail);
        String msg = switch (status) {
            case 401 -> "Token Lichess non valido o scaduto: aggiornalo nelle Impostazioni.";
            case 403 -> "Il token Lichess non ha il permesso \"board:play\": creane uno nuovo su lichess.org/account/oauth/token.";
            case 404 -> "Partita non trovata su Lichess.";
            case 429 -> "Troppe richieste a Lichess: attendi un minuto e riprova.";
            case 400 -> detail.isEmpty() ? "Richiesta rifiutata da Lichess." : "Lichess: " + translate(detail);
            default -> status >= 500 ? "Lichess ha un problema temporaneo (errore " + status + "), riprova più tardi."
                    : "Errore Lichess " + status + (detail.isEmpty() ? "" : ": " + translate(detail));
        };
        return new LichessException(msg, status, null);
    }

    /** Extracts the "error" text from a Lichess error body ({"error": "..."} or {"error": {"field": ["msg"]}}). */
    static String parseErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(body.trim());
            Object err = o.opt("error");
            if (err instanceof String s) {
                return s;
            }
            if (err instanceof JSONObject fields) {
                List<String> parts = new ArrayList<>();
                for (String key : fields.keySet()) {
                    Object v = fields.get(key);
                    if (v instanceof JSONArray arr) {
                        for (int i = 0; i < arr.length(); i++) {
                            parts.add(arr.optString(i));
                        }
                    } else {
                        parts.add(String.valueOf(v));
                    }
                }
                return String.join("; ", parts);
            }
            return "";
        } catch (JSONException e) {
            String t = body.trim();
            return t.length() > 200 ? t.substring(0, 200) : t;
        }
    }

    private static String translate(String detail) {
        String d = detail.toLowerCase(Locale.ROOT);
        if (d.contains("time control") || d.contains("too fast") || d.contains("rapid")) {
            return "tempo di gioco non ammesso dalla Board API (servono partite rapid o classiche, almeno 8 minuti).";
        }
        if (d.contains("not your turn")) {
            return "non è il tuo turno.";
        }
        if (d.contains("illegal") || d.contains("invalid move")) {
            return "mossa non valida.";
        }
        return detail;
    }

    /** Game ids from the /api/account/playing response (empty list when none or malformed). */
    static List<String> parsePlayingGameIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray now = new JSONObject(body).optJSONArray("nowPlaying");
            if (now == null) {
                return ids;
            }
            for (int i = 0; i < now.length(); i++) {
                JSONObject g = now.optJSONObject(i);
                String id = g == null ? "" : g.optString("gameId", "");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        } catch (JSONException e) {
            log.warn("Malformed /api/account/playing response");
        }
        return ids;
    }

    private static JSONObject parseObject(String body) throws LichessException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new LichessException("Risposta di Lichess non valida", 200, e);
        }
    }

    /** State of a game as reported by "gameFull" / "gameState" events. */
    public record GameState(List<String> movesUci, String status, String winner, long whiteTimeMs, long blackTimeMs) {

        public boolean isOver() {
            return !(status.equals("created") || status.equals("started"));
        }

        /** "1-0", "0-1", "1/2-1/2" or "*" (aborted / not finished). */
        public String result() {
            if (!isOver() || status.equals("aborted") || status.equals("noStart")) {
                return "*";
            }
            if ("white".equals(winner)) {
                return "1-0";
            }
            if ("black".equals(winner)) {
                return "0-1";
            }
            return status.equals("unknownFinish") ? "*" : "1/2-1/2";
        }

        /** Italian description of how the game ended. */
        public String termination() {
            return switch (status) {
                case "mate" -> "Scaccomatto";
                case "resign" -> "Abbandono";
                case "stalemate" -> "Stallo";
                case "timeout" -> "Abbandono per disconnessione";
                case "draw" -> "Patta";
                case "outoftime" -> "Tempo";
                case "aborted" -> "Annullata";
                case "noStart" -> "Non iniziata";
                case "cheat" -> "Irregolarità";
                case "variantEnd" -> "Fine variante";
                case "insufficientMaterialClaim" -> "Materiale insufficiente";
                case "created", "started" -> "";
                default -> "Terminata";
            };
        }
    }

    /** Parses the "state" part of a gameFull event or a gameState event. */
    public static GameState parseGameState(JSONObject state) {
        String moves = state.optString("moves", "").trim();
        List<String> list = moves.isEmpty() ? List.of() : Arrays.asList(moves.split("\\s+"));
        return new GameState(list, state.optString("status", "started"), state.optString("winner", null),
                state.optLong("wtime", -1), state.optLong("btime", -1));
    }

    /** Player info from a gameFull event: id (may be empty for the AI) and display name. */
    public record Player(String id, String name, int rating, int aiLevel) {
        public String displayName() {
            if (aiLevel > 0) {
                return "Stockfish livello " + aiLevel;
            }
            String n = name.isEmpty() ? (id.isEmpty() ? "?" : id) : name;
            return rating > 0 ? n + " (" + rating + ")" : n;
        }
    }

    public static Player parsePlayer(JSONObject p) {
        if (p == null) {
            return new Player("", "?", 0, 0);
        }
        return new Player(p.optString("id", ""), p.optString("name", ""), p.optInt("rating", 0),
                p.optInt("aiLevel", 0));
    }
}
