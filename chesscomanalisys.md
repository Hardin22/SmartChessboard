OBIETTIVO: Sviluppare un sistema Java ad alte prestazioni per l'etichettatura delle mosse e il calcolo dell'accuratezza, emulando il comportamento di Chess.com Game Review.

ATTENZIONE, SALTA COMPLETAMENTE LA PARTE DI OTTIMIZZAZIONE, SUDDIVIDENDO IN THREAD E CHIAMANDO L'API DI LICHESS. NON FARLO. NON IMPLEMENTARE ALCUNA DI QUESTE LOGICHE, FAI UN SEMPLICE CICLO FOR ANALIZZANDO UNA MOSSA ALLA VOLTA DEL PGN. NIENTE DI PIU'.

1. ARCHITETTURA E CALCOLO MATEMATICO
Il sistema non deve ragionare in "Centipawns" (cp), ma in Win Probability (WP). Ogni valutazione deve essere convertita immediatamente.
Formula WP: WP=0.5+0.5∗(2/(1+exp(−0.0036∗cp))−1)
Nota: Se la mossa porta al matto (Mate in X), assegna un valore cp di ±10000 (regolato dalla distanza del matto) per tendere a WP=1.0 o 0.0.
2. LOGICA DI CALCOLO DEL SACRIFICIO (Cruciale per !!)
Per identificare un sacrificio, implementa una funzione isSacrifice(Move m):
Stato Materiale T0: Calcola il valore del materiale del giocatore prima della mossa (P=100, N=300, B=320, R=500, Q=900).
Stato Materiale T1: Calcola il valore dopo la mossa m E dopo la risposta più probabile dell'avversario (la PV 
1
​	
  dell'avversario).
Condizione di Sacrificio: Se Material(T1) < Material(T0) e il giocatore non ha recuperato il materiale entro la linea principale (PV 
1
​	
 ) della profondità analizzata, la mossa è un sacrificio.
Esclusione: Non è un sacrificio se è un "cambio alla pari" (es. Alfiere per Cavallo) o se è una "mossa forzata" per uscire da uno scacco perdendo materiale inevitabilmente.
3. PIPELINE DI ETICHETTATURA (ORDINE RIGOROSO)
Per ogni mossa giocata M, esegui i controlli in questo esatto ordine. Una volta trovata una corrispondenza, assegna l'etichetta e passa alla mossa successiva.

A. Book Move
Query: API Lichess Master Database.
Condizione: Se la mossa è presente nel database con >5 partite.
B. Forced Move
Condizione: Il numero di mosse legali nella posizione precedente è uguale a 1.
Etichetta: "Forced". Non influisce negativamente sull'accuratezza.
C. Brilliant Move (!!)
Chess.com ha reso questa etichetta più generosa, ma richiede:
Deve essere un Sacrificio: isSacrifice(M) deve essere true.
Qualità della Mossa: La mossa deve essere la migliore (PV 
1
​	
 ) o la differenza di WP rispetto alla migliore deve essere quasi nulla (ΔWP<0.01).
Sicurezza: La valutazione finale dopo la mossa non deve scendere sotto lo 0.0 (non si è in svantaggio dopo il sacrificio).
Non Ovvietà: Se la mossa è una ricattura o l'unica mossa legale, NON può essere Brilliant.
D. Great Move (!)
Questa etichetta indica l'unica mossa che salva la partita o garantisce un vantaggio critico.
Unicità: WP(M) (mossa giocata) deve essere >0.5 (posizione vinta o pari).
Gap Critico: La differenza di WP tra la mossa giocata (PV 
1
​	
 ) e la seconda migliore (PV 
2
​	
 ) deve essere >0.15 (15%).
Esempio: Se l'unica mossa per non perdere è un sacrificio di qualità o una manovra precisa, e tutte le altre mosse portano a −2.0, allora è "Great".
Esclusione: Se la valutazione è >+5.0, nessuna mossa è "Great", è solo "Best".
E. Miss (X) - "Missed Win"
Potenziale: La posizione precedente aveva una mossa con WP>0.75 (vantaggio decisivo).
Fallimento: La mossa giocata ha portato a una WP<0.45 (parità o svantaggio).
Identificazione: Il giocatore ha "mancato" una linea di matto o un guadagno di materiale netto che Stockfish aveva individuato come PV 
1
​	
 .
F. Blunder (??)
Condizione: ΔWP>0.20 (Perdita di probabilità di vittoria superiore al 20%).
Eccezione: Se la posizione era già completamente persa (es. da −8.0 a −12.0), non etichettare come Blunder, ma come "Inaccuracy".
G. Mistake (?)
Condizione: ΔWP∈[0.10,0.20].
H. Inaccuracy (?!)
Condizione: ΔWP∈[0.05,0.10].
I. Best / Excellent / Good
Best: Mossa giocata = PV 
1
​	
 .
Excellent: ΔWP<0.02.
Good: ΔWP<0.05.
4. REQUISITI TECNICI E OTTIMIZZAZIONE
Parallelismo Totale: Usa CompletableFuture in Java per inviare ogni FEN a un pool di processi Stockfish. Non aspettare che la mossa 1 finisca per analizzare la mossa 2.
Caching Ibrido:
Controlla cache locale (DB).
Controlla Lichess Cloud API (se Depth ≥20).
Se mancano entrambi, avvia Stockfish locale con MultiPV 3 e Depth 18.
Filtro "Ricatture Ovvie": Se la mossa è una ricattura immediata (isRecapture) dello stesso pezzo e il valore del materiale torna uguale, assegna "Best" senza calcoli complessi di "Great" o "Brilliant".
Accuratezza Finale: Implementa la formula CAPS2: Accuracy=100∗(Mean(WP 
per_move
​	
 )) 
2
  (adattata con una distribuzione gaussiana per riflettere i punteggi di Chess.com).
Istruzione Finale per l'IA:
"Non inventare nuove soglie. Se trovi una situazione ambigua, dai la priorità alla stabilità della valutazione. Implementa un logger dettagliato che spieghi, per ogni mossa, perché è stata assegnata una determinata etichetta (es: 'Label Great assegnata perché PV1-PV2 WP Delta è 0.18')."