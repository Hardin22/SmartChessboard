# Spec UI 01 — Revisione e analisi: linee del motore, varianti, scacchiera fisica, approfondimenti

Per: sessione **design** (proprietaria della UI). Logica e view-model: branch `features/v1`, package
`io.github.hardin22.javachess.Analysis`. Tutto vive sul thread JavaFX; le proprietà sono read-only; nessun nodo.
Le coordinate sono sempre case/UCI (mai pixel): la scacchiera a schermo può essere girata (`setFlipped`).

## 1. Il view-model: `AnalysisSession`

Uno per partita aperta in revisione (o per un'analisi libera da posizione).

```java
AnalysisSession s = new AnalysisSession(initialFen /* null = iniziale */, uciMoves /* List<String> */);
// alla fine dell'analisi completa (GameAnalyzer.review(...) restituisce GameReview, o analyzer.lastReview()):
s.attachReview(gameReview);          // anche più volte (parziali), null = nessuna
// uscendo dalla schermata:
s.close();                            // ferma il motore, libera la scacchiera fisica
```

Sostituisce nel ReviewController: `reviewChessBoard.loadPgn/nextMove/previousMove/goToMove`, il debounce e la chiamata
`PositionAnalyzer.analyze(..., 1, ...)` (le linee lo fanno da sole), il lookup online dell'apertura.

| Proprietà | Tipo | Uso |
|---|---|---|
| `fenProperty()` | String | posizione da disegnare: `board.setPosition(fen, lastMove)` |
| `lastMoveProperty()` | String UCI / null | evidenziazione dell'ultima mossa |
| `plyProperty()` | int | semimosse dall'inizio (varianti comprese) |
| `mainPlyProperty()` | int | semimossa **della partita** a cui appartiene la posizione: cursore del grafico ed evidenziazione nella lista mosse (in variante resta sulla mossa da cui parte) |
| `inVariationProperty()` | boolean | mostra "Torna alla partita" e il nastro della variante |
| `variationTextProperty()` | String | mosse della variante: `12… Ad6 13. Cf3` |
| `titleProperty()` | String | `12. Cf3`, `12… Cc6`, `Posizione iniziale` (titolo della carta della mossa) |
| `canGoBackProperty()` / `canGoForwardProperty()` | boolean | abilitazione delle frecce |
| `openingProperty()` | String | apertura dal libro **offline** (`C50 Italian Game`), l'ultima incontrata; "" se nessuna |
| `bookMoveProperty()` | boolean | posizione ancora di teoria |
| `insightProperty()` | `ReviewInsights.MoveInsight` / null | dati della revisione della mossa mostrata (solo linea principale) |
| `hasVariationsProperty()` | boolean | offrire "Esporta con le varianti" |
| `revisionProperty()` | int | cambia quando si aggiunge/elimina una variante (ridisegna liste di varianti) |

Azioni: `next()`, `previous()`, `first()`, `last()`, `goToPly(int)` (lista mosse/grafico), `play(uci)` (mossa
toccata sullo schermo → `true` se legale), `playLine(index)` (tocco su una linea del motore), `showBestLine()`,
`backToGame()`, `deleteVariation()`, `legalTargets("e2")` (case di arrivo per il tocco-tocco), `movetext()`
(PGN con varianti), `setBoardFollowing(boolean)`.

**Gesti già previsti nel tuo design**: frecce in basso → `previous/next`, trascinamento orizzontale → idem,
`|◀ ▶|` → `first/last`. In variante `next()` va avanti **nella variante**.

## 2. Pannello "Linee del computer" — `s.lines()` (`EngineLines`)

Sotto la carta della mossa, sempre visibile, senza tocchi. 1–3 righe da ~72 px.

| Proprietà | Contenuto |
|---|---|
| `linesProperty()` | `List<Line>` (0..3), migliore prima |
| `Line.eval()` | `+0.35`, `−1.20` (meno tipografico), `M3`, `−M2` — sempre dal punto di vista del Bianco |
| `Line.whiteBetter()` | chip chiaro (true) o scuro (false), come la barra di valutazione |
| `Line.text()` | `12. Cf3 Cc6 13. d4 exd4 …` (lettere italiane, numerate; troncare a una riga con …) |
| `Line.moveText()` | solo la prima mossa: `12. Cf3` (se serve una versione corta) |
| `Line.move()` | prima mossa UCI: per la freccia della linea 1 |
| `Line.depth()` / `depthProperty()` | profondità: a destra, piccolo, `prof. 22` |
| `statusProperty()` | `SEARCHING` (spinner discreto o "…" accanto alla profondità; righe possono essere vuote ai primi istanti), `DONE`, `CHECKMATE`, `STALEMATE`, `UNAVAILABLE`, `IDLE` |
| `messageProperty()` | testo per gli stati senza linee: `Scacco matto`, `Stallo`, messaggio del motore mancante |
| `evalTextProperty()` / `whitePawnsProperty()` | valutazione della posizione per il chip della carta e la **barra** (`evalBar.updateEvaluation(whitePawns)`) |
| `bestMoveProperty()` | UCI della mossa migliore → freccia verde (sostituisce `onReviewAnalysisUpdate`) |
| `lineCountProperty()` | 1..3 (default **2**, come chiedevi), si cambia con `setLineCount(k)` (salvato in `analysis.lines`) |

- **Tocco su una riga** → `s.playLine(i)`: la linea diventa variante, il cursore va sulla sua prima mossa; con →
  si scorre la linea.
- Impostazione "Linee del computer: 1 / 2 / 3" (Impostazioni > Analisi o nel foglio ⋯ della revisione) →
  `setLineCount`. Interruttore "Mostra le linee" → `setEnabled(false)` ferma il motore.
- Stato vuoto all'arrivo su una posizione: le righe precedenti spariscono subito (mai linee della posizione
  sbagliata); suggerisco 2 righe scheletro alte uguali per non far saltare il layout.

