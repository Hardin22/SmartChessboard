package org.example.javachess.Services;

import com.sun.net.httpserver.HttpServer;
import org.example.javachess.Utils.ConfigManager;
import org.example.javachess.Utils.ErrorReporter;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * "Connect your Lichess account" without copying tokens by hand: OAuth 2.0 authorization code flow with PKCE
 * (Lichess needs no client registration).
 *
 * <ol>
 *   <li>A one-shot HTTP server listens on {@code 127.0.0.1:<random port>/callback}.</li>
 *   <li>The authorization page ({@code lichess.org/oauth}) is opened in a browser (the integrated JCEF browser on the
 *       board, or the desktop browser); the user approves the {@code board:play} scope.</li>
 *   <li>Lichess redirects to the local server with a code, exchanged (with the PKCE verifier) for a token at
 *       {@code /api/token}. The token is stored with {@link ConfigManager} (file mode 600) and never logged.</li>
 * </ol>
 */
public class LichessOAuth {

    private static final Logger log = LoggerFactory.getLogger(LichessOAuth.class);
    public static final String CLIENT_ID = "javachess-smartboard";
    public static final String SCOPE = "board:play";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public LichessOAuth() {
        this(LichessClient.DEFAULT_BASE_URL);
    }

    public LichessOAuth(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** PKCE verifier and its S256 challenge (RFC 7636). */
    public record Pkce(String verifier, String challenge) {
        public static Pkce generate() {
            byte[] bytes = new byte[48];
            RANDOM.nextBytes(bytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return new Pkce(verifier, challengeOf(verifier));
        }
    }

    static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    URI authorizationUri(String redirectUri, Pkce pkce, String state) {
        return URI.create(baseUrl + "/oauth?response_type=code"
                + "&client_id=" + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(redirectUri)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(pkce.challenge())
                + "&scope=" + enc(SCOPE)
                + "&state=" + enc(state));
    }

    /**
     * Starts the login. {@code openBrowser} receives the authorization URL (call it from any thread). The returned
     * future completes with the Lichess username once the token is stored, or fails with a
     * {@link LichessClient.LichessException} carrying a message for the user (denied, timeout, network).
     */
    public CompletableFuture<String> login(Consumer<URI> openBrowser, Duration timeout) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Pkce pkce = Pkce.generate();
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(16));
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            result.completeExceptionally(new LichessClient.LichessException(
                    "Impossibile avviare il collegamento con Lichess: " + e.getMessage(), 0, e));
            return result;
        }
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lichess-oauth-timeout");
            t.setDaemon(true);
            return t;
        });
        server.createContext("/callback", exchange -> {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            String page;
            Runnable outcome;
            String code = q.get("code");
            if (!state.equals(q.get("state"))) {
                page = "Richiesta non valida.";
                outcome = () -> result.completeExceptionally(new LichessClient.LichessException(
                        "Risposta di Lichess non valida (state diverso): riprova.", 0, null));
            } else if (q.containsKey("error") || code == null || code.isBlank()) {
                page = "Autorizzazione negata. Puoi chiudere questa pagina.";
                outcome = () -> result.completeExceptionally(new LichessClient.LichessException(
                        "Autorizzazione Lichess negata.", 0, null));
            } else {
                page = "Account Lichess collegato. Puoi tornare a javaChess.";
                outcome = () -> CompletableFuture.runAsync(() -> {
                    try {
                        String token = exchangeCode(code, pkce.verifier(), redirect);
                        ConfigManager.setProperty(ConfigManager.LICHESS_TOKEN, token);
                        String user = new LichessClient(baseUrl, () -> token).getAccountId();
                        ConfigManager.setProperty("lichess.username", user);
                        log.info("Lichess account {} connected", user);
                        result.complete(user);
                    } catch (LichessClient.LichessException e) {
                        result.completeExceptionally(e);
                    } catch (Throwable e) {
                        log.error("Lichess login failed", e);
                        result.completeExceptionally(new LichessClient.LichessException(
                                "Collegamento a Lichess non riuscito: " + ErrorReporter.userMessage(e), 0, e));
                    }
                });
            }
            byte[] body = ("<!doctype html><meta charset=utf-8><title>javaChess</title>"
                    + "<body style='font-family:sans-serif;background:#111;color:#eee;padding:3em'><h2>"
                    + page + "</h2></body>").getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } finally {
                outcome.run(); // after the page is sent: completing may stop this server
            }
        });
        server.start();
        timer.schedule(() -> result.completeExceptionally(new LichessClient.LichessException(
                "Tempo scaduto per il collegamento a Lichess: riprova.", 0, null)), timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        result.whenComplete((user, error) -> {
            timer.shutdownNow();
            // stop asynchronously: this may run on the server's own handler thread
            Thread stopper = new Thread(() -> server.stop(1), "lichess-oauth-stop");
            stopper.setDaemon(true);
            stopper.start();
        });
        try {
            openBrowser.accept(authorizationUri(redirect, pkce, state));
        } catch (RuntimeException e) {
            result.completeExceptionally(new LichessClient.LichessException(
                    "Impossibile aprire il browser: " + ErrorReporter.userMessage(e), 0, e));
        }
        return result;
    }

    /** Exchanges the authorization code for an access token (POST /api/token). */
    String exchangeCode(String code, String verifier, String redirectUri) throws LichessClient.LichessException {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&code_verifier=" + enc(verifier)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(CLIENT_ID);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw LichessClient.errorFor(response.statusCode(), response.body());
            }
            return parseAccessToken(response.body());
        } catch (IOException e) {
            throw new LichessClient.LichessException("Lichess non raggiungibile: " + ErrorReporter.userMessage(e), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LichessClient.LichessException("Collegamento interrotto", 0, e);
        }
    }

    static String parseAccessToken(String body) throws LichessClient.LichessException {
        try {
            String token = new JSONObject(body).optString("access_token", "");
            if (token.isBlank()) {
                throw new LichessClient.LichessException("Lichess non ha restituito un token.", 200, null);
            }
            return token;
        } catch (JSONException e) {
            throw new LichessClient.LichessException("Risposta di Lichess non valida.", 200, e);
        }
    }

    /** Revokes the stored token on Lichess (best effort) and removes it from the settings. */
    public void logout() {
        String token = ConfigManager.getProperty(ConfigManager.LICHESS_TOKEN);
        ConfigManager.setProperty(ConfigManager.LICHESS_TOKEN, null);
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .DELETE().build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.warn("Could not revoke the Lichess token: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
