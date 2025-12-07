#!/bin/bash

# Raspberry Pi Optimization Script for JavaChess

# 1. Memory Limit: 512MB Heap (Prevents OOM on Pi 3/4/Zero 2)
# 2. GC: SerialGC (Low overhead)
# 3. Graphics: Force OpenGL ES2 (Hardware Acceleration)
# 4. Animation: Full speed

echo "Starting JavaChess in Raspberry Pi Optimized Mode..."

java -Xmx512m \
     -XX:+UseSerialGC \
     -Dprism.order=es2 \
     -Dprism.verbose=true \
     -Djavafx.animation.fullspeed=true \
     -Dprism.forceGPU=true \
     -jar target/javaChess-1.0-SNAPSHOT.jar
