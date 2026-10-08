# QA v1 — riepilogo

Sessione QA (branch `qa/v1`), 7-8 ottobre 2026. Obiettivo: percorrere ogni flusso dell'app dall'avvio allo
spegnimento, correggere i bug di logica con un test di regressione ciascuno, girare i problemi di UI a design e
quelli del browser a browser, e costruire una rete di sicurezza E2E che giri in CI.

Documenti: [FLOWS.md](FLOWS.md) (mappa dei flussi e come sono stati percorsi), [ISSUES.md](ISSUES.md) (ogni problema
con gravità, passi, area e stato).

## Numeri

| | |
|---|---|
| Problemi trovati | 32 (QA-001 … QA-032) |
| Corretti da QA (logica, con test) | 18: QA-001 (parte logica), 002, 003, 004, 007, 008 (parte logica), 010, 018, 019, 020, 021, 025, 027, 028, 029, 030, 031, 032 + resync per l'annulla mossa |
| Assegnati e già risolti | design: 005, 006, 008 (UI), 009, 015, 016, 022, 023, 024; features: 012, 013, 014, 026 |
| Ancora aperti / in corso | browser: 011 (da verificare con browser/v2) |
| Test | unit 516 (10 skip senza motori), E2E 31 (6 classi), tutti verdi con e senza finestra; nel "Pi in scatola" (Docker linux/arm64, 4 CPU, 2 GB, senza Stockfish) unit 495 (29 skip) ed E2E 28/28 senza finestra |

## Bug di logica corretti (i più importanti)

- **Partita bloccata se il motore fallisce** (QA-002): la mossa del bot non veniva mai ritentata; ora tentativi a
  2/5/15/30 s, subito al cambio di motore, `retryBotMove()`.
- **Scacchiera scollegata/ricollegata** (QA-004): nuova modalità `RESYNC` del `BoardStateManager` (LED e messaggio
  per rimettere i pezzi, mossa fatta a cavo staccato accettata); `resyncToLogical()` usata anche dall'annulla mossa.
- **Ultima mossa del bot non replicata** e risultato coperto da "Errore: controlla…" (QA-003).
- **Revisione che continuava in background** con 6 processi Stockfish e l'analisi live sospesa (QA-018).
- **Eccezione quando si solleva il re** subito dopo una spinta di due case (QA-020, trovata dagli E2E).
- **Puzzle**: mossa durante la risposta dell'avversario contata come errore, mosse avversarie lette dalla scacchiera,
  nessuna guida LED per la mossa sbagliata (QA-007).
- **Archivio**: risultati PvP brevi persi (QA-021), import PGN lento 4,5× (QA-019) e senza lock durante il parsing
  (QA-025), avviso di archivio danneggiato a ogni visita (QA-010).
- **Home** (QA-029): al ritorno restava per un attimo la carta "Riprendi" della visita precedente: si poteva
  riprendere la partita sbagliata.
- **PvP in pausa** (QA-030): una mossa sulla scacchiera faceva ripartire gli orologi; **PvC con orologio** (QA-031):
  cadenza persa in archivio; **impostazioni** (QA-032) lette col valore vecchio se la scrittura era in coda.
- **Mosse dallo schermo** (QA-001): `handleMoveInput` con promozione (`e7e8n`), `isAwaitingHumanMove()`; la UI di
  design le usa (tocco sulle case, selettore della promozione).

## Rete di sicurezza

- `SimBoardEndToEndTest` (scacchiera simulata, mosse sollevando e appoggiando i pezzi sui sensori, mosse del bot
  riprodotte come farebbe una persona): PvC matto del bot e dell'umano, PvP matto (orologi fermi) e triplice
  ripetizione, patta d'accordo, abbandono, uscita con conferma, cavo staccato durante la mossa del bot, mossa fatta a
  cavo staccato, annulla mossa durante la replica, mossa durante la pausa, riavvio a partita in corso con ripresa
  dalla Home, puzzle impostato e risolto sulla scacchiera.
- `AppEndToEndTest` (senza scacchiera): in più crash/blocco del motore con ripresa, uscita dalla revisione che chiude
  i motori, partita giocata toccando lo schermo, ogni schermata aperta/ruotata/con cambio tema senza errori nel log,
  partita col bot con orologio archiviata con la sua cadenza.
- `RandomWalkEndToEndTest` e `SimRandomWalkEndToEndTest` ("monkey" con seme): centinaia di azioni casuali
  (schermate, partite, mosse con promozioni, annulla, suggerimenti, patte, pause, abbandoni, uscite, revisioni,
  puzzle, rotazione, tema; sulla scacchiera simulata anche cavo staccato con mosse fatte offline e mosse in pausa);
  falliscono su qualsiasi ERROR nel log o thread FX bloccato e stampano seme e traccia. 5 semi × 600 e 6 semi ×
  400-500 azioni puliti.
- `LongRunEndToEndTest`: 20 partite di fila (misura anche l'attesa del thread FX: max 20 ms, p99 13 ms); thread, processi figli e heap (dopo aver liberato le cache soft)
  restano fermi (es. thread 37→37, processi 2→2, heap 70→72 MB). Con i flag di `run_pi.sh` (512 MB, SerialGC) 30
  partite senza OutOfMemoryError.
- Esecuzione senza finestra: `-De2e.headless=true` (JavaFX Monocle, rendering software), oltre a `xvfb-run` in CI.
  `-De2e.prism=sw` per la pipeline del Raspberry Pi con finestra.
- `ScriptedUciEngine`: direttive `!crash`, `!hang`, `!slow` per simulare motori che si chiudono, si bloccano o sono
  lenti. `SimulatedBoard.setConnected` e pulsante "Scollega" nella finestra del simulatore.
- `-Djavachess.exitAfterMs=N`: chiusura normale dell'app (partita in corso archiviata) per le prove manuali.

## Verifiche sull'app vera (scacchiera simulata)

- PvC fino al matto con autoplay sul monitor 720×1920 (vecchia e nuova UI): partita archiviata, ultima mossa da
  replicare, scheda di fine partita.
- Chiusura con partita in corso → archiviata come interrotta, nessun processo Stockfish residuo.
- `kill -9` a metà partita → `current-game.json` coerente, ripresa possibile (logica di features), nessun processo
  orfano.
- Primo avvio senza dati, senza scacchiera: home, puzzle, archivio vuoto, impostazioni, PvP senza errori nel log.
- Avvio con config, archivio, puzzle-progress e snapshot danneggiati: file messi da parte in `backups/`, app avviata.
- Revisione nella nuova UI: le linee del computer (2, con valutazione e profondità) si vedono — l'esempio citato
  dall'utente ("non posso più vedere le linee del computer") è risolto (features + design).

## Decisioni per l'utente

- `archive.json` nella radice del repository è tracciato da git: è un vecchio archivio (formato v1) con partite reali
  del 2024. Prima del rilascio open source valuta se toglierlo dal repository (l'app non lo usa più: l'archivio vive
  in `~/.javachess/`).

## Cosa resta

- Browser e Lichess (QA-011): da verificare quando browser/v2 è su main (uscita a partita in corso, archiviazione,
  chiusura con JCEF aperto).
- Hardware vero: tutto è provato con la scacchiera simulata e l'emulatore del firmware; con il PCB vanno rifatti a
  mano i casi di cavo staccato/riattaccato e i tempi di assestamento dei sensori.
