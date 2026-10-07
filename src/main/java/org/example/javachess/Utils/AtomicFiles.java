package org.example.javachess.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Crash-safe file writes: data goes to a temporary file in the same folder, is flushed to disk and then
 * renamed over the target, so a power cut (common on a Raspberry Pi) leaves either the old or the new file,
 * never a truncated one.
 */
public final class AtomicFiles {

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private AtomicFiles() {
    }

    public static void writeString(Path target, String content, boolean ownerOnly) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8), ownerOnly);
    }

    public static void write(Path target, byte[] content, boolean ownerOnly) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            if (ownerOnly) {
                AppPaths.restrictToOwner(tmp);
            }
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Copies {@code source} into {@code backupDir} with a timestamp suffix and keeps only the newest
     * {@code keep} backups of that file. Returns the backup path, or null if the source does not exist.
     */
    public static Path backup(Path source, Path backupDir, int keep) throws IOException {
        if (!Files.exists(source)) {
            return null;
        }
        Files.createDirectories(backupDir);
        String name = source.getFileName().toString();
        Path backup = backupDir.resolve(name + "." + LocalDateTime.now().format(BACKUP_STAMP) + ".bak");
        Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
        AppPaths.restrictToOwner(backup);
        prune(backupDir, name, keep);
        return backup;
    }

    private static void prune(Path backupDir, String name, int keep) throws IOException {
        List<Path> backups = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, name + ".*.bak")) {
            stream.forEach(backups::add);
        }
        backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (int i = Math.max(keep, 0); i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }
}
