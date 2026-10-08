# Spec UI 04 — Guida LED alla disposizione, ritiro in analisi, allenamento sulle aperture

Per: sessione **design**. Logica: branch `features/v1` (commit `f6780e5`, `cdf9427`), package `Hardware`,
`Analysis`, `Training`. Thread: come le spec 01–03 (view-model sul thread JavaFX; le callback della scacchiera
arrivano già sul thread FX).

## 1. Disposizione guidata pezzo per pezzo (nessun lavoro obbligatorio)

Già attiva ovunque si usa la disposizione (puzzle, ripresa partita, partita da posizione, analisi con la
scacchiera, rigioca gli errori, browser). Per le posizioni diverse da quella iniziale con più di 3 case da
riempire, i LED accendono **un tipo di pezzo alla volta** (Re bianco, Re nero, Donne, Torri, Alfieri, Cavalli,
Pedoni) e il messaggio di disposizione, quello che già mostrate, diventa ad esempio:

- "Posiziona il Re bianco in g1 · passo 1 di 9"
- "Posiziona le Torri nere: a8, f8 · passo 4 di 9"
- "Posiziona i Pedoni bianchi: a2, b2, f2 · passo 9 di 9, togli i pezzi sulle case rosse (1)"

Il testo inizia sempre con "Posiziona", quindi `GameStatus` lo classifica già come `SETUP`.

**Facoltativo**: per una presentazione più ricca, `Hardware.boardState().setupStep()` (può essere `null`)
restituisce `SetupGuide.Step`: `piece()` (pezzo chesslib, per disegnarne l'icona), `missing()` (case da riempire,
bit i = casa i, a1 = 0), `index()` / `total()` ("passo 2 di 9"). Ad esempio: icona grande del pezzo + case
evidenziate sulla scacchiera dello schermo + barra dei passi.

**Impostazione** (Impostazioni → Scacchiera): interruttore **"Disposizione guidata pezzo per pezzo"**, chiave
`board.setup.guided` (default `true`); al cambio: `Hardware.boardState().setGuidedSetup(valore)`.
Sottotitolo: "Per le posizioni diverse da quella iniziale i LED indicano un tipo di pezzo alla volta".

## 2. Analisi con la scacchiera: un passo indietro = la mossa al contrario (nessun lavoro)

In revisione/analisi con "Usa la scacchiera", tornare indietro di una mossa ora mostra sui LED la mossa da
disfare (da → a, come le mosse del computer) e il messaggio di `BoardFollower.messageProperty()` è, ad esempio,
"Riporta indietro sulla scacchiera: 12. Cxe5, da e5 a f3, poi rimetti il Pedone nero in e5". Salti di più mosse
restano una disposizione (guidata, vedi §1). Lo stesso vale per un tentativo sbagliato in "Rigioca gli errori".
Il messaggio può essere lungo: lasciate due righe.

## 3. Allenamento sulle aperture — `Training.OpeningTrainer` (schermata nuova)

Funziona **senza rete** (dati inclusi). Ingresso proposto: nuova tessera **Allenamento** nella Home (o una sezione
nella dashboard dei puzzle) che apre un piccolo hub: **Aperture** (questa spec), poi **Finali** e **Coordinate**
(spec 05, in arrivo).

### 3a. Scelta dell'apertura

```java
OpeningProgress progress = OpeningProgress.get();                 // legge un piccolo file: va bene anche sul thread FX
List<OpeningCatalog.Opening> list = progress.toReview(OpeningCatalog.forSide(white));  // "da ripassare" in alto
```

Selettore **Con il Bianco / Con il Nero** (9 + 11 aperture). Ogni riga:

| Dato | Esempio |
|---|---|
| `title()` | "Partita Italiana" |
| `san()` | "1. e4 e5 2. Cf3 Cc6 3. Ac4" (piccolo, monospazio) |
| `idea()` | "Sviluppo rapido e Alfiere puntato su f7: la prima apertura da imparare." |
| `progress.entry(id).label()` | etichetta: "Mai provata" / "Da ripassare" / "Quasi" / "Sicura" (`level()` 0–3 per il colore: grigio, arancio, giallo, verde) |
| `entry.runs()` | "4 volte" (nascosto se 0) |

Tocco su una riga → schermata di allenamento.

### 3b. Allenamento

