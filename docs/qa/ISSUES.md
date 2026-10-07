# Problemi trovati (audit QA)

Gravità: **alta** (vicolo cieco, perdita di dati, partita bloccata), **media** (stato incoerente, guida sbagliata),
**bassa** (fastidio, rifinitura). Area: logica / UI / browser. Stato: corretto (commit, test) · assegnato
(sessione) · aperto.

| ID | Gravità | Area | Titolo | Stato |
|---|---|---|---|---|
| QA-001 | alta | logica+UI | Nessuna mossa possibile dallo schermo: senza scacchiera (o scollegata) partite e puzzle sono un vicolo cieco | logica corretta; UI assegnata a design |
| QA-002 | alta | logica | Se il motore fallisce (assente, crash, bloccato) la mossa del bot non viene mai ritentata: partita ferma | corretto |
| QA-003 | media | logica | La mossa del bot che chiude la partita non viene fatta replicare; "Errore: controlla X" copre il risultato | corretto |
| QA-004 | media | logica | Ricollegamento della scacchiera a metà partita: nessuna guida per rimettere i pezzi, mossa del bot persa | corretto |
| QA-005 | bassa | UI | Impostazioni: "indietro" scarta le modifiche senza avviso (si salvano solo con "Salva") | risolto da design (salvataggio a ogni tocco) |
| QA-006 | media | UI | Rotazione 180° solo dalle impostazioni e non persistente (persa al riavvio) | risolto da design (↻ in ogni intestazione; "Monitor capovolto" salvato in ui.screen.flipped) |
| QA-007 | media | logica | Puzzle: una mossa durante la risposta dell'avversario contava come errore; mosse del lato avversario lette dalla scacchiera; mossa sbagliata senza guida LED | corretto |
| QA-008 | media | logica+UI | Puzzle senza database: "Nessun puzzle trovato con questi filtri" invece di spiegare che mancano i dati | logica corretta (PuzzleService.hasPuzzleData); UI in carico a design |
| QA-009 | bassa | UI | Revisione aperta dalla home: "indietro" porta all'archivio | risolto da design (indietro torna alla schermata di provenienza) |
| QA-010 | bassa | logica | Archivio danneggiato: il dialogo d'errore ricompare a ogni apertura dell'archivio | aperto |
| QA-011 | media | browser | Partita Lichess (API) lasciata a metà: non archiviata e non abbandonata su Lichess | assegnato a browser |
| QA-012 | alta | logica | Kill/spegnimento del Pi a partita in corso: partita persa (lo shutdown hook non salva); nessuna ripresa | logica fatta da features (snapshot a ogni mossa, GameResume); carta in Home a design |
| QA-013 | media | logica+UI | Nessun abbandono / offerta di patta: "Termina" archivia sempre come interrotta (`*`) | fatto: abbandono e patta nella UI di design, patta col bot (BotDrawPolicy) da features |
| QA-014 | bassa | logica | Nome dell'apertura solo online (explorer Lichess, che ora chiede un token): offline non compare | fatto da features (libro offline prima dell'explorer) |
| QA-015 | media | UI | Revisione: dopo "Analizza partita" la scacchiera si rimpicciolisce e le etichette si troncano | risolto da design (scacchiera a tutta larghezza nel ridisegno) |
| QA-016 | media | UI | Motore che fallisce in PvC: manca un "Riprova" accanto al messaggio | in carico a design |
| QA-017 | bassa | UI+logica | Home: il riquadro "Partita in corso / Riprendi" non compare mai (uscire da GAME termina la partita) | assegnato a design + features |
| QA-018 | media | logica | Revisione: l'analisi completa continua (6 processi Stockfish, analisi live sospesa) dopo aver lasciato la schermata o aperto un'altra partita | corretto |
| QA-019 | media | logica | Import PGN lentissimo (1000 partite: 9,6 s su Mac, minuti sul Pi) | corretto |
| QA-020 | media | logica | Re sollevato subito dopo una spinta di due case: eccezione nel listener della scacchiera, schermo non aggiornato | corretto |
| QA-021 | media | logica | PvP: abbandono / patta d'accordo nelle prime mosse non archiviati (soglia delle partite interrotte applicata a tutte) | corretto |
| QA-022 | media | UI | A partita finita "Abbandona" resta attivo e non fa nulla | assegnato a design |
| QA-023 | bassa | UI | A partita finita il riquadro coach mostra ancora la valutazione e "mossa consigliata" | assegnato a design |
| QA-024 | bassa | UI/prodotto | PvP: frecce della mossa migliore e verdetti LED attivi di default per entrambi i giocatori | assegnato a design (decisione) |
| QA-025 | media | logica | Import PGN grande: il parsing teneva il lock dell'archivio (il salvataggio della partita appena finita aspettava minuti sul Pi) | corretto |
| QA-026 | bassa | logica | Ripresa rifiutata dopo uno spegnimento: la partita non finisce in archivio | proposto a features |
| QA-027 | media | logica | E2E dipendenti dall'ordine: salvataggi asincroni del test precedente contati nel successivo | corretto |

