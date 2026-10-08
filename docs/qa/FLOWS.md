# Mappa dei flussi di javaChess (audit QA)

Stato del codice: branch `qa/v1` partito da main `5accc41`, poi allineato a main con la nuova UI (design/v2) e le
funzioni di features (orologio PvC, annulla, ripresa, livelli in Elo). Ogni flusso indica da dove si entra, gli stati che
attraversa, come se ne esce e **come è stato percorso** (E2E = test end-to-end automatico, SIM = app vera con la
scacchiera simulata `-Djavachess.board=sim`, CODICE = solo lettura del codice). I problemi trovati hanno un
codice `QA-nnn` che rimanda a [ISSUES.md](ISSUES.md).

Legenda dei componenti: `MainController` (navigazione fra viste e fogli), `ActiveGameController` (schermata di
gioco), `PvcGame`/`PvpGame`/`PuzzleGame`/`OnlineGame` (logica), `BoardStateManager` (sensori → mosse, LED),
`EngineManager` (motori), `GameArchiveService` (archivio `~/.javachess/archive.json`).

---

## 0. Avvio e spegnimento

```
main() ─ Bootstrap.init ─ cookie store ─ JavaFX start
   │        ├─ cartella dati ~/.javachess (o -Djavachess.home), migrazione config/archivio vecchi
   │        ├─ handler globale delle eccezioni
   │        └─ GameArchiveService (archivio corrotto → messo da parte, si parte vuoti)
   └─ App.start: MainLayout + HOME, finestra (schermo intero o -Djavachess.windowed)
          └─ dopo il primo frame: Hardware.get() su thread di I/O (seriale / sim / off),
             precaricamento delle altre viste in idle
Chiusura finestra / Esc+Cmd-Q ─ App.stop:
   partita in corso → archiviata come interrotta ─ LED spenti, seriale chiusa ─ scritture pendenti
   (2 s) ─ JCEF ─ "quit" ai motori ─ kill dei processi figli ─ System.exit
SIGTERM / Ctrl+C / kill -9 / blackout ─ la partita in corso resta in current-game.json (snapshot a ogni mossa,
   features) → al riavvio la Home offre "Partita interrotta · Riprendi / Ignora" (QA-012, verificato con kill -9)
```

| Caso | Atteso | Verifica |
|---|---|---|
| Primo avvio, nessun dato | home con archivio vuoto, puzzle 1500, nessun errore | SIM |
| Nessun motore installato | home "motore non disponibile", partita PvC: messaggio e nuovi tentativi (QA-002) | E2E (crash/hang), CODICE |
| Nessuna scacchiera (`auto` senza Arduino) | chip "scacchiera scollegata", partite giocabili solo dallo schermo (QA-001) | CODICE |
| Archivio corrotto | file messo da parte in `backups/`, avviso (una volta, QA-010) | test unitari archivio |
| Chiusura a partita in corso | partita archiviata come interrotta, nessun processo/thread residuo | SIM (`-Djavachess.exitAfterMs`), E2E lunga durata |
| kill -9 a partita in corso | snapshot coerente, nessun processo Stockfish orfano, ripresa dalla Home | SIM, E2E `gameLeftByARestart…` |
| Ogni schermata (anche ruotata e con cambio tema) | nessun errore nel log | E2E `everyScreenOpens…` |

## 1. Home

Entrate: avvio, "indietro" da ogni schermata, fine partita ("Termina").
Contenuto (nuova UI): stato scacchiera e motore, saluto, carta "Partita interrotta · Riprendi / Ignora" (snapshot
su disco) oppure "Ultima partita · Rivedi", azioni: Contro il computer (ultimo livello/colore), Due giocatori
(ultima cadenza), Puzzle, Archivio, Online, Temi; ↻ e impostazioni in alto. La carta si nasconde a ogni ritorno
finché i dati non sono ricaricati (QA-029).

## 2. Partite locali

