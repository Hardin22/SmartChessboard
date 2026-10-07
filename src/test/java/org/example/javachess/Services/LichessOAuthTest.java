package org.example.javachess.Services;

import com.sun.net.httpserver.HttpServer;
import org.example.javachess.Utils.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** OAuth PKCE login against a fake Lichess that really checks the code verifier. */
class LichessOAuthTest {

    private HttpServer lichess;
    private String base;
    private volatile String expectedChallenge;

    @BeforeEach
    void start() throws Exception {
        ConfigManager.reload();
        ConfigManager.setProperty(ConfigManager.LICHESS_TOKEN, null);
        lichess = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lichess.createContext("/api/token", ex -> {
            Map<String, String> form = parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean ok = "authorization_code".equals(form.get("grant_type")) && "the-code".equals(form.get("code"))
                    && LichessOAuth.challengeOf(form.get("code_verifier")).equals(expectedChallenge);
            reply(ex, ok ? 200 : 400, ok ? "{\"token_type\":\"Bearer\",\"access_token\":\"lio_fake\",\"expires_in\":31536000}"
                    : "{\"error\":\"invalid_grant\"}");
        });
        lichess.createContext("/api/account", ex -> reply(ex, "Bearer lio_fake".equals(
                ex.getRequestHeaders().getFirst("Authorization")) ? 200 : 401, "{\"id\":\"pkceuser\"}"));
        lichess.start();
        base = "http://127.0.0.1:" + lichess.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        lichess.stop(0);
        ConfigManager.setProperty(ConfigManager.LICHESS_TOKEN, null);
        ConfigManager.setProperty("lichess.username", null);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<>();
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void challengeMatchesAnIndependentImplementation() {
        assertEquals("qjrzSW9gMiUgpUvqgEPE4_-8swvyCtfOVvg55o5S_es",
                LichessOAuth.challengeOf("M25iVXpKU3puUjFaYWg3T1NDTDQtcW1ROUY5YXlwalNoc0hhakxifmZHag"));
        LichessOAuth.Pkce p = LichessOAuth.Pkce.generate();
        assertTrue(p.verifier().length() >= 43 && p.verifier().length() <= 128);
        assertEquals(LichessOAuth.challengeOf(p.verifier()), p.challenge());
    }

    /** Simulates the user approving the request in the browser: Lichess redirects to the local callback. */
    private void approve(URI authUrl, String code) {
        Map<String, String> q = parse(authUrl.getRawQuery());
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals("board:play", q.get("scope"));
        expectedChallenge = q.get("code_challenge");
        String redirect = q.get("redirect_uri") + "?" + (code == null ? "error=access_denied"
                : "code=" + code) + "&state=" + q.get("state");
        Thread.ofVirtual().start(() -> {
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(redirect)).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void fullLoginStoresTheTokenAndUsername() throws Exception {
        String user = new LichessOAuth(base).login(url -> approve(url, "the-code"), Duration.ofSeconds(10))
                .get(10, TimeUnit.SECONDS);
        assertEquals("pkceuser", user);
        assertEquals("lio_fake", ConfigManager.getProperty(ConfigManager.LICHESS_TOKEN));
        assertEquals("pkceuser", ConfigManager.getProperty("lichess.username"));
    }

    @Test
    void deniedAuthorizationFailsWithAUserMessage() {
        ExecutionException e = assertThrows(ExecutionException.class, () -> new LichessOAuth(base)
                .login(url -> approve(url, null), Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS));
        assertEquals("Autorizzazione Lichess negata.", e.getCause().getMessage());
        assertFalse(ConfigManager.hasLichessToken());
    }

    @Test
    void timeout() {
        ExecutionException e = assertThrows(ExecutionException.class, () -> new LichessOAuth(base)
                .login(url -> { }, Duration.ofMillis(200)).get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause().getMessage().startsWith("Tempo scaduto"));
    }

    @Test
    void tokenResponseParsing() throws Exception {
        assertEquals("lio_x", LichessOAuth.parseAccessToken("{\"access_token\":\"lio_x\"}"));
        assertThrows(LichessClient.LichessException.class, () -> LichessOAuth.parseAccessToken("{}"));
        assertThrows(LichessClient.LichessException.class, () -> LichessOAuth.parseAccessToken("<html>"));
    }
}
