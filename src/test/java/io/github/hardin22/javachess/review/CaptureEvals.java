package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.EngineLine;
import io.github.hardin22.javachess.Engine.review.ReviewInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The stored after-capture searches ({@code src/test/resources/review/capture-evals.tsv}, built by
 * {@code scripts/review/capture_evals.py}): for a move that leaves a piece en prise, the opponent's capture and our
 * engine's evaluation after it, as {@link ReviewInput#afterCapture()} expects them (Phase 4 "threat ignored").
 */
public final class CaptureEvals {

    public static final Path FILE = Path.of("src/test/resources/review/capture-evals.tsv");

    private CaptureEvals() {
    }

    /** Game id -> move index (0-based) -> capture line; empty when the file is missing. */
    public static Map<String, Map<Integer, EngineLine>> load(Path file) {
        Map<String, Map<Integer, EngineLine>> out = new HashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.startsWith("#") || l.startsWith("id\t")) {
                    continue;
                }
                String[] c = l.split("\t", -1);
                List<String> pv = new ArrayList<>();
                pv.add(c[2]);
                if (c.length > 7 && !c[7].isBlank()) {
                    pv.addAll(List.of(c[7].trim().split("\\s+")));
                }
                int depth = c.length > 5 && !c[5].isBlank() ? Integer.parseInt(c[5]) : 0;
                out.computeIfAbsent(c[0], k -> new HashMap<>()).put(Integer.parseInt(c[1]) - 1,
                        new EngineLine(c[2], EvalDump.parse(c[3]), pv, depth));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** {@code in} with the stored searches of game {@code id} (unchanged when there are none). */
    public static ReviewInput attach(ReviewInput in, String id, Map<String, Map<Integer, EngineLine>> all) {
        Map<Integer, EngineLine> m = all.get(id);
        return m == null || m.isEmpty() ? in : in.withAfterCapture(m);
    }
}
