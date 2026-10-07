package org.example.javachess.Services;

import org.example.javachess.Oggetti.ArchivedGame;
import org.example.javachess.Oggetti.ArchivedGame.GameMode;
import org.example.javachess.Utils.ErrorReporter;
import org.example.javachess.Utils.PgnCodec;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GameArchiveServiceTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void noDialogs() {
        ErrorReporter.setDialogsEnabled(false);
    }

    private GameArchiveService service() {
        return new GameArchiveService(dir.resolve("data/archive.json"), dir.resolve("legacy/archive.json"),
                dir.resolve("data/backups"));
    }

    private static ArchivedGame scholarsMate() {
        return new ArchivedGame(0, GameMode.PVC, "Stockfish livello 1", "Io", "Stockfish", "*", "", "",
                "10+0", LocalDateTime.of(2026, 1, 12, 18, 22), PgnCodec.START_FEN, "",
                List.of("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"));
    }

    @Test
    void emptyArchiveWhenNothingExists() {
        GameArchiveService s = service();
        assertEquals(0, s.size());
        assertNull(s.getLoadProblem());
        assertFalse(Files.exists(dir.resolve("data/archive.json")));
    }

    @Test
    void createReadUpdateDeleteRoundTrip() throws Exception {
        GameArchiveService s = service();
        ArchivedGame a = s.add(scholarsMate());
        ArchivedGame b = s.add(scholarsMate().withNames("Anna", "Bruno"));
        assertEquals(1, a.id());
        assertEquals(2, b.id());
        // result derived from the final position (mate) because the draft said "*"
        assertEquals("1-0", a.result());
        assertEquals("Scaccomatto", a.termination());
        assertEquals("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4", a.finalFen());

        // reload from disk
        GameArchiveService reloaded = service();
        assertEquals(2, reloaded.size());
        assertEquals(List.of(2, 1), reloaded.list().stream().map(ArchivedGame::id).toList(), "newest first");
        ArchivedGame readBack = reloaded.get(1).orElseThrow();
        assertEquals(a, readBack);

        assertTrue(reloaded.update(readBack.withOpening("Italian Game")));
        assertEquals("Italian Game", service().get(1).orElseThrow().opening());

        assertTrue(reloaded.delete(1));
        assertFalse(reloaded.delete(1));
        assertFalse(reloaded.update(readBack.withId(99)));
        GameArchiveService afterDelete = service();
        assertEquals(1, afterDelete.size());
        // ids are never reused
        assertEquals(3, afterDelete.add(scholarsMate()).id());

        String json = Files.readString(dir.resolve("data/archive.json"));
        JSONObject root = new JSONObject(json);
        assertEquals(GameArchiveService.SCHEMA_VERSION, root.getInt("schemaVersion"));
        assertTrue(root.getJSONArray("games").getJSONObject(0).getString("pgn").contains("4. Qxf7# 1-0"));
    }

    @Test
    void illegalMovesAreDroppedOnAdd() {
        GameArchiveService s = service();
        ArchivedGame g = s.add(new ArchivedGame(0, GameMode.PVP, "", "", "", "*", "", "", "", null, null, null,
                List.of("e2e4", "e7e5", "e1e3", "g1f3")));
        assertEquals(List.of("e2e4", "e7e5"), g.movesUci());
        assertEquals(PgnCodec.START_FEN, g.initialFen());
        assertEquals("?", g.white());
    }

    @Test
    void writesAreAtomicAndLeaveNoTemporaryFiles() throws Exception {
        GameArchiveService s = service();
        for (int i = 0; i < 5; i++) {
            s.add(scholarsMate());
        }
        try (Stream<Path> files = Files.list(dir.resolve("data"))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void firstWriteOfASessionMakesABackup() throws Exception {
        service().add(scholarsMate());
        GameArchiveService second = service();
        second.add(scholarsMate());
        second.add(scholarsMate());
        try (Stream<Path> files = Files.list(dir.resolve("data/backups"))) {
            assertEquals(1, files.count(), "one backup per session");
        }
    }

    @Test
    void corruptFileIsMovedAsideNotOverwritten() throws Exception {
        Path file = dir.resolve("data/archive.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");
        GameArchiveService s = service();
        assertEquals(0, s.size());
        assertNotNull(s.getLoadProblem());
        try (Stream<Path> files = Files.list(dir.resolve("data/backups"))) {
            Path moved = files.filter(p -> p.getFileName().toString().contains("corrupt")).findFirst().orElseThrow();
            assertEquals("{ this is not json", Files.readString(moved));
        }
        s.add(scholarsMate());
        assertEquals(1, service().size());
    }

    @Test
    void newerSchemaIsOpenedReadOnly() throws Exception {
        Path file = dir.resolve("data/archive.json");
        Files.createDirectories(file.getParent());
        String content = "{\"schemaVersion\": 99, \"games\": []}";
        Files.writeString(file, content);
        GameArchiveService s = service();
        assertNotNull(s.getLoadProblem());
        s.add(scholarsMate());
        assertEquals(content, Files.readString(file), "a newer file must never be overwritten");
        String aside = Files.readString(file.resolveSibling("unsaved-games.pgn"));
        assertTrue(aside.contains("4. Qxf7# 1-0"), "the game is kept aside instead of being lost");
    }

    @Test
    void unknownRecordsArePreserved() throws Exception {
        Path file = dir.resolve("data/archive.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schemaVersion\": 2, \"nextId\": 3, \"games\": [{\"id\": \"broken\", \"x\": 1}]}");
        GameArchiveService s = service();
        assertEquals(0, s.size());
        s.add(scholarsMate());
        String json = Files.readString(file);
        assertTrue(json.contains("\"broken\""), json);
        assertTrue(new JSONObject(json).has("unreadable"));
        GameArchiveService again = service();
        assertEquals(1, again.size(), "kept records are never read back as games");
        assertEquals(3, again.list().get(0).id());
        again.add(scholarsMate());
        assertTrue(Files.readString(file).contains("\"broken\""), "still kept after another save");
    }

    @Test
    void migratesSchemaOneFromTheOldWorkingDirectory() throws Exception {
        Path legacy = dir.resolve("legacy/archive.json");
        Files.createDirectories(legacy.getParent());
        try (InputStream in = getClass().getResourceAsStream("/archive/archive-v1.json")) {
            Files.copy(in, legacy);
        }
        String original = Files.readString(legacy, StandardCharsets.UTF_8);

        GameArchiveService s = service();
        assertEquals(5, s.size(), "every game record is migrated");
        assertEquals(original, Files.readString(legacy, StandardCharsets.UTF_8), "old file left untouched");
        assertTrue(Files.exists(dir.resolve("data/archive.json")));

        List<ArchivedGame> games = s.list();
        // ids: 0 -> renumbered, 1 kept, duplicate 1 -> renumbered, 5 and 6 kept
        assertEquals(5, games.stream().map(ArchivedGame::id).distinct().count());
        assertTrue(games.stream().allMatch(g -> g.id() > 0));

        ArchivedGame interrupted = byLabel(games, "Player vs Stockfish livello 20");
        assertEquals(GameMode.PVC, interrupted.mode());
        assertEquals("*", interrupted.result());
        assertEquals("Interrotta", interrupted.termination());
        assertEquals(List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6"), interrupted.movesUci());
        assertEquals(LocalDateTime.of(2024, 9, 5, 3, 57), interrupted.playedAt());
        assertEquals("", interrupted.timeControl());

        ArchivedGame mate = byLabel(games, "Player vs Stockfish livello 1");
        assertEquals("1-0", mate.result());
        assertEquals("Scaccomatto", mate.termination());
        assertEquals("", mate.opening(), "'Error in API Call' is not an opening");
        assertEquals(7, mate.movesUci().size());

        ArchivedGame onTime = byLabel(games, "Player vs Player");
        assertEquals(GameMode.PVP, onTime.mode());
        assertEquals("0-1", onTime.result());
        assertEquals("Tempo", onTime.termination());
        assertEquals("5+3", onTime.timeControl());

        ArchivedGame custom = games.stream().filter(g -> g.initialFen().startsWith("4k3/P7")).findFirst()
                .orElseThrow();
        assertEquals(GameMode.BROWSER, custom.mode());
        assertEquals(List.of("a7a8q", "e8d7"), custom.movesUci(), "missing promotion letter -> queen");
        assertEquals("1/2-1/2", custom.result());
        assertEquals("Patta", custom.termination());

        ArchivedGame broken = games.stream().filter(g -> g.playedAt() == null).findFirst().orElseThrow();
        assertEquals(List.of("e2e4", "e7e5"), broken.movesUci(), "replay stops at the illegal move");

        // exported PGN is parseable again
        String pgn = s.exportAllPgn();
        assertEquals(5, PgnCodec.parsePgn(pgn).size());

        // second start: reads the new file, does not migrate again
        GameArchiveService again = service();
        assertEquals(5, again.size());
    }

    @Test
    void migratesSchemaOneInPlaceWithBackup() throws Exception {
        Path file = dir.resolve("data/archive.json");
        Files.createDirectories(file.getParent());
        try (InputStream in = getClass().getResourceAsStream("/archive/archive-v1.json")) {
            Files.copy(in, file);
        }
        GameArchiveService s = service();
        assertEquals(5, s.size());
        try (Stream<Path> files = Files.list(dir.resolve("data/backups"))) {
            assertEquals(1, files.count(), "original kept as backup");
        }
        assertEquals(2, new JSONObject(Files.readString(file)).getInt("schemaVersion"));
    }

    @Test
    void pgnImportAndExport() throws Exception {
        GameArchiveService s = service();
        String text = """
                [Event "Club"]
                [Date "2025.03.14"]
                [White "Alice"]
                [Black "Bob"]
                [Result "1-0"]
                [Termination "Abbandono"]

                1. e4 e5 2. Nf3 Nc6 1-0

                [Event "Broken"]
                [Result "*"]

                1. e4 e5 2. Qxe7?? *

                [Event "Nothing"]
                1. Zz9 *
                """;
        GameArchiveService.ImportReport report = s.importPgn(text);
        assertEquals(2, report.imported().size());
        assertEquals(1, report.skipped());
        assertEquals(2, report.warnings().size());
        ArchivedGame club = report.imported().get(0);
        assertEquals(GameMode.IMPORTED, club.mode());
        assertEquals("Alice", club.white());
        assertEquals("1-0", club.result());
        assertEquals("Abbandono", club.termination());
        assertEquals(LocalDateTime.of(2025, 3, 14, 0, 0), club.playedAt());
        assertEquals(List.of("e2e4", "e7e5", "g1f3", "b8c6"), club.movesUci());
        assertEquals(List.of("e2e4", "e7e5"), report.imported().get(1).movesUci());

        String exported = s.exportPgn(List.of(club.id(), 12345));
        assertTrue(exported.contains("[White \"Alice\"]"));
        assertTrue(exported.contains("1. e4 e5 2. Nf3 Nc6 1-0"), exported);

        Path out = dir.resolve("export.pgn");
        s.exportAllPgn(out);
        assertEquals(2, PgnCodec.parsePgn(Files.readString(out)).size());
    }

    @Test
    void repetitionIsNotAnAutomaticDraw() {
        GameArchiveService s = service();
        ArchivedGame g = s.add(new ArchivedGame(0, GameMode.PVP, "", "", "", "*", "", "", "", null, null, null,
                List.of("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8")));
        assertEquals("*", g.result(), "threefold repetition must be claimed");
    }

    @Test
    void legacyResultMapping() {
        assertArrayEquals(new String[]{"1-0", "Scaccomatto"},
                GameArchiveService.mapLegacyResult("Scaccomatto! Vince il Bianco.", null));
        assertArrayEquals(new String[]{"0-1", "Scaccomatto"},
                GameArchiveService.mapLegacyResult("Scaccomatto! Vince il Nero.", null));
        assertArrayEquals(new String[]{"0-1", "Tempo"},
                GameArchiveService.mapLegacyResult("Il Nero vince per tempo", null));
        assertArrayEquals(new String[]{"1-0", "Tempo"},
                GameArchiveService.mapLegacyResult("Il Bianco vince per tempo", null));
        assertArrayEquals(new String[]{"1/2-1/2", "Triplice ripetizione"},
                GameArchiveService.mapLegacyResult("Partita patta per Triplice ripetizione.", null));
        assertArrayEquals(new String[]{"1/2-1/2", "Stallo"},
                GameArchiveService.mapLegacyResult("Stallo", null));
        assertArrayEquals(new String[]{"*", "Interrotta"},
                GameArchiveService.mapLegacyResult("Partita interrotta", null));
        assertArrayEquals(new String[]{"*", ""}, GameArchiveService.mapLegacyResult("Unknown", null));
        assertArrayEquals(new String[]{"1/2-1/2", ""}, GameArchiveService.mapLegacyResult("1/2-1/2", null));
    }

    @Test
    void legacySaveGameApiNormalisesInput() throws Exception {
        String savedHome = System.getProperty("javachess.home");
        String savedLegacy = System.getProperty("javachess.legacyDir");
        System.setProperty("javachess.home", dir.resolve("home").toString());
        System.setProperty("javachess.legacyDir", dir.resolve("nolegacy").toString());
        try {
            GameArchiveService.resetInstance();
            GameArchiveService.saveGame("Player vs Stockfish livello 5", "Unknown Opening",
                    "1. e2e4 e7e5 2. f1c4 b8c6 3. d1h5 g8f6 4. h5f7  Scaccomatto! Vince il Bianco.",
                    PgnCodec.START_FEN, "ignored", "Scaccomatto! Vince il Bianco.", "∞");
            GameArchiveService.saveGame("Player vs Player", "", "", PgnCodec.START_FEN, "", "Partita interrotta", "");
            GameArchiveService s = GameArchiveService.getInstance();
            assertEquals(1, s.size(), "games without moves are not archived");
            ArchivedGame g = s.list().get(0);
            assertEquals("1-0", g.result());
            assertEquals(GameMode.PVC, g.mode());
            assertNotNull(g.playedAt());
            assertEquals(7, g.movesUci().size());
        } finally {
            if (savedHome == null) {
                System.clearProperty("javachess.home");
            } else {
                System.setProperty("javachess.home", savedHome);
            }
            if (savedLegacy == null) {
                System.clearProperty("javachess.legacyDir");
            } else {
                System.setProperty("javachess.legacyDir", savedLegacy);
            }
            GameArchiveService.resetInstance();
        }
    }

    private static ArchivedGame byLabel(List<ArchivedGame> games, String label) {
        return games.stream().filter(g -> g.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no game " + label));
    }
}
