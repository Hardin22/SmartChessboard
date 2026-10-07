#!/bin/bash
# Runs javaChess in a "Raspberry Pi in a box": Docker linux/arm64 Debian bookworm + Temurin 21 + Xvfb 720x1920,
# software rendering, CPU and memory limited like a small Pi 4. Needs Docker with arm64 support
# (Apple Silicon with Docker Desktop/OrbStack, or an arm64 Linux host). See docs/raspberry-pi.md.
#
#   scripts/pi-sim.sh image              build the image (once)
#   scripts/pi-sim.sh sync               copy the working tree (tracked + new files, no datasets) into the box
#   scripts/pi-sim.sh package            build the Pi jar inside the box (linux-aarch64 natives)
#   scripts/pi-sim.sh test               ./mvnw test inside the box
#   scripts/pi-sim.sh engines            scripts/install-engines.sh inside the box + Stockfish nodes/s with 1-4 threads
#   scripts/pi-sim.sh run [-Dopt=...]    run_pi.sh on the virtual 720x1920 screen; extra JVM options are passed on
#   scripts/pi-sim.sh shell              interactive shell in the box
#   scripts/pi-sim.sh all                image + sync + package + run (snapshot of HOME, then exit)
#
# Environment: PI_CPUS (default 4), PI_MEM (default 1g), PI_SIM_OUT (host folder for snapshots/logs,
# default /tmp/pi-sim), PI_SCREEN (default 720x1920). State (Maven cache, ~/.javachess, engines, sources)
# lives in the Docker volume "javachess-pi-home"; `docker volume rm javachess-pi-home` resets it.
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=javachess-pi-sim
VOLUME=javachess-pi-home
CPUS=${PI_CPUS:-4}
MEM=${PI_MEM:-1g}
OUT=${PI_SIM_OUT:-/tmp/pi-sim}
SCREEN=${PI_SCREEN:-720x1920}
mkdir -p "$OUT"
# macOS bsdtar: no extended attributes / resource forks in the archive
TAR_OPTS=""
tar --version 2>/dev/null | grep -q bsdtar && TAR_OPTS="--no-mac-metadata --no-xattrs"

box() { # box [docker options...] -- command...
  local opts=()
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do opts+=("$1"); shift; done
  shift
  docker run --rm --platform linux/arm64 --hostname raspberrypi --cpus="$CPUS" --memory="$MEM" --memory-swap="$MEM" \
    -v "$VOLUME":/home/pi -v "$OUT":/out ${opts[@]+"${opts[@]}"} "$IMAGE" bash -lc "$*"
}

# Xvfb as the board monitor, then the command
with_screen() {
  echo "Xvfb :99 -screen 0 ${SCREEN}x24 -nolisten tcp >/tmp/xvfb.log 2>&1 & for i in \$(seq 50); do xdpyinfo -display :99 >/dev/null 2>&1 && break; sleep 0.1; done; $*"
}

cmd=${1:-help}
shift || true
case "$cmd" in
  image)
    docker build --platform linux/arm64 -t "$IMAGE" docker/pi-sim
    ;;
  sync)
    # tracked and new (not ignored) files: no datasets, jcef-bundle, target or secrets
    git ls-files -co --exclude-standard -z | grep -zv '^config.properties$' \
      | tar --null -T - $TAR_OPTS -cf - \
      | docker run --rm -i --platform linux/arm64 -v "$VOLUME":/home/pi "$IMAGE" \
          bash -c 'mkdir -p ~/javachess && cd ~/javachess && rm -rf src docs scripts firmware && tar -xf - && chmod +x mvnw run_pi.sh run.sh scripts/*.sh'
    echo "sources copied into $VOLUME:/home/pi/javachess"
    ;;
  package)
    box -- "cd ~/javachess && ./mvnw -q clean -Ppi -DskipTests package && ls -l target/javaChess-*.jar"
    ;;
  test)
    box -- "cd ~/javachess && ./mvnw test $*"
    ;;
  engines)
    box -- "cd ~/javachess && scripts/install-engines.sh $* && for t in 1 2 3 4; do \
      printf 'setoption name Threads value %s\nsetoption name Hash value 64\nposition startpos\ngo movetime 5000\n' \$t \
        | (cat; sleep 6) | engines/stockfish/stockfish | grep -E '^info depth [0-9]+ .*nps' | tail -1 \
        | sed -E \"s/.*depth ([0-9]+).* nps ([0-9]+).*/threads=\$t depth=\\1 nps=\\2/\"; done"
    ;;
  run)
    box -- "$(with_screen "cd ~/javachess && JAVACHESS_OPTS='-Djavachess.metrics=true $*' ./run_pi.sh 2>&1 | tee /out/run.log")"
    ;;
  shell)
    box -it -- "$(with_screen bash)"
    ;;
  all)
    "$0" image && "$0" sync && "$0" package
    "$0" run -Djavachess.snapshot=/out/home.png -Djavachess.snapshot.delayMs=8000 -Djavachess.snapshot.exit=true
    echo "snapshot: $OUT/home.png, log: $OUT/run.log"
    ;;
  *)
    sed -n '2,20p' "$0"
    ;;
esac
