# Spec UI 03 — Rigioca i tuoi errori, revisione salvata, statistiche, importazione online, puzzle

Per: sessione **design**. Logica: branch `features/v1`, package `Analysis`, `Stats`, `Play`. Thread: come le
spec 01/02 (proprietà aggiornate sul thread JavaFX; le letture da disco indicate "bloccanti" vanno fatte fuori dal
thread FX, ad es. `CompletableFuture.supplyAsync(..., AppExecutors.io())`).

## 1. Revisione salvata (si riapre senza rianalizzare)

`Stats.ReviewStore`:

```java
// aprendo una partita dell'archivio (fuori dal thread FX):
Optional<GameReview> saved = ReviewStore.get().find(archivedGame);
// se c'è: session.attachReview(saved.get()) + accuracy/grafico subito, niente pulsante "Analizza"
//         (GameAnalyzer.toMoveAnalysis(saved.get()) dà le righe per grafico e lista come oggi)
// a fine analisi completa:
ReviewStore.get().save(archivedGame, gameReview);
// per partite non archiviate (fine partita → Rivedi): ReviewStore.key(initialFen, uciMoves, wRating, bRating)
```

- La chiave include mosse e rating: le etichette non vengono mai riusate per un'altra partita.
- Nella lista dell'archivio puoi mostrare la precisione se c'è: `ReviewStore.get().summary(ReviewStore.key(g))`
  → `Summary.accuracy(white)` (es. una piccola etichetta "89%" accanto al risultato) e un'icona "analizzata".

## 2. Rigioca i tuoi errori — `Analysis.MistakeTrainer`

Dalla revisione (dopo l'analisi): pulsante **Rigioca i tuoi errori** (scheda Riepilogo, vicino ai momenti
chiave), visibile se `MistakeTrainer.exercises(review, mioLato, false)` non è vuoto. Il "mio lato":
`PlayerStats.localSide(archivedGame)` (contro il computer il lato del giocatore, online il lato con il proprio nome);
se è vuoto (partita a due) chiedi "Bianco o Nero?".

```java
MistakeTrainer t = new MistakeTrainer(MistakeTrainer.exercises(review, white, false), session.boardFollower());
```

Schermata (può riusare la revisione con la carta della mossa al posto della lista):

| Proprietà | Uso |
|---|---|
| `fenProperty()` | posizione da disegnare (ruota la scacchiera dal lato di chi muove) |
| `currentProperty()` | `Exercise`: `task()` "Trova la mossa migliore per il Bianco", `moveText()` "18. Dxb7" (la mossa sbagliata della partita, da mostrare piccola: "In partita: 18. Dxb7 ??" con la tessera di `label()`) |
| `stateProperty()` | `YOUR_MOVE` (tocca a te) · `CHECKING` (il motore controlla) · `CORRECT` / `ALSO_GOOD` (verde) · `WRONG` (rosso, pulsante **Riprova**) · `SOLUTION` · `FINISHED` |
| `messageProperty()` | frase pronta: "Giusto! È la mossa migliore: 18. Cf5", "Anche questa va bene! La migliore era 18. Cf5", "Non è la mossa giusta: riprova", "È la mossa giocata in partita: cercane una migliore", "La mossa migliore era … · linea: …", "Finito: 3 su 5 risolte" |
| `shownMoveProperty()` | freccia del tentativo o della soluzione |
| `indexProperty()` / `total()` | "2 di 5" |
| `solvedProperty()`, `triesProperty()` | contatori |

