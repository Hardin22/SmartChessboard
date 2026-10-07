package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Engine.BotStrength;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Engine.EngineProfile;
import io.github.hardin22.javachess.Services.EngineService.EngineType;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Computer opponents as a ladder of understandable levels, from beginner to full strength, each with an approximate
 * rating (chess.com/lichess style) instead of "Skill Level 7". The low and middle rungs alternate Stockfish with
 * reduced strength and the Maia networks, which play like humans of that rating.
 *
 * <p>The ratings are approximate by nature: Stockfish's UCI_Elo is calibrated against engines, the weakest levels
 * use a very shallow search, and Maia is trained on lichess games. The interface shows them as "circa 1600".</p>
 */
public final class BotLevels {

    /**
     * One rung of the ladder.
     *
     * @param id          stable identifier (saved in the settings and in resumed games)
     * @param name        short Italian name for the card ("Circolo")
     * @param description one line about how it plays
     * @param elo         approximate rating
     * @param engine      Stockfish or a Maia network
     * @param strength    Stockfish strength (ignored by Maia)
     */
    public record Level(String id, String name, String description, int elo, EngineType engine,
                        BotStrength strength) {

        /** "circa 1600", "oltre 3000" for the strongest. */
        public String eloText() {
            return elo >= 3000 ? "oltre 3000" : "circa " + elo;
        }

        /**
         * Name stored in the archive for the bot: "Stockfish (1700)", "Maia 1500". Both forms carry the rating that
         * the review reads back ({@code PlayerRating}) to judge the moves at the right level.
         */
        public String playerName() {
            return engine == EngineType.STOCKFISH ? "Stockfish (" + elo + ")" : "Maia " + engine.name().substring(5);
        }

        public boolean isMaia() {
            return engine != EngineType.STOCKFISH;
        }
    }

    public static final List<Level> ALL = List.of(
            sf("beginner", "Principiante", "Sbaglia spesso e lascia pezzi in presa: per imparare le regole", 600,
                    new BotStrength(0, 0, 1, 300)),
            sf("novice", "Esordiente", "Conosce le basi ma non calcola: vede solo le minacce immediate", 900,
                    new BotStrength(3, 0, 3, 500)),
            maia("maia-1100", "Maia 1100", "Gioca come un giocatore umano da 1100", 1100, EngineType.MAIA_1100),
            sf("club", "Circolo", "Gioca in modo solido ma commette errori", 1350, new BotStrength(20, 1320, 0, 0)),
            maia("maia-1500", "Maia 1500", "Gioca come un giocatore umano da 1500", 1500, EngineType.MAIA_1500),
            sf("intermediate", "Intermedio", "Sfrutta gli errori e attacca bene", 1700,
                    new BotStrength(20, 1700, 0, 0)),
            maia("maia-1900", "Maia 1900", "Gioca come un giocatore umano da 1900", 1900, EngineType.MAIA_1900),
            sf("expert", "Esperto", "Forte a ogni fase della partita", 2000, new BotStrength(20, 2000, 0, 0)),
            sf("candidate", "Candidato maestro", "Raramente sbaglia: serve giocare molto bene", 2200,
                    new BotStrength(20, 2200, 0, 0)),
            sf("master", "Maestro", "Livello da maestro internazionale", 2500, new BotStrength(20, 2500, 0, 0)),
            sf("champion", "Campione", "Livello da campione del mondo", 2800, new BotStrength(20, 2800, 0, 0)),
            sf("max", "Stockfish al massimo", "La forza piena del motore", 3200, BotStrength.full()));

    /** Level suggested to a new player. */
    public static final String DEFAULT_ID = "club";

    private BotLevels() {
    }

    private static Level sf(String id, String name, String description, int elo, BotStrength s) {
        return new Level(id, name, description, elo, EngineType.STOCKFISH, s);
    }

    private static Level maia(String id, String name, String description, int elo, EngineType engine) {
        return new Level(id, name, description, elo, engine, BotStrength.full());
    }

    public static Optional<Level> byId(String id) {
        return ALL.stream().filter(l -> l.id().equals(id)).findFirst();
    }

    /** The level with the rating closest to {@code elo} among {@code candidates}. */
    public static Level closestTo(int elo, List<Level> candidates) {
        Level best = candidates.get(0);
        for (Level l : candidates) {
            if (Math.abs(l.elo() - elo) < Math.abs(best.elo() - elo)) {
                best = l;
            }
        }
        return best;
    }

    /** Levels that can be played on this machine (Maia needs lc0 and its weights). */
    public static List<Level> available() {
        return available(BotLevels::engineAvailable);
    }

    public static List<Level> available(Predicate<Level> playable) {
        return ALL.stream().filter(playable).toList();
    }

    /** One level up or down the ladder (within {@code levels}); the same level at the ends. */
    public static Level step(Level from, int delta, List<Level> levels) {
        int i = levels.indexOf(from);
        if (i < 0) {
            return closestTo(from.elo(), levels);
        }
        return levels.get(Math.max(0, Math.min(levels.size() - 1, i + delta)));
    }

    /**
     * Level of an old setting (engine + Stockfish skill 0..20): Maia keeps its network, Stockfish maps the skill to
     * the nearest rating (skill 0 ≈ 1350 … 20 = full strength).
     */
    public static Level fromLegacy(EngineType engine, int skill) {
        if (engine != null && engine != EngineType.STOCKFISH) {
            return ALL.stream().filter(l -> l.engine() == engine).findFirst().orElseThrow();
        }
        if (skill >= 20) {
            return byId("max").orElseThrow();
        }
        int elo = 1350 + Math.max(0, skill) * 75;
        List<Level> sfLevels = ALL.stream().filter(l -> !l.isMaia() && l.elo() >= 1350).toList();
        return closestTo(elo, sfLevels);
    }

    private static boolean engineAvailable(Level level) {
        try {
            String profileId = level.engine() == EngineType.STOCKFISH ? EngineManager.STOCKFISH
                    : level.engine().profileId();
            for (EngineProfile p : EngineManager.get().profiles()) {
                if (p.id().equals(profileId)) {
                    return p.available();
                }
            }
            return false;
        } catch (RuntimeException e) {
            return level.engine() == EngineType.STOCKFISH;
        }
    }
}
