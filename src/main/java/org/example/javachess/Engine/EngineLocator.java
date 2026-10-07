package org.example.javachess.Engine;

import org.example.javachess.Utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds engine binaries portably, in this order:
 * <ol>
 *   <li>the path configured in {@code config.properties} ({@code stockfish.path}, {@code lc0.path});</li>
 *   <li>the project's {@code engines/} folder ({@code engines/<name>}, {@code engines/<name>/<name>*},
 *       also next to the application jar, or {@code -Djavachess.engines.dir});</li>
 *   <li>the {@code PATH} plus the usual install locations ({@code /opt/homebrew/bin}, {@code /usr/local/bin},
 *       {@code /usr/games} where Debian/Raspberry Pi OS put Stockfish).</li>
 * </ol>
 */
public final class EngineLocator {

    private static final Logger log = LoggerFactory.getLogger(EngineLocator.class);

    private EngineLocator() {
    }

    /** Result of a lookup: the binary, or the list of places that were checked (for the error message). */
    public record Lookup(String engine, Optional<Path> path, List<String> checked) {
        public String describeMissing() {
            return engine + " not found. Checked: " + String.join(", ", checked)
                    + ". Install it (scripts/install-engines.sh on the Raspberry Pi, 'brew install " + engine
                    + "' on macOS) or set " + engine + ".path in config.properties.";
        }
    }

    public static Lookup stockfish() {
        return find("stockfish", "stockfish.path");
    }

    public static Lookup lc0() {
        return find("lc0", "lc0.path");
    }

    /** Directory holding engine assets (Maia weights...), first existing candidate or "engines". */
    public static Path enginesDir() {
        for (Path p : engineDirs()) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Paths.get("engines");
    }

    public static Lookup find(String name, String configKey) {
        Set<String> checked = new LinkedHashSet<>();
        String configured = ConfigManager.getProperty(configKey, "").trim();
        if (!configured.isEmpty()) {
            Path p = Paths.get(expandHome(configured));
            checked.add(configKey + "=" + configured);
            if (isExecutable(p)) {
                return new Lookup(name, Optional.of(p), List.copyOf(checked));
            }
            log.warn("{}={} is not an executable file, looking elsewhere", configKey, configured);
        }
        for (Path dir : engineDirs()) {
            for (Path candidate : candidatesIn(dir, name)) {
                checked.add(candidate.toString());
                if (isExecutable(candidate)) {
                    return new Lookup(name, Optional.of(candidate), List.copyOf(checked));
                }
            }
        }
        for (Path dir : searchPath()) {
            Path candidate = dir.resolve(name);
            if (isExecutable(candidate)) {
                return new Lookup(name, Optional.of(candidate), List.copyOf(checked));
            }
        }
        checked.add("PATH");
        return new Lookup(name, Optional.empty(), List.copyOf(checked));
    }

    private static List<Path> engineDirs() {
        List<Path> dirs = new ArrayList<>();
        String prop = System.getProperty("javachess.engines.dir");
        if (prop != null && !prop.isBlank()) {
            dirs.add(Paths.get(expandHome(prop)));
        }
        dirs.add(Paths.get("engines").toAbsolutePath());
        try {
            Path code = Paths.get(EngineLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path base = Files.isDirectory(code) ? code : code.getParent();
            // target/classes -> project root, or app dir next to the jar
            for (int i = 0; i < 3 && base != null; i++, base = base.getParent()) {
                Path e = base.resolve("engines");
                if (!dirs.contains(e)) {
                    dirs.add(e);
                }
            }
        } catch (Exception ignored) {
            // code source not available (tests, custom class loaders)
        }
        return dirs;
    }

    private static List<Path> candidatesIn(Path dir, String name) {
        List<Path> out = new ArrayList<>();
        out.add(dir.resolve(name));
        Path sub = dir.resolve(name);
        if (Files.isDirectory(sub)) {
            out.add(sub.resolve(name));
            try (Stream<Path> files = Files.list(sub)) {
                files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(name))
                        .filter(f -> !f.getFileName().toString().endsWith(".tar")
                                && !f.getFileName().toString().endsWith(".gz")
                                && !f.getFileName().toString().endsWith(".zip"))
                        .sorted()
                        .forEach(out::add);
            } catch (Exception ignored) {
                // unreadable directory
            }
        }
        return out;
    }

    private static List<Path> searchPath() {
        Set<Path> dirs = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(Paths.get(d));
                }
            }
        }
        for (String d : new String[] { "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/usr/games",
                System.getProperty("user.home") + "/.local/bin" }) {
            dirs.add(Paths.get(d));
        }
        return new ArrayList<>(dirs);
    }

    private static boolean isExecutable(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }

    private static String expandHome(String s) {
        return s.startsWith("~/") ? System.getProperty("user.home") + s.substring(1) : s;
    }
}
