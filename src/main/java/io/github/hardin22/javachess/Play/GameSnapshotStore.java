package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.AppPaths;
import io.github.hardin22.javachess.Utils.AtomicFiles;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The game in progress on disk ({@code ~/.javachess/current-game.json}), so it can be resumed after a restart, a
 * crash or a power cut (the Raspberry Pi is often switched off at the plug).
 *
 * <ul>
 *   <li>Written after every move, atomically (temporary file + rename): a power cut leaves the previous version.</li>
 *   <li>Writes run on the storage thread and are coalesced: only the latest snapshot is written.</li>
 *   <li>Cleared when the game ends with a result, or when the player discards it; a game left half-way (app closed,
 *       screen left) stays resumable.</li>
 *   <li>A damaged or inconsistent file is ignored (and left on disk for inspection until the next game).</li>
 * </ul>
 */
public final class GameSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(GameSnapshotStore.class);
    private static volatile GameSnapshotStore instance;

    private final Path file;
    private final Executor io;
    private final AtomicReference<Optional<GameSnapshot>> pending = new AtomicReference<>();
    private final Object writeLock = new Object();

    public static GameSnapshotStore get() {
        GameSnapshotStore s = instance;
        if (s == null) {
            synchronized (GameSnapshotStore.class) {
                s = instance;
                if (s == null) {
                    s = new GameSnapshotStore(AppPaths.resolve("current-game.json"), AppExecutors.storage());
                    instance = s;
                }
            }
        }
        return s;
    }

    /** Forgets the shared instance (tests change {@code javachess.home}). */
    public static synchronized void resetInstance() {
        instance = null;
    }

    /** @param io executor of the writes (a direct executor in tests) */
    public GameSnapshotStore(Path file, Executor io) {
        this.file = file;
        this.io = io;
    }

    public Path file() {
        return file;
    }

    /** Saves {@code snapshot} in the background (the latest call wins). */
    public void save(GameSnapshot snapshot) {
        submit(Optional.of(snapshot));
    }

    /** Removes the saved game in the background. */
    public void clear() {
        submit(Optional.empty());
    }

    /**
     * The saved game, if there is one that can be resumed. Blocking file read (small): call it off the JavaFX thread
     * when possible.
     */
    public Optional<GameSnapshot> load() {
        Optional<GameSnapshot> queued = pending.get();
        if (queued != null) {
            return queued; // not written yet: the newest state is in memory
        }
        synchronized (writeLock) {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                GameSnapshot s = GameSnapshot.fromJson(new JSONObject(text));
                if (!s.isConsistent()) {
                    log.warn("saved game {} has illegal moves: not offered for resuming", file);
                    return Optional.empty();
                }
                return Optional.of(s);
            } catch (IOException | RuntimeException e) {
                log.warn("saved game {} not readable: {}", file, e.toString());
                return Optional.empty();
            }
        }
    }

    private void submit(Optional<GameSnapshot> value) {
        if (pending.getAndSet(value) == null) {
            io.execute(this::flush);
        }
    }

    private void flush() {
        Optional<GameSnapshot> value = pending.getAndSet(null);
        if (value == null) {
            return;
        }
        synchronized (writeLock) {
            try {
                if (value.isPresent()) {
                    Files.createDirectories(file.getParent());
                    AtomicFiles.writeString(file, value.get().toJson().toString(1), false);
                } else {
                    Files.deleteIfExists(file);
                }
            } catch (IOException | RuntimeException e) {
                log.error("cannot write the game in progress to {}: {}", file, e.toString());
            }
        }
    }
}
