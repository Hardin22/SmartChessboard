#!/bin/bash

# Raspberry Pi Optimization Script for JavaChess

# 1. Memory Limit: 512MB Heap (Prevents OOM on Pi 3/4/Zero 2)
# 2. GC: SerialGC (Low overhead)
# 3. Graphics: Force OpenGL ES2 (Hardware Acceleration)
# 4. Animation: Full speed

# Ensure DISPLAY is set (defaults to :0)
export DISPLAY=${DISPLAY:-:0}
# CRITICAL: Force GTK (JavaFX) to use X11 backend. 
# Prevents focus loss/freeze when mixing heavy/lightweight components on Pi.
export GDK_BACKEND=x11

echo "Starting JavaChess in Raspberry Pi Optimized Mode..."

java -Xmx512m \
     -XX:+UseSerialGC \
     -Dprism.order=sw \
     -Dprism.verbose=true \
     -Djavafx.animation.fullspeed=true \
     -jar javaChess-1.0-SNAPSHOT.jar
