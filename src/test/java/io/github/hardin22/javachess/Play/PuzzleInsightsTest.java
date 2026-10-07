package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Services.PuzzleProgressService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PuzzleInsightsTest {

    @Test
    void weakestAndStrongestTactics() {
        Map<String, int[]> byTheme = new TreeMap<>();
        byTheme.put("fork", new int[]{10, 3});
        byTheme.put("pin", new int[]{8, 6});
        byTheme.put("skewer", new int[]{4, 0}); // too few attempts
        byTheme.put("short", new int[]{30, 5}); // not a tactic
        byTheme.put("mateIn2", new int[]{12, 1}); // not advice either
        byTheme.put("discoveredAttack", new int[]{6, 6});
        PuzzleProgressService.Stats stats = new PuzzleProgressService.Stats(1500, 40, 20, 0, 3, byTheme);
        List<PuzzleInsights.ThemeScore> weak = PuzzleInsights.weakest(stats, 2);
        assertEquals(List.of("fork", "pin"), weak.stream().map(PuzzleInsights.ThemeScore::tag).toList());
        assertEquals("30%", weak.get(0).rateText());
        assertEquals("discoveredAttack", PuzzleInsights.strongest(stats, 1).get(0).tag());
        assertEquals(3, PuzzleInsights.weakest(stats, 10).size());
    }
}
