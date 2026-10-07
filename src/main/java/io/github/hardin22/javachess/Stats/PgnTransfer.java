package io.github.hardin22.javachess.Stats;

import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Utils.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Moving games in and out of the board without a keyboard: PGN files on a USB stick. Finds the removable drives
 * (Raspberry Pi OS mounts them under {@code /media/<user>/}, macOS under {@code /Volumes/}), lists the PGN files on
 * them, imports one into the archive and exports the whole archive to a dated file. Blocking file I/O: call it off
 * the JavaFX thread.
 */
public final class PgnTransfer {

    private static final Logger log = LoggerFactory.getLogger(PgnTransfer.class);
    private static final int MAX_DEPTH = 3;
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    /** A drive the user can pick: label for the list and its root. */
    public record Drive(String label, Path root) {
    }

    /** A PGN file found on a drive. */
    public record PgnFile(Path path, String name, long bytes) {
        /** "partite.pgn · 120 KB". */
        public String description() {
            long kb = Math.max(1, bytes / 1024);
            return name + " · " + (kb >= 1024 ? (kb / 1024) + " MB" : kb + " KB");
        }
    }

    private final List<Path> mountRoots;

    /** The usual mount points of this system. */
    public PgnTransfer() {
        this(defaultMountRoots());
    }

    /** @param mountRoots folders whose sub-folders are drives (e.g. {@code /media/pi}) */
    public PgnTransfer(List<Path> mountRoots) {
        this.mountRoots = List.copyOf(mountRoots);
    }