---

## QA-001 · Nessuna mossa dallo schermo (alta, logica+UI)
**Passi**: avvio senza Arduino (`board.mode=auto`, nessuna porta) → Contro il computer → Inizia. Il setup si
completa subito (scacchiera scollegata), ma `ChessBoardUI` non ha alcun gestore di tocco/trascinamento: non si
può muovere. Lo stesso in PvP, nei puzzle e quando il cavo si stacca a metà partita.
**Correzione logica** (qa/v1 c5ee9ca): `AbstractGame.parseMoveInput` accetta l'UCI con il pezzo di promozione
(`e7e8n`) e restituisce null per testo non valido (prima lanciava eccezioni nei puzzle); `isAwaitingHumanMove()`
dice alla UI quando una mossa è attesa (PvC: turno dell'umano e bot fermo; PvP: partita in corso; puzzle: turno
di chi risolve). Test: `MoveInputParsingTest`.
**UI (design-a0, accordato)**: tocco sul pezzo → destinazioni legali → tocco sulla destinazione →
`handleMoveInput("e2e4")`, selettore D/T/A/C per la promozione; attivo solo con la scacchiera scollegata.

## QA-002 · Bot fermo dopo un errore del motore (alta, logica)
**Passi**: PvC con Stockfish che si chiude o non risponde durante la ricerca (simulato con `ScriptedUciEngine`
`!crash` / `!hang`). `PvcGame.handleComputerMove` mostrava "Motore non disponibile" e non ritentava più: turno
del bot per sempre, l'unica uscita era "Termina".
**Correzione**: nuovi tentativi dopo 2, 5, 15, 30 s (poi ogni 30 s) finché la posizione non cambia, subito se si
sceglie un altro motore, `retryBotMove()` per un eventuale "Riprova"; i tentativi pendenti si annullano a fine
partita. Nota: `UciClient` rifiuta di riavviare un motore che è caduto 3 volte in un minuto; il recupero
avviene quindi al più entro ~1 minuto. Test: E2E `botMoveIsRetriedWhenTheEngineCrashesOrHangs`.

## QA-003 · Ultima mossa del bot non replicata (media, logica)
**Passi**: SIM `-Djavachess.board=sim -Djavachess.devgame=pvc -Djavachess.sim.autoplay=40`: il bot dà matto
(14…Nxe4#). La mossa non veniva chiesta sulla scacchiera (`startBotMoveReplication` solo se la partita
continuava): cavallo ancora su f6, LED spenti, e dopo 0,8 s "Errore: controlla F6" sostituiva "Scaccomatto!
Vince il Nero" nella riga di stato.
**Correzione**: la replica parte sempre; dopo la fine gli errori della scacchiera evidenziano la casa ma non
sostituiscono il risultato. Test: E2E scacchiera simulata (matto del bot).

## QA-004 · Ricollegamento a metà partita (media, logica)
**Passi**: partita in corso → cavo staccato → il bot muove (replica considerata fatta perché scollegata) →
cavo riattaccato. In PLAY i pezzi mancanti non venivano mai indicati (un pezzo mancante è "sollevato") e i pezzi
in più diventavano dopo 0,8 s "Errore: controlla e7" senza dire cosa fare; una mossa fatta a cavo staccato
veniva vista solo per caso.
**Correzione**: `BoardStateManager` aspetta la prima fotografia dei sensori dopo il ricollegamento: mossa legale
fatta offline → accettata; altrimenti modalità `RESYNC` (LED mancanti/da togliere come nel setup, schermo con la
posizione della partita, "Rimetti i pezzi come sullo schermo: …", poi "Scacchiera allineata").
`resyncToLogical()` per l'annulla mossa di features. `SimulatedBoard.setConnected` e pulsante "Scollega"
nella finestra del simulatore. Test: 4 casi in `BoardStateManagerTest`, E2E scacchiera simulata.

