package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Services.GameArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameResumeTest {

    @TempDir
    Path dir;

    static GameSnapshot snapshot(List<String> moves) {
        return new GameSnapshot(GameSnapshot.Mode.PVC, null, moves, true, "club", "STOCKFISH", 10,
                TimeControl.UNLIMITED, 0, 0, 0, 0, LocalDateTime.now(), LocalDateTime.now());
    }

    static ArchivedGame archived(ArchivedGame.GameMode mode, String result, List<String> moves) {
        return new ArchivedGame(0, mode, "", "Giocatore", "Stockfish (1350)", result, "", "", "",
                LocalDateTime.now(), "", "", moves);
    }

    @Test
    void findsOnlyTheInterruptedCopyOfTheSameGame() {
        GameArchiveService archive = new GameArchiveService(dir.resolve("archive.json"), null, dir.resolve("b"));
        List<String> moves = List.of("e2e4", "e7e5", "g1f3");
        archive.add(archived(ArchivedGame.GameMode.PVC, "*", List.of("d2d4", "d7d5")));    // another game
        archive.add(archived(ArchivedGame.GameMode.PVC, "1-0", moves));                    // finished
        archive.add(archived(ArchivedGame.GameMode.PVP, "*", moves));                      // other mode
        assertEquals(0, GameResume.archivedInterruption(snapshot(moves), archive));
        int id = archive.add(archived(ArchivedGame.GameMode.PVC, "*", moves)).id();       // the copy
        assertEquals(id, GameResume.archivedInterruption(snapshot(moves), archive));
        assertEquals(4, archive.size(), "nothing is deleted when resuming");
    }

    @Test
    void aDroppedSavedGameIsKeptInTheArchiveOnce() {
        GameArchiveService archive = new GameArchiveService(dir.resolve("archive.json"), null, dir.resolve("b"));
        GameSnapshot s = snapshot(List.of("e2e4", "c7c5"));
        GameResume.keepInArchive(s, archive);
        assertEquals(1, archive.size());
        ArchivedGame g = archive.list().get(0);
        assertEquals("*", g.result());
        assertEquals("Interrotta", g.termination());
        assertEquals("Giocatore", g.white());
        assertEquals("Stockfish (1350)", g.black());
        assertEquals(ArchivedGame.GameMode.PVC, g.mode());
        GameResume.keepInArchive(s, archive); // already there
        assertEquals(1, archive.size());
        GameResume.keepInArchive(snapshot(List.of()), archive); // nothing played
        assertEquals(1, archive.size());
    }

    @Test
    void finishedPositionsAreNotResumable() {
        assertTrue(GameResume.isResumable(snapshot(List.of("e2e4"))));
        // fool's mate: White is mated
        assertFalse(GameResume.isResumable(snapshot(List.of("f2f3", "e7e5", "g2g4", "d8h4"))));
        assertFalse(GameResume.isResumable(snapshot(List.of("e2e4", "e2e4"))), "illegal moves");
    }

    @Test
    void interruptionMessages() {
        assertTrue(GameResume.isInterruption("Partita interrotta"));
        assertTrue(GameResume.isInterruption("Partita interrotta."));
        assertFalse(GameResume.isInterruption("Il Nero vince per tempo"));
        assertFalse(GameResume.isInterruption("Patta d'accordo"));
        assertFalse(GameResume.isInterruption(null));
    }
}
