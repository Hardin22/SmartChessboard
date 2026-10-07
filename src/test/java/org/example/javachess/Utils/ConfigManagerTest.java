package org.example.javachess.Utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @TempDir
    Path dir;
    Path home;
    Path legacy;
    private String savedHome;
    private String savedLegacy;

    @BeforeEach
    void setUp() throws Exception {
        home = dir.resolve("home");
        legacy = dir.resolve("legacy");
        Files.createDirectories(legacy);
        savedHome = System.getProperty(AppPaths.HOME_PROPERTY);
        savedLegacy = System.getProperty(AppPaths.LEGACY_DIR_PROPERTY);
        System.setProperty(AppPaths.HOME_PROPERTY, home.toString());
        System.setProperty(AppPaths.LEGACY_DIR_PROPERTY, legacy.toString());
    }

    @AfterEach
    void tearDown() {
        restore(AppPaths.HOME_PROPERTY, savedHome);
        restore(AppPaths.LEGACY_DIR_PROPERTY, savedLegacy);
        if (savedHome != null) {
            ConfigManager.reload(); // back to the sandbox configured by surefire, never the real home
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private Properties stored() throws Exception {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(home.resolve("config.properties"))) {
            p.load(r);
        }
        return p;
    }

    @Test
    void defaultsComeFromClasspathAndArguments() {
        ConfigManager.reload();
        assertEquals("10", ConfigManager.getProperty("game.default.duration"));
        assertEquals(10, ConfigManager.getIntProperty("game.default.duration", 99));
        assertEquals(42, ConfigManager.getIntProperty("missing.key", 42));
        assertEquals("x", ConfigManager.getProperty("missing.key", "x"));
        assertTrue(ConfigManager.getBooleanProperty("missing.bool", true));
        assertFalse(Files.exists(home.resolve("config.properties")), "nothing written until a value is set");
    }

    @Test
    void valuesAreValidatedAndClamped() throws Exception {
        ConfigManager.reload();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("game.bot.level", "35");          // max 20
        values.put("hardware.led.brightness", "-5"); // min 0
        values.put("game.depth", "abc");             // ignored
        values.put("game.evaluation", "OFF");
        values.put("theme.board", "  Legno.png ");
        ConfigManager.setProperties(values);
        assertEquals(20, ConfigManager.getIntProperty("game.bot.level", 10));
        assertEquals(0, ConfigManager.getIntProperty("hardware.led.brightness", 100));
        assertEquals(18, ConfigManager.getIntProperty("game.depth", 18));
        assertFalse(ConfigManager.getBooleanProperty("game.evaluation", true));
        assertEquals("Legno.png", ConfigManager.getProperty("theme.board"));

        ConfigManager.reload();
        assertEquals("20", stored().getProperty("game.bot.level"));
        assertEquals(20, ConfigManager.getIntProperty("game.bot.level", 10));
    }

    @Test
    void invalidStoredValuesFallBack() throws Exception {
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.properties"),
                "game.bot.level=lots\ngame.default.duration=999\ngame.suggestions=maybe\n");
        ConfigManager.reload();
        assertEquals(10, ConfigManager.getIntProperty("game.bot.level", 10));
        assertEquals(180, ConfigManager.getIntProperty("game.default.duration", 10));
        assertTrue(ConfigManager.getBooleanProperty("game.suggestions", true));
    }

    @Test
    void blankValueRemovesTheKey() throws Exception {
        ConfigManager.reload();
        ConfigManager.setProperty("lichess.username", "someone");
        assertEquals("someone", stored().getProperty("lichess.username"));
        ConfigManager.setProperty("lichess.username", "  ");
        assertNull(stored().getProperty("lichess.username"));
    }

    @Test
    void passwordsAreNeverStored() throws Exception {
        ConfigManager.reload();
        ConfigManager.setProperty("chess.com.password", "secret");
        ConfigManager.setProperty("lichess.password", "secret");
        ConfigManager.setProperty("lichess.username", "me");
        Properties p = stored();
        assertNull(p.getProperty("chess.com.password"));
        assertNull(p.getProperty("lichess.password"));
        assertNull(ConfigManager.getProperty("chess.com.password"));
    }

    @Test
    void settingsFileIsPrivate() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        ConfigManager.reload();
        ConfigManager.setProperty(ConfigManager.LICHESS_TOKEN, "lip_test");
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("config.properties"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home)));
        assertEquals("***", ConfigManager.describe().get(ConfigManager.LICHESS_TOKEN), "token masked");
        assertTrue(ConfigManager.hasLichessToken());
    }

    @Test
    void migratesOldWorkingDirectoryFileAndScrubsSecrets() throws Exception {
        Files.writeString(legacy.resolve("config.properties"), """
                theme.board=Legno.png
                game.bot.level=7
                lichess.token=lip_abc
                lichess.password=pw1
                chess.com.password=pw2
                lichess.api.key=lip_abc
                """);
        ConfigManager.reload();
        assertEquals("Legno.png", ConfigManager.getProperty("theme.board"));
        assertEquals(7, ConfigManager.getIntProperty("game.bot.level", 10));
        assertEquals("lip_abc", ConfigManager.getProperty(ConfigManager.LICHESS_TOKEN));
        Properties p = stored();
        assertNull(p.getProperty("lichess.password"));
        assertNull(p.getProperty("chess.com.password"));
        assertNull(p.getProperty("lichess.api.key"));

        Properties old = new Properties();
        try (Reader r = Files.newBufferedReader(legacy.resolve("config.properties"))) {
            old.load(r);
        }
        assertNull(old.getProperty("lichess.token"), "token removed from the old copy");
        assertNull(old.getProperty("lichess.password"));
        assertEquals("Legno.png", old.getProperty("theme.board"));

        // second start does not migrate again (new values are not overwritten)
        ConfigManager.setProperty("theme.board", "Neon.png");
        ConfigManager.reload();
        assertEquals("Neon.png", ConfigManager.getProperty("theme.board"));
    }

    @Test
    void passwordsInTheCurrentFileAreRemovedOnLoad() throws Exception {
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.properties"), "chess.com.password=pw\ntheme.piece=Neon\n");
        ConfigManager.reload();
        assertNull(stored().getProperty("chess.com.password"));
        assertEquals("Neon", ConfigManager.getProperty("theme.piece"));
    }

    @Test
    void corruptFileFallsBackToDefaults() throws Exception {
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.properties"), "game.bot.level=\\u00zz\n");
        ConfigManager.reload();
        assertEquals(10, ConfigManager.getIntProperty("game.bot.level", 10));
    }
}