## QA-005 · Impostazioni perse con "indietro" (bassa, UI)
**Passi**: Impostazioni → cambia livello bot o luminosità → freccia indietro: nessun avviso, valori persi.
**Proposta**: salvataggio immediato di ogni controllo (come tema e rotazione) oppure avviso "Modifiche non salvate".

## QA-006 · Rotazione non persistente e poco raggiungibile (media, UI)
**Passi**: Impostazioni → Ruota schermo → riavvio: la UI torna diritta. La rotazione esiste solo nelle
impostazioni (in partita bisogna uscire, cioè terminare la partita). Il brief design la vuole in ogni schermata;
va salvata in config (`ui.rotated`).

## QA-007 · Puzzle (media, logica)
1. Dopo una mossa giusta l'avversario risponde dopo 500 ms: una mossa in quell'intervallo veniva confrontata con
   la risposta attesa dell'avversario e contava come **errore** (il puzzle non risultava più risolto).
2. `PuzzleGame` non impostava il lato che muove sulla scacchiera: i pezzi dell'avversario mossi fisicamente
   erano letti come mosse.
3. Mossa sbagliata: solo "Mossa errata. Torna indietro." senza LED.
**Correzione**: input accettato solo quando `isAwaitingHumanMove()`; `setPhysicalMoveSide(lato di chi risolve)`;
mossa sbagliata → `resyncToLogical()` (LED per rimettere il pezzo). Test: E2E puzzle (esistente) e scacchiera
simulata.

## QA-008 · Puzzle senza database (media, logica+UI)
**Passi**: primo avvio senza `data/puzzles.db` né CSV → Puzzle → Inizia → toast "Nessun puzzle trovato con
questi filtri". L'utente cambia filtri senza esito. Serve sapere che mancano i dati (e come scaricarli).

## QA-009 · "Indietro" dalla revisione (bassa, UI)
**Passi**: Home → "Rivedi" sull'ultima partita → freccia indietro: si arriva all'archivio, non alla home.

## QA-010 · Avviso archivio ripetuto (bassa, logica)
**Passi**: archivio danneggiato all'avvio → Archivio: dialogo d'errore; Home → Archivio: di nuovo, a ogni
apertura (`ArchiveController.readRows` lo mostra sempre).

## QA-011 · Lichess lasciata a metà (media, browser)
**Passi**: Home → Lichess con partita API in corso → indietro → Termina: `OnlineGame.endGame(…, false)` ferma lo
stream; la partita non va in archivio e su Lichess resta aperta finché scade il tempo. Il brief browser sposta
Lichess nel browser integrato: da verificare lì.

## QA-012 · Partita persa con kill / spegnimento (alta, logica)
**Passi**: partita in corso → `kill <pid>` (o spegnimento del Pi): lo shutdown hook spegne i LED ma non archivia
la partita (lo fa solo `App.stop`, che con SIGTERM non viene chiamato). Features salva uno snapshot a ogni mossa
(ripresa dopo il riavvio): copre anche questo caso.

## QA-013 · Abbandono e patta (media)
"Termina partita" è l'unica uscita: risultato `*` "Interrotta" anche quando chi gioca vuole abbandonare o le due
persone si accordano per la patta. In carico a features (offerta/accettazione, abbandono distinto).

## QA-014 · Nome dell'apertura offline (bassa)
`OpeningExplorer` usa solo explorer.lichess.ovh: senza rete non c'è il nome. Features cura "nome apertura".

## QA-015 · Revisione: layout dopo l'analisi (media, UI)
**Passi**: archivio → partita → "Analizza partita" (720×1280): al termine compaiono precisione e grafico e la
scacchiera passa da ~530 a ~310 px di lato; "Mossa 12 di 28" diventa "Mo...", "Classificazione delle mo...".

## QA-016 · "Riprova" per il motore (media, UI)
Con QA-002 la logica ritenta da sola e mette in stato "Motore non disponibile: <motivo>. Nuovo tentativo tra N s";
serve un pulsante che chiami `PvcGame.retryBotMove()`.

