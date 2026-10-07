package org.example.javachess.Utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpCookie;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PersistentCookieStoreTest {

    @TempDir
    Path dir;

    @Test
    void domainMatchingFollowsRfc6265() {
        assertTrue(PersistentCookieStore.domainMatches("chess.com", "chess.com"));
        assertTrue(PersistentCookieStore.domainMatches(".chess.com", "www.chess.com"));
        assertTrue(PersistentCookieStore.domainMatches("chess.com", "www.chess.com"));
        assertFalse(PersistentCookieStore.domainMatches("chess.com", "evilchess.com"));
        assertFalse(PersistentCookieStore.domainMatches("www.chess.com", "chess.com"));
        assertTrue(PersistentCookieStore.pathMatches("/api", "/api/x"));
        assertFalse(PersistentCookieStore.pathMatches("/api", "/apix"));
    }

    @Test
    void persistentCookiesSurviveARestartSessionCookiesDoNot() throws Exception {
        Path file = dir.resolve("cookies.json");
        PersistentCookieStore store = new PersistentCookieStore(file);
        HttpCookie persistent = new HttpCookie("sid", "v1");
        persistent.setMaxAge(3600);
        persistent.setSecure(true);
        store.add(URI.create("https://lichess.org/"), persistent);
        store.add(URI.create("https://lichess.org/"), new HttpCookie("session", "v2"));
        assertEquals(2, store.get(URI.create("https://lichess.org/api")).size());
        assertEquals(0, store.get(URI.create("http://lichess.org/api")).stream()
                .filter(c -> c.getName().equals("sid")).count(), "secure cookie not sent over http");
        store.saveCookies();

        PersistentCookieStore reloaded = new PersistentCookieStore(file);
        assertEquals(1, reloaded.getCookies().size());
        assertEquals("sid", reloaded.getCookies().get(0).getName());
        assertTrue(Files.readString(file).contains("expiresAt"));
    }

    @Test
    void sitesCannotSetCookiesForOtherDomains() {
        PersistentCookieStore store = new PersistentCookieStore(dir.resolve("c.json"));
        HttpCookie evil = new HttpCookie("sid", "x");
        evil.setDomain("lichess.org");
        store.add(URI.create("https://evil.example/"), evil);
        assertTrue(store.getCookies().isEmpty());
        HttpCookie parent = new HttpCookie("sid", "y");
        parent.setDomain(".chess.com");
        store.add(URI.create("https://www.chess.com/"), parent);
        assertEquals(1, store.getCookies().size());
    }

    @Test
    void corruptFileStartsEmpty() throws Exception {
        Path file = dir.resolve("cookies.json");
        Files.writeString(file, "[{");
        assertTrue(new PersistentCookieStore(file).getCookies().isEmpty());
    }
}
