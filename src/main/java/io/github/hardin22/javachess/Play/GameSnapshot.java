package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Analysis.MoveText;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything needed to resume a game after a restart, a crash or a power cut: how it was set up, the moves played
 * and the clocks. Written after every move by the game, read back by the home screen.
 *
 * @param mode         PVC (against the computer) or PVP (two players)
 * @param initialFen   starting position
 * @param moves        moves played (UCI), legal from {@code initialFen}
 * @param humanWhite   PVC: the human plays White
 * @param botLevelId   PVC: {@link BotLevels} id, or null for an old-style skill level
 * @param botEngine    PVC: engine type name (STOCKFISH, MAIA_1100...) used with {@code skillLevel} when no level id
 * @param skillLevel   PVC: Stockfish skill 0..20 when no level id
 * @param timeControl  the clock, {@link TimeControl#UNLIMITED} for none
 * @param whiteMillis  White's remaining time (ignored without a clock)
 * @param blackMillis  Black's remaining time
 * @param takebacks    moves taken back so far (PVC)
 * @param hints        hints used so far (PVC)
 * @param startedAt    when the game started
 * @param savedAt      when this snapshot was written
 */
public record GameSnapshot(Mode mode, String initialFen, List<String> moves, boolean humanWhite, String botLevelId,
                           String botEngine, int skillLevel, TimeControl timeControl, long whiteMillis,
                           long blackMillis, int takebacks, int hints, LocalDateTime startedAt,
                           LocalDateTime savedAt) {

    public static final int VERSION = 1;

    public enum Mode { PVC, PVP }

    public GameSnapshot {
        Objects.requireNonNull(mode, "mode");
        initialFen = initialFen == null || initialFen.isBlank()
                ? io.github.hardin22.javachess.Analysis.AnalysisTree.START_FEN : initialFen;
        moves = moves == null ? List.of() : List.copyOf(moves);
        timeControl = timeControl == null ? TimeControl.UNLIMITED : timeControl;
    }

    /** The position reached (replaying the moves); the initial position if a move is not legal. */
    public String currentFen() {
        Board b = new Board();
        b.loadFromFen(initialFen);
        for (String uci : moves) {
            var m = MoveText.legal(b, uci);
            if (m == null) {
                break;
            }
            b.doMove(m);
        }
        return b.getFen();
    }

    /** True when every move is legal from the start (a damaged file is not offered for resuming). */
    public boolean isConsistent() {
        try {
            Board b = new Board();
            b.loadFromFen(initialFen);
            for (String uci : moves) {
                var m = MoveText.legal(b, uci);
                if (m == null) {
                    return false;
                }
                b.doMove(m);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Full moves played (rounded up), for "Mossa 14". */
    public int moveNumber() {
        return MoveText.moveNumber(currentFen());
    }

    /** The same snapshot with other moves, clocks and counters (written after each move). */
    public GameSnapshot advanced(List<String> newMoves, long white, long black, int newTakebacks, int newHints) {
        return new GameSnapshot(mode, initialFen, newMoves, humanWhite, botLevelId, botEngine, skillLevel,
                timeControl, white, black, newTakebacks, newHints, startedAt, LocalDateTime.now());
    }

    // ------------------------------------------------------------------ JSON

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("version", VERSION);
        o.put("mode", mode.name());
        o.put("initialFen", initialFen);
        o.put("moves", new JSONArray(moves));
        o.put("humanWhite", humanWhite);
        if (botLevelId != null) {
            o.put("botLevel", botLevelId);
        }
        if (botEngine != null) {
            o.put("botEngine", botEngine);
        }
        o.put("skillLevel", skillLevel);
        o.put("timeControl", timeControl.storage());
        o.put("whiteMillis", whiteMillis);
        o.put("blackMillis", blackMillis);
        o.put("takebacks", takebacks);
        o.put("hints", hints);
        if (startedAt != null) {
            o.put("startedAt", startedAt.toString());
        }
        if (savedAt != null) {
            o.put("savedAt", savedAt.toString());
        }
        return o;
    }

    /** Reads {@link #toJson()}; throws on a record that cannot be understood. */
    public static GameSnapshot fromJson(JSONObject o) {
        if (o.optInt("version", 0) > VERSION) {
            throw new IllegalArgumentException("snapshot written by a newer version");
        }
        List<String> moves = new ArrayList<>();
        JSONArray arr = o.optJSONArray("moves");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                moves.add(arr.getString(i));
            }
        }
        return new GameSnapshot(Mode.valueOf(o.getString("mode")), o.optString("initialFen", null), moves,
                o.optBoolean("humanWhite", true), o.optString("botLevel", null), o.optString("botEngine", null),
                o.optInt("skillLevel", 10),
                TimeControl.parseStorage(o.optString("timeControl", "")).orElse(TimeControl.UNLIMITED),
                o.optLong("whiteMillis", 0), o.optLong("blackMillis", 0), o.optInt("takebacks", 0),
                o.optInt("hints", 0), date(o.optString("startedAt", null)), date(o.optString("savedAt", null)));
    }

    private static LocalDateTime date(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
