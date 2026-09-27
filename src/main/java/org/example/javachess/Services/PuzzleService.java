package org.example.javachess.Services;

import org.example.javachess.Oggetti.Puzzle;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.JsonNode;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PuzzleService {

    private static PuzzleService instance;
    private static final String DAILY_PUZZLE_URL = "https://lichess.org/api/puzzle/daily";

    // Path to the large CSV file.
    // In production, this should likely be configured or placed in a standard user
    // directory.
    // For this environment, we point to the source resource or a known location.
    private static final String CSV_PATH = "src/main/resources/data/puzzles.csv";

    private PuzzleService() {
    }

    public static synchronized PuzzleService getInstance() {
        if (instance == null) {
            instance = new PuzzleService();
        }
        return instance;
    }

    /**
     * Finds a random puzzle matching the criteria using efficient RandomAccessFile
     * seeking.
     * This avoids loading the 1GB file into memory.
     */
    public Puzzle findPuzzle(int targetRating, int range, List<String> themes) {
        File file = new File(CSV_PATH);
        if (!file.exists()) {
            file = new File("puzzles.csv");
            if (!file.exists()) {
                System.err.println("Puzzle Database not found at: " + CSV_PATH);
                return null;
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLength = raf.length();
            // Optimization: Instead of 300 random seeks (slow), we do fewer seeks
            // but scan a chunk of lines sequentially (fast).
            // 20 attempts * 2000 lines = 40,000 puzzles checked per call.
            int maxSeeks = 20;
            int linesPerChunk = 2000;

            for (int i = 0; i < maxSeeks; i++) {
                // 1. Pick random position
                long pos = (long) (Math.random() * (fileLength - 10000));
                if (pos < 0)
                    pos = 0;

                raf.seek(pos);
                if (pos != 0)
                    raf.readLine(); // Discard partial line

                // 2. Scan sequentially
                for (int j = 0; j < linesPerChunk; j++) {
                    String line = raf.readLine();
                    if (line == null)
                        break; // End of file

                    Puzzle p = parseCsvLine(line);
                    if (p == null)
                        continue;

                    // 3. Check Criteria
                    if (Math.abs(p.getRating() - targetRating) <= range) {
                        boolean themeMatch = true;
                        if (themes != null && !themes.isEmpty() && !themes.contains("Tutti")) {
                            // Valid if ANY of the puzzle's themes matches ANY of the requested themes (OR
                            // logic)
                            // Optimization: Check raw string first to avoid extensive list processing if
                            // possible?
                            // Current implementation is fine for now.
                            themeMatch = themes.stream().anyMatch(
                                    reqTheme -> p.getThemes().stream().anyMatch(pt -> pt.equalsIgnoreCase(reqTheme)));
                        }

                        if (themeMatch) {
                            return p;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println(
                "No puzzle found after extensive search for: " + targetRating + " +/-" + range + " Theme: " + themes);
        return null;
    }

    // Adapters for existing API calls (redirect to new random seeker)

    public List<Puzzle> getPuzzlesByRating(int rating, int range) {
        return getPuzzlesByThemeAndRating(null, rating, range);
    }

    public List<Puzzle> getPuzzlesByThemeAndRating(List<String> themes, int rating, int range) {
        List<Puzzle> found = new ArrayList<>();
        // In a real DB we'd query. Here we try 'n' random fetches.
        // Since findPuzzle does random seeking, calling it multiple times gives
        // different results.
        for (int i = 0; i < 50; i++) {
            Puzzle p = findPuzzle(rating, range, themes);
            if (p != null) {
                found.add(p);
                if (found.size() >= 10)
                    break; // Limit candidates
            }
        }
        return found;
    }

    public Puzzle getRandomPuzzle(List<Puzzle> candidates) {
        if (candidates == null || candidates.isEmpty())
            return null;
        return candidates.get((int) (Math.random() * candidates.size()));
    }

    private Puzzle parseCsvLine(String line) {
        try {
            // PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags
            String[] parts = line.split(",");
            if (parts.length < 9)
                return null; // Relaxed check

            String id = parts[0];
            String fen = parts[1];
            List<String> moves = Arrays.asList(parts[2].split(" "));
            int rating = Integer.parseInt(parts[3]);
            int ratingDeviation = Integer.parseInt(parts[4]);
            int popularity = Integer.parseInt(parts[5]);
            int nbPlays = Integer.parseInt(parts[6]);
            // Themes can be space separated
            List<String> themes = Arrays.asList(parts[7].split(" "));
            String gameUrl = parts[8];
            String openingTags = parts.length > 9 ? parts[9] : "";

            return new Puzzle(id, fen, moves, rating, ratingDeviation, popularity, nbPlays, themes, gameUrl,
                    openingTags);
        } catch (Exception e) {
            // e.printStackTrace(); // noise on partial lines
            return null;
        }
    }

    // --- Legacy / Online Fallback ---

    public CompletableFuture<Puzzle> fetchDailyPuzzle() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<JsonNode> response = Unirest.get(DAILY_PUZZLE_URL).asJson();
                if (response.isSuccess()) {
                    org.json.JSONObject json = new org.json.JSONObject(response.getBody().toString());
                    return parseJsonPuzzle(json);
                }
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private Puzzle parseJsonPuzzle(org.json.JSONObject json) {
        // Adapt JSON structure to new Puzzle class (mocking missing fields)
        org.json.JSONObject game = json.getJSONObject("game");
        JSONObject puzzle = json.getJSONObject("puzzle");

        String id = puzzle.getString("id");
        int rating = puzzle.getInt("rating");
        int initialPly = puzzle.getInt("initialPly");
        String pgn = game.getString("pgn");
        String fen = calculateFenFromPgn(pgn, initialPly);

        List<String> solution = new ArrayList<>();
        org.json.JSONArray solutionArray = puzzle.getJSONArray("solution");
        for (int i = 0; i < solutionArray.length(); i++)
            solution.add(solutionArray.getString(i));

        List<String> themes = new ArrayList<>();
        if (puzzle.has("themes")) {
            org.json.JSONArray themesArray = puzzle.getJSONArray("themes");
            for (int i = 0; i < themesArray.length(); i++)
                themes.add(themesArray.getString(i));
        }

        return new Puzzle(id, fen, solution, rating, 0, 0, 0, themes, "", "");
    }

    private String calculateFenFromPgn(String pgn, int initialPly) {
        try {
            com.github.bhlangonijr.chesslib.Board board = new com.github.bhlangonijr.chesslib.Board();
            String cleanPgn = pgn.replaceAll("\\d+\\.", "").replace("\n", " ");
            String[] moves = cleanPgn.split("\\s+");

            int plysApplied = 0;
            for (String moveSan : moves) {
                if (plysApplied >= initialPly)
                    break;
                if (moveSan.trim().isEmpty())
                    continue;
                board.doMove(moveSan);
                plysApplied++;
            }
            return board.getFen();
        } catch (Exception e) {
            return com.github.bhlangonijr.chesslib.Constants.startStandardFENPosition;
        }
    }
}
