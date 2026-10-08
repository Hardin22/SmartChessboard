# Funzioni da scacchista — riepilogo (branch `features/v1`)

Analisi delle mancanze: [GAP.md](GAP.md). Specifiche per la UI (mandate alla sessione design):
[01 revisione e analisi](specs/01-analysis.md), [02 partita](specs/02-play.md),
[03 allenamento, statistiche, importazione, puzzle](specs/03-training-and-stats.md).

Regola seguita: logica, servizi e view-model osservabili (proprietà JavaFX read-only aggiornate sul thread FX, testi
italiani pronti) con test; **nessuna** modifica a schermate, CSS o controller, che appartengono alla sessione
design: lei integra dalle specifiche. La classificazione della revisione (`Engine/review`) non è stata toccata.

## Stato per funzione

**fatto** = logica + test; **UI** = in attesa che design colleghi la schermata secondo la spec; **in UI** = già
collegato da design.

| # | Funzione | Stato | Dove |
|---|---|---|---|
| R1 | Linee del computer (MultiPV 1–3, valutazione, profondità, notazione italiana) | fatto, **in UI** (revisione) | `Analysis.EngineLines` |
| R2 | Varianti: mosse provate a schermo o con i pezzi veri, torna alla partita, elimina, PGN con varianti | fatto, **in UI** | `Analysis.AnalysisTree`, `AnalysisSession` |
| R3 | Mostra la mossa e la linea migliore sugli errori, come variante | fatto, **in UI** | `AnalysisSession.showBestLine`, `ReviewInsights.move` |
| R4 | Nome dell'apertura offline e in italiano (revisione), offline anche in partita | fatto, **in UI** (revisione) | `AnalysisSession.openingProperty`, `OpeningNames`, `AbstractGame.updateOpeningLabel` |
| R5 | Rigioca i tuoi errori (schermo o scacchiera; tentativo giudicato dal motore) | fatto · UI | `Analysis.MistakeTrainer` |
| R6 | Precisione per fase (apertura / mediogioco / finale) | fatto, **in UI** | `ReviewInsights.phases` |
| — | Momenti chiave | fatto, **in UI** | `ReviewInsights.keyMoments` |
| R7 | Analisi libera da una posizione | fatto · UI (punto d'ingresso) | `new AnalysisSession(fen, List.of())` |
| R8 | Revisione salvata: la partita si riapre già analizzata | fatto · UI | `Stats.ReviewStore` |
| H1/H2 | Analisi e allenamento con i pezzi veri; LED che guidano a qualsiasi posizione | fatto, **in UI** (interruttore in revisione) | `Analysis.BoardFollower` |
| P1 | Ripresa della partita interrotta (riavvio, crash, blackout), PvC e PvP; risolve anche QA-012 e QA-017 | fatto, **in UI** (carta in Home) | `Play.GameSnapshotStore`, `GameResume`, `PvcGame/PvpGame.fromSnapshot` |
| P2 | Annulla mossa contro il computer con guida LED | fatto, **in UI** | `PvcGame.takeBack`, `AbstractGame.undoPlies` + `resyncToLogical` di QA |
| P3 | Orologio contro il computer: cadenze, incremento, tempo del bot adattato | fatto, **in UI** | `Play.TimeControl`, `GameClock`, `PvcGame.getClock` |
| P5 | Suggerimento a richiesta: prima il pezzo, poi la mossa (con LED) | fatto, **in UI** | `Play.HintAdvisor`, `PvcGame.requestHint` |
| P6 | Livelli del computer in Elo comprensibili (12 gradini, Stockfish e Maia) | fatto, **in UI** | `Play.BotLevels`, `Engine.BotStrength`, `EngineManager.setBotStrength` |
| P7 | Patta proposta al computer (risolve QA-013 insieme all'abbandono di design) | fatto, **in UI** | `Play.BotDrawPolicy`, `PvcGame.offerDraw` |
| P8 | Partita da posizione (FEN / editor) con validazione | fatto · UI (editor) | `Play.PositionSetup`, `AbstractGame.setStartPosition` |
| A1 | Importa / esporta PGN da chiavetta USB | fatto · UI | `Stats.PgnTransfer` |
| A2 | Statistiche personali | fatto · UI | `Stats.PlayerStats` |
| A3 | Importa le mie partite da Lichess / Chess.com | fatto · UI | `Stats.OnlineImport` |
| Z1 | Ripasso dei puzzle sbagliati | fatto · UI | `Play.PuzzleReview` |
| Z2 | Serie a tempo (3/5 minuti, sopravvivenza) con record | fatto · UI | `Play.PuzzleRush`, `PuzzleGame.setResultListener/setRated` |
| Z3 | Punti deboli per tema | fatto · UI | `Play.PuzzleInsights` |
| P4 | Mossa dallo schermo | fatta da QA (logica) e design (tocco) | — |

### Non fatto, e perché

- **R9 · codice QR per aprire la partita su lichess**: richiede di caricare la partita su un servizio esterno
  (azione verso l'esterno) e una libreria QR; valore basso rispetto al resto.
- **P10 · ritiro riconosciuto rimettendo indietro i pezzi veri** (stile DGT): va fatto nel riconoscitore di mosse
  (`BoardStateManager`, area QA/hardware); l'annulla con pulsante + guida LED copre il bisogno.
- **P9 · rivincita a colori invertiti**: è una scelta di flusso della schermata di fine partita (design).

## Verifiche

- Test unitari nuovi: `Analysis` (MoveText, EngineLines, AnalysisTree, AnalysisSession, BoardFollower con sensori
  simulati e board manager vero, ReviewInsights, MistakeTrainer anche sulla scacchiera, OpeningNames), `Play`
  (TimeControl, GameClock, BotLevels, BotStrength, GameSnapshotStore, GameResume, PositionSetup, HintAdvisor,
  BotDrawPolicy, PuzzleReview, PuzzleRush, PuzzleInsights), `Stats` (ReviewStore, PlayerStats, OnlineImport con
  risposte finte, PgnTransfer), `Oggetti/GameFeaturesTest` (mosse, annulla, posizione iniziale, ripresa e archivio).
  Suite completa `./mvnw -q test -DskipE2E=true` verde dopo ogni integrazione.
- E2E `e2e/GameFeaturesEndToEndTest` (classe di gioco vera, gestore dei motori vero, motore UCI finto, nessun
  controller): livello Elo + orologio con incremento + annulla + ripresa dopo "riavvio", il bot che muove per primo
  da una posizione, tempo scaduto archiviato come sconfitta, suggerimento in due passi — 4/4 verde.
- Prove sull'app vera sullo schermo 1 (cartelle dati temporanee): partita PvC simulata con `current-game.json`
  scritto a ogni mossa; revisione ridisegnata da design con le linee del motore (segno della 2ª linea verificato
  con Stockfish da riga di comando); PvC nella nuova UI con livello "Circolo · circa 1350", orologi 10+5,
  Annulla / Suggerimento ("Muovi il pedone in e2") / Patta; partita interrotta e, al riavvio, la Home che propone
  "Partita interrotta · Mossa 4 · Riprendi / Ignora" con la copia interrotta già in archivio.
- Revisione indipendente del codice (un agente in sola lettura): 12 problemi trovati e corretti, con test di
  regressione — tra cui la perdita possibile di una partita ripresa e poi interrotta da un blackout, partite PvP
  finite che restavano "riprendibili", la patta data per accettata a posizione cambiata, l'esportazione PGN delle
  mosse dopo la fine della partita, posizioni già finite accettate come posizione iniziale.

## File toccati fuori dai package nuovi (minimi, concordati con QA)

`Oggetti/AbstractGame` (lista mosse, posizione iniziale, annulla, snapshot, apertura offline), `Oggetti/PvcGame`
(livelli, orologio, annulla, suggerimento, patta, ripresa), `Oggetti/PvpGame` (orologio di chi muove, ripresa),
`Oggetti/PuzzleGame` (ascoltatore dei risultati, modalità non valutata), `Oggetti/ChessClock.setRemainingMillis`,
`Engine/EngineManager` (forza del bot: UCI_Elo / skill / profondità / tempo), `Services/GameArchiveService.addAll`.

## Decisioni per l'utente

- **Elo dei livelli**: indicativi ("circa 1350"). Stockfish usa la propria calibrazione `UCI_Elo` (minimo 1320),
  i due gradini più bassi usano skill + profondità ridotta, Maia è addestrata su partite lichess. Da tarare
  giocando, se serve (`Play.BotLevels.ALL`).
- **Orologio e scacchiera**: dopo la mossa del computer il tempo del giocatore parte quando l'ha riprodotta sulla
  scacchiera (equo, ma si può pensare mentre la si riproduce). `game.clock.replicationFree=false` lo fa partire
  subito, come online.
- **Annulla con l'orologio**: i tempi non vengono rimborsati e l'incremento della mossa annullata resta.
- **Partita lasciata con "Esci"**: resta riprendibile dalla Home finché non si gioca un'altra partita o si tocca
  "Ignora" (in archivio è già presente come interrotta; la ripresa la sostituisce, senza doppioni).
- **Nomi delle aperture**: in italiano sullo schermo, in inglese standard nell'archivio e nel PGN.

## Aperti

- In carico a design: editor di posizione (spec 02 §7) e tutta la spec 03 (Rigioca gli errori, revisione salvata,
  Statistiche, Importa da Lichess/Chess.com e da chiavetta, Ripasso/Serie a tempo dei puzzle). Segnalato a design:
  al primo livello del suggerimento la freccia dei "suggerimenti sempre accesi" svela la mossa (proposta: frecce
  continue spente di default nel PvC) e il testo "Mostra la mossa" troncato nel pulsante.
- Dopo l'integrazione della UI: un E2E sulle nuove schermate (QA) e una taratura a mano dei livelli Elo.
