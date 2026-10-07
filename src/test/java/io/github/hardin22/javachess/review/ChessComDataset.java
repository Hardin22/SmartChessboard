package io.github.hardin22.javachess.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * chess.com Game Review ground truth ({@code src/test/resources/review/chesscom-games.jsonl}, written by
 * {@code scripts/review/import_chesscom_fixtures.py} from the review team's dataset): moves, ratings, accuracies and,
 * when available, the per-move labels.
 */
public final class ChessComDataset {

    public static final String RESOURCE = "/review/chesscom-games.jsonl";

    private ChessComDataset() {
    }

    /**
     * One reviewed game.
     *
     * @param uci    moves in UCI
     * @param fens   positions, {@code fens.get(i)} before ply {@code i} (size = plies + 1)
     * @param whiteAccuracy chess.com accuracy, NaN for label-only games
     * @param labels chess.com labels per ply, or null when the dataset has none for this game (entries may be null)
     */
    public record Game(String id, String url, String timeClass, int whiteRating, int blackRating, String result,
                       List<String> san, List<String> uci, List<String> fens, double whiteAccuracy,
                       double blackAccuracy, List<ReviewLabel> labels, Set<String> tags) {

        public int plies() {
            return uci.size();
        }

        public int averageRating() {
            return (whiteRating + blackRating) / 2;
        }

        /** "1-399", "400-799"... "2000+" band of the average rating. */
        public String ratingBand() {
            int r = averageRating();
            if (r >= 2000) {
                return "2000+";
            }
            int lo = r / 400 * 400;
            return lo + "-" + (lo + 399);
        }

        /** False for label-only games (no chess.com accuracies). */
        public boolean hasAccuracy() {
            return !Double.isNaN(whiteAccuracy) && !Double.isNaN(blackAccuracy);
        }

        public boolean hasLabels() {
            return labels != null;
        }
    }

    public static List<Game> load() {
        InputStream in = ChessComDataset.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return List.of();
        }
        List<Game> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    out.add(parse(new JSONObject(line)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Collections.unmodifiableList(out);
    }

    static Game parse(JSONObject o) {
        List<String> san = strings(o.getJSONArray("moves_san"));
        MoveList ml = new MoveList();
        try {
            ml.loadFromSan(String.join(" ", san));
        } catch (Exception e) {
            throw new IllegalArgumentException(o.optString("id") + ": unreadable SAN: " + e.getMessage(), e);
        }
        List<String> uci = new ArrayList<>();
        List<String> fens = new ArrayList<>();
        Board b = new Board();
        fens.add(b.getFen());
        for (Move m : ml) {
            uci.add(m.toString());
            b.doMove(m);
            fens.add(b.getFen());
        }
        JSONObject acc = o.optJSONObject("accuracy", new JSONObject());
        Set<String> tags = new TreeSet<>();
        JSONArray t = o.optJSONArray("tags");
        if (t != null) {
            tags.addAll(strings(t));
        }
        return new Game(o.getString("id"), o.optString("url"), o.optString("time_class"), o.optInt("white_rating"),
                o.optInt("black_rating"), o.optString("result"), List.copyOf(san), List.copyOf(uci), List.copyOf(fens),
                acc.optDouble("white", Double.NaN), acc.optDouble("black", Double.NaN), labels(o.opt("labels"), san.size()), tags);
    }

    /**
     * Labels as a list per ply: strings ("best", "blunder"...) or objects with {@code ply} (1-based) and
     * {@code label}/{@code classification}. Unknown spellings become null entries.
     */
    static List<ReviewLabel> labels(Object raw, int plies) {
        if (!(raw instanceof JSONArray a) || a.isEmpty()) {
            return null;
        }
        List<ReviewLabel> out = new ArrayList<>(Collections.nCopies(plies, null));
        for (int i = 0; i < a.length(); i++) {
            Object e = a.get(i);
            if (e instanceof JSONObject jo) {
                int ply = jo.optInt("ply", i + 1) - 1;
                String label = jo.optString("label", jo.optString("classification", null));
                if (ply >= 0 && ply < plies) {
                    out.set(ply, ReviewLabel.parse(label));
                }
            } else if (i < plies && e != JSONObject.NULL) {
                out.set(i, ReviewLabel.parse(String.valueOf(e)));
            }
        }
        return Collections.unmodifiableList(out);
    }

    static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            out.add(a.getString(i));
        }
        return out;
    }
}
