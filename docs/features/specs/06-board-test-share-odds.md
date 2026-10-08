# Spec UI 06 — Prova della scacchiera, partita sul telefono, partite con vantaggio

Per: sessione **design**. Logica: branch `features/v1`. Tre aggiunte piccole, ognuna indipendente.

## 1. Prova della scacchiera — `Hardware.BoardDiagnostics`

Per chi costruisce la scacchiera (il progetto è open source) o sospetta un guasto. Ingresso: Impostazioni →
Scacchiera → **Prova la scacchiera** (accanto a luminosità e disposizione guidata).

```java
BoardDiagnostics d = new BoardDiagnostics();   // usa la scacchiera dell'app
d.startLedTest();      // ~15 s
d.startSensorTest();
d.stop();              // all'uscita dalla schermata: OBBLIGATORIO (restituisce la scacchiera)
```

| Proprietà | Uso |
|---|---|
| `phaseProperty()` | `IDLE` · `LEDS` · `SENSORS` · `DONE` |
| `messageProperty()` | istruzione o risultato: "Controlla che ogni casa si accenda del colore indicato", "Ora si accende una casa alla volta, da a1 a h8: deve essere quella scritta sullo schermo", "Appoggia un pezzo su ogni casa e toglilo…", "37 case su 64 · mancano: a3, b3, …", "Tutti i 64 sensori funzionano", "Scacchiera non collegata: collega il cavo e riprova" |
| `ledStepProperty()` | durante la prova dei LED: "Rosso", "Verde", "Blu", "Bianco", poi la casa accesa "a1"…"h8" — mostrala **grande** (e la casa evidenziata sulla scacchiera disegnata) |
| `checkedCountProperty()` | "37 / 64" con barra |
| `checkedProperty()`, `occupiedProperty()` | bit per casa (a1 = bit 0): sulla scacchiera disegnata verdi le case provate, bianche quelle lette come occupate |

Due pulsanti **Prova i LED** / **Prova i sensori**; **Fine** chiama `stop()`. Nota per il testo d'aiuto: se
l'ordine delle case è sbagliato, le impostazioni `led.layout`, `led.origin`, `led.direction` lo correggono (README).

## 2. La partita sul telefono — `Stats.GameLinks`

In revisione (menu "…" o scheda Riepilogo) e nell'anteprima dell'archivio: **Apri sul telefono**. Un foglio con
il codice QR e sotto il link in piccolo: inquadrandolo, il telefono apre la partita sulla scacchiera d'analisi di
lichess.org (nessun caricamento: le mosse sono nell'indirizzo; non serve un account).

```java
String url = GameLinks.lichessGame(initialFen, uciMoves);          // tutta la partita
String url = GameLinks.lichessPosition(fen);                        // solo la posizione (es. in analisi)
WritableImage qr = GameLinks.qrImage(url, 8);                       // 8 px per modulo, bordo bianco incluso
// null = troppo lunga per un QR (partite oltre ~250 mosse): mostra solo il link
```

Il QR va mostrato su fondo bianco anche nel tema scuro, senza smussature (`ImageView.setSmooth(false)`), almeno
300 px di lato. Testo: "Inquadra con la fotocamera del telefono per continuare l'analisi su lichess.org".

## 3. Partite con vantaggio — `Play.OddsPresets`

Nelle impostazioni di una partita (PvC e PvP), sotto "Posizione iniziale": **Con vantaggio** → 5 scelte
(`OddsPresets.all()`: `title()` "Senza Donna", `note()` una riga). Per chi dà il vantaggio: il Bianco usa `fen()`,
il Nero `blackGives()`. La partita parte dalla posizione come quelle dell'editor (`setStartPosition`); la
disposizione guidata indica quali pezzi lasciare fuori.

## 4. Rivincita (P9, solo flusso)

A fine partita contro il computer, **Rivincita** dovrebbe riproporre le stesse impostazioni (livello, cadenza,
posizione/vantaggio) con i **colori invertiti**; in PvP lo fa già.

## 5. Puzzle del giorno (offline) — `Play.DailyPuzzle`

Una carta nella dashboard dei puzzle (o nella Home): **Puzzle del giorno** con lo stato `statusText()` ("Da
risolvere · serie di 3 giorni", "Risolto · 4 giorni di fila", "Non risolto · domani un altro").

```java
DailyPuzzle daily = new DailyPuzzle();
daily.load().thenAccept(p -> runFx(() -> { if (p != null) apriNelPuzzle(p); }));   // null: nessun database
// alla fine del puzzle (risolto o no), una volta sola al giorno:
daily.recordResult(solved);
```

Stesso puzzle per tutto il giorno (scelto dalla data tra i puzzle popolari 1300–1900 con soluzione breve), funziona
senza rete. Se il database dei puzzle manca, la carta non va mostrata.
