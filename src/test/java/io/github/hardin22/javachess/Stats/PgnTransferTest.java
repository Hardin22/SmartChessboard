package io.github.hardin22.javachess.Stats;

import io.github.hardin22.javachess.Services.GameArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgnTransferTest {

    @TempDir
    Path tmp;

    @Test
    void findsDrivesAndPgnFilesThenImportsAndExports() throws Exception {
        Path media = Files.createDirectories(tmp.resolve("media"));
        Path stick = Files.createDirectories(media.resolve("CHIAVETTA"));
        Files.createDirectories(stick.resolve("scacchi/torneo"));
        Files.writeString(stick.resolve("scacchi/torneo/round1.pgn"), """
                [White "A"]
                [Black "B"]
                [Result "1-0"]

                1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0
                """);
        Files.writeString(stick.resolve("note.txt"), "x");
        Files.writeString(stick.resolve(".hidden.pgn"), "x");
        PgnTransfer t = new PgnTransfer(List.of(media, tmp.resolve("missing")));
        List<PgnTransfer.Drive> drives = t.drives();
        assertEquals(1, drives.size());
        assertEquals("CHIAVETTA", drives.get(0).label());
        List<PgnTransfer.PgnFile> files = t.pgnFiles(drives.get(0));
        assertEquals(1, files.size());
        assertEquals("round1.pgn · 1 KB", files.get(0).description());

        GameArchiveService archive = new GameArchiveService(tmp.resolve("archive.json"), null, tmp.resolve("b"));
        GameArchiveService.ImportReport report = t.importFile(files.get(0), archive);
        assertEquals("1 partita importata", PgnTransfer.describe(report));
        assertEquals(1, archive.size());

        Path exported = t.exportAll(drives.get(0), archive);
        assertTrue(exported.getFileName().toString().startsWith("javachess-partite-"));
        assertTrue(Files.readString(exported).contains("Qxf7#"));
    }

    @Test
    void hiddenAndUnreadableFoldersAreSkipped() throws Exception {
        Path media = Files.createDirectories(tmp.resolve("m"));
        Path stick = Files.createDirectories(media.resolve("USB"));
        Path locked = Files.createDirectories(stick.resolve("aaa-locked"));
        Files.writeString(locked.resolve("x.pgn"), "x");
        Files.createDirectories(stick.resolve(".Trashes"));
        Files.writeString(stick.resolve(".Trashes/old.pgn"), "x");
        Files.writeString(stick.resolve("z-partite.pgn"), "x");
        locked.toFile().setReadable(false);
        locked.toFile().setExecutable(false);
        try {
            PgnTransfer t = new PgnTransfer(List.of(media));
            List<PgnTransfer.PgnFile> files = t.pgnFiles(t.drives().get(0));
            assertEquals(List.of("z-partite.pgn"), files.stream().map(PgnTransfer.PgnFile::name).toList());
        } finally {
            locked.toFile().setReadable(true);
            locked.toFile().setExecutable(true);
        }
    }

    @Test
    void noDrives() {
        assertTrue(new PgnTransfer(List.of(tmp.resolve("nothing"))).drives().isEmpty());
    }
}
