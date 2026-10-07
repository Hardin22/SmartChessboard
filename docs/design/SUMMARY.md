# Ridisegno v2 — riepilogo delle scelte

Il ridisegno parte dal dispositivo vero (schermo touch 720×1920 di fianco alla scacchiera, letto a un braccio di
distanza, usato con le dita da una o due persone sedute ai lati opposti, Raspberry Pi con rendering spesso
software). I principi e la struttura di ogni schermata sono in [UX.md](UX.md); qui ci sono le decisioni, il perché,
e cosa è cambiato nel codice.

## Le scelte principali

| Scelta | Perché |
|---|---|
| **Una colonna, il basso a chi guarda.** Azioni principali in fondo alla schermata (o alla propria metà), informazioni sopra. | Lo schermo è lungo e stretto e la mano arriva dal basso. Su 1920 px di altezza c'è spazio per non affollare. |
| **Ruota sempre a portata**: pulsante ↻ nello stesso angolo di ogni intestazione e di ogni foglio, rotazione a due dita, `Ctrl+R` sul desktop. | Il brief chiede la rotazione "in ogni schermata e in ogni situazione". Un posto fisso si trova senza guardare, anche dopo aver girato lo schermo. |
| **Orientamento automatico**: contro il computer e nei puzzle lo schermo si gira verso chi gioca; impostazione «Monitor capovolto» per il montaggio. | Chi gioca col Nero siede dall'altra parte: non deve chiedere niente per leggere. Disattivabile («Orientamento automatico»). |
| **La scacchiera sullo schermo è quella vera vista da chi guarda**: quando l'interfaccia è girata verso il Nero, la scacchiera è disegnata dal lato del Nero (`ChessBoardUI.setFlipped`). | Girare l'interfaccia senza girare la scacchiera metterebbe i pezzi bianchi dal lato del Nero, al contrario di quella fisica. |
| **Scacchiera a tutta larghezza** (720 px, nessun margine) in partita, revisione, analisi e puzzle, con la barra di valutazione come striscia sotto. | Richiesta dell'utente e scelta giusta per la lettura a distanza: è l'elemento più guardato. In orizzontale la barra torna verticale. |
| **Due giocatori: una metà a testa**, quella in alto girata di 180°; orologio enorme (Geist Light, cifre in celle fisse), nome, stato, Pausa / Patta / Abbandona / Altro nella propria metà; conferme e proposta di patta nella metà di chi deve rispondere; al centro la posizione letta dai sensori, nascondibile. | Nessuno deve leggere al contrario. La scacchiera disegnata col Bianco verso la metà del Bianco si legge giusta da entrambi i lati, come quella vera: è l'unica cosa condivisa e sta a uguale distanza. |
| **Contro il computer: la carta di stato** dice la cosa da fare adesso — «Tocca a te», «Muovi per Stockfish: f8 → c5» (con il nome del pezzo), «Prepara la scacchiera», «Controlla la casa E4», «Hai vinto / Hai perso». | Sulla scacchiera fisica la mossa del computer la esegue l'umano: è l'informazione più importante e deve leggersi da lontano. |
| **Etichette della revisione con identità propria**: tessere quadrate arrotondate disegnate in vettoriale (come il logo e le case), colori del linguaggio chess.com; rimosse le PNG simili alle icone di chess.com. | Il brief chiede "stile chess.com ma con identità propria"; le icone copiate non sono adatte a un progetto open source. |
| **Archivio**: righe da 140 px raggruppate per giorno, ricerca con tastiera a schermo, tre filtri (modalità, risultato, periodo), anteprima in un foglio con Rivedi / Esporta / Elimina (con conferma). | 200+ partite: serve trovare, non scorrere. I filtri sono tre pulsanti grandi che aprono scelte grandi, invece di righe di chip che rubano spazio alla lista. |
| **Niente slider**: selettori − / + e chip rapidi (livello, cadenza, difficoltà). | Gli slider sono imprecisi col dito, soprattutto su schermi grandi letti di lato. |
| **Impostazioni che si salvano da sole**. | Un "Salva" in fondo a una pagina lunga si dimentica. |
| **Tastiera a schermo propria** (`TouchKeyboard`). | Sulla scacchiera non c'è tastiera: serve per la ricerca nell'archivio e per gli account. |
| **Tipografia**: Geist e Geist Light per i numeri grandi; niente cifre monospaziate visibili (lo zero barrato di Geist Mono stona negli orologi e nei punteggi). Testo corrente 22–24 px, titoli 38–44, numeri 52–76, orologi fino a 230. | Leggibilità a distanza e un'aria elegante senza effetti costosi. |
| **Prestazioni**: nessuna ombra né sfocatura, nessuna animazione continua; transizioni da 160–240 ms solo dopo un tocco (`-Djavachess.animations=off` le toglie); l'orologio aggiorna un'etichetta per cella al secondo; liste virtualizzate. | Il Pi usa spesso il rendering software, dove ogni effetto si paga a ogni ridisegno. |
| **Lichess nel browser integrato** come Chess.com (tile «Online» → foglio con le due voci). | Richiesta dell'utente; il flusso via Board API resta in Impostazioni → Avanzate. |

