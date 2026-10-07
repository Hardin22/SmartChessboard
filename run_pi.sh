#!/bin/bash
# Starts javaChess on a Raspberry Pi 4/5 (arm64), full screen. Used by deploy/javachess.service.
# Every setting can be overridden from the environment, e.g.  JAVACHESS_PRISM=es2,sw ./run_pi.sh
#
#   JAVACHESS_JAR     jar to run (default: newest javaChess*.jar here or in target/)
#   JAVACHESS_HEAP    max heap (default 512m; the app needs ~60-120 MB, puzzles are memory-mapped)
#   JAVACHESS_PRISM   JavaFX pipeline: sw (default, software, always works) or es2,sw (GPU, falls back to sw)
#   JAVACHESS_FPS     JavaFX pulse rate (default 60; 30 halves the CPU spent on animations)
#   JAVACHESS_OPTS    extra JVM options, e.g. -Djavachess.board=sim or -Djavachess.metrics=true
#   JAVACHESS_DEBUG=1 verbose rendering info
set -euo pipefail
cd "$(dirname "$0")"

export DISPLAY=${DISPLAY:-:0}
# GTK through X11 (XWayland on Bookworm): JavaFX has no native Wayland backend
export GDK_BACKEND=${GDK_BACKEND:-x11}
# fewer glibc malloc arenas: less native memory with many threads, no measurable cost
export MALLOC_ARENA_MAX=${MALLOC_ARENA_MAX:-2}

JAR=${JAVACHESS_JAR:-$(ls -t javaChess*.jar target/javaChess*.jar 2>/dev/null | grep -v original | head -1 || true)}
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "javaChess jar not found (build it with: ./mvnw -Ppi -Djavafx.platform=linux-aarch64 -DskipTests package)" >&2
  exit 1
fi
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java

# Integrated browser (JCEF) on arm64 Linux: libcef.so needs more static TLS than glibc reserves for libraries
# opened later ("cannot allocate memory in static TLS block"), so it must be preloaded. The bundle is downloaded
# the first time the browser is opened; from the next start it is preloaded here.
JCEF_LIB="$HOME/.jcef-bundle-v141/libcef.so"
if [ "$(uname -m)" = aarch64 ] && [ -f "$JCEF_LIB" ]; then
  export LD_PRELOAD="$JCEF_LIB${LD_PRELOAD:+:$LD_PRELOAD}"
fi

# Class data sharing archive: created on the first run, then loaded at every start (much faster class
# loading on the Pi). It is rebuilt automatically when the jar or the JDK change.
CACHE_DIR=${XDG_CACHE_HOME:-$HOME/.cache}/javachess
mkdir -p "$CACHE_DIR"

JVM_OPTS=(
  -Xms64m -Xmx"${JAVACHESS_HEAP:-512m}"
  # small heap, 4 cores: the serial collector has the smallest footprint and short young pauses
  -XX:+UseSerialGC
  # give unused heap back to the OS: committed heap stays close to the ~100 MB in use (Pi with 1-2 GB)
  -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30
  -XX:ReservedCodeCacheSize=64m
  -XX:+ExitOnOutOfMemoryError
  -XX:SharedArchiveFile="$CACHE_DIR/app-cds.jsa" -XX:+AutoCreateSharedArchive -Xlog:cds=off -Xlog:cds+dynamic=off
  -Dprism.order="${JAVACHESS_PRISM:-sw}"
  -Djavafx.animation.pulse="${JAVACHESS_FPS:-60}"
  -Djavachess.kiosk=true
)
if [ "${JAVACHESS_DEBUG:-0}" = "1" ]; then
  JVM_OPTS+=(-Dprism.verbose=true -Djavachess.metrics=true -Djavachess.log.level=DEBUG)
fi

# shellcheck disable=SC2086
exec "$JAVA" "${JVM_OPTS[@]}" ${JAVACHESS_OPTS:-} -jar "$JAR" "$@"
