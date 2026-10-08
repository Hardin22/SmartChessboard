package io.github.hardin22.javachess.Training;

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
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Results of the endgame drills ({@code ~/.javachess/training/endgames.json}): tries, whether a drill was solved,
 * with or without hints, and in how few moves. Thread-safe; atomic writes on the storage thread.
 */
public final class DrillProgress {

    private static final Logger log = LoggerFactory.getLogger(DrillProgress.class);
    private static volatile DrillProgress instance;

    /**
     * @param tries      drills finished (solved or not)
     * @param solved     times solved
     * @param clean      times solved without hints
     * @param bestMoves  fewest moves of a solution (0: never solved)
     * @param last       day of the last try (null: never)
     */
    public record Entry(int tries, int solved, int clean, int bestMoves, LocalDate last) {
        static final Entry NEVER = new Entry(0, 0, 0, 0, null);

        /** 0 not solved, 1 solved with hints, 2 solved without hints. */
        public int stars() {
            return clean > 0 ? 2 : solved > 0 ? 1 : 0;
        }

        /** "Da provare", "Non ancora risolto", "Risolto con aiuto", "Risolto · 9 mosse". */
        public String label() {
            if (tries == 0) {
                return "Da provare";
            }
            if (solved == 0) {
                return "Non ancora risolto";
            }
            return clean == 0 ? "Risolto con aiuto" : "Risolto · " + bestMoves + (bestMoves == 1 ? " mossa" : " mosse");
        }
    }

    private final Path file;
    private final Executor io;
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public static DrillProgress get() {
        DrillProgress p = instance;
        if (p == null) {
            synchronized (DrillProgress.class) {
                p = instance;
                if (p == null) {
                    p = new DrillProgress(AppPaths.resolve("training").resolve("endgames.json"),
                            AppExecutors.storage(), Clock.systemDefaultZone());
                    instance = p;
                }
            }
        }
        return p;
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    public DrillProgress(Path file, Executor io, Clock clock) {
        this.file = file;
        this.io = io;
        this.clock = clock;
        load();
    }

    public synchronized Entry entry(String drillId) {
        return entries.getOrDefault(drillId, Entry.NEVER);
    }

    public void record(String drillId, boolean solved, int moves, int hints) {
        String json;
        synchronized (this) {
            Entry e = entry(drillId);
            boolean clean = solved && hints == 0;
            int best = e.bestMoves();
            if (clean && (best == 0 || moves < best)) {
                best = moves;
            }
            entries.put(drillId, new Entry(e.tries() + 1, e.solved() + (solved ? 1 : 0), e.clean() + (clean ? 1 : 0),
                    best, LocalDate.now(clock)));
            json = toJson();
        }
        io.execute(() -> {
            try {
                AtomicFiles.writeString(file, json, false);
            } catch (IOException ex) {
                log.warn("endgame progress not saved: {}", ex.toString());
            }
        });
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JSONObject list = new JSONObject(Files.readString(file, StandardCharsets.UTF_8)).optJSONObject("drills");
            if (list == null) {
                return;
            }
            for (String id : list.keySet()) {
                JSONObject o = list.getJSONObject(id);
                String last = o.optString("last", "");
                entries.put(id, new Entry(o.optInt("tries"), o.optInt("solved"), o.optInt("clean"),
                        o.optInt("bestMoves"), last.isEmpty() ? null : LocalDate.parse(last)));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("endgame progress unreadable, starting again: {}", e.toString());
            entries.clear();
        }
    }

    private String toJson() {
        JSONObject list = new JSONObject();
        entries.forEach((id, e) -> list.put(id, new JSONObject().put("tries", e.tries()).put("solved", e.solved())
                .put("clean", e.clean()).put("bestMoves", e.bestMoves())
                .put("last", e.last() == null ? "" : e.last().toString())));
        return new JSONObject().put("version", 1).put("drills", list).toString(1);
    }
}
