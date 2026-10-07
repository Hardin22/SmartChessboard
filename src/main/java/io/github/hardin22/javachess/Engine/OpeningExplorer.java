package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Utils.ConfigManager;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Opening names from the Lichess opening explorer, asynchronous with short timeouts and a cache.
 * After a failure (offline, rate limit, auth required) lookups are skipped for a minute, so a missing network
 * never slows the game down. Replaces the blocking {@code getOpeningName} of the old engine wrappers.
 */
public final class OpeningExplorer {

    private static final Logger log = LoggerFactory.getLogger(OpeningExplorer.class);
    private static final String URL = "https://explorer.lichess.ovh/masters?moves=0&topGames=0&fen=";
    private static final long BACKOFF_MS = 60_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500))
            .executor(Executors.newSingleThreadExecutor(UciClient.daemonFactory("opening-explorer")))
            .build();
    private static final Map<String, Optional<String>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> e) {
            return size() > 512;
        }
    };
    private static volatile long disabledUntil;

    private OpeningExplorer() {
    }

    /** "ECO Name" of the position (e.g. "C50 Italian Game"), or empty. Never fails, never blocks. */
    public static CompletableFuture<Optional<String>> lookup(String fen) {
        if (fen == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String key = MoveCoach.key(fen);
        synchronized (CACHE) {
            Optional<String> hit = CACHE.get(key);
            if (hit != null) {
                return CompletableFuture.completedFuture(hit);
            }
        }
        if (System.currentTimeMillis() < disabledUntil) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(URL + URLEncoder.encode(fen, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(3)).header("Accept", "application/json").GET();
        String token = ConfigManager.getProperty("lichess.token", "");
        if (token != null && !token.isBlank()) {
            req.header("Authorization", "Bearer " + token.trim());
        }
        return HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).handle((resp, err) -> {
            if (err != null || resp.statusCode() != 200) {
                disabledUntil = System.currentTimeMillis() + BACKOFF_MS;
                log.debug("opening explorer unavailable ({}), retry in {} s",
                        err != null ? err.toString() : "HTTP " + resp.statusCode(), BACKOFF_MS / 1000);
                return Optional.<String>empty();
            }
            Optional<String> name = parse(resp.body());
            synchronized (CACHE) {
                CACHE.put(key, name);
            }
            return name;
        });
    }

    /**
     * Number of Lichess masters games in which {@code uci} was played from {@code fen} (0 when unknown,
     * offline or rate limited). Used by the review to label book moves.
     */
    public static CompletableFuture<Integer> masterGames(String fen, String uci) {
        if (fen == null || uci == null || System.currentTimeMillis() < disabledUntil) {
            return CompletableFuture.completedFuture(0);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(
                        "https://explorer.lichess.ovh/masters?topGames=0&fen=" + URLEncoder.encode(fen, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(2)).header("Accept", "application/json").GET();
        String token = ConfigManager.getProperty("lichess.token", "");
        if (token != null && !token.isBlank()) {
            req.header("Authorization", "Bearer " + token.trim());
        }
        return HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).handle((resp, err) -> {
            if (err != null || resp.statusCode() != 200) {
                disabledUntil = System.currentTimeMillis() + BACKOFF_MS;
                return 0;
            }
            try {
                JSONObject json = new JSONObject(resp.body());
                org.json.JSONArray moves = json.optJSONArray("moves");
                if (moves != null) {
                    for (int i = 0; i < moves.length(); i++) {
                        JSONObject m = moves.getJSONObject(i);
                        if (uci.equals(m.optString("uci"))) {
                            return m.optInt("white") + m.optInt("draws") + m.optInt("black");
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("bad explorer response: {}", e.toString());
            }
            return 0;
        });
    }

    static Optional<String> parse(String body) {
        try {
            if (body == null || !body.trim().startsWith("{")) {
                return Optional.empty();
            }
            JSONObject json = new JSONObject(body);
            if (json.has("opening") && !json.isNull("opening")) {
                JSONObject o = json.getJSONObject("opening");
                return Optional.of((o.optString("eco", "") + " " + o.optString("name", "")).trim());
            }
        } catch (Exception e) {
            log.debug("bad explorer response: {}", e.toString());
        }
        return Optional.empty();
    }
}