## QA-017 · Riquadro "Partita in corso" morto (bassa, UI+logica)
`HomeController` mostra "Partita in corso / Riprendi" se `ActiveGameController.isGameInProgress()`, ma ogni uscita
da GAME passa da `onNavigatedFrom → stopAndSaveGame`, quindi non c'è mai una partita in corso fuori dalla schermata
di gioco. Da decidere con la ripresa di features (sospendere invece di terminare).

## QA-018 · Revisione che continua in background (media, logica)
**Passi**: archivio → partita lunga → "Analizza partita" → indietro → Home → nuova partita PvC. Il thread
`game-analysis` continuava con il suo pool (6 processi Stockfish su questo Mac) e teneva sospesa l'analisi live
(`holdLive`) fino alla fine; aprendo un'altra partita la vecchia analisi continuava in parallelo alla nuova.
**Correzione**: `ReviewController` interrompe la revisione quando si lascia la schermata o si carica un'altra
partita (il pool si chiude subito), senza dialogo d'errore né barra di avanzamento rimasta. Test: E2E
`leavingTheReviewStopsTheFullAnalysisAndItsEngines` (motori chiusi entro 3 s).

## QA-019 · Import PGN lento (media, logica)
`PgnCodec.fromSan` generava il SAN di ogni mossa legale (e compilava una regex) per ogni mossa letta.
Misura (`ArchiveBench`, 1000 partite da 80 semimosse): import 9,6 s → 2,1 s; caricamento 3000 partite 127 ms;
salvataggio di una partita con 3000 in archivio ~60 ms (file di 7 MB riscritto ogni volta: accettabile sul Pi,
sul thread di storage). Test: `PgnCodecTest.fromSanCapturesPromotionsAndDisambiguation`.

## QA-020 · FEN della scacchiera fisica illeggibile (media, logica)
**Passi**: 1.e4 c5 2.e5 d5 (en passant possibile su d6) → il Bianco solleva il re. `BoardStateManager.displayFen`
pubblicava la posizione fisica (senza il re) con il campo en passant `d6` della posizione logica; chesslib, nel
caricarla, verifica la cattura en passant contro il re e lancia `ArrayIndexOutOfBoundsException`: "Board listener
failed" nel log e scacchiera a schermo non aggiornata. Trovato dall'E2E con la scacchiera simulata.
**Correzione**: nella fotografia parziale (pezzi sollevati) il campo en passant è `-`. Test
`BoardStateManagerTest.liftingTheKingAfterADoublePawnPushPublishesAReadableFen`.

## QA-021 · Risultati PvP brevi persi (media, logica)
**Passi**: Due giocatori → 1.e4 e5 → patta d'accordo (o abbandono): la partita non andava in archivio perché
`PvpGame.endGame` scartava ogni partita con meno di ~4 semimosse, anche con un risultato (contro il bot invece
vengono tenute). **Correzione**: la soglia vale solo per le partite interrotte. Test E2E `pvpDrawByAgreement`.

## QA-022/023/024 · Nuova UI, fine partita e PvP
Passi (`-Djavachess.board=sim -Djavachess.devgame=pvc -Djavachess.sim.autoplay=40`, monitor 720×1920): al matto
del bot la scheda "Partita finita · Hai perso · Rivedi / Nuova partita" è corretta, ma "Abbandona" resta attivo
(nessun effetto) e il riquadro coach mostra "-M1 · Al tuo turno vedrai la mossa consigliata". In PvP
(`-Djavachess.devgame=pvp`) a inizio partita compare la freccia e2-e4 per entrambi.

## QA-025 · Import PGN e lock dell'archivio (media, logica)
`importPgn` era `synchronized` per tutta la durata (parsing + controllo delle mosse). Con l'import da USB di
features (file fino a 50 MB) il salvataggio della partita appena giocata restava in attesa. Ora parsing e
controllo sono fuori dal lock. Test `aGameIsSavedWhileABigPgnIsBeingImported` (prima: 667 ms di attesa).

## QA-026 · Ripresa rifiutata (bassa, logica)
Verificato con kill -9 sull'app vera: `current-game.json` resta coerente e la ripresa è possibile; se però
l'utente rifiuta la ripresa (`GameResume.discard`) la partita non va in archivio. Proposto a features di
archiviarla come interrotta.

## QA-027 · E2E dipendenti dall'ordine (media, test)
Fallimento visto solo nella cartella di main (5 → 6 partite): la partita lasciata dal test precedente veniva
archiviata in modo asincrono durante il test successivo. `E2eHarness.awaitStorage()` prima di ogni test.
