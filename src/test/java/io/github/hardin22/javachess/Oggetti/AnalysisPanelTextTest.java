package io.github.hardin22.javachess.Oggetti;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisPanelTextTest {

    @Test
    void capitalMessagesBecomeSentenceCase() {
        assertEquals("Scacchiera pronta! Partita iniziata", AnalysisPanel.prettify("SCACCHIERA PRONTA! Partita Iniziata"));
        assertEquals("Errore: controlla E4", AnalysisPanel.prettify("ERRORE: Controlla E4"));
    }

    @Test
    void normalMessagesAreKept() {
        assertEquals("Posiziona i pezzi...", AnalysisPanel.prettify("Posiziona i pezzi..."));
        assertEquals("Muovi l'avversario: solleva da F8", AnalysisPanel.prettify("Muovi l'avversario: solleva da F8"));
    }

    @Test
    void scoresAreSignedAndPlaceholdersClean() {
        assertEquals("+0.35", AnalysisPanel.formatScore("0.35"));
        assertEquals("−1.20", AnalysisPanel.formatScore("-1.20"));
        assertEquals("0.00", AnalysisPanel.formatScore("-0.00"));
        assertEquals("M3", AnalysisPanel.formatScore("M3"));
        assertEquals("−M2", AnalysisPanel.formatScore("-M2"));
        assertEquals("–", AnalysisPanel.formatScore("-..."));
        assertEquals("1-0", AnalysisPanel.formatScore("1-0"));
        assertEquals("½-½", AnalysisPanel.formatScore("½-½"));
    }
}
