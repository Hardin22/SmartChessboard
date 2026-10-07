package io.github.hardin22.javachess.review;

import com.github.bhlangonijr.chesslib.move.MoveList;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A game with the full chess.com Game Review labels ({@code data/labels_chesscom/<id>.json}, one label per ply) and
 * its moves from {@code data/games/<id>.json}. Read from the review team's data folder, never copied into the repo.
 *
 * @param labels one chess.com label per ply
 */
public record LabelledGame(String id, String url, String timeClass, int whiteRating, int blackRating,
                           List<String> uci, List<String> san, List<ReviewLabel> labels, double whiteAccuracy,
                           double blackAccuracy) {

    public static List<LabelledGame> loadAll(Path labelsDir, Path gamesDir) {
        try (Stream<Path> files = Files.list(labelsDir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted()
                    .map(f -> load(f, gamesDir)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static LabelledGame load(Path labelFile, Path gamesDir) {
        try {
            JSONObject l = new JSONObject(Files.readString(labelFile));
            String id = l.getString("id");
            JSONObject g = new JSONObject(Files.readString(gamesDir.resolve(id + ".json")));
            JSONArray a = l.getJSONArray("labels");
            List<String> uci = g.has("moves_uci") ? ChessComDataset.strings(g.getJSONArray("moves_uci"))
                    : uciFromSan(a);
            if (a.length() != uci.size()) {
                throw new IllegalStateException(id + ": " + a.length() + " labels for " + uci.size() + " plies");
            }
            List<String> san = new ArrayList<>();
            List<ReviewLabel> labels = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                if (e.getInt("ply") != i + 1) {
                    throw new IllegalStateException(id + ": labels out of order at " + i);
                }
                san.add(e.getString("san"));
                ReviewLabel r = ReviewLabel.parse(e.getString("label"));
                if (r == null) {
                    throw new IllegalStateException(id + ": unknown label " + e.getString("label"));
                }
                labels.add(r);
            }
            return new LabelledGame(id, l.optString("url"), g.optString("time_class"), g.optInt("white_rating"),
                    g.optInt("black_rating"), List.copyOf(uci), List.copyOf(san), List.copyOf(labels),
                    l.optDouble("accuracy_white", Double.NaN), l.optDouble("accuracy_black", Double.NaN));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Moves of a game file without {@code moves_uci} (older schema), from the SAN of the labels. */
    private static List<String> uciFromSan(JSONArray labels) {
        StringBuilder san = new StringBuilder();
        for (int i = 0; i < labels.length(); i++) {
            san.append(labels.getJSONObject(i).getString("san")).append(' ');
        }
        MoveList ml = new MoveList();
        ml.loadFromSan(san.toString().trim());
        List<String> out = new ArrayList<>();
        ml.forEach(m -> out.add(m.toString()));
        return out;
    }
}
