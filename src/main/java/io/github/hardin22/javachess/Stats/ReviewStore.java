package io.github.hardin22.javachess.Stats;

import io.github.hardin22.javachess.Analysis.AnalysisTree;
import io.github.hardin22.javachess.Analysis.ReviewInsights;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
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
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Finished game reviews kept on disk ({@code ~/.javachess/reviews/}), so a game opens already analysed (a review
 * takes about a minute on a Raspberry Pi) and the personal statistics know the accuracy of every reviewed game.
 *
 * <ul>
 *   <li>One file per review ({@code <key>.json}) with the moves' labels and evaluations, plus a small index
 *       ({@code index.json}) with the summaries the statistics read.</li>
 *   <li>The key is a hash of the starting position, the moves, the players' ratings (the labels depend on them) and
 *       {@link #REVIEW_VERSION}: a different game, other ratings or a new classifier never reuse a review.</li>
 *   <li>Writes are atomic and run on the storage thread; reads are small and may run anywhere (better off the
 *       JavaFX thread).</li>
 * </ul>
 */
public final class ReviewStore {

    private static final Logger log = LoggerFactory.getLogger(ReviewStore.class);
    /** Bump when the review classification changes: older reviews are then recomputed. */
    public static final String REVIEW_VERSION = "classifier-v2.4-special";
    public static final int SCHEMA_VERSION = 1;

    /**
     * What the statistics need from a review.
     *
     * @param whiteAccuracy accuracy (0..100), NaN when White made no counted move
     * @param blackAccuracy accuracy of Black
     * @param whiteCounts   labels of White's moves
     * @param blackCounts   labels of Black's moves
     * @param phases        accuracy by phase (from {@link ReviewInsights#phases})
     * @param opening       opening reached ("C50 Italian Game") or ""
     * @param analyzedAt    when the review was made
     */
    public record Summary(double whiteAccuracy, double blackAccuracy, Map<MoveClassification, Integer> whiteCounts,
                          Map<MoveClassification, Integer> blackCounts, List<ReviewInsights.PhaseScore> phases,
                          String opening, LocalDateTime analyzedAt) {
        public Summary {
            whiteCounts = Map.copyOf(whiteCounts);
            blackCounts = Map.copyOf(blackCounts);
            phases = List.copyOf(phases);
            opening = opening == null ? "" : opening;
        }

        /** Accuracy of one side. */
        public double accuracy(boolean white) {
            return white ? whiteAccuracy : blackAccuracy;
        }

        /** Count of {@code label} for one side. */
        public int count(boolean white, MoveClassification label) {
            return (white ? whiteCounts : blackCounts).getOrDefault(label, 0);
        }
    }

    private static volatile ReviewStore instance;

    private final Path dir;
    private final Executor io;
    private Map<String, Summary> index; // guarded by this, loaded lazily

    public static ReviewStore get() {
        ReviewStore s = instance;
        if (s == null) {
            synchronized (ReviewStore.class) {
                s = instance;
                if (s == null) {
                    s = new ReviewStore(AppPaths.resolve("reviews"), AppExecutors.storage());
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

    public ReviewStore(Path dir, Executor io) {
        this.dir = dir;
        this.io = io;
    }

    // ------------------------------------------------------------------ keys

    /** Key of a review: start, moves, ratings and the classifier version. */
    public static String key(String initialFen, List<String> uciMoves, int whiteRating, int blackRating) {
        String start = initialFen == null || initialFen.isBlank() ? AnalysisTree.START_FEN : initialFen.trim();
        String text = REVIEW_VERSION + "|" + start + "|" + String.join(" ", uciMoves) + "|" + whiteRating + "|"
                + blackRating;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Key of an archived game (with the ratings the review screen uses for it). */
    public static String key(ArchivedGame g) {
        return key(g.initialFen(), g.movesUci(), g.whiteRating(), g.blackRating());
    }

    // ------------------------------------------------------------------ reading

    /** The saved review, if any. */
    public Optional<GameReview> find(String key) {
        Path f = dir.resolve(key + ".json");
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromJson(new JSONObject(Files.readString(f, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException e) {
            log.warn("saved review {} not readable: {}", f, e.toString());
            return Optional.empty();
        }
    }

    public Optional<GameReview> find(ArchivedGame g) {
        return find(key(g));
    }

    /** Summaries of every saved review, by key. */
    public synchronized Map<String, Summary> summaries() {
        if (index == null) {
            index = readIndex();
        }
        return Map.copyOf(index);
    }

    public Optional<Summary> summary(String key) {
        return Optional.ofNullable(summaries().get(key));
    }

    // ------------------------------------------------------------------ writing

    /** Saves a finished review in the background. */
    public void save(String key, GameReview review) {
        if (review == null || review.moves().isEmpty()) {
            return;
        }
        Summary s = summarize(review);
        synchronized (this) {
            if (index == null) {
                index = readIndex();
            }
            index.put(key, s);
        }
        JSONObject full = toJson(review);
        io.execute(() -> write(key, full));
    }

    public void save(ArchivedGame g, GameReview review) {
        save(key(g), review);
    }

    /** Summary of a review (what the index keeps). */
    public static Summary summarize(GameReview r) {
        ReviewInsights.PhaseSummary p = ReviewInsights.phases(r);
        List<ReviewInsights.PhaseScore> phases = new ArrayList<>(p.white());
        phases.addAll(p.black());
        return new Summary(r.whiteAccuracy(), r.blackAccuracy(), r.counts(true), r.counts(false), phases,
                r.opening(), LocalDateTime.now());
    }

    private void write(String key, JSONObject full) {
        try {
            Files.createDirectories(dir);
            AtomicFiles.writeString(dir.resolve(key + ".json"), full.toString(), false);
            JSONObject idx = new JSONObject();
            idx.put("schemaVersion", SCHEMA_VERSION);
            JSONObject reviews = new JSONObject();
            synchronized (this) {
                index.forEach((k, s) -> reviews.put(k, summaryJson(s)));
            }
            idx.put("reviews", reviews);
            AtomicFiles.writeString(dir.resolve("index.json"), idx.toString(), false);
        } catch (IOException | RuntimeException e) {
            log.error("cannot save the review {}: {}", key, e.toString());
        }
    }

    private Map<String, Summary> readIndex() {
        Map<String, Summary> m = new HashMap<>();
        Path f = dir.resolve("index.json");
        if (!Files.isRegularFile(f)) {
            return m;
        }
        try {
            JSONObject o = new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
            if (o.optInt("schemaVersion", 0) > SCHEMA_VERSION) {
                log.warn("review index written by a newer version: ignored");
                return m;
            }
            JSONObject reviews = o.optJSONObject("reviews");
            if (reviews != null) {
                for (String k : reviews.keySet()) {
                    try {
                        m.put(k, summaryFrom(reviews.getJSONObject(k)));
                    } catch (RuntimeException e) {
                        log.debug("review summary {} skipped: {}", k, e.toString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("review index {} not readable: {}", f, e.toString());
        }
        return m;
    }

    // ------------------------------------------------------------------ JSON

    static JSONObject toJson(GameReview r) {
        JSONObject o = new JSONObject();
        o.put("version", SCHEMA_VERSION);
        o.put("initialFen", r.initialFen());
        o.put("whiteAccuracy", num(r.whiteAccuracy()));
        o.put("blackAccuracy", num(r.blackAccuracy()));
        o.put("opening", r.opening() == null ? "" : r.opening());
        JSONArray moves = new JSONArray();
        for (MoveReview m : r.moves()) {
            JSONObject j = new JSONObject();
            j.put("uci", m.uci());
            j.put("san", m.san());
            j.put("fen", m.fenBefore());
            j.put("white", m.whiteMoved());
            j.put("label", m.label().name());
            j.put("before", eval(m.before()));
            j.put("after", eval(m.after()));
            if (m.bestMove() != null) {
                j.put("best", m.bestMove());
            }
            j.put("line", new JSONArray(m.bestLine()));
            j.put("winBefore", m.winBefore());
            j.put("winAfter", m.winAfter());
            j.put("accuracy", m.accuracy());
            moves.put(j);
        }
        o.put("moves", moves);
        JSONArray positions = new JSONArray();
        for (PositionEval p : r.positions()) {
            JSONObject j = new JSONObject();
            j.put("fen", p.fen());
            j.put("eval", eval(p.eval()));
            j.put("depth", p.depth());
            j.put("terminal", p.terminal());
            positions.put(j);
        }
        o.put("positions", positions);
        return o;
    }

    static GameReview fromJson(JSONObject o) {
        if (o.optInt("version", 0) > SCHEMA_VERSION) {
            throw new IllegalArgumentException("review written by a newer version");
        }
        List<MoveReview> moves = new ArrayList<>();
        JSONArray arr = o.getJSONArray("moves");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.getJSONObject(i);
            List<String> line = new ArrayList<>();
            JSONArray l = j.optJSONArray("line");
            if (l != null) {
                for (int k = 0; k < l.length(); k++) {
                    line.add(l.getString(k));
                }
            }
            moves.add(new MoveReview(i, j.getString("uci"), j.optString("san", j.getString("uci")),
                    j.getString("fen"), j.getBoolean("white"), MoveClassification.valueOf(j.getString("label")),
                    eval(j.getString("before")), eval(j.getString("after")), j.optString("best", null), line,
                    j.getDouble("winBefore"), j.getDouble("winAfter"), j.getDouble("accuracy")));
        }
        List<PositionEval> positions = new ArrayList<>();
        JSONArray ps = o.optJSONArray("positions");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject j = ps.getJSONObject(i);
                positions.add(new PositionEval(j.getString("fen"), eval(j.getString("eval")), List.of(),
                        j.optInt("depth"), 0, j.optBoolean("terminal")));
            }
        }
        return new GameReview(o.getString("initialFen"), moves, positions, o.optDouble("whiteAccuracy", Double.NaN),
                o.optDouble("blackAccuracy", Double.NaN), emptyToNull(o.optString("opening", "")),
                GameReview.Stats.NONE);
    }

    private static JSONObject summaryJson(Summary s) {
        JSONObject j = new JSONObject();
        j.put("w", num(s.whiteAccuracy()));
        j.put("b", num(s.blackAccuracy()));
        j.put("wc", counts(s.whiteCounts()));
        j.put("bc", counts(s.blackCounts()));
        JSONArray phases = new JSONArray();
        for (ReviewInsights.PhaseScore p : s.phases()) {
            JSONObject x = new JSONObject();
            x.put("phase", p.phase().name());
            x.put("white", p.white());
            x.put("moves", p.moves());
            x.put("acc", num(p.accuracy()));
            x.put("i", p.inaccuracies());
            x.put("m", p.mistakes());
            x.put("bl", p.blunders());
            x.put("mi", p.misses());
            x.put("g", p.grade().name());
            phases.put(x);
        }
        j.put("phases", phases);
        j.put("opening", s.opening());
        j.put("at", s.analyzedAt() == null ? "" : s.analyzedAt().toString());
        return j;
    }

    private static Summary summaryFrom(JSONObject j) {
        List<ReviewInsights.PhaseScore> phases = new ArrayList<>();
        JSONArray arr = j.optJSONArray("phases");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.getJSONObject(i);
                phases.add(new ReviewInsights.PhaseScore(ReviewInsights.Phase.valueOf(x.getString("phase")),
                        x.getBoolean("white"), x.getInt("moves"), x.optDouble("acc", Double.NaN), x.optInt("i"),
                        x.optInt("m"), x.optInt("bl"), x.optInt("mi"), ReviewInsights.Grade.valueOf(x.getString("g"))));
            }
        }
        LocalDateTime at = null;
        try {
            String s = j.optString("at", "");
            at = s.isEmpty() ? null : LocalDateTime.parse(s);
        } catch (RuntimeException ignored) {
            // unknown date
        }
        return new Summary(j.optDouble("w", Double.NaN), j.optDouble("b", Double.NaN), counts(j.optJSONObject("wc")),
                counts(j.optJSONObject("bc")), phases, j.optString("opening", ""), at);
    }

    private static JSONObject counts(Map<MoveClassification, Integer> m) {
        JSONObject o = new JSONObject();
        m.forEach((k, v) -> {
            if (v != null && v > 0) {
                o.put(k.name(), v);
            }
        });
        return o;
    }

    private static Map<MoveClassification, Integer> counts(JSONObject o) {
        Map<MoveClassification, Integer> m = new EnumMap<>(MoveClassification.class);
        if (o != null) {
            for (String k : o.keySet()) {
                try {
                    m.put(MoveClassification.valueOf(k), o.getInt(k));
                } catch (RuntimeException ignored) {
                    // label of another version
                }
            }
        }
        return m;
    }

    /** Evaluations as their own text form: "+0.35", "M3", "-M2", "1-0", "0-1". */
    private static String eval(Eval e) {
        return e == null ? "+0.00" : e.format();
    }

    private static Eval eval(String s) {
        if (s == null || s.isBlank()) {
            return Eval.DRAW;
        }
        if (s.equals("1-0")) {
            return Eval.whiteMates(0);
        }
        if (s.equals("0-1")) {
            return Eval.blackMates(0);
        }
        if (s.startsWith("-M")) {
            return Eval.blackMates(Integer.parseInt(s.substring(2)));
        }
        if (s.startsWith("M")) {
            return Eval.whiteMates(Integer.parseInt(s.substring(1)));
        }
        return Eval.cp((int) Math.round(Double.parseDouble(s) * 100));
    }

    private static Object num(double d) {
        return Double.isNaN(d) ? JSONObject.NULL : d;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
