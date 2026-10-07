package io.github.hardin22.javachess.Play;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSnapshotStoreTest {

    @TempDir
    Path dir;

    static GameSnapshot sample(List<String> moves) {
        return new GameSnapshot(GameSnapshot.Mode.PVC, null, moves, false, "club", "STOCKFISH", 10,
                TimeControl.minutes(10, 5), 512_000, 498_500, 1, 2, LocalDateTime.of(2026, 10, 7, 21, 0),
                LocalDateTime.of(2026, 10, 7, 21, 15));
    }

    @Test
    void roundTripOnDisk() {
        GameSnapshotStore store = new GameSnapshotStore(dir.resolve("current-game.json"), Runnable::run);
        GameSnapshot s = sample(List.of("e2e4", "c7c5", "g1f3"));
        store.save(s);
        assertTrue(Files.exists(store.file()));
        GameSnapshot read = new GameSnapshotStore(store.file(), Runnable::run).load().orElseThrow();
        assertEquals(s, read);
        assertEquals("rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", read.currentFen());
        assertEquals(2, read.moveNumber());
        assertEquals(TimeControl.minutes(10, 5), read.timeControl());
    }

    @Test
    void clearRemovesTheFile() {
        GameSnapshotStore store = new GameSnapshotStore(dir.resolve("g.json"), Runnable::run);
        store.save(sample(List.of("e2e4")));
        store.clear();
        assertFalse(Files.exists(store.file()));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void damagedOrInconsistentFilesAreNotOffered() throws Exception {
        Path f = dir.resolve("g.json");
        Files.writeString(f, "{ not json");
        assertTrue(new GameSnapshotStore(f, Runnable::run).load().isEmpty());
        Files.writeString(f, sample(List.of("e2e4", "e2e4")).toJson().toString());
        assertTrue(new GameSnapshotStore(f, Runnable::run).load().isEmpty(), "illegal move");
        Files.writeString(f, sample(List.of()).toJson().put("version", 99).toString());
        assertTrue(new GameSnapshotStore(f, Runnable::run).load().isEmpty(), "newer version");
    }

    @Test
    void writesAreCoalescedAndPendingStateIsVisible() {
        List<Runnable> queue = new ArrayList<>();
        GameSnapshotStore store = new GameSnapshotStore(dir.resolve("g.json"), queue::add);
        store.save(sample(List.of("e2e4")));
        store.save(sample(List.of("e2e4", "e7e5")));
        assertEquals(1, queue.size(), "one write scheduled for both saves");
        assertEquals(2, store.load().orElseThrow().moves().size(), "the newest state before it is written");
        queue.get(0).run();
        assertEquals(2, new GameSnapshotStore(store.file(), Runnable::run).load().orElseThrow().moves().size());
    }

    @Test
    void aDifferentGameReplacingTheSavedOneIsReported() {
        Path f = dir.resolve("g.json");
        new GameSnapshotStore(f, Runnable::run).save(sample(List.of("e2e4"))); // left by a power cut
        List<GameSnapshot> replaced = new ArrayList<>();
        GameSnapshotStore store = new GameSnapshotStore(f, Runnable::run);
        store.setOnReplaced(replaced::add);
        GameSnapshot sameGame = sample(List.of("e2e4", "e7e5"));
        store.save(sameGame); // same start time: the same game going on
        assertTrue(replaced.isEmpty());
        GameSnapshot newGame = new GameSnapshot(GameSnapshot.Mode.PVP, null, List.of("d2d4"), true, null, null, 0,
                TimeControl.minutes(5, 0), 300_000, 300_000, 0, 0, LocalDateTime.of(2026, 10, 8, 9, 0), null);
        store.save(newGame);
        assertEquals(List.of(sameGame), replaced);
        store.clear();
        store.save(sample(List.of("c2c4")));
        assertEquals(1, replaced.size(), "after a clear there is nothing to replace");
    }

    @Test
    void advancedKeepsTheSetUp() {
        GameSnapshot s = sample(List.of("e2e4"));
        GameSnapshot next = s.advanced(List.of("e2e4", "e7e5"), 1000, 2000, 3, 4);
        assertEquals("club", next.botLevelId());
        assertEquals(2, next.moves().size());
        assertEquals(3, next.takebacks());
        assertEquals(s.startedAt(), next.startedAt());
        assertTrue(next.savedAt() != null);
    }
}
