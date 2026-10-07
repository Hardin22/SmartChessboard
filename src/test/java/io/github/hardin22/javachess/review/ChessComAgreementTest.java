package io.github.hardin22.javachess.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full comparison with chess.com on the fixture dataset. Opt-in (minutes of engine time):
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=ChessComAgreementTest -Dreview.harness=true \
 *     -Djavachess.engines.dir=$HOME/Developer/javaChess/engines [-Dreview.depth=12] [-Dreview.limit=50] \
 *     [-Dreview.games=id1,id2] [-Dreview.timeClass=blitz] [-Dreview.out=target/review]
 * </pre>
 * Writes {@code report.md}, {@code summary.json} and {@code plies.csv} to {@code review.out}. The only hard assertion
 * is the mate sanity check (a mate given is never an error, an avoidable mate in one is never a good move), gated by
 * {@code -Dreview.strictMates=true} until the classification core lands.
 */
@EnabledIfSystemProperty(named = "review.harness", matches = "true")
class ChessComAgreementTest {

    @Test
    void compareWithChessCom() throws Exception {
        List<ChessComDataset.Game> games = selectedGames();
        assertFalse(games.isEmpty(), "no games selected");
        int depth = Integer.getInteger("review.depth", 12);
        Path out = Path.of(System.getProperty("review.out", "target/review"));
        Files.createDirectories(out);

        AgreementReport report;
        StringBuilder csv = new StringBuilder("game,ply,san,ours,chesscom,white_cp\n");
        try (Reviewer reviewer = Reviewers.create(System.getProperty("review.reviewer", "legacy"), depth)) {
            report = new AgreementReport(reviewer.name());
            int done = 0;
            for (ChessComDataset.Game g : games) {
                Reviewer.Result r = reviewer.review(g);
                report.add(g, r);
                for (int i = 0; i < g.plies(); i++) {
                    ReviewLabel t = g.hasLabels() ? g.labels().get(i) : null;
                    csv.append(String.format(Locale.ROOT, "%s,%d,%s,%s,%s,%s%n", g.id(), i + 1, g.san().get(i),
                            r.labels().get(i), t == null ? "" : t, i < r.whiteCp().size() ? r.whiteCp().get(i) : ""));
                }
                if (++done % 10 == 0) {
                    System.out.printf(Locale.ROOT, "review harness: %d/%d games, MAE %.2f, %.1f ms/ply%n", done,
                            games.size(), report.accuracyMae(), report.msPerPly());
                }
            }
        }
        String md = report.markdown(Integer.getInteger("review.worst", 15));
        write(out.resolve("report.md"), md);
        write(out.resolve("summary.json"), report.summaryJson().toString(2));
        write(out.resolve("plies.csv"), csv.toString());
        System.out.println(report.summaryJson().toString(2));
        if (Boolean.getBoolean("review.strictMates")) {
            assertTrue(report.mateViolations().isEmpty(), report.mateViolations().size() + " mate violations:\n"
                    + report.mateViolations().stream().map(Object::toString).collect(Collectors.joining("\n")));
        }
    }

    static List<ChessComDataset.Game> selectedGames() {
        List<ChessComDataset.Game> all = ChessComDataset.load();
        String ids = System.getProperty("review.games", "");
        String tc = System.getProperty("review.timeClass", "");
        Set<String> wanted = Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        List<ChessComDataset.Game> out = new ArrayList<>();
        for (ChessComDataset.Game g : all) {
            if ((wanted.isEmpty() || wanted.contains(g.id())) && (tc.isEmpty() || tc.equals(g.timeClass()))) {
                out.add(g);
            }
        }
        int limit = Integer.getInteger("review.limit", Integer.MAX_VALUE);
        if (limit < out.size()) {
            // a fixed pseudo-random sample mixes time classes and ratings (the file is sorted by id)
            Collections.shuffle(out, new Random(42));
            out = new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    private static void write(Path p, String s) throws IOException {
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }
}
