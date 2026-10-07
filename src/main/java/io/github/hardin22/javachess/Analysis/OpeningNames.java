package io.github.hardin22.javachess.Analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opening names in Italian for the interface. The opening book and the online explorer give the English names of
 * the lichess chess-openings set ("C50 Italian Game: Giuoco Piano"); the archive keeps them as they are (standard,
 * searchable), the screens show "C50 Partita Italiana: Giuoco Piano". Families are translated with the names used
 * in Italian chess books; the usual words of the variations (Defense, Variation, Attack, Gambit, Accepted...) too;
 * proper names stay as they are.
 */
public final class OpeningNames {

    /** Longest first, so "Queen's Gambit Declined" wins over "Queen's Gambit". */
    private static final Map<String, String> FAMILIES = new LinkedHashMap<>();
    private static final Map<String, String> WORDS = new LinkedHashMap<>();
    private static final Pattern ECO = Pattern.compile("^([A-E]\\d\\d)\\s+(.*)$");

    static {
        family("Queen's Gambit Declined", "Gambetto di Donna rifiutato");
        family("Queen's Gambit Accepted", "Gambetto di Donna accettato");
        family("Queen's Gambit", "Gambetto di Donna");
        family("King's Gambit Declined", "Gambetto di Re rifiutato");
        family("King's Gambit Accepted", "Gambetto di Re accettato");
        family("King's Gambit", "Gambetto di Re");
        family("King's Indian Defense", "Difesa Est-Indiana");
        family("King's Indian Attack", "Attacco Est-Indiano");
        family("Queen's Indian Defense", "Difesa Ovest-Indiana");
        family("Nimzo-Indian Defense", "Difesa Nimzo-Indiana");
        family("Bogo-Indian Defense", "Difesa Bogo-Indiana");
        family("Old Indian Defense", "Difesa Vecchia Indiana");
        family("Indian Defense", "Difesa Indiana");
        family("Italian Game", "Partita Italiana");
        family("Ruy Lopez", "Partita Spagnola");
        family("Spanish Game", "Partita Spagnola");
        family("Scotch Game", "Partita Scozzese");
        family("Vienna Game", "Partita Viennese");
        family("Four Knights Game", "Partita dei quattro cavalli");
        family("Three Knights Opening", "Apertura dei tre cavalli");
        family("Two Knights Defense", "Difesa dei due cavalli");
        family("Petrov's Defense", "Difesa Russa");
        family("Russian Game", "Difesa Russa");
        family("Philidor Defense", "Difesa Philidor");
        family("Center Game", "Partita del centro");
        family("Bishop's Opening", "Apertura d'Alfiere");
        family("Ponziani Opening", "Apertura Ponziani");
        family("King's Pawn Game", "Partita di pedone di Re");
        family("King's Knight Opening", "Apertura di Cavallo di Re");
        family("Sicilian Defense", "Difesa Siciliana");
        family("French Defense", "Difesa Francese");
        family("Caro-Kann Defense", "Difesa Caro-Kann");
        family("Scandinavian Defense", "Difesa Scandinava");
        family("Alekhine Defense", "Difesa Alekhine");
        family("Pirc Defense", "Difesa Pirc");
        family("Modern Defense", "Difesa Moderna");
        family("Owen Defense", "Difesa Owen");
        family("Nimzowitsch Defense", "Difesa Nimzowitsch");
        family("Slav Defense", "Difesa Slava");
        family("Semi-Slav Defense", "Difesa Semi-Slava");
        family("Grünfeld Defense", "Difesa Grünfeld");
        family("Gruenfeld Defense", "Difesa Grünfeld");
        family("Benoni Defense", "Difesa Benoni");
        family("Modern Benoni", "Benoni Moderna");
        family("Benko Gambit", "Gambetto Benko");
        family("Dutch Defense", "Difesa Olandese");
        family("Catalan Opening", "Apertura Catalana");
        family("English Opening", "Apertura Inglese");
        family("Réti Opening", "Apertura Réti");
        family("Reti Opening", "Apertura Réti");
        family("Bird Opening", "Apertura Bird");
        family("London System", "Sistema Londra");
        family("Trompowsky Attack", "Attacco Trompowsky");
        family("Queen's Pawn Game", "Partita di pedone di Donna");
        family("Zukertort Opening", "Apertura Zukertort");
        family("Evans Gambit", "Gambetto Evans");
        family("Danish Gambit", "Gambetto Danese");
        family("Budapest Defense", "Difesa Budapest");
        family("Englund Gambit", "Gambetto Englund");
        family("Elephant Gambit", "Gambetto dell'Elefante");
        family("Latvian Gambit", "Gambetto Lettone");
        family("Van't Kruijs Opening", "Apertura Van't Kruijs");
        family("Polish Opening", "Apertura Polacca");
        family("Hungarian Opening", "Apertura Ungherese");
        family("Grob Opening", "Apertura Grob");

        WORDS.put("Two Knights Defense", "Difesa dei due cavalli");
        WORDS.put("Four Knights Variation", "Variante dei quattro cavalli");
        WORDS.put("Main Line", "linea principale");
        WORDS.put("Exchange Variation", "Variante di cambio");
        WORDS.put("Advance Variation", "Variante di spinta");
        WORDS.put("Classical Variation", "Variante classica");
        WORDS.put("Closed Variation", "Variante chiusa");
        WORDS.put("Open Variation", "Variante aperta");
        WORDS.put("Accelerated Dragon", "Dragone accelerato");
        WORDS.put("Dragon Variation", "Variante del Dragone");
        WORDS.put("Variation", "Variante");
        WORDS.put("Attack", "Attacco");
        WORDS.put("Gambit", "Gambetto");
        WORDS.put("Defense", "Difesa");
        WORDS.put("Accepted", "accettato");
        WORDS.put("Declined", "rifiutato");
        WORDS.put("Countergambit", "Controgambetto");
        WORDS.put("Line", "linea");
        WORDS.put("System", "Sistema");
        WORDS.put("Opening", "Apertura");
        WORDS.put("Game", "Partita");
    }