## Architettura UI

- Le schermate sono costruite in codice (`Controllers/*` implementano `Screen`); restano FXML solo
  `MainLayout.fxml` (radice) e `BrowserView.fxml` (contratto con la logica del browser).
- `MainController`: registro delle viste, precaricamento in background, transizioni, orientamento
  (`face`, `rotateScreen`, `setMountedFlipped`, `facingBlackProperty`), fogli (`showSheet`, `showSheetFor` per la
  metà lontana, `showModalSheet`), toast, errori dentro l'app, notifica larghezza/altezza (`Screen.setWide`).
- Componenti in `Components/`: `Ui` (mattoni e misure), `ScreenHeader`, `RotateButton`, `StatusCard`,
  `ClockFace`, `Stepper`, `TouchKeyboard`, `BoardFrame`, `PlayerRow`, `MaterialView`, `ReviewLabels`, `Prefs`.
- Tutti i colori sono token in `Styles/Style.css` (`.theme-dark`, `.theme-light`); i canvas li leggono da
  `ThemeManager.palette()`.

## Aggiunte minime fuori dallo strato UI (documentate)

- `AbstractGame.isRunning()` e `getInitialFen()`: la schermata deve sapere se la partita è finita (abbandono,
  tempo, patta d'accordo non lasciano un matto sulla scacchiera) e da quale posizione rivederla.
- `PvpGame.pauseClock()`, `resumeClock()`, `isClockPaused()`: il pulsante Pausa delle due metà.
- `ErrorReporter.setPresenter(...)`: gli errori compaiono come foglio dentro l'app (che gira con lo schermo) invece
  di una finestra di sistema separata; senza presenter resta il comportamento di prima.
- Abbandono e patta d'accordo usano l'API esistente `endGame(messaggio, salva)` con messaggi che l'archivio già
  interpreta («Il Bianco abbandona: vince il Nero» → 0-1, Abbandono; «Patta d'accordo» → ½-½).

## Verifica

- Screenshot sul monitor reale (schermo 1) per ogni schermata e stato, scuro e chiaro, verticale, ruotato e
  1920×720: `DevOptions`/`DevDemos` (`-Djavachess.demo=pvp|pvp-draw|pvp-pause|pvp-end|pvc|pvc-black|pvc-replicate|
  review|archive-preview|archive-search|online|puzzle|...`). Le finestre di screenshot ignorano tastiera e mouse
  reali (lo schermo può essere in uso).
- Archivio dimostrativo: `-Djavachess.home=<cartella vuota> -Djavachess.demo.seed=240` lo riempie con partite
  storiche di pubblico dominio (`src/main/resources/demo/famous-games.tsv`) registrate come partite locali di ogni
  modalità. Le etichette e la precisione della revisione sono calcolate dal classificatore reale.
- Test: unitari e E2E verdi (vedi il commit finale).
