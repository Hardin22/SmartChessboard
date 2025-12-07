#!/bin/bash
# Script di avvio ottimizzato per Raspberry Pi
# Abilita l'accelerazione hardware GPU per JavaFX

# Imposta il display (necessario se lanciato da SSH)
export DISPLAY=:0

# Opzioni Java per forzare l'uso della GPU (OpenGL ES2)
# -Dprism.order=es2: Usa il rendering pipeline OpenGL ES2
# -Dprism.forceGPU=true: Forza l'uso della GPU anche se non riconosciuta
# -Djavafx.platform=gtk: Usa il toolkit GTK (stabile su Pi)
# -Dprism.verbose=true: Stampa info sul rendering (utile per debug)
JAVA_OPTS="-Dprism.order=es2 -Dprism.forceGPU=true -Djavafx.platform=gtk -Dprism.verbose=true"

echo "Avvio javaChess con accelerazione hardware..."
java $JAVA_OPTS -jar javaChess-1.0-SNAPSHOT.jar
