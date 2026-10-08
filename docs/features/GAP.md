# Funzioni mancanti o scadenti — analisi da scacchista

Punto di vista: chi gioca **sulla scacchiera fisica** di javaChess (sensori + 64 LED, schermo touch 720×1920 di
fianco), contro il computer, in due, con i puzzle, e poi rivede le partite. Base dell'analisi: main `5accc41`
(logica di gioco, motore, revisione, archivio, puzzle, hardware) e il ridisegno in corso `design/v2`
(`c9bcc72`, nuove schermate). Confronto con ciò che offrono chess.com, lichess e le scacchiere elettroniche
(Chessnut Evo, DGT Pegasus/Centaur, ChessUp, Square Off).

Priorità: **indispensabile** (senza, un giocatore la considera una mancanza grave o un vicolo cieco),
**importante** (la si aspetta da qualunque prodotto serio del settore), **bello** (valore aggiunto).

## Cosa c'è già (e va bene)

- Partita contro Stockfish (livello 1–20) e Maia 1100/1500/1900, scelta del colore (Bianco/Casuale/Nero),
  cambio di motore a metà partita, frecce/LED di suggerimento continui (on/off), barra di valutazione.
- Partita a due con orologio, cadenze e incremento; pausa, patta e abbandono (ridisegno v2).
- LED: case di partenza, mosse legali al sollevamento di un pezzo, qualità delle mosse candidate, scacco,
  mossa del computer da replicare, pezzi mancanti/sbagliati durante la disposizione.
- Revisione: precisione dei due giocatori, etichette stile chess.com (classificazione congelata), grafico,
  freccia della mossa migliore sugli errori, valutazione della posizione corrente, riepilogo dei conteggi.
- Archivio con filtri, ricerca, esportazione PGN di una partita; il servizio sa anche importare PGN.
- Puzzle Lichess per tema e difficoltà, punteggio del solutore, serie di risolti, suggerimento e soluzione.

## Mancanze

### Revisione e analisi

| # | Funzione | Priorità | Perché conta per chi gioca alla scacchiera | Stato oggi |
|---|---|---|---|---|
| R1 | **Linee del computer** (MultiPV 1–3) con valutazione e profondità in ogni posizione | indispensabile | È la prima cosa che si guarda dopo una partita ("cosa dovevo giocare? quanto era grave?"). chess.com e lichess mostrano sempre 1–3 linee. L'utente lo ha segnalato esplicitamente. | La revisione v2 mostra solo un numero di valutazione; il vecchio pannello delle linee è sparito. |
| R2 | **Varianti**: provare una mossa propria dalla posizione (sullo schermo o muovendo i pezzi veri), seguirla, tornare alla partita | indispensabile | Analizzare = chiedersi "e se avessi giocato…?". Con una scacchiera fisica è il gesto più naturale: si spostano i pezzi. DGT Centaur e Chessnut hanno una modalità analisi libera. | Assente: la revisione si muove solo lungo la partita. |
| R3 | **Mostra la mossa migliore / la linea migliore** sugli errori, giocabile come variante | importante | Una freccia da sola non spiega il seguito. chess.com: "Mostra" + linea. | Freccia della prima mossa e "migliore era X"; la linea (già calcolata dalla revisione) non è esposta. |
| R4 | **Nome dell'apertura** in ogni posizione e "mossa di teoria", anche **offline** | importante | Il Pi spesso non ha rete; il nome dell'apertura è il primo appiglio per studiare. | Solo via Internet (explorer Lichess); il libro offline esiste ma serve solo alla classificazione. |
| R5 | **Momenti chiave** e **"Rigioca i tuoi errori"** (allenamento sulle proprie imprecisioni: si ripropone la posizione, il giocatore cerca la mossa giusta, anche sulla scacchiera) | importante | È ciò che trasforma la revisione in allenamento (lichess "Learn from your mistakes", chess.com "Retry"). Sulla scacchiera fisica è perfetto: posizione guidata dai LED e si muove il pezzo. | Assente. |
| R6 | **Riepilogo per fase** (apertura / mediogioco / finale) della precisione | importante | Dice *dove* si perde (chess.com Game Review). | Assente. |
| R7 | **Analisi libera** da una posizione qualunque (disposta sulla scacchiera o da FEN) | importante | "Ho questa posizione sulla scacchiera: cosa dice il motore?" — tipico delle scacchiere elettroniche. | Assente. Stesso motore di R1+R2. |
| R8 | Revisione **salvata** (precisione ed etichette ricordate: si riapre senza rianalizzare) | importante | Rifare un minuto di analisi ogni volta è frustrante su un Pi; serve anche alle statistiche (A2). | Non salvata (la cache del motore accelera, ma il riepilogo non è conservato). |
| R9 | Codice QR della partita (aprirla su lichess dal telefono) | bello | Comodo per continuare l'analisi altrove. | Assente. |

