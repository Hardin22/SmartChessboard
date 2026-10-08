# Spec UI 02 — Partita: livelli in Elo, orologio, annulla, suggerimento, patta, ripresa, partita da posizione

Per: sessione **design**. Logica: branch `features/v1` (package `Play` + metodi nuovi di `PvcGame`/`PvpGame`/
`AbstractGame`). Tutti i metodi dei giochi si chiamano sul thread JavaFX; le proprietà cambiano sul thread JavaFX.

## 1. Preparazione "Contro il computer": livelli in Elo (al posto di "livello 1–20")

`Play.BotLevels`:

- `BotLevels.available()` → `List<Level>` giocabili su questa macchina (i Maia spariscono se manca lc0), in
  ordine di forza. `BotLevels.ALL` è la lista completa (12 gradini).
- `Level`: `name()` ("Principiante", "Esordiente", "Maia 1100", "Circolo", "Maia 1500", "Intermedio", "Maia 1900",
  "Esperto", "Candidato maestro", "Maestro", "Campione", "Stockfish al massimo"), `eloText()` ("circa 1350",
  "oltre 3000"), `description()` (una riga: "Gioca in modo solido ma commette errori"), `isMaia()` (icona "umano"),
  `id()` da salvare nelle preferenze (`game.pvc.level`).
- Default per un nuovo giocatore: `BotLevels.byId(BotLevels.DEFAULT_ID)` ("Circolo").
- Selettore suggerito: una carta grande con nome + "circa 1350" + descrizione, e − / + ai lati
  (`BotLevels.step(level, ±1, available)`); sotto, chip rapidi (Principiante · Circolo · Esperto · Massimo).
  Conversione delle vecchie preferenze: `BotLevels.fromLegacy(engineType, skill)`.
- Testo d'aiuto: "Gli Elo sono indicativi: Maia gioca come un umano di quel livello, Stockfish è indebolito."

**Cadenza contro il computer** (nuova): stessi tasselli del PvP (`TimeControl.PRESETS`: 1+0 … 90+30, con
`category().italian()` Bullet/Blitz/Rapid/Classica e `label()` "10 + 5") **più** un tassello "Senza tempo"
(`TimeControl.UNLIMITED`, default). Personalizzata: `TimeControl.minutes(min, inc)`.

Avvio:

```java
PvcGame game = new PvcGame(chessBoardUI, evalBar, openingLabel, humanWhite, level, timeControl);
// facoltativo: partita da posizione (vedi §7)
game.setStartPosition(fen);
game.startGame();
```

Nome dell'avversario da mostrare: `level.name()` (sottotitolo "circa 1350"); in archivio viene salvato
`Stockfish (1350)` / `Maia 1500`.

## 2. Orologio nella partita contro il computer — `game.getClock()` (null = senza tempo)

`Play.GameClock`: `whiteTextProperty()` / `blackTextProperty()` ("05:00", "00:09.4"), `runningProperty()` (Side
che sta consumando, null = fermo), `whiteLowProperty()` / `blackLowProperty()` (sotto 20 s → rosso),
`flaggedProperty()`.

- Mettilo nelle righe giocatore (riga del computer in alto, la tua in basso), cifre Geist Mono grandi; l'orologio
  che corre è "acceso".
- Regola sulla scacchiera fisica: dopo la mossa del computer il **tuo** tempo parte solo quando l'hai riprodotta
  sulla scacchiera (prima corre il tempo del computer, che pensa entro ~1/30 del suo tempo).