### 2.1 Contro il computer (PvC)
```
HOME ─ PVC_SETUP (livello in Elo approssimativo o Maia, colore, cadenza opzionale)
  └─ Inizia ─ GAME: PvcGame.startGame
        ├─ BoardStateManager SETUP: LED dei pezzi mancanti/sbagliati, "Posiziona i pezzi…"
        │     (scacchiera scollegata: setup completato subito)
        ├─ setup completo → PLAY; se l'umano ha il nero il bot muove subito
        ├─ mossa umana (sensori o schermo) → coach LED (verdetto) → bot (EngineManager.botMove)
        │     ├─ ok → mossa sullo schermo + REPLICATE: LED from/to, "Muovi l'avversario…" → PLAY
        │     └─ errore motore → messaggio + nuovo tentativo 2/5/15/30 s (QA-002, corretto)
        ├─ in partita: annulla (anche durante la replica: RESYNC), suggerimento, offerta di patta, abbandono
        └─ fine: matto (umano o bot), patta (regole o accordo), tempo, abbandono (conferma), "Esci" (conferma),
              chiusura app → archivio (risultato PGN + terminazione), ultima mossa del bot da replicare (QA-003),
              carta di fine "Rivedi / Nuova partita"
```
Verifica: SIM (partita intera con autoplay fino al matto del bot, vecchia e nuova UI), E2E (matto, cambio motore a
caldo, crash/blocco del motore, partita giocata toccando lo schermo), E2E scacchiera simulata (matto del bot e
dell'umano con mosse fisiche, abbandono, uscita con conferma, annulla durante la replica), E2E di features (livello,
orologio, annulla, ripresa, tempo scaduto, suggerimento).

### 2.2 Due giocatori (PvP)
```
HOME ─ PVP_SETUP (minuti, incremento, preset) ─ GAME: PvpGame (orologi, barra avversario ruotata)
  setup → PLAY (entrambi i lati muovono sulla scacchiera) → orologio del bianco parte al setup completo
  fine: matto, patta automatica (stallo, triplice, materiale, 50 mosse), tempo (FIDE 6.9: patta se chi
  resta non può dare matto), "Termina" (conferma) → archivio
```
Pausa (nuova UI): orologi fermi; una mossa sulla scacchiera in pausa viene rifiutata e rimessa (QA-030).
Verifica: E2E (bandierina), E2E scacchiera simulata (matto con orologi fermi, triplice ripetizione, patta d'accordo,
mossa in pausa).

### 2.3 Patta / abbandono
Nuova UI: abbandono con conferma (PvC e per ciascun lato in PvP), offerta/accettazione di patta in PvP, patta
offerta al bot (BotDrawPolicy, features). Archiviati come 0-1/1-0 "Abbandono" e ½-½ "Patta d'accordo" anche dopo
poche mosse (QA-021).

### 2.4 Scacchiera scollegata / ricollegata a metà partita
```
PLAY ── cavo staccato ──▶ hardwareConnected=false: lo schermo mostra la posizione logica,
   │                       replica del bot considerata fatta, mosse solo dallo schermo (QA-001)
   └── cavo riattaccato ─▶ attesa della prima fotografia dei sensori
         ├─ coincide con la partita → si continua
         ├─ è una mossa legale fatta a cavo staccato → presa come mossa
         └─ altro → RESYNC: LED mancanti/da togliere, "Rimetti i pezzi come sullo schermo…" → PLAY
```
Prima della correzione (QA-004) dopo il ricollegamento i pezzi mancanti non venivano indicati e la mossa del
bot fatta nel frattempo non veniva chiesta. Verifica: test unitari BoardStateManager, E2E scacchiera simulata.

### 2.5 Motore che si blocca / si chiude
`UciClient` riavvia il processo (max 3 riavvii al minuto, poi rifiuta per il resto del minuto); il bot ritenta
da solo (QA-002). Analisi live: si riavvia in silenzio. Verifica: E2E `!crash` / `!hang`.