## 3. Varianti

- Quando `inVariationProperty` è true: sopra la carta della mossa (o al posto del sottotitolo) un nastro
  `Variante · 12… Ad6 13. Cf3` + due azioni: **Torna alla partita** (`backToGame()`, primaria) e **Elimina**
  (`deleteVariation()`, icona cestino, nessuna conferma: si rifà con un tocco).
- La lista mosse della partita non cambia; l'evidenziazione segue `mainPlyProperty` (la mossa da cui parte la
  variante), magari in stile "contorno" invece che pieno quando si è in variante.
- La carta della mossa in variante: titolo `titleProperty()`, niente etichetta di revisione (`insight` è null),
  valutazione da `lines().evalTextProperty()`.
- Esportazione PGN con varianti: `s.movetext()` (lettere inglesi, standard PGN) — nel foglio ⋯ della revisione,
  solo se `hasVariationsProperty()`.

## 4. "Mostra la mossa migliore" e carta della mossa

`insightProperty()` (`ReviewInsights.MoveInsight`, null in variante/inizio/prima della revisione):

| Campo | Uso |
|---|---|
| `label()` | etichetta (tessera) — classificazione congelata, invariata |
| `moveText()` | `18. Dxb7` |
| `bestText()` | `18. Cf5` ("" se la mossa giocata era la migliore) → "La migliore era 18. Cf5" |
| `bestLineText()` | `18. Cf5 gxf5 19. Dg3+ Rh8` → riga piccola sotto, troncata |
| `evalBefore()` / `evalAfter()` | `+0.80` → `−1.20`: "La valutazione passa da +0.80 a −1.20" |
| `winLoss()` | 0..1: `−32%` di probabilità di vittoria (facoltativo) |
| `showBest()` | true per imprecisione/errore/errore grave/occasione persa con migliore diversa → pulsante **Mostra la migliore** (`s.showBestLine()`: torna alla posizione prima della mossa e gioca la linea migliore come variante; → per scorrerla, "Torna alla partita" per uscire). `showBestLine()` funziona anche per mosse buone non migliori, se vuoi offrirlo nel foglio ⋯ |
| `book()` | mossa di teoria |

