package org.example.javachess.Utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {

    @TempDir
    Path dir;

    @Test
    void replacesContentWithoutLeavingTemporaryFiles() throws Exception {
        Path f = dir.resolve("a/b/file.json");
        AtomicFiles.writeString(f, "one", false);
        AtomicFiles.writeString(f, "two", true);
        assertEquals("two", Files.readString(f));
        try (Stream<Path> s = Files.list(f.getParent())) {
            assertEquals(1, s.count());
        }
    }

    @Test
    void backupsAreRotated() throws Exception {
        Path f = dir.resolve("archive.json");
        Files.writeString(f, "x");
        Path backups = dir.resolve("backups");
        for (int i = 0; i < 7; i++) {
            assertNotNull(AtomicFiles.backup(f, backups, 3));
            Thread.sleep(2);
        }
        try (Stream<Path> s = Files.list(backups)) {
            assertEquals(3, s.count());
        }
        assertNull(AtomicFiles.backup(dir.resolve("missing"), backups, 3));
    }
}
