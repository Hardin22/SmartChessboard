package io.github.hardin22.javachess.Analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpeningNamesTest {

    @Test
    void familiesAndVariations() {
        assertEquals("C50 Partita Italiana: Giuoco Piano", OpeningNames.italian("C50 Italian Game: Giuoco Piano"));
        assertEquals("B90 Difesa Siciliana: Variante Najdorf, Attacco English",
                OpeningNames.italian("B90 Sicilian Defense: Najdorf Variation, English Attack"));
        assertEquals("D35 Gambetto di Donna rifiutato: Variante di cambio",
                OpeningNames.italian("D35 Queen's Gambit Declined: Exchange Variation"));
        assertEquals("Partita Spagnola", OpeningNames.italian("Ruy Lopez"));
        assertEquals("C42 Difesa Russa", OpeningNames.italian("C42 Russian Game"));
        assertEquals("B12 Difesa Caro-Kann: Variante di spinta",
                OpeningNames.italian("B12 Caro-Kann Defense: Advance Variation"));
        assertEquals("C30 Gambetto di Re accettato", OpeningNames.italian("C30 King's Gambit Accepted"));
        assertEquals("C55 Partita Italiana: Difesa dei due cavalli",
                OpeningNames.italian("C55 Italian Game: Two Knights Defense"));
    }

    @Test
    void unknownNamesStayAsTheyAre() {
        assertEquals("A00 Amar Opening: Paris Gambit", OpeningNames.italian("A00 Amar Opening: Paris Gambit"));
        assertEquals("", OpeningNames.italian(null));
        assertEquals("", OpeningNames.italian("  "));
    }
}
