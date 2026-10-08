package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Utils.AppPaths;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Login details for chess.com and lichess, saved once (with the user's consent) and typed into the site's login
 * form by the integrated browser when its session has expired.
 *
 * <p>Where they are kept, best first: the macOS Keychain ({@code security}), the Linux Secret Service
 * ({@code secret-tool}, e.g. GNOME Keyring), otherwise {@code ~/.javachess/credentials} readable by the owner only
 * (mode 600). Secrets are passed to the system tools through their standard input, never on the command line
 * (visible to other processes), are never logged and never written to {@code config.properties}.</p>
 */
public final class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);
    static final String SERVICE = "javachess-browser";

    /** A site's login: the name or e-mail typed in the form, and the password. */
    public record Login(String username, String password) {
        @Override
        public String toString() {
            return "Login[" + username + ", ****]"; // never print the password
        }
    }

    /** Where secrets live. */
    interface Backend {
        String name();

        boolean available();

        Optional<String> read(String account) throws IOException;

        void write(String account, String secret) throws IOException;

        void delete(String account) throws IOException;
    }

    private final List<Backend> backends;

    /**
     * The store for this computer: system keyring when there is one, the owner-only file otherwise. With another
     * data folder ({@code -Djavachess.home}, used by tests, trials and screenshots) only the file in that folder is
     * used, so that nothing run there can read or remove the user's real saved logins;
     * {@code -Djavachess.credentials=system|file} overrides the choice.
     */
    public static CredentialStore system() {
        List<Backend> list = new ArrayList<>();
        if (useSystemKeyring(System.getProperty("javachess.credentials"),
                System.getProperty(AppPaths.HOME_PROPERTY), System.getenv(AppPaths.HOME_ENV))) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) {
                list.add(new MacKeychain());
            } else if (os.contains("linux")) {
                list.add(new SecretTool());
            }
        }
        list.add(new OwnerOnlyFile(AppPaths.resolve("credentials")));
        return new CredentialStore(list);
    }

    static boolean useSystemKeyring(String override, String homeProperty, String homeEnv) {
        if (override != null && !override.isBlank()) {
            return override.trim().equalsIgnoreCase("system");
        }
        return isBlank(homeProperty) && isBlank(homeEnv);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    CredentialStore(List<Backend> backends) {
        this.backends = List.copyOf(backends);
    }

    /** Which backends are used, for tests and logs. */
    List<String> backendNames() {
        return backends.stream().map(b -> b.getClass().getSimpleName()).toList();
    }

    /** Name of the place where new logins are saved ("Portachiavi di macOS"...), for the settings screen. */
    public String backendName() {
        Backend b = writable();
        return b == null ? "-" : b.name();
    }

    /** Saves (or replaces) the login for a site. */
    public void save(ChessSite site, Login login) throws IOException {
        if (site == ChessSite.OTHER || login == null || login.username().isBlank() || login.password().isEmpty()) {
            throw new IllegalArgumentException("Nothing to save");
        }
        String secret = new JSONObject().put("u", login.username()).put("p", login.password()).toString();
        Backend b = writable();
        if (b == null) {
            throw new IOException("No place to keep the login");
        }
        b.write(account(site), secret);
        log.info("Login for {} saved ({})", site.displayName(), b.name());
    }

    /** The saved login for a site, if any. */
    public Optional<Login> load(ChessSite site) {
        if (site == ChessSite.OTHER) {
            return Optional.empty();
        }
        for (Backend b : backends) {
            if (!b.available()) {
                continue;
            }
            try {
                Optional<String> secret = b.read(account(site));
                if (secret.isPresent()) {
                    JSONObject o = new JSONObject(secret.get());
                    return Optional.of(new Login(o.optString("u"), o.optString("p")));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot read the saved login from {}: {}", b.name(), e.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    /** True when a login is saved for the site (the password is not read). */
    public boolean has(ChessSite site) {
        return load(site).isPresent();
    }

    /** Removes the saved login for a site, everywhere. */
    public void remove(ChessSite site) {
        for (Backend b : backends) {
            if (!b.available()) {
                continue;
            }
            try {
                b.delete(account(site));
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot remove the saved login from {}: {}", b.name(), e.getClass().getSimpleName());
            }
        }
        log.info("Saved login for {} removed", site.displayName());
    }

    private Backend writable() {
        for (Backend b : backends) {
            if (b.available()) {
                return b;
            }
        }
        return null;
    }

    static String account(ChessSite site) {
        return site == ChessSite.CHESS_COM ? "chess.com" : "lichess.org";
    }

    // ------------------------------------------------------------------ backends

    /** Runs a system tool with the given standard input; returns its standard output (null on failure). */
    static String run(List<String> command, String stdin, long timeoutMs) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] out;
        try (InputStream o = p.getInputStream()) {
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException(command.get(0) + " did not answer");
            }
            out = o.readAllBytes();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted");
        }
        return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
    }

    static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, tool))) {
                return true;
            }
        }
        return false;
    }

    /** macOS Keychain through {@code security -i}: commands (and the hex-encoded secret) on standard input. */
    static final class MacKeychain implements Backend {
        @Override
        public String name() {
            return "Portachiavi di macOS";
        }

        @Override
        public boolean available() {
            return Files.isExecutable(Path.of("/usr/bin/security"));
        }

        @Override
        public Optional<String> read(String account) throws IOException {
            String out = run(List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"),
                    null, 5000);
            return out == null ? Optional.empty() : Optional.of(out.strip());
        }

        @Override
        public void write(String account, String secret) throws IOException {
            String hex = HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8));
            String cmd = "add-generic-password -U -s " + SERVICE + " -a " + account + " -l \"javaChess " + account
                    + "\" -X " + hex + "\n";
            if (run(List.of("/usr/bin/security", "-i"), cmd, 10000) == null) {
                throw new IOException("Keychain refused the login");
            }
        }

        @Override
        public void delete(String account) throws IOException {
            run(List.of("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account), null, 5000);
        }
    }

    /** Linux Secret Service (GNOME Keyring, KWallet...) through {@code secret-tool}; secrets on standard input. */
    static final class SecretTool implements Backend {
        private Boolean usable;

        @Override
        public String name() {
            return "portachiavi del sistema";
        }

        @Override
        public synchronized boolean available() {
            if (usable == null) {
                // the tool may exist without a running keyring (kiosk session): probe once, quickly
                boolean ok = false;
                if (onPath("secret-tool")) {
                    try {
                        ok = run(List.of("secret-tool", "search", "service", SERVICE), null, 3000) != null;
                    } catch (IOException e) {
                        ok = false;
                    }
                }
                usable = ok;
            }
            return usable;
        }

        @Override
        public Optional<String> read(String account) throws IOException {
            String out = run(List.of("secret-tool", "lookup", "service", SERVICE, "account", account), null, 5000);
            return out == null || out.isEmpty() ? Optional.empty() : Optional.of(out.strip());
        }

        @Override
        public void write(String account, String secret) throws IOException {
            if (run(List.of("secret-tool", "store", "--label=javaChess " + account, "service", SERVICE, "account",
                    account), secret, 10000) == null) {
                throw new IOException("Secret Service refused the login");
            }
        }

        @Override
        public void delete(String account) throws IOException {
            run(List.of("secret-tool", "clear", "service", SERVICE, "account", account), null, 5000);
        }
    }

    /** {@code ~/.javachess/credentials}, owner read/write only; values base64-encoded (not encryption). */
    static final class OwnerOnlyFile implements Backend {
        private final Path file;

        OwnerOnlyFile(Path file) {
            this.file = file;
        }

        @Override
        public String name() {
            return "file protetto in ~/.javachess";
        }

        @Override
        public boolean available() {
            return true;
        }

        private synchronized Properties load() throws IOException {
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            }
            return p;
        }

        private synchronized void store(Properties p) throws IOException {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.deleteIfExists(tmp);
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp); // not a POSIX file system (Windows): the user folder is private anyway
            }
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "javaChess saved logins (owner only). Remove them from the app's settings.");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public Optional<String> read(String account) throws IOException {
            String v = load().getProperty(account);
            return v == null ? Optional.empty()
                    : Optional.of(new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8));
        }

        @Override
        public void write(String account, String secret) throws IOException {
            Properties p = load();
            p.setProperty(account, Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            store(p);
        }

        @Override
        public void delete(String account) throws IOException {
            Properties p = load();
            if (p.remove(account) != null) {
                store(p);
            }
        }
    }
}
