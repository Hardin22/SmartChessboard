package io.github.hardin22.javachess.Training;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpeningProgressTest {

    @TempDir
    Path dir;

    OpeningProgress open(String day) {
        return new OpeningProgress(dir.resolve("training/openings.json"), Runnable::run,
                Clock.fixed(Instant.parse(day + "T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void runsAreKeptOnDiskAndGiveAMasteryLevel() {
        OpeningProgress p = open("2026-10-01");
        p.record("italiana", 16, 2);
        p.record("italiana", 16, 0);
        assertEquals("Quasi", p.entry("italiana").label());
        p.record("italiana", 16, 0);
        p.record("italiana", 14, 0);

        OpeningProgress again = open("2026-10-02");
        OpeningProgress.Entry e = again.entry("italiana");
        assertEquals(4, e.runs());
        assertEquals(3, e.clean());
        assertEquals(16, e.bestPlies());
        assertEquals(LocalDate.of(2026, 10, 1), e.last());
        assertEquals("Sicura", e.label());
        assertEquals("Mai provata", again.entry("spagnola").label());
    }

    @Test
    void openingsToReviewComeFirst() {
        OpeningProgress p = open("2026-10-01");
        p.record("spagnola", 16, 3);
        for (int i = 0; i < 3; i++) {
            p.record("italiana", 16, 0);
        }
        List<String> order = p.toReview(OpeningCatalog.forSide(true)).stream().map(OpeningCatalog.Opening::id)
                .toList();
        assertEquals("spagnola", order.get(0));
        assertEquals("italiana", order.get(order.size() - 1));
    }

    @Test
    void aDamagedFileStartsAgain() throws Exception {
        Files.createDirectories(dir.resolve("training"));
        Files.writeString(dir.resolve("training/openings.json"), "{not json");
        assertEquals(0, open("2026-10-01").entry("italiana").runs());
    }
}
