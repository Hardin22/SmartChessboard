# Spec UI 05 — Finali e coordinate (hub Allenamento)

Per: sessione **design**. Logica: branch `features/v1`, package `Training`. Thread: come le spec precedenti
(view-model sul thread JavaFX; le risposte del motore e dei sensori arrivano già sul thread FX).

Hub **Allenamento** (spec 04 §3): tre voci — **Aperture** (spec 04), **Finali**, **Coordinate**.

## 1. Finali — `Training.EndgameDrills`, `DrillSession`, `DrillProgress`

Dieci posizioni da vincere o da pareggiare contro il motore a piena forza (verificate con Stockfish).

### 1a. Lista

```java
for (String cat : EndgameDrills.categories())          // "Matti di base", "Finali di pedoni", "Finali di torre"
    for (EndgameDrills.Drill d : EndgameDrills.inCategory(cat)) { ... }
DrillProgress.Entry e = DrillProgress.get().entry(d.id());
```

Riga: miniatura della posizione (`d.fen()`, dal lato di `d.white()`), `d.title()` ("Due Alfieri"), `d.task()`
("Dai scacco matto in 30 mosse"), difficoltà `d.level()` 1–3 (puntini), stato `e.label()` ("Da provare",
"Non ancora risolto", "Risolto con aiuto", "Risolto · 9 mosse") con `e.stars()` 0–2.

### 1b. Esercizio

```java
DrillSession s = new DrillSession(drill, boardFollower /* o null */, DrillProgress.get());
```

Se la posizione ha il computer al tratto (nessuna delle 10 attuali, ma è supportato) lui muove subito.

| Proprietà | Uso |
|---|---|
| `fenProperty()`, `lastMoveProperty()` | scacchiera e ultima mossa |
| `shownMoveProperty()` | freccia del suggerimento |
| `stateProperty()` | `YOUR_MOVE` · `THINKING` (il computer pensa: blocca il tocco sulla scacchiera) · `SUCCESS` (verde) · `FAILED` (rosso) |
| `messageProperty()` | "Dai scacco matto in 15 mosse", "Il computer ha giocato 12… Rd5 · restano 3 mosse", "Scacco matto! Ce l'hai fatta in 9 mosse", "Stallo: la vittoria è sfumata", "Promosso! 14. b8=D in 6 mosse", "Patta tenuta per 20 mosse. Obiettivo raggiunto", "Il pedone è arrivato a promozione: riprova", "Mosse finite: riprova, puoi farcela in 15 mosse" |
| `movesLeftProperty()` | contatore grande "Mosse: 7" (per DRAW è "Da tenere: 7") |
| `hintsProperty()` | numero di suggerimenti usati |
| `drill().tip()` | l'idea, dietro un pulsante **Idea** (testo di 1–2 righe) |

Azioni: mossa a tocco → `s.play(uci)`; **Suggerimento** → `s.hint()` (mossa del motore come freccia, il risultato
conta come "con aiuto"); **Ricomincia** → `s.restart()`; interruttore **Usa la scacchiera** → `s.useBoard(on)`
(LED come in revisione: disposizione guidata, mosse del computer da eseguire, mosse sbagliate riportate indietro);
uscita → `s.close()`. A fine esercizio: carta con il messaggio e **Ricomincia** / **Prossimo**.

Promozione a tocco: usate il vostro `PromotionPicker` e passate la lettera (`"b7b8q"`).

## 2. Coordinate — `Training.CoordinateTrainer`

Allenamento di 30 secondi sui nomi delle case, la cosa che chi gioca su una scacchiera vera senza coordinate
stampate impara più lentamente. Due esercizi × lato:

```java
CoordinateTrainer t = new CoordinateTrainer(CoordinateTrainer.Mode.FIND /* o NAME */, white);
t.start();
```

- **Trova la casa** (`FIND`): grande al centro il nome (`targetProperty()`, "e4"); il giocatore la tocca **sulla
  scacchiera vera** (appoggia un pezzo sulla casa o solleva quello che c'è: i sensori la leggono; rimettere il
  pezzo non conta come risposta) o sulla scacchiera dello schermo → `t.answer("e4")`.
- **Nomina la casa** (`NAME`): la casa si accende sui LED (e va evidenziata sulla scacchiera dello schermo,
  `targetProperty()`); quattro pulsanti con i nomi (`choicesProperty()`); tocco → `t.answer(nome)`.

La scacchiera dello schermo va disegnata **senza coordinate** e dal lato scelto (`t.white()`).

| Proprietà | Uso |
|---|---|
| `stateProperty()` | `READY` (pulsante **Via!** → `start()`) · `PLAYING` · `FINISHED` (carta del risultato, **Ancora**) |
| `timeTextProperty()` | "00:24" |
| `scoreProperty()`, `mistakesProperty()` | giuste / errori |
| `bestProperty()`, `newRecordProperty()` | record dell'esercizio e del lato |
| `messageProperty()` | "Giusto!", "No: quella è d5, cercavi e4", "No: era e4", "Tempo scaduto: 17 giuste, 2 errori", "Nuovo record! 21 giuste, 0 errori"; in `READY` spiega l'esercizio |
| `wrongSquareProperty()` | (FIND) casa toccata per sbaglio: rossa per un attimo sullo schermo |

LED: giusta = verde per 0,6 s; sbagliata = rossa sulla casa toccata e verde su quella giusta. Uscita →
`t.close()` (LED spenti, sensori liberati).

Schermata di scelta: due grandi pulsanti **Trova la casa** / **Nomina la casa**, selettore **Dal Bianco / Dal
Nero**, sotto ciascuno il record ("Record: 21").

## Testi fissi proposti

"Finali", "Matti di base", "Finali di pedoni", "Finali di torre", "Idea", "Suggerimento", "Ricomincia",
"Prossimo", "Usa la scacchiera", "Coordinate", "Trova la casa", "Nomina la casa", "Dal Bianco", "Dal Nero",
"Via!", "Ancora", "Record".
