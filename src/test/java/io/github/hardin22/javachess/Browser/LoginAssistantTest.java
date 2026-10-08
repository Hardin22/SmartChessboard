package io.github.hardin22.javachess.Browser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAssistantTest {

    @TempDir
    Path dir;

    private FakeSite site;
    private CredentialStore store;
    private final AtomicLong clock = new AtomicLong(1000);
    private final AtomicInteger changes = new AtomicInteger();
    private LoginAssistant assistant;

    @BeforeEach
    void setUp() {
        site = new FakeSite();
        site.url = "https://www.chess.com/login";
        site.siteId = "chesscom";
        site.pageHint = "login";
        site.boardShown = false;
        site.loginForm = true;
        store = new CredentialStore(List.of(new CredentialStore.OwnerOnlyFile(dir.resolve("credentials"))));
        assistant = new LoginAssistant(site, store, Runnable::run, Runnable::run, changes::incrementAndGet, clock::get);
    }

    private void poll() {
        assistant.onSnapshot(BoardProbe.parse(site.json()));
    }

    @Test
    void theLoginPageIsNeverReadNorTyped() throws Exception {
        // chess.com's login carries Cloudflare Turnstile: the app must not script the page on its own
        store.save(ChessSite.CHESS_COM, new CredentialStore.Login("player", "pw123"));
        site.formUser = "player@example.com";
        site.formPassword = "typed-by-hand";
        int before = site.evaluations;
        for (int i = 0; i < 5; i++) {
            poll();
        }
        assistant.onLoginPage(ChessSite.CHESS_COM);
        assertEquals(before, site.evaluations, "no script in the page");
        assertTrue(site.typed.isEmpty(), "nothing typed");
        assertTrue(site.clicks.isEmpty(), "nothing clicked");
        assertEquals(java.util.Optional.of(true), assistant.hasSaved(ChessSite.CHESS_COM), "the offer can be shown");
    }

    @Test
    void theSavedLoginIsTypedWhenTheUserAsksAndAFailureIsNoticed() throws Exception {
        store.save(ChessSite.CHESS_COM, new CredentialStore.Login("player", "pw123"));
        AtomicInteger done = new AtomicInteger();
        assistant.fillSaved(ChessSite.CHESS_COM, site.url, done::incrementAndGet);
        assertEquals(List.of("player", "pw123"), site.typed);
        assertEquals(3, site.clicks.size(), "name field, password field, submit");
        assertEquals(1, done.get());
        assistant.tick(site.url);
        assertEquals(LoginAssistant.State.IDLE, assistant.state());
        clock.addAndGet(LoginAssistant.FORM_BACK_MS + 1);
        assistant.tick(site.url); // still the login page: refused
        assertEquals(LoginAssistant.State.FAILED, assistant.state());
        assistant.forget();
        assertFalse(store.has(ChessSite.CHESS_COM));
    }

    @Test
    void aLoginThatWorkedGoesBackToIdle() throws Exception {
        store.save(ChessSite.CHESS_COM, new CredentialStore.Login("player", "pw123"));
        assistant.fillSaved(ChessSite.CHESS_COM, site.url, () -> { });
        site.loginForm = false;
        site.url = "https://www.chess.com/home";
        site.pageHint = "home";
        clock.addAndGet(LoginAssistant.FORM_BACK_MS + 1);
        assistant.tick(site.url);
        poll();
        assertEquals(LoginAssistant.State.IDLE, assistant.state());
    }

    @Test
    void neverOnOtherSitesOrWithoutHttps() throws Exception {
        store.save(ChessSite.CHESS_COM, new CredentialStore.Login("player", "pw123"));
        assistant.fillSaved(ChessSite.CHESS_COM, "http://www.chess.com/login", () -> { });
        assertTrue(site.typed.isEmpty(), "no https");
        assistant.fillSaved(ChessSite.CHESS_COM, "https://chess.com.evil.example/login", () -> { });
        assertTrue(site.typed.isEmpty(), "another site");
    }
}