## 5. Analizzare con la scacchiera fisica — `s.boardFollower()` (`BoardFollower`, può essere null)

Interruttore/pulsante **Usa la scacchiera** nella revisione (visibile se `boardFollower() != null`; ha senso
quando la scacchiera è collegata: `Hardware.get().connectedProperty()`).

- On → `s.setBoardFollowing(true)`. I LED guidano a disporre i pezzi come sullo schermo; poi **ogni mossa fatta
  con i pezzi veri** viene giocata nell'analisi (variante se non è la mossa della partita).
- Se l'utente va avanti di **una** mossa sullo schermo, i LED la mostrano da→a (come la mossa del computer in
  partita) e il messaggio dice `Esegui sulla scacchiera: 12. Cf3`. Indietro / salti / altra linea → i LED
  mostrano le case da riempire e da liberare.
- `stateProperty()`: `OFF`, `PLACING` (disponi i pezzi), `REPLICATING` (esegui una mossa), `FOLLOWING` (in
  sincronia). `messageProperty()`: frase pronta da mettere in una riga di stato sotto la scacchiera
  (es. `Disponi i pezzi come sullo schermo: i LED indicano le case`, `Posiziona i pezzi: mancano 3`,
  `Esegui sulla scacchiera: 12. Cf3`, `Muovi i pezzi per provare una variante`, `Controlla la casa E4`).
- Off / uscita → `setBoardFollowing(false)` o `s.close()`: LED spenti, scacchiera libera.

## 6. Riepilogo per fase e momenti chiave (scheda "Riepilogo")

Funzioni pure su `GameReview` (nessun motore):

- `ReviewInsights.phases(review)` → `PhaseSummary`: per Bianco e Nero una `PhaseScore` per fase raggiunta
  (`Apertura`, `Mediogioco`, `Finale` = `phase().italian()`), con `accuracyText()` (`86,4` o `—`), `grade()`
  (`EXCELLENT/GOOD/FAIR/POOR/NONE`, testo `grade().italian()`: Ottima / Buona / Discreta / Da rivedere — per
  un'icona: ✓ verde, ✓, ~, ✗ rosso), conteggi `mistakes()`, `blunders()`, `inaccuracies()`, `misses()`.
  Layout suggerito, 3 righe sotto i conteggi delle etichette: `Apertura | 92,1 ✓ | 85,0 ✓`.
  `phaseOf(ply)` e `middlegameStart()`/`endgameStart()` se vuoi due tacche verticali sul grafico.
- `ReviewInsights.keyMoments(review, side)` (side: null = entrambi, TRUE = Bianco, FALSE = Nero) →
  `KeyMoment`: `moveText()` `18. Dxb7`, `label()`, `bestText()`, `swingText()` `−32%`, `kind()` ERROR /
  GREAT_MOVE, `ply()` (0-based: tocco → `s.goToPly(ply + 1)`). Lista "Momenti chiave" con righe da 80 px:
  tessera etichetta · mossa · "migliore 18. Cf5" · `−32%`. Pulsante **Rigioca i tuoi errori** → spec 02.

## Testi (chiavi i18n proposte; i testi dinamici arrivano già pronti dai VM)

```
analysis.lines.title=Linee del computer
analysis.lines.depth=prof. {0}
analysis.lines.count=Linee del computer
analysis.lines.count.description=Quante varianti mostrare sotto la scacchiera
analysis.lines.show=Mostra le linee
analysis.lines.searching=Il motore sta pensando…
analysis.variation=Variante
analysis.variation.back=Torna alla partita
analysis.variation.delete=Elimina variante
analysis.best.show=Mostra la migliore
analysis.best.was=La migliore era {0}
analysis.eval.change=La valutazione passa da {0} a {1}
analysis.board.follow=Usa la scacchiera
analysis.board.follow.description=Muovi i pezzi veri per provare varianti; i LED seguono lo schermo
analysis.export.variations=Esporta con le varianti
analysis.book=Teoria
review.phases.title=Fasi della partita
review.moments.title=Momenti chiave
review.moments.empty=Nessun errore grave: bella partita!
review.moments.best=migliore {0}
```
