package org.example.javachess.Services;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class GameArchiveService {

    private static final Path ARCHIVE_PATH = Paths.get("archive.json");

    public static void saveGame(String type, String opening, String pgn, String initialFen, String finalFen,
            String result, String timeControl) {
        try {
            int gameId = getNextGameId();
            JSONObject gameJson = new JSONObject();
            gameJson.put("id", gameId);
            gameJson.put("type", type);
            gameJson.put("opening", opening != null ? opening : "Unknown");
            gameJson.put("pgn", pgn);
            gameJson.put("initialFen", initialFen);
            gameJson.put("fen", finalFen); // Final FEN
            gameJson.put("result", result);
            gameJson.put("time", timeControl);

            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
            gameJson.put("datetime", now.format(formatter));

            JSONArray gamesArray;
            if (Files.exists(ARCHIVE_PATH)) {
                String content = new String(Files.readAllBytes(ARCHIVE_PATH));
                gamesArray = new JSONArray(content);
            } else {
                gamesArray = new JSONArray();
            }

            gamesArray.put(gameJson);
            Files.write(ARCHIVE_PATH, gamesArray.toString(4).getBytes());
            System.out.println("[GameArchiveService] Game saved with ID: " + gameId);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int getNextGameId() {
        int nextId = 1;
        try {
            if (Files.exists(ARCHIVE_PATH)) {
                String content = new String(Files.readAllBytes(ARCHIVE_PATH));
                JSONArray gamesArray = new JSONArray(content);
                for (int i = 0; i < gamesArray.length(); i++) {
                    JSONObject game = gamesArray.getJSONObject(i);
                    int id = game.getInt("id");
                    if (id >= nextId) {
                        nextId = id + 1;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return nextId;
    }
}
