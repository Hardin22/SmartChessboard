package io.github.hardin22.javachess.Services;

import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Utils.AppExecutors;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Puzzles from the local Lichess database.
 *
 * <p>Uses the compact database built by {@code scripts/build-puzzle-db.sh} (memory-mapped, a few KB of heap,
 * a lookup in milliseconds). Without it, falls back to sampling the raw CSV. Neither file is inside the jar:
 * they are looked up in {@code -Djavachess.puzzles=<file>}, {@code data/}, then {@code ~/.javachess/}.
 * All lookups may take time on a Raspberry Pi: call them off the JavaFX thread ({@link #findPuzzleAsync}).</p>
 */
public class PuzzleService {

    private static final Logger log = LoggerFactory.getLogger(PuzzleService.class);
    private static final String DAILY_PUZZLE_URL = "https://lichess.org/api/puzzle/daily";
    private static final int CANDIDATES = 10;

    private static PuzzleService instance;

    private final Object dbLock = new Object();
    private PuzzleDatabase database;
    private boolean databaseChecked;

    private PuzzleService() {
    }

    public static synchronized PuzzleService getInstance() {
        if (instance == null) {
            instance = new PuzzleService();
        }
        return instance;
    }

    // --- locations -----------------------------------------------------------------------------------------

    private static Path userHome() {
        return io.github.hardin22.javachess.Utils.AppPaths.dataDir(); // honours -Djavachess.home and JAVACHESS_HOME
    }

    /** Where the compact database is looked for, in order. */
    public static List<Path> databaseLocations() {
        List<Path> paths = new ArrayList<>();
        String configured = System.getProperty("javachess.puzzles");
        if (configured != null && !configured.isBlank()) {
            paths.add(Path.of(configured));
        }
        paths.add(Path.of("data", "puzzles.db"));
        paths.add(userHome().resolve("puzzles.db"));
        return paths;
    }

    /** Where the raw Lichess CSV is looked for (fallback), in order. */
    public static List<Path> csvLocations() {
        return List.of(Path.of("data", "puzzles.csv"), userHome().resolve("puzzles.csv"),
                Path.of("src", "main", "resources", "data", "puzzles.csv"), Path.of("puzzles.csv"));
    }

    private PuzzleDatabase database() {
        synchronized (dbLock) {
            if (!databaseChecked) {
                databaseChecked = true;
                for (Path path : databaseLocations()) {
                    if (Files.isRegularFile(path)) {
                        try {
                            long start = System.nanoTime();
                            database = PuzzleDatabase.open(path);
                            log.info("Puzzle database {}: {} puzzles, opened in {} ms", path.toAbsolutePath(),
                                    database.size(), (System.nanoTime() - start) / 1_000_000);
                            break;
                        } catch (IOException e) {
                            log.warn("Cannot open puzzle database {}: {}", path, e.getMessage());
                        }
                    }
                }
                if (database == null) {
                    log.info("No puzzle database found ({}); using the CSV. Build it with scripts/build-puzzle-db.sh",
                            databaseLocations());
                }
            }
            return database;
        }
    }

    // --- queries -------------------------------------------------------------------------------------------

    /** Same as {@link #findPuzzle} on a background thread. */
    public CompletableFuture<Puzzle> findPuzzleAsync(int targetRating, int range, List<String> themes) {
        return CompletableFuture.supplyAsync(() -> findPuzzle(targetRating, range, themes), AppExecutors.compute());
    }

    /**
     * A random puzzle rated targetRating±range with any of {@code themes} ("Tutti" or empty = any), widening
     * the rating window if nothing matches. Null when nothing is found. Blocking.
     */
    public Puzzle findPuzzle(int targetRating, int range, List<String> themes) {
        PuzzleDatabase db = database();
        if (db != null) {
            for (int window = range; window <= 3200; window *= 2) {
                Puzzle puzzle = db.random(targetRating, window, themes);
                if (puzzle != null) {
                    return puzzle;
                }
            }
            log.info("No puzzle for rating {} and themes {}", targetRating, themes);
            return null;
        }
        return findInCsv(targetRating, range, themes);
    }

    public List<Puzzle> getPuzzlesByRating(int rating, int range) {
        return getPuzzlesByThemeAndRating(null, rating, range);
    }

    /** Up to ten random candidates. Blocking: call off the JavaFX thread. */
    public List<Puzzle> getPuzzlesByThemeAndRating(List<String> themes, int rating, int range) {
        List<Puzzle> found = new ArrayList<>();
        int attempts = database() != null ? CANDIDATES : 1; // the CSV fallback is slow: one is enough
        for (int i = 0; i < attempts; i++) {
            Puzzle p = findPuzzle(rating, range, themes);
            if (p != null) {
                found.add(p);
            }
        }
        return found;
    }

    public Puzzle getRandomPuzzle(List<Puzzle> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    // --- CSV fallback --------------------------------------------------------------------------------------

    private Puzzle findInCsv(int targetRating, int range, List<String> themes) {
        Path csv = csvLocations().stream().filter(Files::isRegularFile).findFirst().orElse(null);
        if (csv == null) {
            log.warn("No puzzle data found (looked for {} and {})", databaseLocations(), csvLocations());
            return null;
        }
        boolean anyTheme = themes == null || themes.isEmpty() || themes.contains("Tutti");
        try (FileChannel channel = FileChannel.open(csv, StandardOpenOption.READ)) {
            long length = channel.size();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int seek = 0; seek < 20; seek++) {
                channel.position(random.nextLong(Math.max(1, length - 10_000)));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8), 1 << 16);
                reader.readLine(); // partial line
                for (int i = 0; i < 2000; i++) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    Puzzle p = parseCsvLine(line);
                    if (p != null && Math.abs(p.getRating() - targetRating) <= range
                            && (anyTheme || themes.stream().anyMatch(t -> p.getThemes().stream().anyMatch(t::equalsIgnoreCase)))) {
                        return p;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", csv, e.getMessage());
        }
        log.info("No puzzle found in the CSV for {} +/-{} {}", targetRating, range, themes);
        return null;
    }

    static Puzzle parseCsvLine(String line) {
        // PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags
        String[] parts = line.split(",", -1);
        if (parts.length < 9) {
            return null;
        }
        try {
            return new Puzzle(parts[0], parts[1], Arrays.asList(parts[2].split(" ")), Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]),
                    Arrays.asList(parts[7].split(" ")), parts[8], parts.length > 9 ? parts[9] : "");
        } catch (NumberFormatException e) {
            return null; // header or partial line
        }
    }

    // --- online daily puzzle -------------------------------------------------------------------------------

    public CompletableFuture<Puzzle> fetchDailyPuzzle() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<JsonNode> response = Unirest.get(DAILY_PUZZLE_URL).asJson();
                if (response.isSuccess()) {
                    return parseJsonPuzzle(new JSONObject(response.getBody().toString()));
                }
                return null;
            } catch (RuntimeException e) {
                log.warn("Daily puzzle unavailable: {}", e.getMessage());
                return null;
            }
        }, AppExecutors.io());
    }

    private Puzzle parseJsonPuzzle(JSONObject json) {
        JSONObject game = json.getJSONObject("game");
        JSONObject puzzle = json.getJSONObject("puzzle");

        String id = puzzle.getString("id");
        int rating = puzzle.getInt("rating");
        int initialPly = puzzle.getInt("initialPly");
        String fen = calculateFenFromPgn(game.getString("pgn"), initialPly);

        List<String> solution = new ArrayList<>();
        org.json.JSONArray solutionArray = puzzle.getJSONArray("solution");
        for (int i = 0; i < solutionArray.length(); i++) {
            solution.add(solutionArray.getString(i));
        }
        List<String> themes = new ArrayList<>();
        if (puzzle.has("themes")) {
            org.json.JSONArray themesArray = puzzle.getJSONArray("themes");
            for (int i = 0; i < themesArray.length(); i++) {
                themes.add(themesArray.getString(i));
            }
        }
        return new Puzzle(id, fen, solution, rating, 0, 0, 0, themes, "", "");
    }

    private String calculateFenFromPgn(String pgn, int initialPly) {
        try {
            com.github.bhlangonijr.chesslib.Board board = new com.github.bhlangonijr.chesslib.Board();
            String[] moves = pgn.replaceAll("\\d+\\.", "").replace("\n", " ").split("\\s+");
            int plysApplied = 0;
            for (String moveSan : moves) {
                if (plysApplied >= initialPly) {
                    break;
                }
                if (moveSan.trim().isEmpty()) {
                    continue;
                }
                board.doMove(moveSan);
                plysApplied++;
            }
            return board.getFen();
        } catch (RuntimeException e) {
            return com.github.bhlangonijr.chesslib.Constants.startStandardFENPosition;
        }
    }
}
