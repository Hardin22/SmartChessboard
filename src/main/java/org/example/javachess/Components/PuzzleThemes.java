package org.example.javachess.Components;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lichess puzzle theme tags with their Italian labels, grouped as in the puzzle dashboard. */
public final class PuzzleThemes {

    /** Section key (i18n) -> (tag -> label). */
    public static final Map<String, Map<String, String>> SECTIONS = new LinkedHashMap<>();
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        section("puzzle.themes.tactics",
                "fork", "Forchetta", "pin", "Inchiodatura", "skewer", "Infilata",
                "discoveredAttack", "Attacco di scoperta", "sacrifice", "Sacrificio", "deflection", "Deviazione",
                "interference", "Interferenza", "hangingPiece", "Pezzo in presa", "zugzwang", "Zugzwang",
                "quietMove", "Mossa tranquilla");
        section("puzzle.themes.phase",
                "opening", "Apertura", "middlegame", "Mediogioco", "endgame", "Finale", "advantage", "Vantaggio");
        section("puzzle.themes.special",
                "promotion", "Promozione", "underPromotion", "Sottopromozione", "castling", "Arrocco",
                "enPassant", "En passant");
        section("puzzle.themes.mate",
                "mate", "Scacco matto", "smotheredMate", "Matto affogato", "backRankMate", "Matto del corridoio",
                "bodenMate", "Matto di Boden");
        // Frequent tags not offered as filters but shown on the puzzle screen.
        LABELS.put("short", "Breve");
        LABELS.put("long", "Lungo");
        LABELS.put("veryLong", "Molto lungo");
        LABELS.put("oneMove", "Una mossa");
        LABELS.put("crushing", "Schiacciante");
        LABELS.put("mateIn1", "Matto in 1");
        LABELS.put("mateIn2", "Matto in 2");
        LABELS.put("mateIn3", "Matto in 3");
        LABELS.put("mateIn4", "Matto in 4");
        LABELS.put("master", "Partita di maestri");
        LABELS.put("kingsideAttack", "Attacco sul re");
        LABELS.put("attraction", "Adescamento");
        LABELS.put("defensiveMove", "Mossa difensiva");
        LABELS.put("trappedPiece", "Pezzo intrappolato");
        LABELS.put("exposedKing", "Re esposto");
        LABELS.put("rookEndgame", "Finale di torri");
        LABELS.put("pawnEndgame", "Finale di pedoni");
        LABELS.put("equality", "Parità");
        LABELS.put("intermezzo", "Intermezzo");
        LABELS.put("xRayAttack", "Attacco a raggi X");
        LABELS.put("doubleCheck", "Scacco doppio");
        LABELS.put("clearance", "Sgombero");
    }

    private PuzzleThemes() {
    }

    private static void section(String key, String... tagLabelPairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < tagLabelPairs.length; i += 2) {
            map.put(tagLabelPairs[i], tagLabelPairs[i + 1]);
            LABELS.put(tagLabelPairs[i], tagLabelPairs[i + 1]);
        }
        SECTIONS.put(key, map);
    }

    /** Italian label for a tag (the tag itself when unknown). */
    public static String label(String tag) {
        return LABELS.getOrDefault(tag, tag);
    }
}
