# ISTRUZIONI DI RIFATTORIZZAZIONE STEP-BY-STEP PER GAMEANALYZER.JAVA

**OBIETTIVO:** Migliorare la precisione dell'analisi partita per allinearla a Chess.com V3. 
**VINCOLO:** Non implementare sistemi di caching. Concentrati esclusivamente sulla logica matematica e sulla classificazione delle mosse.

---

### STEP 1: UNIFICAZIONE DEL CORE MATEMATICO (WP & DEPTH)
La prima causa di imprecisione è la discrepanza tra le formule e la profondità di analisi. Modifica quanto segue:

1.  **Formula WP Unica:** Utilizza esclusivamente la formula sigmoidale standard di Lichess/Chess.com per ogni calcolo:
    `WP = 1.0 / (1.0 + Math.pow(10, -cp / 400.0))`
2.  **Coerenza della Profondità:** Nel metodo `analyzeGame`, se la mossa giocata non è presente nei primi 3 risultati del MultiPV, NON analizzare a profondità 10. Esegui una nuova analisi della posizione risultante alla **stessa profondità (es. 18)** impostata per la partita. Il confronto tra punteggi a profondità diverse deve essere eliminato.
3.  **Gestione Matti:** Per i calcoli WP, se una mossa è un Matto in X, assegna un valore convenzionale di `cp = 10000` (per il bianco) o `-10000` (per il nero).

---

### STEP 2: RIFATTORIZZAZIONE LOGICA ETICHETTATURA (LABELS)
Applica una gerarchia rigida. Una mossa riceve una sola etichetta, controllata in questo ordine:

1.  **Book & Forced:** (Mantieni la logica attuale, è corretta).
2.  **Brilliant (!!):** - Deve essere un sacrificio reale (`isSacrifice` == true).
    - Deve essere tra le 3 mosse migliori e contemporaneamente  avere un `deltaWp < 0.02` (quasi pari alla migliore).

3.  **Great Move (!):**
    - Deve essere l'unica mossa che mantiene un vantaggio significativo.
    - Condizione: `(bestWp - secondBestWp > 0.15)` E `deltaWp < 0.02`.
    - **Filtro Severità:** Se la posizione era già stravinta (`bestWp > 0.92`), non assegnare "Great", ma solo "Best".
    - **Filtro Ricattura:** Se la mossa è una ricattura di un pezzo di pari valore che ripristina il materiale e non cambia la valutazione, forza l'etichetta a "Best".
4.  **Miss (X):**
    - Deve rappresentare una vittoria mancata. 
    - Condizione: `bestWp > 0.75` (eri quasi vinto) E `playedWp < 0.50` (hai perso il vantaggio decisivo).
5.  **Standard Labels:** Se nessuna delle precedenti è soddisfatta, usa i deltaWP:
    - Best: < 0.005
    - Excellent: < 0.02
    - Good: < 0.05
    - Inaccuracy: < 0.10
    - Mistake: < 0.20
    - Blunder: >= 0.20 (Se però la posizione era già persa, es. `bestWp < 0.1`, declassa a Inaccuracy).

---

### STEP 3: NUOVO CALCOLO ACCURATEZZA (MEDIA QUADRATICA)
Riscrivi il metodo `calculateAccuracy` per eliminare l'inflazione dei punteggi:

1.  **Accuratezza Move-by-Move (Acc_m):**
    `Acc_m = 100 * Math.exp(-4 * deltaWp)`
2.  **Eccezione Posizione Vinta:** Se `wpPrev > 0.95` e `wpPost > 0.92`, imposta `Acc_m = 100`. In una posizione totalmente vinta, le scelte umane semplificate non devono essere penalizzate.
3.  **Aggregazione RMS (Root Mean Square):** - Escludi mosse `BOOK` e `FORCED`.
    - Somma i quadrati di ogni `Acc_m`.
    - Risultato finale = `Math.sqrt(sommaQuadrati / numeroMosse)`.
4.  **Output:** Arrotonda il risultato a un decimale.

---

### STEP 4: PULIZIA E INTEGRAZIONE
- Assicurati che il metodo `isSacrifice` utilizzi pesi realistici (P=100, N=300, B=320, R=500, Q=900) per rilevare piccoli sacrifici posizionali.
- Pulisci i log di debug in modo che mostrino chiaramente: "Mossa | WP Prev | WP Post | Delta | Label".