package io.github.hardin22.javachess.Browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saved logins in the owner-only file (the system keyrings are not touched by tests). */
class CredentialStoreTest {

    @TempDir
    Path dir;

    private CredentialStore store() {
        return new CredentialStore(List.of(new CredentialStore.OwnerOnlyFile(dir.resolve("credentials"))));
    }

    @Test
    void savesLoadsAndRemovesPerSite() throws Exception {
        CredentialStore s = store();
        assertFalse(s.has(ChessSite.CHESS_COM));
        s.save(ChessSite.CHESS_COM, new CredentialStore.Login("player@example.com", "pä$$ wörd \"x\""));
        s.save(ChessSite.LICHESS, new CredentialStore.Login("lichessplayer", "secret2"));
        assertEquals(Optional.of(new CredentialStore.Login("player@example.com", "pä$$ wörd \"x\"")),
                store().load(ChessSite.CHESS_COM), "read back by another instance");
        assertEquals("secret2", s.load(ChessSite.LICHESS).orElseThrow().password());
        s.remove(ChessSite.CHESS_COM);
        assertFalse(s.has(ChessSite.CHESS_COM));
        assertTrue(s.has(ChessSite.LICHESS));
        assertFalse(s.load(ChessSite.OTHER).isPresent());
    }

    @Test
    void theFileIsPrivateAndHasNoPlainPassword() throws Exception {
        store().save(ChessSite.LICHESS, new CredentialStore.Login("me", "VerySecretPassword"));
        Path file = dir.resolve("credentials");
        String text = Files.readString(file);
        assertFalse(text.contains("VerySecretPassword"), "not in plain text");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void passwordsNeverAppearInLogsOrToString() {
        CredentialStore.Login login = new CredentialStore.Login("me", "VerySecretPassword");
        assertFalse(login.toString().contains("VerySecretPassword"));
    }

    @Test
    void refusesEmptyLoginsAndUnknownSites() {
        CredentialStore s = store();
        assertThrows(IllegalArgumentException.class, () -> s.save(ChessSite.OTHER, new CredentialStore.Login("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> s.save(ChessSite.LICHESS, new CredentialStore.Login("", "b")));
        assertThrows(IllegalArgumentException.class, () -> s.save(ChessSite.LICHESS, new CredentialStore.Login("a", "")));
    }

    @Test
    void anUnavailableKeyringFallsBackToTheFile() throws Exception {
        CredentialStore.Backend broken = new CredentialStore.Backend() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<String> read(String account) {
                throw new AssertionError("not available");
            }

            @Override
            public void write(String account, String secret) {
                throw new AssertionError("not available");
            }

            @Override
            public void delete(String account) {
                throw new AssertionError("not available");
            }
        };
        CredentialStore s = new CredentialStore(List.of(broken,
                new CredentialStore.OwnerOnlyFile(dir.resolve("credentials"))));
        s.save(ChessSite.CHESS_COM, new CredentialStore.Login("u", "p"));
        assertTrue(s.has(ChessSite.CHESS_COM));
        assertEquals("file protetto in ~/.javachess", s.backendName());
    }
}
