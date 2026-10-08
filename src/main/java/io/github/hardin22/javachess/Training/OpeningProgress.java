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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * What the player has trained, per opening ({@code ~/.javachess/training/openings.json}): runs, runs without
 * mistakes, the last run's mistakes and date. It orders the list ("da ripassare" first) and shows a mastery mark.
 * Thread-safe; writes are atomic and run on the storage thread.
 */
public final class OpeningProgress {

    private static final Logger log = LoggerFactory.getLogger(OpeningProgress.class);
    private static volatile OpeningProgress instance;

    /**
     * @param runs       lines played to the end
     * @param clean      of which without mistakes
     * @param lastErrors mistakes of the last run
     * @param bestPlies  longest line played
     * @param last       day of the last run (null: never)
     */
    public record Entry(int runs, int clean, int lastErrors, int bestPlies, LocalDate last) {
        static final Entry NEVER = new Entry(0, 0, 0, 0, null);

        /** Mastery: the last three runs weigh the most. 0 never trained, 1 learning, 2 almost, 3 known. */
        public int level() {
            if (runs == 0) {
                return 0;
            }
            if (lastErrors == 0 && clean >= 3) {
                return 3;
            }
            return lastErrors == 0 ? 2 : 1;
        }

        /** "Mai provata", "Da ripassare", "Quasi", "Sicura". */
        public String label() {
            return switch (level()) {
                case 0 -> "Mai provata";
                case 1 -> "Da ripassare";
                case 2 -> "Quasi";
                default -> "Sicura";
            };
        }
    }

    private final Path file;
    private final Executor io;
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public static OpeningProgress get() {
        OpeningProgress p = instance;
        if (p == null) {
            synchronized (OpeningProgress.class) {
                p = instance;
                if (p == null) {
                    p = new OpeningProgress(AppPaths.resolve("training").resolve("openings.json"),
                            AppExecutors.storage(), Clock.systemDefaultZone());
                    instance = p;
                }
            }
        }
        return p;
    }

    /** Tests: forget the shared instance (after changing the data folder). */
    public static synchronized void resetInstance() {
        instance = null;
    }

    public OpeningProgress(Path file, Executor io, Clock clock) {
        this.file = file;
        this.io = io;
        this.clock = clock;
        load();
    }

    public synchronized Entry entry(String openingId) {
        return entries.getOrDefault(openingId, Entry.NEVER);
    }

    /** A finished run of {@code openingId}: {@code plies} played, {@code errors} positions gone wrong. */
    public void record(String openingId, int plies, int errors) {
        String json;
        synchronized (this) {
            Entry e = entry(openingId);
            entries.put(openingId, new Entry(e.runs() + 1, e.clean() + (errors == 0 ? 1 : 0), errors,
                    Math.max(e.bestPlies(), plies), LocalDate.now(clock)));
            json = toJson();
        }
        io.execute(() -> {
            try {
                AtomicFiles.writeString(file, json, false);
            } catch (IOException ex) {
                log.warn("opening training progress not saved: {}", ex.toString());
            }
        });
    }

    /**
     * The openings in the order to train them: started but not mastered first (older first), then never tried,
     * then the known ones.
     */
    public List<OpeningCatalog.Opening> toReview(List<OpeningCatalog.Opening> openings) {
        Comparator<OpeningCatalog.Opening> order = Comparator.comparingInt(o -> switch (entry(o.id()).level()) {
            case 1 -> 0;
            case 2 -> 1;
            case 0 -> 2;
            default -> 3;
        });
        order = order.thenComparing(o -> entry(o.id()).last(), Comparator.nullsLast(Comparator.naturalOrder()));
        return openings.stream().sorted(order).toList();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONObject list = root.optJSONObject("openings");
            if (list == null) {
                return;
            }
            for (String id : list.keySet()) {
                JSONObject o = list.getJSONObject(id);
                String last = o.optString("last", "");
                entries.put(id, new Entry(o.optInt("runs"), o.optInt("clean"), o.optInt("lastErrors"),
                        o.optInt("bestPlies"), last.isEmpty() ? null : LocalDate.parse(last)));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("opening training progress unreadable, starting again: {}", e.toString());
            entries.clear();
        }
    }

    private String toJson() {
        JSONObject list = new JSONObject();
        entries.forEach((id, e) -> list.put(id, new JSONObject()
                .put("runs", e.runs()).put("clean", e.clean()).put("lastErrors", e.lastErrors())
                .put("bestPlies", e.bestPlies()).put("last", e.last() == null ? "" : e.last().toString())));
        return new JSONObject().put("version", 1).put("openings", list).toString(1);
    }
}
