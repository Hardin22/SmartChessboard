package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.review.ChessComDataset;
import io.github.hardin22.javachess.review.ReviewLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.TreeMap;

/**
 * The static sacrifice detector of Brilliant (no engine) on chess.com-certified Brilliants (Chessigma benchmark and
 * the labelled games) and on every other move of the labelled games. Opt-in: {@code -Dreview.probe=true}.
 */
@EnabledIfSystemProperty(named = "review.probe", matches = "true")
class BrilliantProbeTest {

    @Test
    void sacrificeDetector() {
        Map<String, Integer> positives = new TreeMap<>();
        Map<String, Integer> others = new TreeMap<>();
        Map<String, Integer> othersByLabel = new TreeMap<>();
        for (ChessComDataset.Game g : ChessComDataset.load()) {
            if (!g.hasLabels()) {
                continue;
            }
            boolean full = g.labels().stream().allMatch(java.util.Objects::nonNull);
            for (int i = 0; i < g.plies(); i++) {
                ReviewLabel l = g.labels().get(i);
                if (l == null) {
                    continue;
                }
                Board b = new Board();
                b.loadFromFen(g.fens().get(i));
                if (b.isKingAttacked()) {
                    if (l == ReviewLabel.BRILLIANT) {
                        positives.merge("in check", 1, Integer::sum);
                        System.out.println("  in check: " + g.id() + " ply " + (i + 1) + " " + g.san().get(i));
                    }
                    continue;
                }
                String v = ReviewClassifier.sacrificeVerdict(b, g.uci().get(i), i % 2 == 0);
                String k = v == null ? "SACRIFICE" : v;
                if (l == ReviewLabel.BRILLIANT) {
                    positives.merge(k, 1, Integer::sum);
                    if (v != null) {
                        System.out.println("  missed (" + v + "): " + g.id() + " ply " + (i + 1) + " " + g.san().get(i)
                                + "  " + g.fens().get(i));
                    }
                } else if (full) {
                    others.merge(k, 1, Integer::sum);
                    if (v == null || v.equals("fake sacrifice") || v.equals("trapped anyway")) {
                        othersByLabel.merge(k + " " + l.name(), 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("chess.com Brilliants: " + positives);
        System.out.println("other moves of labelled games: " + others);
        System.out.println("other moves (sacrifice / fake / trapped), by chess.com label: " + othersByLabel);
    }
}