- Tempo scaduto: la partita finisce da sola ("Il Nero vince per tempo" / "Patta: tempo scaduto e materiale
  insufficiente"), arriva come le altre fini partita (status callback + `isRunning()` false).

## 3. Annulla mossa — `game.canTakeBack()` / `game.takeBack()`

- Pulsante **Annulla** nella barra strumenti (icona ↶), abilitato se `canTakeBack()` (rileggilo dopo ogni mossa e
  dopo ogni status; è falso finché i pezzi non sono disposti all'inizio). Nessuna conferma: è reversibile rigiocando.
- Toglie la tua ultima mossa e la risposta del computer (o solo la tua se il computer sta ancora pensando). La
  scacchiera a schermo torna indietro, i **LED** mostrano quali pezzi rimettere (case da riempire / da liberare),
  lo status dice "Mossa annullata: rimetti i pezzi come sullo schermo", poi (scacchiera collegata) "Rimetti i pezzi
  come sullo schermo: mancano N, da togliere M (in rosso)" e infine "Scacchiera allineata"
  (`BoardStateManager.resyncToLogical` di QA). Gli orologi non vengono rimborsati.
- `game.getTakebacks()` → contatore (facoltativo: "Annullate: 2" nel foglio ⋯ o a fine partita).

## 4. Suggerimento a richiesta — `game.requestHint()` / `game.hints()`

`Play.HintAdvisor`: `levelProperty()` (NONE, THINKING, PIECE, MOVE, FAILED), `textProperty()` (frase pronta),
`fromSquareProperty()` ("g1"), `moveProperty()` (UCI, per la freccia), `canAskMoreProperty()`, `usedProperty()`.

- Pulsante **Suggerimento** (lampadina) nella barra, solo nel tuo turno (`game.isAwaitingHumanMove()` di QA o il
  tuo stato "tocca a te").
- 1° tocco → `THINKING` ("Sto cercando la mossa…") → `PIECE`: "Muovi il Cavallo in g1", la casa si accende sulla
  scacchiera (LED) → evidenzia la casa anche sullo schermo. Il pulsante diventa **Mostra la mossa**.
- 2° tocco → `MOVE`: "Il suggerimento: 12. Cf3", freccia sullo schermo, LED da→a. Pulsante disabilitato
  (`canAskMore` false).
- Sparisce da solo dopo la tua mossa (`NONE`). `FAILED`: "Il motore non ha risposto: riprova".
- Diverso dall'interruttore "Suggerimenti" (frecce sempre accese) che resta com'è.

## 5. Proporre patta al computer — `game.canOfferDraw()` / `game.offerDraw()`

- Voce **Proponi patta** (½) nel foglio ⋯ o nella barra; disabilitata se `!canOfferDraw()` (dopo un'offerta bisogna
  fare 3 mosse).
- `offerDraw()` → `CompletableFuture<Decision>` completato sul thread FX: `accepted()` + `message()` pronto:
  "Circolo accetta la patta" (nome del livello; la partita finisce: risultato ½-½, "Patta d'accordo" in archivio) /
  "… rifiuta: è presto per una patta" / "… rifiuta: pensa di stare meglio" / "Hai appena proposto la patta:
  riprova tra qualche mossa" / "La posizione è cambiata: riproponi la patta" (una mossa è arrivata mentre il
  computer valutava). Mostra il messaggio in un toast/carta di stato per 3 s.
- Abbandono: resta quello che hai già (messaggio "Il Bianco abbandona: vince il Nero").

## 6. Ripresa della partita interrotta (riavvio, crash, mancanza di corrente)

La partita in corso (contro il computer o a due) viene salvata su disco dopo ogni mossa. Alla Home:

```java
Optional<GameSnapshot> s = GameResume.available();   // leggere fuori dal thread FX (file piccolo)
```

- Se presente (e non c'è una partita in memoria), carta **"Partita interrotta"**: miniatura di `s.currentFen()`,
  titolo (contro il computer: nome del livello da `BotLevels.byId(s.botLevelId())`; a due: "Due giocatori ·
  " + `s.timeControl().label()`), dettaglio "Mossa " + `s.moveNumber()` + data `s.savedAt()`; azioni
  **Riprendi** (primaria) e **Ignora** (`GameResume.discard()`: la partita finisce in archivio come interrotta se
  non c'è già, quindi niente conferma).
- Riprendi:
  - `s.mode() == PVC`: `PvcGame game = PvcGame.fromSnapshot(s, chessBoardUI, evalBar, openingLabel);`
    lato umano `s.humanWhite()`, poi come un avvio normale (`game.startGame()`): i LED guidano a disporre i pezzi
    nella posizione raggiunta, poi tocca a chi deve muovere (se tocca al computer, muove lui).
  - `s.mode() == PVP`: `PvpGame.fromSnapshot(s, board, evalBar, openingLabel, whiteClockLabel, blackClockLabel)`.
  - Orologi, mosse annullate e suggerimenti usati sono ripristinati. Se la partita era già in archivio come
    "interrotta", quella copia viene sostituita quando la partita ripresa viene archiviata di nuovo (finita o di
    nuovo interrotta): una sola copia, e mai nessuna partita persa (anche iniziando una partita nuova al posto di
    riprenderla, quella salvata va in archivio come interrotta).
- Una partita finita con un risultato non viene mai proposta. Uscire con "Esci" la lascia riprendibile (la carta
  la propone finché non si gioca un'altra partita o si tocca Ignora).

## 7. Partita da posizione (FEN / editor)

`Play.PositionSetup`:

- `PositionSetup.check(fenText)` → `Result`: `ok()`, `fen()` normalizzato, `errors()` (frasi italiane: "Manca il re
  per il Nero", "Pedone su a8: …", "Il re nero è sotto scacco ma non tocca a lui muovere", "I due re non possono
  stare su case vicine", "La traversa 3 ha 9 case invece di 8", "La partita è già finita: scacco matto"…), `notes()` (correzioni innocue: "Arrocco tolto
  (Kq): re o torre non sono sulla casa iniziale").
- Editor a schermo: `PositionSetup.fromEditor(Piece[64], Side toMove)` (A1 = 0 … H8 = 63); diritti d'arrocco
  dedotti da re e torri sulle case iniziali.
- Dove: nella preparazione PvC/PvP una riga "Posizione iniziale: Standard ▸" che apre un foglio con: campo FEN
  (tastiera a schermo; incolla), anteprima della scacchiera, errori in rosso sotto, "Tocca al Bianco / al Nero",
  editor (tavolozza dei pezzi + tocco sulla casa, "Svuota", "Posizione iniziale"). Pulsante **Usa questa
  posizione** abilitato se `ok()`.
- Avvio: `game.setStartPosition(result.fen())` **prima** di `startGame()`. La scacchiera fisica viene guidata
  dai LED a disporre la posizione (i sensori vedono solo dove ci sono pezzi: l'utente mette i pezzi giusti
  guardando lo schermo).

## Testi (chiavi i18n proposte)

```
pvc.level=Avversario
pvc.level.elo={0}
pvc.level.help=Gli Elo sono indicativi: Maia gioca come un umano di quel livello, Stockfish è indebolito.
pvc.timecontrol=Cadenza
pvc.timecontrol.none=Senza tempo
game.takeback=Annulla
game.takeback.count=Mosse annullate: {0}
game.hint=Suggerimento
game.hint.more=Mostra la mossa
game.draw.offer=Proponi patta
home.resume.interrupted=Partita interrotta
home.resume.move=Mossa {0}
home.resume.discard=Ignora
setup.position=Posizione iniziale
setup.position.standard=Standard
setup.position.custom=Da posizione
setup.position.fen=FEN
setup.position.use=Usa questa posizione
setup.position.clear=Svuota
setup.position.tomove.white=Tocca al Bianco
setup.position.tomove.black=Tocca al Nero
```
