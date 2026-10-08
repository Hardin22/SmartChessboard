# QA v1 — riepilogo

Sessione QA (branch `qa/v1`), 7-8 ottobre 2026. Obiettivo: percorrere ogni flusso dell'app dall'avvio allo
spegnimento, correggere i bug di logica con un test di regressione ciascuno, girare i problemi di UI a design e
quelli del browser a browser, e costruire una rete di sicurezza E2E che giri in CI.

Documenti: [FLOWS.md](FLOWS.md) (mappa dei flussi e come sono stati percorsi), [ISSUES.md](ISSUES.md) (ogni problema
con gravità, passi, area e stato).

## Stato finale (8 ottobre, origin/main 2bbc266 + qa)

| | |
|---|---|
| Problemi trovati | 36 (QA-001 … QA-036), tutti chiusi |
| Corretti da QA (logica, con test) | 22: QA-001 (logica), 002, 003, 004, 007, 008 (logica), 010, 011, 018, 019, 020, 021, 025, 027, 028, 029, 030, 031, 032, 033, 035, 036 + resync dell'annulla mossa |
| Risolti dai proprietari su segnalazione | design 005, 006, 008 (UI), 009, 015, 016, 022, 023, 024; features 012, 013, 014, 026; browser 034 |
| Suite sul Mac (senza finestra) | 757 test, 0 falliti, 12 saltati, 40 E2E in 11 classi |
| Chromium vero (opt-in) | `BrowserJcefE2E` 9, `JcefShutdownJcefE2E` 4, `AppBrowserJcefE2E` 2: verdi, nessun crash di macOS |
| Pi in scatola (Docker arm64, 4 CPU, 2 GB, senza Stockfish) | 722 test, 0 falliti, 31 saltati, 38 E2E; long run thread 33→34, processi 2→2, heap 178→187 MB, thread FX max 22 ms |
| Monkey | monkey dei tocchi 4 semi × 1500 + simulata 3 × 1000 sulle schermate nuove (allenamento, coordinate, finali, aperture, test della scacchiera): nessun errore |
| Long run | 20 partite + giro di tutte le schermate: thread 37→38, processi 2→2, heap 181→189 MB, thread FX max 56 ms |

Rete di sicurezza in breve: E2E con la scacchiera simulata per ogni modalità fino alla fine, monkey a livello di API
(`RandomWalk`, `SimRandomWalk`) e di tocchi (`TapWalk`, `SimTapWalk`) che raggiungono da soli le schermate nuove,
long run con giro delle schermate, Lichess API contro un server finto, app intera con Chromium vero (opt-in), tutto
eseguibile senza finestra e nel Pi in scatola. Gli E2E reggono una macchina carica (passi fino a 90 s, limiti scalati
col carico, diagnosi automatica allo scadere) e girano solo nella loro JVM.

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
- **E2E affidabili sotto carico** (richiesta dell'orchestratore: 3 timeout in `AppEndToEndTest` e una crescita
  dell'heap nel long run, con il Mac a 6 GB di swap). Uno dei tre era un bug vero (QA-036: la selezione del pezzo
  spariva se la scacchiera si ridimensionava fra i due tocchi, la mossa andava persa e il bot non rispondeva mai);
  gli altri due erano stalli da swap (la classe impiegava 123 s invece di 41). Ora ogni passo ha 90 s
  (`-De2e.timeoutMs`), i limiti sui tempi si allungano col carico per CPU (`-De2e.timeScale`), e allo scadere il test
  stampa carico, memoria libera e swap, i frame che JavaFX disegna in un secondo e gli stack dei thread occupati
  dell'app. Long run: la JVM degli E2E libera le cache soft a ogni GC (`-XX:SoftRefLRUPolicyMSPerMB=0`,
  `-De2e.argLine` per altri flag), la misura è la minima di tre, prima di parlare di perdita misura di nuovo e stampa le
  classi cresciute; i worker dei pool sono contati a parte; il giro tocca tutte le schermate registrate. Istogramma
  prima/dopo 20 partite: +4,6 MB di texture del rendering software e cache del testo, nessuna perdita. Suite completa
  con 12 processi che occupano le CPU: 753 test, 0 falliti, 40 E2E.
- Con `-Dtest=...` Surefire faceva girare le classi E2E anche nella JVM dei test unitari, tutte insieme (scacchiera
  spenta e simulata e motori in conflitto: 3 timeout su 13 nel comando dell'orchestratore): ora si saltano fuori
  dalla loro JVM (`e2e.ownJvm`, da IDE `-De2e.ownJvm=true`). Su qa 6cd056f: Mac 756 test, 0 falliti, 40 E2E; Pi in
  scatola 721 test, 0 falliti, 38 E2E, long run thread 33→34, processi 2→2, heap 178→188 MB.
- Test instabili sotto carico segnalati ai proprietari: `CoordinateTrainerTest` (features, già corretto),
  `SyncWithRealBoardTest` (browser).

## Numeri del primo giro (7-8 ottobre notte)

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
