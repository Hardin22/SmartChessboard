package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.AppPaths;
import io.github.hardin22.javachess.Utils.AtomicFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * "Ripasso": the puzzles the player did not solve cleanly (a wrong move, a hint, the solution) come back later until
 * they are solved without help. Kept in {@code ~/.javachess/puzzle-review.json} with the whole puzzle (the database
 * has no index by id), oldest first, at most {@value #MAX} puzzles. Thread-safe.
 */
public final class PuzzleReview {

    private static final Logger log = LoggerFactory.getLogger(PuzzleReview.class);
    public static final int MAX = 200;
    /** A puzzle failed just now is not offered again before this many others (or a new session). */
    static final int SPACING = 3;

    /** One puzzle to redo. */
    public record Item(Puzzle puzzle, int failures, LocalDateTime lastFailed) {
    }

    private static volatile PuzzleReview instance;

    private final Path file;
    private final Executor io;
    private final List<Item> items = new ArrayList<>();
    private final List<String> recentlyShown = new ArrayList<>();
    private boolean loaded;

    public static PuzzleReview get() {
        PuzzleReview r = instance;
        if (r == null) {
            synchronized (PuzzleReview.class) {
                r = instance;
                if (r == null) {
                    r = new PuzzleReview(AppPaths.resolve("puzzle-review.json"), AppExecutors.storage());
                    instance = r;
                }
            }
        }
        return r;
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    public PuzzleReview(Path file, Executor io) {
        this.file = file;
        this.io = io;
    }

    /** Called when a puzzle ends: not clean → into the review (or one more failure); clean → out of it. */
    public synchronized void onAttempt(Puzzle puzzle, boolean clean) {
        if (puzzle == null || puzzle.getId() == null) {
            return;
        }
        load();
        int i = indexOf(puzzle.getId());
        if (clean) {
            if (i >= 0) {
                items.remove(i);
                save();
            }
            return;
        }
        int failures = i >= 0 ? items.remove(i).failures() + 1 : 1;
        items.add(new Item(puzzle, failures, LocalDateTime.now().withNano(0)));
        while (items.size() > MAX) {
            items.remove(0);
        }
        recentlyShown.add(puzzle.getId());
        save();
    }

    /** Puzzles waiting to be redone. */
    public synchronized int size() {
        load();
        return items.size();
    }

    /** All of them, oldest first. */
    public synchronized List<Item> items() {
        load();
        return List.copyOf(items);
    }

    /**
     * The next puzzle to redo: the oldest one not seen in the last {@value #SPACING} puzzles (when every one was,
     * the oldest). Empty when there is nothing to review.
     */
    public synchronized Optional<Puzzle> next() {
        load();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        List<String> recent = recentlyShown.subList(Math.max(0, recentlyShown.size() - SPACING),
                recentlyShown.size());
        Puzzle chosen = items.stream().map(Item::puzzle).filter(p -> !recent.contains(p.getId())).findFirst()
                .orElse(items.get(0).puzzle());
        recentlyShown.add(chosen.getId());
        if (recentlyShown.size() > 50) {
            recentlyShown.subList(0, recentlyShown.size() - 50).clear();
        }
        return Optional.of(chosen);
    }

    /** Empties the review. */
    public synchronized void clear() {
        load();
        items.clear();
        save();
    }

    // ------------------------------------------------------------------ storage

    private int indexOf(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).puzzle().getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JSONObject o = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray arr = o.optJSONArray("puzzles");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                try {
                    items.add(fromJson(arr.getJSONObject(i)));
                } catch (RuntimeException e) {
                    log.debug("puzzle review item {} skipped: {}", i, e.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("puzzle review {} not readable: {}", file, e.toString());
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            arr.put(toJson(it));
        }
        String text = new JSONObject().put("schemaVersion", 1).put("puzzles", arr).toString();
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                AtomicFiles.writeString(file, text, false);
            } catch (IOException | RuntimeException e) {
                log.error("cannot save the puzzle review: {}", e.toString());
            }
        });
    }

    private static JSONObject toJson(Item it) {
        Puzzle p = it.puzzle();
        return new JSONObject().put("id", p.getId()).put("fen", p.getFen()).put("moves", new JSONArray(p.getMoves()))
                .put("rating", p.getRating()).put("themes", new JSONArray(p.getThemes() == null ? List.of()
                        : p.getThemes())).put("url", p.getGameUrl() == null ? "" : p.getGameUrl())
                .put("failures", it.failures()).put("lastFailed", String.valueOf(it.lastFailed()));
    }

    private static Item fromJson(JSONObject o) {
        List<String> moves = new ArrayList<>();
        JSONArray m = o.getJSONArray("moves");
        for (int i = 0; i < m.length(); i++) {
            moves.add(m.getString(i));
        }
        List<String> themes = new ArrayList<>();
        JSONArray t = o.optJSONArray("themes");
        for (int i = 0; t != null && i < t.length(); i++) {
            themes.add(t.getString(i));
        }
        Puzzle p = new Puzzle(o.getString("id"), o.getString("fen"), moves, o.optInt("rating", 1500), 0, 0, 0,
                themes, o.optString("url", ""), "");
        LocalDateTime at = null;
        try {
            at = LocalDateTime.parse(o.optString("lastFailed", ""));
        } catch (RuntimeException ignored) {
            // unknown
        }
        return new Item(p, o.optInt("failures", 1), at);
    }
}
