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
    void aSavedLoginIsTypedAndSentOnce() throws Exception {
        store.save(ChessSite.CHESS_COM, new CredentialStore.Login("player", "pw123"));
        poll(); // finds out a login is saved
        poll(); // fills
        assertEquals(List.of("player", "pw123"), site.typed);
        assertEquals(3, site.clicks.size(), "name field, password field, submit");
        poll();
        poll();
        assertEquals(2, site.typed.size(), "sent once per page");
        clock.addAndGet(LoginAssistant.FORM_BACK_MS + 1);
        poll(); // the form is still there: refused
        assertEquals(LoginAssistant.State.FAILED, assistant.state());
        assistant.forget();
        assertFalse(store.has(ChessSite.CHESS_COM));
    }

    @Test
    void aSuccessfulLoginGoesBackToIdleAndLaterFormsAreFilledAgain() throws Exception {
        store.save(ChessSite.CHESS_COM, new CredentialStore.Login("player", "pw123"));
        poll();
        poll();
        site.loginForm = false;
        site.url = "https://www.chess.com/home";
        site.pageHint = "home";
        poll();
        assertEquals(LoginAssistant.State.IDLE, assistant.state());
        site.loginForm = true; // the session expired weeks later
        site.url = "https://www.chess.com/login";
        poll();
        assertEquals(4, site.typed.size());
    }

    @Test
    void aLoginTypedByHandIsOfferedForSavingAfterItWorked() {
        poll(); // nothing saved
        site.formUser = "player@example.com";
        site.formPassword = "typed-by-hand";
        poll(); // remembers what is in the form
        assertTrue(site.typed.isEmpty(), "nothing typed by the app");
        site.loginForm = false;
        site.url = "https://www.chess.com/home";
        site.pageHint = "home";
        poll();
        assertEquals(LoginAssistant.State.OFFER_SAVE, assistant.state());
        assistant.save();
        assertEquals(LoginAssistant.State.IDLE, assistant.state());
        assertEquals("typed-by-hand", store.load(ChessSite.CHESS_COM).orElseThrow().password());
    }

    @Test
    void declinedLoginsAreForgotten() {
        poll();
        site.formUser = "player";
        site.formPassword = "pw";
        poll();
        site.loginForm = false;
        site.url = "https://www.chess.com/home";
        poll();
        assistant.dismiss();
        assertFalse(store.has(ChessSite.CHESS_COM));
        assertEquals(LoginAssistant.State.IDLE, assistant.state());
    }

    @Test
    void neverOnOtherSitesOrWithoutHttps() throws Exception {
        store.save(ChessSite.CHESS_COM, new CredentialStore.Login("player", "pw123"));
        site.url = "http://www.chess.com/login";
        poll();
        poll();
        assertTrue(site.typed.isEmpty(), "no https");
        site.url = "https://chess.com.evil.example/login";
        site.siteId = "other";
        poll();
        poll();
        assertTrue(site.typed.isEmpty(), "another site");
    }
}
