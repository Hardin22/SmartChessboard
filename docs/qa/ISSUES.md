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
| QA-005 | bassa | UI | Impostazioni: "indietro" scarta le modifiche senza avviso (si salvano solo con "Salva") | assegnato a design |
| QA-006 | media | UI | Rotazione 180° solo dalle impostazioni e non persistente (persa al riavvio) | assegnato a design |
| QA-007 | media | logica | Puzzle: una mossa durante la risposta dell'avversario contava come errore; mosse del lato avversario lette dalla scacchiera; mossa sbagliata senza guida LED | corretto |
| QA-008 | media | logica+UI | Puzzle senza database: "Nessun puzzle trovato con questi filtri" invece di spiegare che mancano i dati | aperto |
| QA-009 | bassa | UI | Revisione aperta dalla home: "indietro" porta all'archivio | assegnato a design |
| QA-010 | bassa | logica | Archivio danneggiato: il dialogo d'errore ricompare a ogni apertura dell'archivio | aperto |
| QA-011 | media | browser | Partita Lichess (API) lasciata a metà: non archiviata e non abbandonata su Lichess | assegnato a browser |
| QA-012 | alta | logica | Kill/spegnimento del Pi a partita in corso: partita persa (lo shutdown hook non salva); nessuna ripresa | assegnato a features (snapshot a ogni mossa) |
| QA-013 | media | logica+UI | Nessun abbandono / offerta di patta: "Termina" archivia sempre come interrotta (`*`) | assegnato a features |
| QA-014 | bassa | logica | Nome dell'apertura solo online (explorer Lichess, che ora chiede un token): offline non compare | assegnato a features |

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