Azioni: tocco-tocco sulla scacchiera → `t.attempt(uci)`; **Riprova** → `retry()`; **Soluzione** →
`showSolution()`; **Avanti** → `next()` (dopo CORRECT/ALSO_GOOD/SOLUTION); uscita → `close()`.
Con la scacchiera fisica: interruttore **Usa la scacchiera** → `t.useBoard(true)`: i LED dispongono la posizione,
il tentativo si fa muovendo il pezzo; se è sbagliato i LED mostrano come rimetterlo (messaggio "… rimetti il pezzo
e riprova") e si può subito riprovare.

## 3. Statistiche personali — `Stats.PlayerStats`

Nuova schermata **Statistiche** (dalla Home, accanto ad Archivio; o una scheda dell'Archivio).

```java
PlayerStats.Stats s = PlayerStats.compute();   // bloccante: fuori dal thread FX
// periodo: PlayerStats.compute(PlayerStats.since(archivio.list(), LocalDateTime.now().minusDays(30)), ReviewStore.get().summaries(), PlayerStats.Identity.fromSettings())
```

Contano solo le partite in cui "io" sono riconoscibile: contro il computer, e online con il nome utente Lichess /
Chess.com impostato o usato nell'importazione (le partite a due alla scacchiera non hanno un "io").

Contenuto suggerito (dall'alto, righe grandi):
1. **Risultati**: `s.total()` → vinte/patte/perse (barra a tre colori) e `percentText()` "63%";
   con il Bianco `s.asWhite()`, con il Nero `s.asBlack()`.
2. **Forma**: `s.form()` ultime 10 (pallini V/P/S, il più recente a sinistra), `currentStreak()` "3 vittorie di
   fila", `bestStreak()`.
3. **Precisione**: `s.accuracyText()` "84,2" su `s.reviewed()` partite analizzate; ultime 10
   `recentAccuracy()`; tendenza `trendText()` "+3,1" (verde) / "−2,0" (rosso). Se `reviewed() == 0`:
   "Analizza le tue partite per vedere la precisione".
4. **Avversari**: `s.byOpponent()` → `Row(name, score, accuracyText(), reviewed)`: "Stockfish (1350) · 5 partite ·
   3V 1P 1S · 60%".
5. **Aperture**: `s.openings()` → famiglia ("Italian Game"), partite, V/P/S, %.
6. **Per tipo**: `s.byMode()` ("Contro il computer", "Lichess", "Online (browser)", "Importate").
`s.unfinished()`: "più 2 partite interrotte". Filtri periodo a chip: Sempre · 30 giorni · 7 giorni.

## 4. Importa le partite online — `Stats.OnlineImport`

Nell'Archivio (menu ⋯ o pulsante **Importa**): foglio con due schede **Lichess** / **Chess.com**, campo nome utente
(tastiera a schermo, precompilato con `OnlineImport.lastUsername(source)`), scelta "ultime 20 / 50 / 100",
pulsante **Importa**.

```java
new OnlineImport().importRecent(OnlineImport.Source.LICHESS, user, 50)
        .thenAccept(r -> Platform.runLater(() -> { /* r.message(), r.ok(), r.imported() */ }));
```

- Stato in corso: "Scarico le partite…" con spinner (rete: fino a qualche secondo).
- `r.message()` pronto: "12 partite importate, 3 già presenti", "Utente «x» non trovato su Lichess",
  "Impossibile scaricare le partite da Chess.com: controlla la connessione", "Nome utente non valido".
- Le partite entrano in archivio come Lichess / Online (browser), con il rating nel nome e l'apertura; reimportare
  non crea doppioni. Solo API pubbliche in lettura: nessun accesso all'account.

## 4b. PGN da chiavetta USB — `Stats.PgnTransfer`

Nell'Archivio (menu ⋯): **Importa da chiavetta** / **Esporta su chiavetta**.
- `new PgnTransfer().drives()` → chiavette inserite (`Drive.label()`); nessuna → "Inserisci una chiavetta USB".
- `pgnFiles(drive)` → `PgnFile.description()` "torneo.pgn · 120 KB" (più recenti prima).
- Prima di importare: `PgnTransfer.countGames(file)`; sopra ~500 partite avvisa ("12.000 partite: sul Raspberry
  richiede alcuni minuti") e proponi "Importa le prime 500 / tutte".
- `importFile(file, archive, maxGames, fraction -> ...)` (fuori dal thread FX; l'avanzamento 0..1 arriva sullo
  stesso thread: passalo con `Platform.runLater`) → `PgnTransfer.describe(report)` "1.240 partite importate,
  3 ignorate" + `report.warnings()`.
- Esporta: `exportAll(drive, archive)` → file `javachess-partite-AAAA-MM-GG.pgn` sulla chiavetta.

## 5. Puzzle: ripasso e serie a tempo

**Ripasso dei puzzle sbagliati** — `Play.PuzzleReview`: i puzzle non risolti "puliti" (errore, aiuto, soluzione)
entrano da soli; escono quando li risolvi senza aiuti.
- Pannello puzzle: carta **Ripasso** con `PuzzleReview.get().size()` ("7 puzzle da rifare"), pulsante **Ripassa**
  → `PuzzleReview.get().next()` dà il prossimo `Puzzle` da caricare nella schermata puzzle normale.

**Serie a tempo** — `Play.PuzzleRush`: modalità `THREE_MINUTES` ("3 minuti"), `FIVE_MINUTES`, `SURVIVAL`
("Sopravvivenza": senza tempo, 3 errori).

```java
PuzzleRush rush = new PuzzleRush(PuzzleRush.Mode.THREE_MINUTES, PuzzleProgressService.getInstance().getRating());
puzzleGame.setRated(false);                  // la serie non cambia il punteggio
puzzleGame.setResultListener(new PuzzleGame.ResultListener() {
    public void finished(Puzzle p, boolean solved, boolean clean) { if (solved) rush.solved(); else rush.failed(); }
    public void wrongMove(Puzzle p) { rush.failed(); }   // un errore = puzzle fallito, si passa al prossimo
});
rush.currentProperty().addListener((o, a, p) -> { if (p != null) puzzleGame.startPuzzle(p); });
rush.start();
```

- Testata della serie: `timeTextProperty()` grande ("02:41", rosso se `timeLowProperty()`), `scoreProperty()`
  (punti), tre cuori/croci per `failuresProperty()` (0..3), record `bestProperty()`.
- `stateProperty()`: LOADING (breve) / PLAYING / FINISHED → carta finale con `messageProperty()` ("Tempo scaduto:
  14 puzzle risolti · nuovo record!"), **Ancora** (`start()`), **Esci** (`stop()` se in corso).
- Ricordati di rimettere `setRated(true)` e `setResultListener(null)` per i puzzle normali.

## 6. Nomi delle aperture in italiano

`Analysis.OpeningNames.italian(nome)`: "C50 Italian Game: Giuoco Piano" → "C50 Partita Italiana: Giuoco Piano",
"B90 Sicilian Defense: Najdorf Variation" → "B90 Difesa Siciliana: Variante Najdorf" (nomi sconosciuti invariati).
`AnalysisSession.openingProperty()` e le aperture di `PlayerStats` sono già in italiano; nella partita il
`openingNameLabel` resta in inglese (finisce nell'archivio e nel PGN): mostralo con `OpeningNames.italian(...)`,
e così anche la colonna apertura dell'archivio.

## Testi (chiavi i18n proposte)

```
review.retry.mistakes=Rigioca i tuoi errori
trainer.title=Rigioca i tuoi errori
trainer.progress={0} di {1}
trainer.ingame=In partita: {0}
trainer.retry=Riprova
trainer.solution=Soluzione
trainer.next=Avanti
trainer.board=Usa la scacchiera
trainer.side.question=Di quale giocatore vuoi rigiocare gli errori?
stats.title=Statistiche
stats.results=Risultati
stats.white=Con il Bianco
stats.black=Con il Nero
stats.form=Ultime partite
stats.streak={0} vittorie di fila
stats.accuracy=Precisione media
stats.accuracy.none=Analizza le tue partite per vedere la precisione
stats.accuracy.recent=Ultime 10: {0}
stats.opponents=Avversari
stats.openings=Aperture
stats.modes=Per tipo di partita
stats.unfinished=più {0} partite interrotte
import.title=Importa partite
import.username=Nome utente
import.count=Ultime {0}
import.start=Importa
import.running=Scarico le partite…
puzzle.review=Ripasso
puzzle.review.count={0} puzzle da rifare
puzzle.review.start=Ripassa
puzzle.rush=Serie a tempo
puzzle.rush.three=3 minuti
puzzle.rush.five=5 minuti
puzzle.rush.survival=Sopravvivenza
puzzle.rush.best=Record: {0}
puzzle.rush.again=Ancora
```
