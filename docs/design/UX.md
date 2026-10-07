# javaChess — UX e interfaccia (v2)

Questo documento spiega **per chi** e **dove** è disegnata l'interfaccia, i principi che ne derivano e la struttura
di ogni schermata. È la base del ridisegno v2 (branch `design/v2`); le scelte fatte durante l'implementazione e i
motivi sono riassunti in [SUMMARY.md](SUMMARY.md).

## 1. Il dispositivo reale

| Fatto | Conseguenza |
|---|---|
| Schermo **touch 720×1920 verticale**, lungo e stretto, accanto alla scacchiera fisica | Una colonna sola. Niente griglie a più colonne di testo, niente barre laterali. L'altezza è abbondante, la larghezza no: ogni riga ha **una** informazione principale. |
| È **di fianco** alla scacchiera: l'estremità bassa dello schermo è dal lato di un giocatore, quella alta dal lato dell'altro | Il "basso" dello schermo è sempre verso chi lo usa. Lo schermo deve poter **girare di 180°** in qualunque momento, e in due giocatori ognuno ha la **propria metà orientata verso di sé**. |
| Si guarda **a un braccio o più**, seduti alla scacchiera, spesso con la coda dell'occhio mentre si pensa alla mossa | Testo corrente ≥ 22 px, etichette ≥ 20 px, titoli 40–48 px, numeri importanti 64–120 px, orologi 160–220 px. Contrasto alto, gerarchia evidente: si deve capire lo stato **senza leggere**. |
| Si usa **con le dita**, senza mouse, senza hover, senza tastiera | Bersagli ≥ 72 px (pulsanti principali 88–96 px), distanza tra bersagli ≥ 12 px. Nessuna informazione solo al passaggio del mouse. Slider sostituiti da selettori a passi (− / +) e da chip. Il testo, quando serve (ricerca in archivio, account), si scrive con una **tastiera a schermo** propria. |
| **Raspberry Pi 5**, JavaFX spesso con rendering **software** | Niente blur, niente ombre su molti nodi, niente animazioni continue. Il "wow" viene da tipografia, scala, ritmo, colore e da poche transizioni brevi (≤ 200 ms) solo in risposta a un tocco. L'orologio ridisegna un'etichetta al secondo; le liste sono virtualizzate. |
| La **scacchiera fisica esiste** (sensori + 64 LED) | Lo schermo non deve ripetere la scacchiera: mostra ciò che la scacchiera non sa dire (orologi, di chi è il turno, cosa fare adesso, valutazione, suggerimenti, errori dei sensori, revisione). La scacchiera a schermo serve come **conferma** di ciò che i sensori hanno letto e per la revisione. |

## 2. Chi c'è, dove guarda, cosa tocca

1. **Arrivo alla scacchiera.** Una persona si siede. Vuole iniziare a giocare in due tocchi, oppure riprendere
   la partita lasciata a metà, oppure rivedere l'ultima. → Home con le azioni principali **in basso** (vicino alla
   mano) e lo stato (scacchiera collegata, motore, partita in corso) in alto.
2. **Contro il computer.** Un umano, seduto dal lato del proprio colore. Muove i pezzi veri; quando il computer
   risponde, **deve eseguire lui la mossa del computer** sulla scacchiera (i LED la indicano). Cosa guarda:
   *di chi è il turno*, *quale mossa fare per il computer* (grande, leggibile da lontano: «Alfiere f8 → c5»),
   eventualmente la valutazione e il suggerimento. Cosa tocca: poco — suggerimenti sì/no, cambio motore,
   abbandona. → Lo schermo si **orienta da solo verso l'umano** (gira di 180° se gioca col Nero).
3. **Due giocatori.** Due persone ai lati opposti, lo schermo di lato. Nessuno dei due deve "leggere al contrario".
   Ognuno ha la **propria metà**: orologio enorme, nome e colore, stato («Tocca a te»), materiale, azioni
   (pausa, patta, abbandona) con conferma **nella propria metà**. Il centro, a uguale distanza da entrambi,
   mostra la posizione letta dai sensori — una scacchiera disegnata con il Bianco verso la metà del Bianco si
   legge correttamente da tutti e due i lati, come quella vera — e la barra di valutazione verticale (la parte
   bianca cresce verso il Bianco). Si può nascondere per avere solo gli orologi.
4. **Puzzle.** Un solutore, orientato verso il colore che deve muovere. Grande: chi muove, cosa fare, esito
   (giusto / sbagliato), suggerimento e soluzione a portata di pollice.
5. **Dopo la partita: revisione.** Si guarda con calma, spesso ancora seduti. Precisione dei due giocatori, grafico,
   etichette delle mosse (Geniale … Errore grave) e navigazione mossa per mossa con **pulsanti enormi in basso**
   e **trascinamento orizzontale** sulla scacchiera.
6. **Archivio.** Centinaia di partite. Si cerca «quella contro Maia di ieri» o «le partite perse». → Lista grande
   e leggibile raggruppata per giorno, filtri a chip (modalità, risultato, periodo), ricerca per nome con tastiera
   a schermo, anteprima in un foglio con le azioni (rivedi, esporta, elimina con conferma).
