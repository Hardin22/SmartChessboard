package org.example.javachess.Oggetti;

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
}