### Partita

| # | Funzione | Priorità | Perché | Stato oggi |
|---|---|---|---|---|
| P1 | **Ripresa della partita interrotta** dopo riavvio, crash o mancanza di corrente | indispensabile | Il Pi si spegne staccando la spina; una partita lunga persa per un blackout è inaccettabile. DGT Centaur riprende l'ultima partita all'accensione. | La partita vive solo in memoria; al riavvio è persa (al massimo archiviata come "interrotta"). |
| P2 | **Annulla mossa** contro il computer, con i LED che guidano a rimettere i pezzi | indispensabile | Su una scacchiera fisica si sbaglia anche a toccare un pezzo; ogni scacchiera elettronica e ogni app permettono il "ritiro" contro il computer. | Assente. |
| P3 | **Orologio contro il computer** con cadenze standard (bullet/blitz/rapid/classica) e incremento, o personalizzate, o senza tempo | indispensabile | Giocare a cadenza è l'allenamento normale; oggi contro il computer non c'è tempo. Nota: il tempo dell'umano deve partire **dopo** che ha replicato sulla scacchiera la mossa del computer. | PvC senza orologio. |
| P4 | **Mossa dallo schermo** (senza scacchiera o se si scollega) | indispensabile | Senza scacchiera collegata la partita è un vicolo cieco. | **Assegnata a QA** (logica) e design (UI). |
| P5 | **Suggerimento a richiesta** a due livelli: prima il pezzo da muovere (LED sulla casa), poi la mossa | importante | Chi impara vuole un aiuto puntuale, non le frecce sempre accese. ChessUp/Chessnut/chess.com lo hanno. | Solo suggerimenti continui on/off. |
| P6 | **Livelli del computer in Elo comprensibili** ("principiante ~800", "club ~1600", "esperto ~2000", "massimo") | importante | "Livello 7" non dice niente; chess.com e le scacchiere mostrano un Elo. | Stockfish 1–20 senza indicazione; Maia ha l'Elo nel nome. |
| P7 | **Offerta di patta al computer** (accettata in base alla valutazione) e **abbandono** distinto da "esci" | importante | Regole normali di una partita; la partita archiviata deve dire "patta d'accordo" o "abbandono". | Abbandono sì (v2); patta al computer no. |
| P8 | **Partita da posizione** (FEN o editor; posizione disposta sulla scacchiera e confermata) con validazione | importante | Allenare un finale o riprendere una posizione da un libro. | Le partite partono sempre dalla posizione iniziale; nessuna validazione FEN. |
| P9 | Rivincita a colori invertiti, cadenza ricordata | bello | Flusso naturale tra due partite. | Rivincita c'è (v2), colori invertiti da verificare con design. |
| P10 | Riconoscere il "ritiro" fatto muovendo indietro i pezzi veri (stile DGT) | bello | Ancora più naturale di un pulsante. | Richiede modifiche al riconoscitore di mosse (area QA/hardware). |

### Archivio e statistiche

| # | Funzione | Priorità | Perché | Stato oggi |
|---|---|---|---|---|
| A1 | **Importa / esporta PGN** dall'interfaccia (file o chiavetta USB) | importante | Portare partite dentro e fuori dal dispositivo. | Il servizio lo sa fare; nell'interfaccia v2 c'è solo l'esportazione di una partita. |
| A2 | **Statistiche personali**: risultati (vinte/patte/perse per colore e avversario), precisione media e andamento, aperture più giocate con i risultati | importante | "Sto migliorando?" — chess.com Insights, lichess Insights. | Assenti (solo il numero di partite). |
| A3 | **Importa le mie partite online** (Lichess e Chess.com per nome utente, API pubbliche) per rivederle sulla scacchiera | importante | Chi ha una scacchiera smart gioca anche online e vuole rivedere quelle partite col motore locale. | Assente. |
| A4 | Apri in analisi da posizione dell'archivio (miniatura → analisi libera) | bello | Scorciatoia. | Si apre la revisione dall'inizio. |

### Puzzle

