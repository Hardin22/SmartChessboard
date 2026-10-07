package io.github.hardin22.javachess.Engine;

/**
 * How strong and how fast the Stockfish bot plays (ignored by the Maia bots, whose strength is their network).
 *
 * @param skillLevel Stockfish "Skill Level" 0..20, used when {@code uciElo} is 0
 * @param uciElo     Stockfish UCI_Elo (1320..3190, the engine's own calibration) with UCI_LimitStrength on; 0 = off
 * @param depth      maximum search depth, 0 = no limit (very low depths make the weakest levels)
 * @param movetimeMs thinking time per move, 0 = the configured one ({@code game.bot.movetime})
 */
public record BotStrength(int skillLevel, int uciElo, int depth, int movetimeMs) {

    public static final int MIN_ELO = 1320;
    public static final int MAX_ELO = 3190;

    public BotStrength {
        skillLevel = Math.max(0, Math.min(20, skillLevel));
        uciElo = uciElo <= 0 ? 0 : Math.max(MIN_ELO, Math.min(MAX_ELO, uciElo));
        depth = Math.max(0, depth);
        movetimeMs = Math.max(0, movetimeMs);
    }

    /** Full strength at the configured time. */
    public static BotStrength full() {
        return new BotStrength(20, 0, 0, 0);
    }

    /** The same strength with another thinking time (the clock of a timed game). */
    public BotStrength withMovetime(int ms) {
        return new BotStrength(skillLevel, uciElo, depth, ms);
    }
}