### 2.6 Interruzione e ripresa
Uscire dalla schermata di gioco (indietro/"Esci" con conferma, apertura di un'altra vista, chiusura app) termina la
partita e la archivia come interrotta, ma lo snapshot resta: la Home offre di riprenderla. Riprendendo, la scacchiera
va prima rimessa nella posizione salvata (SETUP), poi si continua; quando la partita ripresa viene archiviata, la
copia "interrotta" viene sostituita (una sola copia). "Ignora" la archivia come interrotta.

### 2.7 Riavvio dell'app a partita in corso
Chiusura regolare o kill/blackout → snapshot su disco → Home "Partita interrotta · Riprendi". Verifica: SIM
(`-Djavachess.exitAfterMs`, kill -9), E2E `gameLeftByARestartIsResumedFromHomeOnTheBoard`.

## 3. Online
### 3.1 chess.com (browser integrato) — area **browser**
HOME ─ Online ─ chess.com → BROWSER (JCEF, login, visione, BotMover). In riscrittura sul branch browser/v2.
### 3.2 Lichess
Oggi: HOME ─ Lichess → se c'è una partita in corso (API) → GAME `OnlineGame`, altrimenti LICHESS_SETUP (seek)
o "apri nel browser". Il brief browser chiede che il pulsante apra lichess.org nel browser (QA-011).

## 4. Revisione
Entrate: archivio (tocco su una riga o "Apri"), home ("Rivedi" ultima partita), DevOptions.
```
REVIEW: scacchiera + navigazione (prima/indietro/avanti/ultima, tocco sulla lista mosse, tocco sul grafico)
  analisi live della posizione (linea + valutazione, freccia) ─ "Analizza partita" → GameAnalyzer (thread)
  → progresso, risultati provvisori, precisione per colore, etichette, grafico, riepilogo (foglio)
  errore del motore → dialogo + pulsante di nuovo attivo
indietro → la schermata di provenienza (nuova UI, QA-009); uscendo, l'analisi completa in corso si ferma e i suoi
motori si chiudono (QA-018)
```
Verifica: E2E (precisione), SIM (snapshot).

## 5. Archivio
Lista (ListView virtualizzata) più recenti in alto; tocco → revisione; "…" → foglio: Apri, Esporta PGN
(in `~/` o `-Djavachess.exportDir`), Elimina (con conferma). Archivio danneggiato → avviso (QA-010).
Verifica: E2E (export, apertura, eliminazione con conferma).

## 6. Puzzle
```
HOME ─ PUZZLE_DASHBOARD (rating obiettivo, temi) ─ Inizia → ricerca (thread) →
  PUZZLE_GAME: mossa dell'avversario animata → SETUP della posizione sulla scacchiera →
  soluzione (solo le mosse del lato che risolve, QA-007) → risposte dell'avversario da replicare →
  "Puzzle risolto" / mossa sbagliata (LED per rimetterla, QA-007) / Suggerimento → Soluzione / Prossimo
  progresso registrato una volta per puzzle (risolto = senza errori, aiuti, resa)
Nessun database puzzle → "Nessun puzzle trovato con questi filtri" (fuorviante, QA-008)
```
Verifica: E2E (risolto / con errore), E2E scacchiera simulata (setup fisico + soluzione).

## 7. Impostazioni e temi
SETTINGS (nuova UI): ogni controllo si salva subito (QA-005); tema, monitor capovolto (salvato), orientamento
automatico, suggerimenti/valutazione separati per PvP e PvC (spenti di default, QA-024), animazione di fine partita,
tempo di riflessione, luminosità LED, account Lichess, Avanzate.
THEME: scacchiera e pezzi.

## 8. Rotazione dello schermo
Nuova UI: ↻ in ogni intestazione, nei fogli e nel menu della partita (temporanea, si riorienta verso chi gioca);
"Monitor capovolto" nelle impostazioni per il montaggio (salvato). Verifica: E2E (ogni schermata ruotata).

## 9. Rete assente
- Lichess (seek, stream, OAuth): errori mostrati come messaggi; area browser/Lichess.
- Nome dell'apertura (`OpeningExplorer`): chiede a explorer.lichess.ovh; senza rete (o senza token, che ora il
  servizio richiede) il nome non compare, con un minuto di pausa fra i tentativi: nessun blocco (QA-014).
- Browser chess.com: area browser.