    static List<Path> defaultMountRoots() {
        List<Path> roots = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            roots.add(Path.of("/Volumes"));
        } else {
            String user = System.getProperty("user.name", "");
            roots.add(Path.of("/media", user));
            roots.add(Path.of("/run/media", user));
            roots.add(Path.of("/media"));
            roots.add(Path.of("/mnt"));
        }
        return roots;
    }

    /** Drives plugged in now (empty when none). */
    public List<Drive> drives() {
        List<Drive> out = new ArrayList<>();
        for (Path root : mountRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.list(root)) {
                s.filter(Files::isDirectory).filter(Files::isWritable).filter(p -> !isSystemVolume(p))
                        .sorted().forEach(p -> {
                            if (out.stream().noneMatch(d -> d.root().equals(p))) {
                                out.add(new Drive(p.getFileName().toString(), p));
                            }
                        });
            } catch (IOException e) {
                log.debug("cannot list {}: {}", root, e.toString());
            }
        }
        return out;
    }

    private static boolean isSystemVolume(Path p) {
        String n = p.getFileName().toString();
        return n.equals("Macintosh HD") || n.startsWith(".") || n.equals("Recovery") || n.equals(System.getProperty(
                "user.name", "\0")) && p.getParent() != null && p.getParent().toString().equals("/media");
    }

    /**
     * PGN files on {@code drive} (a few folder levels deep), newest first. Unreadable or hidden folders
     * ({@code lost+found}, {@code .Trashes}, {@code System Volume Information}) are skipped, not fatal.
     */
    public List<PgnFile> pgnFiles(Drive drive) {
        List<PgnFile> out = new ArrayList<>();
        try {
            Files.walkFileTree(drive.root(), java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                                java.nio.file.attribute.BasicFileAttributes attrs) {
                            String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            boolean skip = !dir.equals(drive.root()) && (n.startsWith(".") || n.equals("lost+found")
                                    || n.equals("System Volume Information") || n.startsWith("$"));
                            return skip ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                                    : java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path p,
                                java.nio.file.attribute.BasicFileAttributes attrs) {
                            String n = p.getFileName().toString();
                            if (attrs.isRegularFile() && !n.startsWith(".")
                                    && n.toLowerCase(Locale.ROOT).endsWith(".pgn")) {
                                out.add(new PgnFile(p, n, attrs.size()));
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path p, IOException e) {
                            log.debug("skipped {}: {}", p, e.toString());
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            log.info("cannot read the drive {}: {}", drive.root(), e.toString());
        }
        out.sort(Comparator.comparing((PgnFile f) -> lastModified(f.path())).reversed());
        return out;
    }

    /** Games per block of an import (each block is parsed and written on its own: progress, bounded memory). */
    static final int BLOCK = 300;

    /** Imports a PGN file into the archive (every game). */
    public GameArchiveService.ImportReport importFile(PgnFile file, GameArchiveService archive) throws IOException {
        return importFile(file, archive, Integer.MAX_VALUE, null);
    }

    /**
     * Imports at most {@code maxGames} games of a PGN file (the first ones), in blocks, telling {@code progress}
     * the fraction done (0..1, called on this thread). A big file takes minutes on a Raspberry Pi: use
     * {@link #countGames} first to warn or to choose a limit.
     */
    public GameArchiveService.ImportReport importFile(PgnFile file, GameArchiveService archive, int maxGames,
                                                      java.util.function.DoubleConsumer progress) throws IOException {
        if (file.bytes() > MAX_FILE_BYTES) {
            throw new IOException("file troppo grande (" + file.bytes() / (1024 * 1024) + " MB)");
        }
        List<String> games = splitGames(Files.readString(file.path(), java.nio.charset.StandardCharsets.UTF_8));
        int total = Math.min(games.size(), Math.max(0, maxGames));
        List<io.github.hardin22.javachess.Oggetti.ArchivedGame> imported = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        for (int from = 0; from < total; from += BLOCK) {
            int to = Math.min(total, from + BLOCK);
            GameArchiveService.ImportReport r = archive.importPgn(String.join("\n\n", games.subList(from, to)));
            imported.addAll(r.imported());
            warnings.addAll(r.warnings());
            skipped += r.skipped();
            if (progress != null) {
                progress.accept(to / (double) total);
            }
        }
        if (games.size() > total) {
            warnings.add("Importate le prime " + total + " partite di " + games.size());
        }
        return new GameArchiveService.ImportReport(imported, skipped, warnings);
    }

    /** Games in a PGN file, counted without parsing the moves (fast, for a warning before a long import). */
    public static int countGames(PgnFile file) {
        try (Stream<String> lines = Files.lines(file.path(), java.nio.charset.StandardCharsets.UTF_8)) {
            int[] count = new int[1];
            boolean[] inTags = {false};
            lines.forEach(line -> {
                boolean tag = line.startsWith("[");
                if (tag && !inTags[0]) {
                    count[0]++;
                }
                if (!line.isBlank()) {
                    inTags[0] = tag;
                }
            });
            return count[0];
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }

    /** Splits PGN text into games: a game starts at a tag line that follows moves (or the start of the text). */
    static List<String> splitGames(String text) {
        List<String> games = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inTags = false;
        boolean hasMoves = false;
        for (String line : text.split("\\R")) {
            boolean tag = line.startsWith("[");
            if (tag && !inTags && current.length() > 0 && hasMoves) {
                games.add(current.toString());
                current.setLength(0);
                hasMoves = false;
            }
            current.append(line).append('\n');
            if (!line.isBlank()) {
                inTags = tag;
                hasMoves |= !tag;
            }
        }
        if (current.toString().strip().length() > 0) {
            games.add(current.toString());
        }
        return games;
    }

    /** Writes the whole archive to {@code javachess-partite-YYYY-MM-DD.pgn} on the drive; returns the file. */
    public Path exportAll(Drive drive, GameArchiveService archive) throws IOException {
        String day = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path target = drive.root().resolve("javachess-partite-" + day + ".pgn");
        AtomicFiles.writeString(target, archive.exportAllPgn(), false);
        return target;
    }

    /** Italian summary of an import ("12 partite importate, 1 ignorata"). */
    public static String describe(GameArchiveService.ImportReport r) {
        int n = r.imported().size();
        String s = n + (n == 1 ? " partita importata" : " partite importate");
        if (r.skipped() > 0) {
            s += ", " + r.skipped() + (r.skipped() == 1 ? " ignorata" : " ignorate");
        }
        return s;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