    private OpeningNames() {
    }

    private static void family(String english, String italian) {
        FAMILIES.put(english, italian);
    }

    /**
     * "C50 Italian Game: Giuoco Piano" → "C50 Partita Italiana: Giuoco Piano"; "B90 Sicilian Defense: Najdorf
     * Variation" → "B90 Difesa Siciliana: Variante Najdorf". Unknown names are returned unchanged; null → "".
     */
    public static String italian(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String text = name.trim();
        String eco = "";
        Matcher m = ECO.matcher(text);
        if (m.matches()) {
            eco = m.group(1) + " ";
            text = m.group(2);
        }
        String family = text;
        String rest = "";
        int colon = text.indexOf(':');
        if (colon >= 0) {
            family = text.substring(0, colon).trim();
            rest = text.substring(colon + 1).trim();
        }
        String fam = FAMILIES.get(family);
        if (fam == null) {
            return name.trim(); // keep unknown families as they are (no half translations)
        }
        return eco + fam + (rest.isEmpty() ? "" : ": " + variation(rest));
    }

    /** "Najdorf Variation, English Attack" → "Variante Najdorf, Attacco English" (Italian word order). */
    static String variation(String rest) {
        StringBuilder out = new StringBuilder();
        for (String part : rest.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(phrase(p));
        }
        return out.toString();
    }

    private static String phrase(String p) {
        for (Map.Entry<String, String> e : WORDS.entrySet()) {
            String key = e.getKey();
            if (p.equals(key)) {
                return capitalize(e.getValue());
            }
            if (p.endsWith(" " + key)) {
                // "Najdorf Variation" → "Variante Najdorf"
                String head = p.substring(0, p.length() - key.length() - 1);
                return capitalize(e.getValue()) + " " + head;
            }
        }
        return p;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