| # | Funzione | Priorità | Perché | Stato oggi |
|---|---|---|---|---|
| Z1 | **Riprova i puzzle sbagliati** (ripasso) | importante | Si impara dagli errori; lichess/chess.com propongono di ripetere i falliti. | Assente (i tentativi sono registrati ma non riproposti). |
| Z2 | **Serie a tempo** (stile Puzzle Rush/Storm: difficoltà crescente, 3 errori o 3/5 minuti) | bello | Molto popolare e divertente alla scacchiera. | Assente. |
| Z3 | Statistiche per tema (punti deboli) | bello | Indica cosa allenare. | Assente (i temi dei tentativi sono salvati). |

### Scacchiera fisica (trasversale)

| # | Funzione | Priorità | Perché |
|---|---|---|---|
| H1 | Ogni nuova funzione deve funzionare **muovendo i pezzi veri**: varianti e analisi libera con la scacchiera, annulla con guida LED, ripresa partita con guida LED alla disposizione, "rigioca gli errori" sulla scacchiera. | indispensabile | È il senso del prodotto. |
| H2 | Guida LED per riportare i pezzi a una posizione qualunque (non solo quella iniziale). | indispensabile | Serve a P1, P2, P8, R2, R5. La modalità "disposizione" del gestore della scacchiera lo fa già per occupazione (case mancanti / da liberare): la riuso. |

## Ordine di lavoro

1. R1 linee del motore → R2/R7 varianti e analisi libera (con la scacchiera) → R3, R4, R6 (approfondimenti della
   revisione) → R5 rigioca gli errori.
2. P1 ripresa → P2 annulla → P3 orologio PvC → P5 suggerimento → P6 livelli Elo → P7 patta → P8 da posizione.
3. R8 + A2 revisione salvata e statistiche → A3 importazione online → Z1/Z2 puzzle.

Per ogni funzione: logica e view-model osservabili con test (package `Analysis`, `Play`, `Stats`), specifica UI
in `docs/features/specs/` mandata alla sessione design. La classificazione della revisione non si tocca.

## Prove sull'app vera (schermo 720×1920, scacchiera simulata)

- **Contro il computer** (`-Djavachess.board=sim -Djavachess.dev.pvc=e2e4,g1f3,f1c4,d2d3`, dati in una cartella
  temporanea): la partita procede, il nome dell'apertura compare ("B00 Nimzowitsch Defense: Declined Variation"),
  e dopo ogni mossa viene scritto `current-game.json` con le 8 mosse giocate (ripresa dopo un riavvio). Nessun
  orologio, nessun annulla né suggerimento a richiesta nella schermata di oggi: confermato P2, P3, P5.
- **Revisione** (`-Djavachess.demo=review`): con il nuovo view-model la scheda mostra 2 linee del computer con
  valutazione, mosse in notazione italiana e profondità ("+2.22 13. h5 Dg5 14. Df3 … prof. 22"); il segno della
  seconda linea è stato verificato con Stockfish da riga di comando (stessa posizione, profondità 20).

## Seconda ondata (8 ottobre): mancanze trovate usando l'app sulla scacchiera

| # | Funzione | Priorità | Perché | Stato |
|---|---|---|---|---|
| H3 | Disposizione guidata per tipo di pezzo | indispensabile | I sensori vedono solo dove ci sono pezzi: per un puzzle o una posizione d'analisi lo schermo era l'unica guida su *quale* pezzo mettere dove. | fatto |
| H4 | Pezzi giusti per occupazione ma sbagliati per tipo | indispensabile | Dalla posizione iniziale a un puzzle il gestore diceva "mancano 1" con due Cavalli al posto dei Re. | fatto |
| H5 | Passo indietro in analisi come mossa al contrario | importante | Tornare indietro di una mossa era una disposizione generica. | fatto |
| T1 | Allenamento sulle aperture offline | importante | Chessnut/Square Off/chess.com hanno corsi di aperture; qui c'erano già i dati di 3 milioni di partite. | fatto |
| T3 | Finali contro il motore | importante | Lucena, Philidor, matti di base: allenamento classico, perfetto sulla scacchiera vera. | fatto |
| T4 | Coordinate con i sensori | bello | Chi gioca su una scacchiera senza coordinate stampate le impara più lentamente. | fatto |
| H6 | Prova della scacchiera (LED e sensori) | importante | Progetto open source da costruire: serve un collaudo. | fatto |
| P11 | Partite con vantaggio | bello | Genitore e figlio alla stessa scacchiera. | fatto |