```java
OpeningTrainer t = new OpeningTrainer(opening, boardFollower /* o null */, OpeningProgress.get());
// boardFollower: new BoardFollower(Hardware.boardState()) se c'è la scacchiera, come in revisione
```

Scacchiera dal lato di chi si allena (`opening.white()`); con il Nero il computer ha già giocato la prima mossa
all'apertura della schermata.

| Proprietà | Uso |
|---|---|
| `fenProperty()` | posizione |
| `lastMoveProperty()` | evidenzia l'ultima mossa (UCI) |
| `shownMoveProperty()` | freccia del suggerimento / della soluzione (null = nessuna) |
| `stateProperty()` | `YOUR_MOVE` (tocca a te) · `WRONG` (rosso: si riprova nella stessa posizione) · `DONE` (fine linea) |
| `messageProperty()` | frase pronta: "Gioca la prima mossa della Partita Italiana", "Giusto: 2. Cf3 · ora tocca a te", "Bene: 4. c3 (38% delle partite) · ora tocca a te", "Si gioca, ma meno spesso (4%): la più comune è 4. c3", "1. d4 non è la mossa della Partita Italiana: riprova", "5. h4 è fuori teoria. Si gioca 5. d3", "Linea completata: 8 mosse, 1 errore", "Fine della teoria: 6 mosse, nessun errore" |
| `movesTextProperty()` | mosse fin qui "1. e4 e5 2. Cf3 Cc6 3. Ac4 Ac5 4. c3" (riga scorrevole) |
| `openingNameProperty()` | "C53 Partita Italiana: Giuoco Pianissimo" (sotto il titolo; si aggiorna mentre si va avanti) |
| `pliesProperty()` / `maxPlies()` | barra di avanzamento della linea (16 semimosse di default) |
| `mistakesProperty()` | "Errori: 1" |
| `theoryProperty()` | lista delle mosse di teoria della posizione (`Candidate`: `san()` "Cf3", `percent()` "45%", `games()`), **vuota mentre il giocatore pensa**; si riempie dopo il secondo errore, dopo un suggerimento e a fine linea. Mostrala come piccole barre orizzontali (mossa, barra proporzionale a `share()`, percentuale). `OpeningTrainer.describe(list, 3)` dà "Cf3 (45%), Cc3 (30%), d4 (12%)" se basta il testo |

Azioni:

- mossa a tocco sulla scacchiera dello schermo → `t.play(uci)` (true = accettata; false = rifiutata: la scacchiera
  resta com'era, mostra il messaggio);
- **Suggerimento** → `t.hint()` (freccia + messaggio "Si gioca 3. Ac4"; conta come errore);
- **Ricomincia** → `t.restart()` (nuova linea, le risposte del computer cambiano);
- interruttore **Usa la scacchiera** → `t.useBoard(on)`: i LED dispongono la posizione, le risposte del computer
  si eseguono sulla scacchiera (LED da → a, messaggio di `boardFollower.messageProperty()`), una mossa sbagliata
  viene fatta riportare indietro dai LED;
- uscita → `t.close()`.

Stato `DONE`: carta di fine con il messaggio, le mosse della linea e i due pulsanti **Ricomincia** / **Altra
apertura**. Il risultato è già salvato (`OpeningProgress`), l'etichetta della lista si aggiorna al ritorno.

## 4. "Mosse più giocate" in revisione e analisi (facoltativo, piccolo)

Nella scheda dell'analisi, sotto le linee del motore, un riquadro **Mosse più giocate** (solo nelle prime 10
mosse, nascosto quando è vuoto):

```java
List<OpeningExplorer.Candidate> m = OpeningExplorer.standard().moves(fen, OpeningExplorer.Rating.CLUB);
```

Al massimo 4 righe "Cf3 · 45%" con barra; tocco su una riga = gioca la mossa come variante
(`session.play(uci)` come per le linee del motore). Dati offline: 3 milioni di partite lichess, giocatori 1600+.

## Testi

Tutti i testi dinamici arrivano già in italiano dai view-model. Testi fissi proposti: "Allenamento",
"Aperture", "Con il Bianco", "Con il Nero", "Suggerimento", "Ricomincia", "Altra apertura", "Usa la scacchiera",
"Mosse di teoria", "Mosse più giocate", "Errori".