7. **Impostazioni.** Rare. Ogni scelta si applica e si salva subito (niente "Salva" da ricordare).

## 3. Principi

1. **Il basso è di chi guarda.** Le azioni principali stanno nella parte bassa della schermata (o della propria
   metà), le informazioni sopra. Il pulsante **Ruota** (↻) è sempre nello stesso angolo dell'intestazione, in ogni
   schermata, nei fogli e in ogni metà della partita a due; in più, una rotazione a due dita gira lo schermo.
2. **Orientamento automatico.** Una partita contro il computer o un puzzle girano lo schermo verso chi gioca;
   la partita a due usa l'orientamento di montaggio (impostazione «Monitor capovolto»). La rotazione manuale vale
   finché non inizia una nuova partita.
3. **Leggibile a distanza, prima di tutto.** Una sola informazione dominante per area, numeri grandi in Geist Mono
   (cifre a larghezza fissa: l'orologio non "balla"), stati espressi con colore **e** testo **e** forma.
4. **Un tocco = una cosa.** Pulsanti grandi con etichetta, niente menu nascosti per le azioni frequenti; le azioni
   distruttive (abbandona, elimina, esci dalla partita) chiedono conferma nello stesso punto.
5. **Calma.** Superfici scure profonde (o carta calda nel tema chiaro), un solo colore d'accento (il blu del LED
   del logo), colori di stato riservati agli stati. Nessun movimento che non sia la risposta a un gesto.
6. **Leggero per il Pi.** Nessun effetto costoso, nodi riciclati, ridisegni minimi, animazioni ≤ 200 ms.

## 4. Sistema visivo

- **Tipografia**: Geist (Light, Regular, Medium, SemiBold, Bold) e Geist Mono (Light, Regular, Medium, SemiBold,
  Bold). Scala: display 160–220 (orologi, Geist Mono Light), numeri 64–120, titolo 44, sottotitolo 26,
  corpo 24, secondario 22, didascalia 20.
- **Colori** (token in `Style.css`, letti anche dai canvas tramite `ThemeManager.palette()`):
  - Scuro: fondo `#0C0C0E`, superfici `#151518` / `#1D1D21` / `#26262B`, linee `#2C2C32`, testo `#F4F4F5` /
    `#ABABB4` / `#76767F`, accento `#4F9DFF`.
  - Chiaro: fondo `#F3F2EF` (carta), superfici `#FFFFFF` / `#ECEAE6` / `#E1DED9`, linee `#DEDBD5`, testo
    `#141416` / `#55555C` / `#85858C`, accento `#1A6BE0`.
  - Stato: successo, attenzione, pericolo; l'orologio attivo è una superficie **accesa** (chiara su scuro, scura
    su chiaro), sotto i 20 secondi diventa rossa.
- **Etichette della revisione** — identità propria: tessere quadrate arrotondate (come il logo e le case della
  scacchiera), glifo bianco, colore per categoria:

  | Etichetta | Glifo | Colore |
  |---|---|---|
  | Geniale | !! | turchese `#1FBFB0` |
  | Grande | ! | blu `#4A8FE7` |
  | Migliore | ★ | verde `#55B45E` |
  | Eccellente | ✓ | verde chiaro `#83B556` |
  | Buona | ✓ (tratto sottile) | salvia `#93A88A` |
  | Teoria | libro | legno `#B48A60` |
  | Forzata | → | grigio `#8A8F98` |
  | Imprecisione | ?! | giallo `#E9B634` |
  | Errore | ? | arancio `#EE8536` |
  | Occasione persa | × | rosa `#E7708A` |
  | Errore grave | ?? | rosso `#E5484D` |

- **Forme**: raggio 20–28 px su carte e pulsanti, 999 px sui chip; bordi da 1–2 px al posto delle ombre.
- **Movimento**: cambio schermata 160 ms (dissolvenza + 24 px), fogli 180 ms, rotazione 220 ms; disattivabile con
  `-Djavachess.animations=off` (consigliato solo se il Pi fatica).

## 5. Struttura delle schermate (720×1920)

Ogni schermata (tranne la partita) ha la stessa intestazione alta 112 px: **Indietro** (80×80) a sinistra,
titolo e sottotitolo, **Ruota** (80×80) a destra.

### Home
- In alto: marchio (logo + «javaChess»), stato della scacchiera e del motore, impostazioni.
- Al centro: la carta **«Partita in corso → Riprendi»** oppure **«Ultima partita → Rivedi»** con miniatura.
- In basso, vicino alla mano: due grandi riquadri **Contro il computer** (con l'ultima configurazione, es.
  «Stockfish · livello 10 · Bianco») e **Due giocatori** (ultima cadenza, es. «10 + 5»), poi **Puzzle**
  (punteggio), **Archivio** (numero di partite), **Online** (Chess.com e Lichess nel browser integrato) e **Temi**.

### Preparazione partita
- **Contro il computer**: avversario (Stockfish, Maia 1100/1500/1900 come carte grandi), livello 1–20 con − / +
  e chip rapidi, colore (Bianco, Casuale, Nero con il re disegnato), pulsante **Gioca** largo quanto lo schermo.
