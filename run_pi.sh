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
  echo "javaChess jar not found (build it with: ./mvnw clean -Ppi -Djavafx.platform=linux-aarch64 -DskipTests package)" >&2
  exit 1
fi
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
# a jar built on a Mac without -Djavafx.platform=linux-aarch64 carries macOS JavaFX natives and cannot start here
if [ "$(uname -s)" = Linux ] && ! grep -aq 'libglassgtk3.so' "$JAR"; then
  echo "$JAR has no Linux JavaFX libraries: build it on the Pi, or with" >&2
  echo "  ./mvnw clean -Ppi -Djavafx.platform=linux-aarch64 -DskipTests package" >&2
  exit 1
fi

# Integrated browser (JCEF) on arm64 Linux: libcef.so needs more static TLS than glibc reserves for libraries
# opened later ("cannot allocate memory in static TLS block"), so it must be preloaded. The bundle is downloaded
# the first time the browser is opened, into ~/.jcef-bundle-<CEF version of this jar>; from the next start it is
# preloaded here (the app asks for that restart itself). JAVACHESS_JCEF_DIR overrides the folder.
JCEF_META=$(unzip -p "$JAR" build_meta.json 2>/dev/null \
  || python3 -c 'import sys, zipfile; print(zipfile.ZipFile(sys.argv[1]).read("build_meta.json").decode())' "$JAR" 2>/dev/null \
  || true)
JCEF_VERSION=$(printf '%s' "$JCEF_META" | grep -o 'cef-[0-9][0-9.]*' | head -1 | cut -c5- || true)
JCEF_DIR=${JAVACHESS_JCEF_DIR:-$HOME/.jcef-bundle-${JCEF_VERSION:-unknown}}
if [ ! -f "$JCEF_DIR/libcef.so" ] && [ -z "${JAVACHESS_JCEF_DIR:-}" ]; then
  JCEF_DIR=$(ls -td "$HOME"/.jcef-bundle-*/ 2>/dev/null | head -1 || true)  # unzip missing: newest bundle
  JCEF_DIR=${JCEF_DIR%/}
fi
if [ "$(uname -m)" = aarch64 ] && [ -n "$JCEF_DIR" ] && [ -f "$JCEF_DIR/libcef.so" ]; then
  export LD_PRELOAD="$JCEF_DIR/libcef.so${LD_PRELOAD:+:$LD_PRELOAD}"
fi
# the browser's "Riavvia l'app" button starts this script again when the app is not run by systemd
export JAVACHESS_LAUNCHER="$(pwd)/$(basename "$0")"

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
if [ -n "${JAVACHESS_JCEF_DIR:-}" ]; then
  JVM_OPTS+=(-Djavachess.jcef.dir="$JAVACHESS_JCEF_DIR")
fi
if [ "${JAVACHESS_DEBUG:-0}" = "1" ]; then
  JVM_OPTS+=(-Dprism.verbose=true -Djavachess.metrics=true -Djavachess.log.level=DEBUG)
fi

if [ "${1:-}" = "--install-browser" ]; then
  # set-up: download the browser engine now (no window), so that the app never restarts for it later
  exec "$JAVA" -Xmx256m ${JAVACHESS_JCEF_DIR:+-Djavachess.jcef.dir="$JAVACHESS_JCEF_DIR"} -jar "$JAR" --install-browser
fi

# shellcheck disable=SC2086
exec "$JAVA" "${JVM_OPTS[@]}" ${JAVACHESS_OPTS:-} -jar "$JAR" "$@"
