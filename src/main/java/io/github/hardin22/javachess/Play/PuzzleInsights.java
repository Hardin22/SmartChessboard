package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Components.PuzzleThemes;
import io.github.hardin22.javachess.Services.PuzzleProgressService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What to train: the puzzle themes with the lowest success rate (with enough attempts to mean something), and the
 * strongest ones, from the progress the puzzle screen already records.
 */
public final class PuzzleInsights {

    /** Attempts needed before a theme is judged. */
    public static final int MIN_ATTEMPTS = 5;
    /** Tags that describe the length or the phase, not a tactic: not useful as advice. */
    private static final Set<String> NOT_TACTICS = Set.of("short", "long", "verylong", "onemove", "middlegame",
            "opening", "endgame", "master", "mastervsmaster", "superGM", "crushing", "advantage", "equality",
            "mate");

    /**
     * One theme.
     *
     * @param tag      Lichess theme tag ("fork")
     * @param name     Italian name for the interface ("Forchetta")
     * @param attempts attempts with this theme
     * @param solved   solved cleanly
     */
    public record ThemeScore(String tag, String name, int attempts, int solved) {
        /** 0..1 */
        public double rate() {
            return attempts == 0 ? 0 : solved / (double) attempts;
        }

        /** "62%". */
        public String rateText() {
            return Math.round(rate() * 100) + "%";
        }
    }

    private PuzzleInsights() {
    }

    /** Weakest tactical themes first (at most {@code max}). */
    public static List<ThemeScore> weakest(PuzzleProgressService.Stats stats, int max) {
        List<ThemeScore> all = judged(stats);
        all.sort(Comparator.comparingDouble(ThemeScore::rate).thenComparing(ThemeScore::attempts,
                Comparator.reverseOrder()));
        return all.subList(0, Math.min(max, all.size()));
    }

    /** Strongest tactical themes first (at most {@code max}). */
    public static List<ThemeScore> strongest(PuzzleProgressService.Stats stats, int max) {
        List<ThemeScore> all = judged(stats);
        all.sort(Comparator.comparingDouble(ThemeScore::rate).reversed().thenComparing(ThemeScore::attempts,
                Comparator.reverseOrder()));
        return all.subList(0, Math.min(max, all.size()));
    }

    private static List<ThemeScore> judged(PuzzleProgressService.Stats stats) {
        List<ThemeScore> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : stats.byTheme().entrySet()) {
            String tag = e.getKey();
            int attempts = e.getValue()[0];
            if (attempts < MIN_ATTEMPTS || NOT_TACTICS.contains(tag) || tag.toLowerCase(Locale.ROOT).startsWith("matein")) {
                continue;
            }
            out.add(new ThemeScore(tag, PuzzleThemes.label(tag), attempts, e.getValue()[1]));
        }
        return out;
    }
}