- **Due giocatori**: cadenze come tessere (1+0 … 30+0, con il nome Bullet/Blitz/Rapid/Classica), personalizzata
  con − / + per minuti e incremento, **Gioca**.

### Partita contro il computer (orientata verso l'umano)
Dall'alto: intestazione compatta (titolo = avversario, sottotitolo = apertura) · riga del computer (stato
«Sta pensando…», materiale) · scacchiera 656 px con barra di valutazione verticale · riga del giocatore ·
**carta di stato** (la cosa più importante: «Tocca a te», «Muovi per Stockfish: f8 → c5» in grande, istruzioni
di posizionamento, errori dei sensori) · suggerimento (mossa migliore e valutazione, se attivo) · striscia delle
ultime mosse · barra azioni: Suggerimenti, Valutazione, Motore, Abbandona.

### Partita a due giocatori
- Metà alta ruotata di 180° (giocatore del Nero), metà bassa per il Bianco; al centro la posizione letta dai
  sensori con la barra di valutazione (nascondibile dal menu).
- Ogni metà, dal centro verso il bordo: ultima mossa e materiale · **orologio** · nome, colore e stato ·
  azioni **Pausa**, **Patta**, **Abbandona**, **⋯** (menu orientato verso chi lo apre).
- Turno: la metà di chi muove ha l'orologio "acceso"; sotto 20 s diventa rosso. Pausa: entrambe le metà mostrano
  «In pausa · tocca per riprendere». Patta: chi la propone vede «Proposta inviata», l'altro «Accetta / Rifiuta»
  nella propria metà. Fine: ciascuno vede l'esito dal proprio punto di vista (Hai vinto / Hai perso / Patta) con
  Rivedi, Nuova partita, Home.

### Puzzle
- Pannello: punteggio, serie e risolti; difficoltà con − / + a passi di 50; temi a chip; **Inizia**.
- Puzzle: orientato verso chi muove; scacchiera, carta di stato grande (trova la mossa / giusto / sbagliato),
  temi, **Suggerimento**, **Soluzione**, **Prossimo**.

### Revisione
- Intestazione con giocatori e data. Prima dell'analisi: grande invito **Analizza partita** (con avanzamento).
- Dopo: precisione dei due giocatori in grande, scacchiera con barra di valutazione, **carta della mossa**
  (tessera dell'etichetta, «Cf3 è la mossa migliore» / «Errore grave · migliore era Dxd5», valutazione),
  grafico tappabile con i punti delle mosse notevoli, schede **Mosse** (lista con tessere) e **Riepilogo**
  (conteggi per etichetta, Bianco | etichetta | Nero), navigazione |◀ ◀ ▶ ▶| alta 104 px in fondo.
  Trascinare la scacchiera a sinistra/destra cambia mossa.

### Archivio
- Ricerca (tastiera a schermo) e chip: modalità (Tutte, Computer, Due giocatori, Online), risultato (Tutti, Vinte,
  Perse, Patte, Interrotte), periodo (Sempre, Oggi, 7 giorni, 30 giorni).
- Lista virtualizzata, intestazioni per giorno («Oggi», «Ieri», «lunedì 5 ottobre»); riga alta 136 px:
  miniatura della posizione finale, titolo (avversario/modalità), ora · mosse · cadenza, apertura, tessera del
  risultato (Vinta / Persa / Patta / 1-0 / 0-1 / —).
- Tocco su una riga → foglio di anteprima (scacchiera grande, giocatori, esito, apertura) con **Rivedi**,
  **Esporta PGN**, **Elimina** (con conferma).
- «Vinte/Perse» sono dal punto di vista del giocatore umano (contro il computer, online); in una partita a due una
  partita decisa è sia vinta che persa (c'è sempre un vincitore seduto alla scacchiera).

### Impostazioni
Sezioni a righe grandi: Aspetto (tema, scacchiera e pezzi), Schermo (monitor capovolto, orientamento automatico),
Partita (suggerimenti, valutazione, animazione di fine partita), Computer (livello, tempo per mossa),
Orologio (cadenza predefinita), Scacchiera e LED (stato, luminosità), Motore, Account Lichess, Avanzate,
Versione. Ogni modifica si salva subito.

## 6. Orientamento: le regole

- `ui.screen.flipped` (impostazione «Monitor capovolto»): orientamento di montaggio, persistente.
- Partita contro il computer / Lichess / puzzle: lo schermo si gira verso il colore dell'umano (il Bianco è dal lato
  "basso" del montaggio).
- Due giocatori: orientamento di montaggio; ogni metà è già girata verso il proprio giocatore.
- Altre schermate: restano come sono; il pulsante ↻ (o due dita) gira subito di 180°.

## 7. Cosa non cambia

Logica di gioco, motore, classificatore della revisione, hardware/seriale e archivio sono usati tramite le loro
API. Aggiunte minime (documentate in SUMMARY.md): `AbstractGame.isRunning()`, pausa dell'orologio in `PvpGame`.
