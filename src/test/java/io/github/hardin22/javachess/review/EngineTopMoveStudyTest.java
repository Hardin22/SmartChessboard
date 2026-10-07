package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.PositionEval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Which engine is chess.com's? Thresholds aside, chess.com calls a move Best (or Great/Brilliant) when it is its
 * engine's top move and Excellent..Blunder when it is not. For each dumped variant of
 * {@link EngineEmulationStudyTest}: how often the played move is our top move on chess.com's Best plies, and is not
 * on its Excellent..Blunder plies (book and forced plies left out). Opt-in, no engine time:
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=EngineTopMoveStudyTest -Demu.top=true -Demu.variants=sf19:n200000,sflite:d18
 * </pre>
 */
@EnabledIfSystemProperty(named = "emu.top", matches = "true")
class EngineTopMoveStudyTest {

    @Test
    void topMoveAgreement() throws Exception {
        Path out = Path.of(System.getProperty("emu.out", "target/emulation"));
        List<ChessComDataset.Game> games = EngineEmulationStudyTest.labelled();
        StringBuilder sb = new StringBuilder("| variant | cc best = our top | cc great/brilliant = our top | "
                + "cc exc..blunder ≠ our top | cc excellent ≠ our top | both |\n|---|---:|---:|---:|---:|---:|\n");
        for (String v : System.getProperty("emu.variants", "sf19:n200000").split(",")) {
            Path f = out.resolve(v.trim().replace(':', '_') + ".jsonl");
            if (!Files.exists(f)) {
                continue;
            }
            List<List<PositionEval>> pos = EngineEmulationStudyTest.read(f).positions();
            int[] best = new int[2];
            int[] great = new int[2];
            int[] other = new int[2];
            int[] exc = new int[2];
            for (int g = 0; g < games.size(); g++) {
                ChessComDataset.Game game = games.get(g);
                for (int i = 0; i < game.plies(); i++) {
                    PositionEval p = pos.get(g).get(i);
                    if (p.terminal()) {
                        continue;
                    }
                    boolean top = game.uci().get(i).equals(p.bestMove());
                    switch (game.labels().get(i)) {
                        case BEST -> count(best, top);
                        case GREAT, BRILLIANT -> count(great, top);
                        case EXCELLENT -> {
                            count(exc, !top);
                            count(other, !top);
                        }
                        case GOOD, INACCURACY, MISTAKE, MISS, BLUNDER -> count(other, !top);
                        default -> {
                        }
                    }
                }
            }
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %.1f%% |%n", v.trim(), pct(best),
                    pct(great), pct(other), pct(exc), 100.0 * (best[0] + other[0]) / (best[1] + other[1])));
        }
        System.out.println(sb);
        Files.writeString(out.resolve("top-move.md"), sb.toString());
        // moves and labels of the dumped games, for ad-hoc scripts
        org.json.JSONObject moves = new org.json.JSONObject();
        for (ChessComDataset.Game g : games) {
            moves.put(g.id(), new org.json.JSONObject().put("uci", new org.json.JSONArray(g.uci()))
                    .put("labels", new org.json.JSONArray(g.labels().stream().map(Enum::name).toList())));
        }
        Files.writeString(out.resolve("games.json"), moves.toString());
    }

    private static void count(int[] c, boolean ok) {
        if (ok) {
            c[0]++;
        }
        c[1]++;
    }

    private static String pct(int[] c) {
        return String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * c[0] / Math.max(1, c[1]), c[0], c[1]);
    }
}
