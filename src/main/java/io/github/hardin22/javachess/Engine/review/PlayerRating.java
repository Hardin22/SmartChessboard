package io.github.hardin22.javachess.Engine.review;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Approximate rating of a player from the name stored in the archive, for the review labels (chess.com judges a loss
 * of win chance by the player's level). Bots have the strength of their profile, online players the rating saved with
 * their name; anyone else is unknown (0) and the review uses its default.
 */
public final class PlayerRating {

    /** "Maia 1500" (lc0 with the Maia weights). */
    private static final Pattern MAIA = Pattern.compile("(?i)^maia\\s*(\\d{3,4})\\b");
    /** "Stockfish livello 10": Skill Level 0..20 here, AI level 1..8 on Lichess. */
    private static final Pattern STOCKFISH = Pattern.compile("(?i)^stockfish\\s+livello\\s+(\\d{1,2})\\b");
    /** "name (1834)": an online player with the rating of the game. */
    private static final Pattern RATED = Pattern.compile("\\((\\d{3,4})\\)\\s*$");
    /** Approximate ratings of the Lichess AI levels 1..8. */
    private static final int[] LICHESS_AI = {800, 1100, 1400, 1700, 2000, 2300, 2700, 3000};

    private PlayerRating() {
    }

    /**
     * @param name    player name as archived
     * @param lichess true for a game played on Lichess ("Stockfish livello N" is then the Lichess AI)
     * @return the rating, 0 when unknown
     */
    public static int of(String name, boolean lichess) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        String n = name.trim();
        Matcher m = MAIA.matcher(n);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = STOCKFISH.matcher(n);
        if (m.find()) {
            int level = Integer.parseInt(m.group(1));
            if (lichess) {
                return LICHESS_AI[Math.max(1, Math.min(8, level)) - 1];
            }
            return stockfishSkillElo(level);
        }
        m = RATED.matcher(n);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    /**
     * Stockfish "Skill Level" 0..20 as Elo: the inverse of Stockfish's own UCI_Elo mapping
     * ({@code level = ((elo - 1346.6) / 143.4) ^ (1 / 0.806)}), capped at 2900 for the full engine.
     */
    static int stockfishSkillElo(int level) {
        int l = Math.max(0, Math.min(20, level));
        return (int) Math.min(2900, Math.round(1346.6 + 143.4 * Math.pow(l, 0.806)));
    }
}
