# Funzioni da scacchista — riepilogo (branch `features/v1`)

Analisi delle mancanze: [GAP.md](GAP.md). Specifiche per la UI (mandate alla sessione design):
[01 revisione e analisi](specs/01-analysis.md), [02 partita](specs/02-play.md),
[03 allenamento, statistiche, importazione, puzzle](specs/03-training-and-stats.md).

Regola seguita: logica, servizi e view-model osservabili con test; **nessuna** modifica a schermate, CSS o
controller (proprietà della sessione design, che integra dalle specifiche). La classificazione della revisione
(`Engine/review`) non è stata toccata: le funzioni usano i dati che produce.

## Stato per funzione

Legenda: **fatto** = logica + test, su `features/v1`; **UI** = in attesa che design colleghi le schermate secondo la
spec; **integrato** = su main.

| # | Funzione | Stato | Dove |
|---|---|---|---|
| R1 | Linee del computer (MultiPV 1–3, valutazione, profondità, notazione italiana) | fatto, integrato su main · UI | `Analysis.EngineLines` |
| R2 | Varianti: provare mosse (schermo o pezzi veri), tornare alla partita, eliminare, PGN con varianti | fatto, integrato · UI | `Analysis.AnalysisTree`, `AnalysisSession` |
| R3 | Mostra la mossa/linea migliore sugli errori, come variante | fatto, integrato · UI | `AnalysisSession.showBestLine`, `ReviewInsights.move` |
| R4 | Nome dell'apertura offline (revisione e partita) e mossa di teoria | fatto · UI (revisione) | `AnalysisSession.openingProperty`, `AbstractGame.updateOpeningLabel` |
| R5 | Rigioca i tuoi errori (schermo o scacchiera, tentativi giudicati dal motore) | fatto · UI | `Analysis.MistakeTrainer` |
| R6 | Precisione per fase (apertura / mediogioco / finale) | fatto, integrato · UI | `ReviewInsights.phases` |
| R7 | Analisi libera da una posizione | fatto, integrato · UI | `AnalysisSession(fen, List.of())` |
| R8 | Revisione salvata (si riapre già analizzata) | fatto · UI | `Stats.ReviewStore` |
| — | Momenti chiave | fatto, integrato · UI | `ReviewInsights.keyMoments` |
| H1/H2 | Analisi e allenamento con i pezzi veri, guida LED a qualsiasi posizione | fatto, integrato · UI (interruttore) | `Analysis.BoardFollower` |
| P1 | Ripresa della partita interrotta (riavvio, crash, mancanza di corrente) | fatto · UI (carta Home) | `Play.GameSnapshot(Store)`, `GameResume`, `PvcGame/PvpGame.fromSnapshot` |
| P2 | Annulla mossa contro il computer con guida LED | fatto · UI (pulsante) | `PvcGame.takeBack`, `AbstractGame.undoPlies` |
| P3 | Orologio contro il computer, cadenze e incremento, tempo del bot adattato | fatto · UI | `Play.TimeControl`, `GameClock`, `PvcGame.getClock` |
| P5 | Suggerimento a richiesta (pezzo, poi mossa; LED) | fatto · UI | `Play.HintAdvisor`, `PvcGame.requestHint` |
| P6 | Livelli del computer in Elo comprensibili | fatto · UI | `Play.BotLevels`, `Engine.BotStrength`, `EngineManager.setBotStrength` |
| P7 | Proposta di patta al computer | fatto · UI | `Play.BotDrawPolicy`, `PvcGame.offerDraw` |
| P8 | Partita da posizione (FEN / editor) con validazione | fatto · UI (editor) | `Play.PositionSetup`, `AbstractGame.setStartPosition` |
| P4 | Mossa dallo schermo | assegnata a QA | — |
| A1 | Importa / esporta PGN da chiavetta USB | fatto · UI | `Stats.PgnTransfer` |
| A2 | Statistiche personali | fatto · UI | `Stats.PlayerStats` |
| A3 | Importa le mie partite da Lichess / Chess.com | fatto · UI | `Stats.OnlineImport` |
| Z1 | Ripasso dei puzzle sbagliati | fatto · UI | `Play.PuzzleReview` |
| Z2 | Serie a tempo (3/5 minuti, sopravvivenza) con record | fatto · UI | `Play.PuzzleRush`, `PuzzleGame.setResultListener/setRated` |
| Z3 | Punti deboli per tema | fatto · UI | `Play.PuzzleInsights` |

(Bozza: completata alla chiusura.)
