package io.github.hardin22.javachess.Components;

import io.github.hardin22.javachess.Browser.ChessSite;
import io.github.hardin22.javachess.Browser.CredentialStore;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The saved browser logins as the settings screen sees them: who is saved for each site, save, remove, and where
 * they are kept. Every method may block (the system keyring is a separate process): call them off the JavaFX
 * thread. The password is never handed back to the UI.
 */
public interface LoginVault {

    /** The user name saved for the site, if a login is saved. */
    Optional<String> username(ChessSite site);

    void save(ChessSite site, String username, String password) throws IOException;

    void remove(ChessSite site);

    /** Where logins are kept, for the explanation ("Portachiavi di macOS"...). */
    String place();

    /** The real store of this computer (macOS Keychain, Linux Secret Service, owner-only file). */
    static LoginVault system() {
        CredentialStore store = CredentialStore.system();
        return new LoginVault() {
            @Override
            public Optional<String> username(ChessSite site) {
                return store.load(site).map(CredentialStore.Login::username);
            }

            @Override
            public void save(ChessSite site, String username, String password) throws IOException {
                store.save(site, new CredentialStore.Login(username, password));
            }

            @Override
            public void remove(ChessSite site) {
                store.remove(site);
            }

            @Override
            public String place() {
                return store.backendName();
            }
        };
    }

    /**
     * In memory only: screenshots and demos never read or change the real keyring. {@code saved} pre-fills
     * sites with a sample user name.
     */
    static LoginVault memory(ChessSite... saved) {
        Map<ChessSite, String> users = new EnumMap<>(ChessSite.class);
        for (ChessSite site : saved) {
            users.put(site, site == ChessSite.LICHESS ? "giocatore_demo" : "demo@example.com");
        }
        return new LoginVault() {
            @Override
            public synchronized Optional<String> username(ChessSite site) {
                return Optional.ofNullable(users.get(site));
            }

            @Override
            public synchronized void save(ChessSite site, String username, String password) {
                users.put(site, username);
            }

            @Override
            public synchronized void remove(ChessSite site) {
                users.remove(site);
            }

            @Override
            public String place() {
                return "Portachiavi di macOS";
            }
        };
    }
}
