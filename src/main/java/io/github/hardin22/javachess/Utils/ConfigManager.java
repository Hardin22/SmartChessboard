package io.github.hardin22.javachess.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Application settings.
 *
 * <ul>
 *   <li>Defaults come from {@code application.properties} on the classpath plus the table of known keys below.</li>
 *   <li>User values live in {@code ~/.javachess/config.properties} (see {@link AppPaths}), written atomically
 *       with owner-only permissions because the file may hold the Lichess API token.</li>
 *   <li>Known numeric keys are validated: invalid values fall back to the default, out-of-range values are clamped.</li>
 *   <li>Passwords are never stored: keys ending in {@code .password} are ignored and removed from old files
 *       (the integrated browser keeps its own login session instead).</li>
 *   <li>On first start a {@code config.properties} found in the working directory (old versions) is migrated.</li>
 * </ul>
 *
 * The static API is kept for compatibility with the rest of the code base. Values are never logged.
 */
public final class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    public static final String LICHESS_TOKEN = "lichess.token";
    /** Environment variable that overrides the stored Lichess token (handy for development and CI). */
    public static final String LICHESS_TOKEN_ENV = "JAVACHESS_LICHESS_TOKEN";

    private record IntRange(int min, int max, int def) {
    }

    /** Known integer keys: range and default used when the stored value is missing or invalid. */
    private static final Map<String, IntRange> INT_KEYS = Map.ofEntries(
            Map.entry("game.bot.level", new IntRange(0, 20, 10)),
            Map.entry("game.bot.movetime", new IntRange(50, 60_000, 2000)),
            Map.entry("game.default.duration", new IntRange(1, 180, 10)),
            Map.entry("game.default.increment", new IntRange(0, 180, 0)),
            Map.entry("game.depth", new IntRange(1, 60, 18)),
            Map.entry("move.eval.depth", new IntRange(1, 60, 8)),
            Map.entry("hardware.led.brightness", new IntRange(0, 100, 100)),
            Map.entry("stockfish.threads", new IntRange(1, 256, 2)),
            Map.entry("stockfish.hash", new IntRange(1, 65_536, 64)));

    private static final Set<String> BOOLEAN_KEYS = Set.of("game.evaluation", "game.suggestions", "ui.mate.animation");

    /** Keys from old versions that must not survive a migration (plain-text passwords, duplicated token). */
    private static final Set<String> OBSOLETE_SECRET_KEYS = Set.of("lichess.password", "chess.com.password",
            "lichess.api.key");

    private static final Properties defaults = new Properties();
    private static final Properties user = new Properties();
    private static Path file;

    static {
        reload();
    }

    private ConfigManager() {
    }

    /** Re-reads defaults and the user file (also used by tests after changing {@code javachess.home}). */
    public static synchronized void reload() {
        defaults.clear();
        user.clear();
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                defaults.load(in);
            }
        } catch (IOException e) {
            log.warn("Cannot read default settings: {}", e.getMessage());
        }
        file = AppPaths.configFile();
        migrateLegacyFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                user.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                log.error("Settings file {} is unreadable ({}); using defaults. A copy is kept in the backups folder.",
                        file, e.getMessage());
                backupQuietly(file);
                user.clear();
            }
        }
        if (removeSecretKeys(user)) {
            log.warn("Removed stored passwords from {} (passwords are no longer saved)", file);
            save();
        }
    }

    // ------------------------------------------------------------------ getters

    public static synchronized String getProperty(String key) {
        if (LICHESS_TOKEN.equals(key)) {
            String env = System.getenv(LICHESS_TOKEN_ENV);
            if (env != null && !env.isBlank()) {
                return env.trim();
            }
        }
        String value = user.getProperty(key);
        return value != null ? value : defaults.getProperty(key);
    }

    public static String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    public static int getIntProperty(String key, int defaultValue) {
        IntRange range = INT_KEYS.get(key);
        String value = getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting {} is not a number, using {}", key, defaultValue);
            return defaultValue;
        }
        if (range != null && (parsed < range.min() || parsed > range.max())) {
            int clamped = Math.max(range.min(), Math.min(range.max(), parsed));
            log.warn("Setting {}={} is outside [{}, {}], using {}", key, parsed, range.min(), range.max(), clamped);
            return clamped;
        }
        return parsed;
    }

    public static double getDoubleProperty(String key, double defaultValue) {
        String value = getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("Setting {} is not a number, using {}", key, defaultValue);
            return defaultValue;
        }
    }

    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String v = value.trim().toLowerCase();
        if (v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1")) {
            return true;
        }
        if (v.equals("false") || v.equals("no") || v.equals("off") || v.equals("0")) {
            return false;
        }
        log.warn("Setting {} is not a boolean, using {}", key, defaultValue);
        return defaultValue;
    }

    /**
     * Value stored in the settings file or defaults, ignoring environment overrides: use it to show and edit settings,
     * so that a token given through {@link #LICHESS_TOKEN_ENV} is never copied into the file.
     */
    public static synchronized String getStoredProperty(String key, String defaultValue) {
        String value = user.getProperty(key);
        if (value == null) {
            value = defaults.getProperty(key);
        }
        return value != null ? value : defaultValue;
    }

    /** True when a non-empty Lichess token is configured (file or environment). */
    public static boolean hasLichessToken() {
        String token = getProperty(LICHESS_TOKEN);
        return token != null && !token.isBlank();
    }

    // ------------------------------------------------------------------ setters

    /** Stores one value and writes the file. A null or blank value removes the key. */
    public static void setProperty(String key, String value) {
        setProperties(Collections.singletonMap(key, value));
    }

    /** Stores several values with a single atomic write. Null or blank values remove the key. */
    public static synchronized void setProperties(Map<String, String> values) {
        boolean changed = false;
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (isSecretPassword(key)) {
                log.warn("Ignoring {}: passwords are not stored", key);
                continue;
            }
            String value = normalize(key, e.getValue());
            if (value == null) {
                changed |= user.remove(key) != null;
            } else if (!value.equals(user.getProperty(key))) {
                user.setProperty(key, value);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** Snapshot of the effective settings (defaults overridden by user values); secrets are masked. */
    public static synchronized Map<String, String> describe() {
        Map<String, String> out = new TreeMap<>();
        for (String k : defaults.stringPropertyNames()) {
            out.put(k, defaults.getProperty(k));
        }
        for (String k : user.stringPropertyNames()) {
            out.put(k, user.getProperty(k));
        }
        if (out.containsKey(LICHESS_TOKEN)) {
            out.put(LICHESS_TOKEN, out.get(LICHESS_TOKEN).isBlank() ? "" : "***");
        }
        return out;
    }

    /** Path of the user settings file. */
    public static synchronized Path file() {
        return file;
    }

    // ------------------------------------------------------------------ internals

    private static String normalize(String key, String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        IntRange range = INT_KEYS.get(key);
        if (range != null) {
            try {
                int parsed = (int) Math.round(Double.parseDouble(v));
                return String.valueOf(Math.max(range.min(), Math.min(range.max(), parsed)));
            } catch (NumberFormatException ex) {
                log.warn("Ignoring non-numeric value for {}", key);
                return user.getProperty(key);
            }
        }
        if (BOOLEAN_KEYS.contains(key)) {
            return String.valueOf(Boolean.parseBoolean(v) || v.equalsIgnoreCase("on") || v.equals("1"));
        }
        return v;
    }

    private static boolean isSecretPassword(String key) {
        return key.endsWith(".password") || OBSOLETE_SECRET_KEYS.contains(key);
    }

    private static boolean removeSecretKeys(Properties props) {
        boolean removed = false;
        for (String key : props.stringPropertyNames()) {
            if (isSecretPassword(key)) {
                props.remove(key);
                removed = true;
            }
        }
        return removed;
    }

    /** Writes the user settings; returns false (after logging) when the file could not be written. */
    private static boolean save() {
        try {
            StringWriter out = new StringWriter();
            user.store(out, "javaChess user settings (contains the Lichess token: keep it private)");
            AtomicFiles.writeString(file, out.toString(), true);
            return true;
        } catch (IOException e) {
            log.error("Cannot save settings to {}: {}", file, e.getMessage());
            return false;
        }
    }

    private static void backupQuietly(Path path) {
        try {
            AtomicFiles.backup(path, AppPaths.backupsDir(), 5);
        } catch (IOException e) {
            log.warn("Cannot back up {}: {}", path, e.getMessage());
        }
    }

    /**
     * Moves {@code config.properties} from the working directory of old versions into the data folder.
     * Passwords are dropped; the token is moved (and removed from the old file, which may be in a synced folder).
     */
    private static void migrateLegacyFile() {
        Path legacy = AppPaths.legacyDir().resolve("config.properties");
        if (Files.exists(file) || !Files.isRegularFile(legacy)) {
            return;
        }
        try {
            if (Files.isSameFile(legacy, file)) {
                return;
            }
        } catch (IOException ignored) {
            // file does not exist yet: not the same
        }
        Properties old = new Properties();
        try (Reader reader = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
            old.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Cannot read old settings {}: {}", legacy, e.getMessage());
            return;
        }
        boolean hadSecrets = removeSecretKeys(old);
        user.clear();
        user.putAll(old);
        if (!save()) {
            log.warn("Settings not migrated: the old file {} is left untouched", legacy);
            return; // keep using the old values from memory, never drop the token
        }
        log.info("Migrated settings from {} to {}{}", legacy, file, hadSecrets ? " (stored passwords dropped)" : "");

        // Scrub secrets from the old copy: it is outside the protected data folder.
        Properties scrubbed = new Properties();
        scrubbed.putAll(old);
        scrubbed.remove(LICHESS_TOKEN);
        try {
            StringWriter out = new StringWriter();
            scrubbed.store(out, "Moved to " + file + " - this copy is no longer used");
            AtomicFiles.writeString(legacy, out.toString(), true);
        } catch (IOException e) {
            log.warn("Could not remove secrets from old settings file {}: {}", legacy, e.getMessage());
        }
        user.clear();
    }
}
