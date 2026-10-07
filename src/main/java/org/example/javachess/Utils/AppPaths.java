package org.example.javachess.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Locations of the user data files (configuration, game archive, cookies, logs, backups).
 *
 * <p>Everything lives in one per-user folder, {@code ~/.javachess/} by default. It can be moved with the
 * {@code -Djavachess.home=/some/dir} system property or the {@code JAVACHESS_HOME} environment variable
 * (tests use the property to work in a temporary folder).
 *
 * <p>Older versions kept {@code config.properties}, {@code archive.json} and {@code cookies.json} in the
 * working directory: {@link #legacyDir()} points there so that the data can be migrated on first start.
 */
public final class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    public static final String HOME_PROPERTY = "javachess.home";
    public static final String HOME_ENV = "JAVACHESS_HOME";
    public static final String LEGACY_DIR_PROPERTY = "javachess.legacyDir";

    private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");

    private AppPaths() {
    }

    private static volatile Path restricted;

    /** The data folder (created on demand, owner-only permissions on POSIX systems). */
    public static Path dataDir() {
        Path dir = configuredHome();
        ensureDirectory(dir);
        if (!dir.equals(restricted)) {
            restrictToOwner(dir); // also fixes folders created by older versions (e.g. for the browser cache)
            restricted = dir;
        }
        return dir;
    }

    /** Resolves a file name inside the data folder. */
    public static Path resolve(String name) {
        return dataDir().resolve(name);
    }

    public static Path configFile() {
        return resolve("config.properties");
    }

    public static Path archiveFile() {
        return resolve("archive.json");
    }

    public static Path cookiesFile() {
        return resolve("cookies.json");
    }

    public static Path backupsDir() {
        Path dir = resolve("backups");
        ensureDirectory(dir);
        return dir;
    }

    public static Path logsDir() {
        Path dir = resolve("logs");
        ensureDirectory(dir);
        return dir;
    }

    /** Folder where pre-1.0 versions stored their files: the working directory (overridable for tests). */
    public static Path legacyDir() {
        String override = System.getProperty(LEGACY_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static Path configuredHome() {
        String prop = System.getProperty(HOME_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop).toAbsolutePath().normalize();
        }
        String env = System.getenv(HOME_ENV);
        if (env != null && !env.isBlank()) {
            return Paths.get(env).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".javachess");
    }

    private static void ensureDirectory(Path dir) {
        if (Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.createDirectories(dir);
            restrictToOwner(dir);
        } catch (IOException e) {
            log.error("Cannot create data folder {}: {}", dir, e.getMessage());
        }
    }

    /** Sets owner-only permissions (700 for folders, 600 for files) where the file system supports it. */
    public static void restrictToOwner(Path path) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path) ? OWNER_DIR : OWNER_FILE);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Cannot restrict permissions of {}: {}", path, e.getMessage());
        }
    }
}
