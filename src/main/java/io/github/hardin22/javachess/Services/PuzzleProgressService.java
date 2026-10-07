package io.github.hardin22.javachess.Services;

import io.github.hardin22.javachess.Utils.AppPaths;
import io.github.hardin22.javachess.Utils.AtomicFiles;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Puzzle progress: every attempt, the player's puzzle rating (Elo-style), streaks and per-theme statistics.
 * Stored in {@code ~/.javachess/puzzle-progress.json} (schema version 1) with atomic writes; a damaged file is moved
 * to the backups folder and progress restarts. Thread-safe.
 *
 * <p>Usage (from the puzzle screen, off the JavaFX thread if possible):
 * <pre>{@code
 * PuzzleProgressService.getInstance().record(puzzle.getId(), puzzle.getRating(), themes, solvedWithoutMistakes);
 * int myRating = PuzzleProgressService.getInstance().getRating();
 * }</pre>
 */
public class PuzzleProgressService {

    private static final Logger log = LoggerFactory.getLogger(PuzzleProgressService.class);
    public static final int SCHEMA_VERSION = 1;
    public static final int INITIAL_RATING = 1500;
    private static final int MAX_ATTEMPTS_KEPT = 10_000;

    private static volatile PuzzleProgressService instance;

    private final Path file;
    private final Path backupDir;
    private final List<Attempt> attempts = new ArrayList<>();
    private final Set<String> solved = new HashSet<>();
    private double rating = INITIAL_RATING;
    /** True when the file must not be overwritten (newer schema, or a damaged file that could not be moved). */
    private boolean readOnly;

    /** One attempt at a puzzle. */
    public record Attempt(String puzzleId, int puzzleRating, List<String> themes, boolean solved,
                          LocalDateTime at, double ratingAfter) {
    }

    /** Summary for the dashboard. */
    public record Stats(int rating, int attempts, int solved, int currentStreak, int bestStreak,
                        Map<String, int[]> byTheme) {
        /** Success rate 0..1 (0 when no attempt). */
        public double successRate() {
            return attempts == 0 ? 0 : solved / (double) attempts;
        }
    }

    public static PuzzleProgressService getInstance() {
        PuzzleProgressService local = instance;
        if (local == null) {
            synchronized (PuzzleProgressService.class) {
                local = instance;
                if (local == null) {
                    local = new PuzzleProgressService(AppPaths.resolve("puzzle-progress.json"), AppPaths.backupsDir());
                    instance = local;
                }
            }
        }
        return local;
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    public PuzzleProgressService(Path file, Path backupDir) {
        this.file = file;
        this.backupDir = backupDir;
        load();
    }

    /**
     * Records an attempt and updates the rating. {@code solved} = solved without a wrong move.
     *
     * @return the stored attempt (with the new rating)
     */
    public synchronized Attempt record(String puzzleId, int puzzleRating, List<String> themes, boolean solvedIt) {
        int n = attempts.size();
        double k = n < 20 ? 40 : n < 100 ? 24 : 16; // move fast while the rating is still unknown
        double expected = 1.0 / (1.0 + Math.pow(10, (puzzleRating - rating) / 400.0));
        rating = Math.max(100, Math.min(3500, rating + k * ((solvedIt ? 1 : 0) - expected)));
        Attempt a = new Attempt(puzzleId == null ? "" : puzzleId, puzzleRating,
                themes == null ? List.of() : List.copyOf(themes), solvedIt, LocalDateTime.now().withNano(0), rating);
        attempts.add(a);
        if (solvedIt) {
            solved.add(a.puzzleId());
        }
        while (attempts.size() > MAX_ATTEMPTS_KEPT) {
            attempts.remove(0);
        }
        save();
        return a;
    }

    public synchronized int getRating() {
        return (int) Math.round(rating);
    }

    public synchronized boolean isSolved(String puzzleId) {
        return solved.contains(puzzleId);
    }

    /** Most recent attempts first. */
    public synchronized List<Attempt> recentAttempts(int max) {
        List<Attempt> copy = new ArrayList<>(attempts);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(max, copy.size()));
    }

    public synchronized Stats getStats() {
        int solvedCount = 0;
        int streak = 0;
        int best = 0;
        Map<String, int[]> byTheme = new TreeMap<>();
        for (Attempt a : attempts) {
            if (a.solved()) {
                solvedCount++;
                streak++;
                best = Math.max(best, streak);
            } else {
                streak = 0;
            }
            for (String t : a.themes()) {
                int[] c = byTheme.computeIfAbsent(t, x -> new int[2]);
                c[0]++;
                if (a.solved()) {
                    c[1]++;
                }
            }
        }
        return new Stats(getRating(), attempts.size(), solvedCount, streak, best, byTheme);
    }

    /** Deletes all progress (a backup of the old file is kept). */
    public synchronized void reset() {
        try {
            AtomicFiles.backup(file, backupDir, 5);
        } catch (IOException e) {
            log.warn("Cannot back up puzzle progress: {}", e.getMessage());
        }
        attempts.clear();
        solved.clear();
        rating = INITIAL_RATING;
        save();
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            if (root.optInt("schemaVersion", 0) > SCHEMA_VERSION) {
                log.warn("Puzzle progress written by a newer version: read-only, it will not be overwritten");
                readOnly = true;
            }
            rating = root.optDouble("rating", INITIAL_RATING);
            if (!Double.isFinite(rating)) {
                rating = INITIAL_RATING;
            }
            JSONArray arr = root.optJSONArray("attempts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    List<String> themes = new ArrayList<>();
                    JSONArray t = o.optJSONArray("themes");
                    if (t != null) {
                        for (int j = 0; j < t.length(); j++) {
                            themes.add(t.optString(j));
                        }
                    }
                    LocalDateTime at = null;
                    try {
                        at = LocalDateTime.parse(o.optString("at", ""));
                    } catch (DateTimeParseException ignored) {
                        // keep null
                    }
                    Attempt a = new Attempt(o.optString("id", ""), o.optInt("rating", 0), themes,
                            o.optBoolean("solved", false), at, o.optDouble("ratingAfter", rating));
                    attempts.add(a);
                    if (a.solved()) {
                        solved.add(a.puzzleId());
                    }
                }
            }
        } catch (IOException | JSONException e) {
            log.error("Puzzle progress file {} unreadable ({}); starting again, old file kept in backups", file,
                    e.getMessage());
            try {
                Files.createDirectories(backupDir);
                Files.move(file, backupDir.resolve(file.getFileName() + ".corrupt-"
                                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                log.warn("Cannot move the damaged file, progress will not be saved: {}", moveError.getMessage());
                readOnly = true; // never overwrite the only copy
            }
            attempts.clear();
            solved.clear();
            rating = INITIAL_RATING;
        }
    }

    private void save() {
        if (readOnly) {
            return;
        }
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("rating", Math.round(rating * 10) / 10.0);
        JSONArray arr = new JSONArray();
        for (Attempt a : attempts) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.puzzleId());
            o.put("rating", a.puzzleRating());
            o.put("themes", new JSONArray(a.themes()));
            o.put("solved", a.solved());
            o.put("at", a.at() == null ? "" : a.at().toString());
            o.put("ratingAfter", Math.round(a.ratingAfter() * 10) / 10.0);
            arr.put(new JSONObject(o));
        }
        root.put("attempts", arr);
        try {
            AtomicFiles.writeString(file, root.toString(1), true);
        } catch (IOException e) {
            log.error("Cannot save puzzle progress: {}", e.getMessage());
        }
    }
}
