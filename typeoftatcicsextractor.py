import csv
import os
import sys

# --- CONFIGURAZIONE ---

NOME_FILE = "puzzle.csv" 
# Indice della colonna (nel tuo esempio 'advantage...' è l'ottava colonna, quindi indice 7)
INDICE_COLONNA_TEMI = 7

def main():
    # Cerca il file nella cartella Downloads del Mac
    percorso_file = os.path.join(os.path.expanduser("~"), "Downloads", NOME_FILE)

    if not os.path.exists(percorso_file):
        print(f"❌ Errore: Non trovo il file qui: {percorso_file}")
        print("Verifica di aver scritto il nome corretto nello script.")
        return

    tattiche_uniche = set()
    conteggio_righe = 0

    print(f"📂 Sto leggendo {NOME_FILE}...")

    try:
        # Apre il file in lettura (encoding utf-8 per sicurezza)
        with open(percorso_file, 'r', encoding='utf-8') as f:
            reader = csv.reader(f)

            for riga in reader:
                conteggio_righe += 1
                
                # Controllo per evitare errori su righe vuote o malformate
                if len(riga) > INDICE_COLONNA_TEMI:
                    stringa_temi = riga[INDICE_COLONNA_TEMI]
                    
                    # Se la cella non è vuota, dividi per spazio
                    if stringa_temi:
                        # 'advantage hangingPiece' diventa ['advantage', 'hangingPiece']
                        singole_tattiche = stringa_temi.split(' ')
                        for t in singole_tattiche:
                            # Aggiunge al set (i duplicati vengono ignorati automaticamente)
                            if t.strip(): # evita stringhe vuote
                                tattiche_uniche.add(t.strip())

                # Stampa un aggiornamento ogni milione di righe
                if conteggio_righe % 1000000 == 0:
                    print(f"   Processate {conteggio_righe} righe...")

    except Exception as e:
        print(f"❌ Errore durante la lettura: {e}")
        return

    print(f"\n✅ Finito! Processate {conteggio_righe} righe.")
    print(f"🏆 Trovati {len(tattiche_uniche)} tipi di tattiche unici:\n")
    print("-" * 30)
    
    # Ordina alfabeticamente e stampa
    for tattica in sorted(list(tattiche_uniche)):
        print(tattica)
    print("-" * 30)

if __name__ == "__main__":
    main()