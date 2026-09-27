# PROMPT PER L'AGENTE IA: REFACTORING ESCLUSIVO DEL CALCOLO ACCURACY

**OBIETTIVO:** Rifattorizzare unicamente il metodo di calcolo della percentuale di accuratezza finale all'interno della classe Java `GameAnalyzer`. 

**IMPORTANTE:** NON modificare, rimuovere o alterare la logica esistente di assegnazione delle label (Best, Brilliant, Blunder, ecc.). Il compito è limitato alla trasformazione dei dati di valutazione in un punteggio di accuratezza (0-100%) realistico e professionale.

---

### 1. LOGICA MATEMATICA: MODELLO WIN PROBABILITY (WP)
Per evitare percentuali inflate, devi abbandonare la media lineare dei centipedoni e implementare il modello di Win Probability (WP) derivato dal framework AS_7.

Per ogni mossa della partita, esegui i seguenti passaggi:

#### A. Conversione Valutazione in WP
Converti il punteggio del motore (cp) in una probabilità (0.0 a 1.0) usando la funzione sigmoidale:
Formula: WP = 1 / (1 + Math.pow(10, -cp / 400.0))

- Se la valutazione è un Matto (Mate), usa cp = 10000 per il bianco o cp = -10000 per il nero.
- Calcola il Delta: deltaWP = WP_precedente - WP_post_mossa (sempre dal punto di vista del giocatore che sta muovendo).
- Se deltaWP è negativo (l'avversario ha fatto un errore che ti ha avvantaggiato), impostalo a 0.

#### B. Punteggio Accuratezza Singola Mossa (Acc_m)
Applica la funzione di decadimento esponenziale per calcolare quanto è stata precisa la mossa:
Formula: Acc_m = 100 * Math.exp(-4 * deltaWP)

- Il coefficiente "4" è il sensore di severità: punisce gli errori in modo esponenziale.
- ESCLUSIONI: Se una mossa è già etichettata come 'BOOK' o 'FORCED', essa deve essere totalmente esclusa dal calcolo (non deve pesare né al numeratore né al denominatore).

---

### 2. AGGREGAZIONE: MEDIA QUADRATICA (RMS)
Per garantire che gli errori gravi (Blunder) pesino correttamente sul totale e non vengano "nascosti" da una serie di mosse corrette, non usare la media aritmetica semplice. Implementa la Media Quadratica (Root Mean Square):

1. Calcola il quadrato di ogni Acc_m ottenuta.
2. Somma tutti i quadrati.
3. Dividi la somma per il numero totale di mosse analizzate (N).
4. Estrai la radice quadrata del risultato finale.

Formula: Accuracy_Finale = Math.sqrt((Somma dei quadrati di Acc_m) / N)

---

### 3. VINCOLI TECNICI DI IMPLEMENTAZIONE
- **Tipi di dato:** Utilizza variabili `double` per tutti i calcoli intermedi per preservare la precisione decimale.
- **Handling posizioni vinte:** Se WP_precedente > 0.95 e WP_post_mossa > 0.90 (posizione stravinta), assicurati che la penalità sia minima o nulla per riflettere il comportamento di Chess.com (non si punisce chi vince in modo semplice invece che col colpo di genio).
- **Output:** Restituisci un valore double arrotondato a una cifra decimale (es. 84.7%).
- **Integrità del Codice:** Mantieni intatta tutta la struttura di controllo che assegna le etichette (Best, Excellent, Good, Inaccuracy, Mistake, Blunder, Miss, Brilliant, Great).

---

**ISTRUZIONE FINALE:** Fornisci solo il codice del metodo di calcolo aggiornato e le eventuali funzioni di supporto (come la conversione in WP).