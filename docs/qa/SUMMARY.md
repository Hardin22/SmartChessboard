# QA v1 — riepilogo

Sessione QA (branch `qa/v1`), 7-8 ottobre 2026. Obiettivo: percorrere ogni flusso dell'app dall'avvio allo
spegnimento, correggere i bug di logica con un test di regressione ciascuno, girare i problemi di UI a design e
quelli del browser a browser, e costruire una rete di sicurezza E2E che giri in CI.

Documenti: [FLOWS.md](FLOWS.md) (mappa dei flussi e come sono stati percorsi), [ISSUES.md](ISSUES.md) (ogni problema
con gravità, passi, area e stato).

## Verifica dopo l'integrazione (8 ottobre, mattina)

main ha ricevuto browser/v2 (browser integrato riscritto), design/v2 (UI completa) e le nuove funzioni di features.
Rifatto il giro sull'app integrata:

- **QA-011 verificato e chiuso**: Lichess si gioca nel browser integrato (uscendo, la partita seguita va in archivio
  come interrotta). Il vecchio flusso via API è rimasto in Impostazioni → Avanzate: lì "Abbandona" era disabilitato
  e la partita lasciata restava aperta su Lichess senza modo di tornarci. Ora "Abbandona" invia l'abbandono (o
  l'annullamento prima che abbiano mosso entrambi), la partita lasciata si riprende dalla stessa voce, e la scheda di
  fine dice vinto/perso dal lato giusto anche col Nero (QA-035). E2E `LichessApiEndToEndTest` contro un Lichess finto
  locale (nessun account reale, nessuna rete).
- **Prove e test isolati dal computer** (QA-033): l'export "su chiavetta" vedeva ogni disco montato in `/Volumes`
  anche con una cartella dati temporanea; ora `-Djavachess.usbRoots`, e gli E2E non caricano mai il Chromium del
  computer. Il Portachiavi delle credenziali del browser ha lo stesso problema: segnalato a browser.
- **Browser** (QA-034, a browser): tornando alla Home mentre Chromium si avvia, a fine avvio la finestra del browser
  compare sopra l'app e può prendersi la scacchiera. Il team browser ha trovato anche un crash della JVM su macOS
  alla chiusura dell'app dopo aver aperto il browser (`CefApp.dispose`), in correzione.
- **Monkey dei tocchi** (`TapWalkEndToEndTest`, `SimTapWalkEndToEndTest`): a ogni passo tocca un pulsante, una riga o
  una casa a caso fra quelli visibili e attivi (solo il foglio aperto, se c'è), raggiungendo ogni schermata con i
  pulsanti dell'app, anche quelle aggiunte in futuro; la rete esterna è tagliata (l'app deve cavarsela offline),
  Chromium non c'è (il browser mostra "non disponibile" con Riprova/Home), c'è una "chiavetta" con un PGN e qualche
  puzzle. Fallisce su errori nel log, eccezioni non gestite, thread FX bloccato o schermata senza nulla da toccare.
  Nella variante con la scacchiera simulata un "giocatore" sistema i pezzi, replica le mosse e muove sui sensori.
  4 semi × 1500 tocchi e le due varianti nella suite: nessun errore. Ha trovato subito un NPE introdotto da me
  nelle Impostazioni, prima del commit.
- **Long run esteso**: dopo ognuna delle 20 partite apre la revisione e avvia l'analisi completa abbandonandola a
  metà, poi statistiche, archivio, allenatore errori, puzzle, impostazioni, temi: thread 48→47, processi 2→2, heap
  dopo GC 127→135 MB, thread FX al massimo 141 ms.
- **Suite** su qa/v1 fad7eae (main af5d0cc): 702 test, 0 falliti, 12 saltati, 34 E2E senza finestra; nel Pi in scatola
  (Docker linux/arm64, 4 CPU, 2 GB, senza Stockfish) 682 test, 0 falliti, 31 saltati, 34/34 E2E, long run con giro
  delle schermate thread 39→38, processi 2→2, heap 124→133 MB. Dopo l'unione di main 698da4b (impostazioni del browser
  di design, funzioni di features) i due monkey dei tocchi (1500 + 1000 tocchi, due volte) sono ancora puliti.
- **Giro su origin/main b3e4552** (con il fix di chiusura di browser): sul Mac i tre test con Chromium vero
  (`AppBrowserJcefE2E`, `BrowserJcefE2E`, `JcefShutdownJcefE2E`) verdi, ciascuno nella propria JVM; l'app vera
  avviata col browser su una pagina locale e la scacchiera simulata (mossa sui sensori giocata sulla pagina) e chiusa
  normalmente: uscita 0, nessun crash report di macOS, nessun processo Chromium rimasto. Pi in scatola: 698 test,
  35/35 E2E, long run thread 38→38, processi 2→2, heap 125→133 MB; 1 fallimento instabile in `CoordinateTrainerTest`
  (un secondo assert sui LED letto troppo presto, segnalato a features).
- Test instabili sotto carico segnalati ai proprietari: `CoordinateTrainerTest` (features, già corretto),
  `SyncWithRealBoardTest` (browser).

## Numeri (primo giro, 7-8 ottobre notte)

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

- Browser: verificato con Chromium vero sul Mac (`AppBrowserJcefE2E`, opt-in) con il fix di browser f2cede3 (QA-034 e
  crash di chiusura); restano le prove sul Raspberry Pi vero (team browser).
- Hardware vero: tutto è provato con la scacchiera simulata e l'emulatore del firmware; con il PCB vanno rifatti a
  mano i casi di cavo staccato/riattaccato e i tempi di assestamento dei sensori.
