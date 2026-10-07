#!/bin/bash
# Starts javaChess on a desktop (macOS / Linux / Windows with Git Bash) from the shaded jar.
# For development use scripts/dev-run.sh, for the Raspberry Pi run_pi.sh.
#   JAVACHESS_OPTS  extra JVM options, e.g. "-Djavachess.windowed=720x1920 -Djavachess.board=sim"
set -euo pipefail
cd "$(dirname "$0")"

JAR=${JAVACHESS_JAR:-$(ls -t javaChess*.jar target/javaChess*.jar 2>/dev/null | grep -v original | head -1 || true)}
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "javaChess jar not found (build it with: ./mvnw -DskipTests package)" >&2
  exit 1
fi
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
CACHE_DIR=${XDG_CACHE_HOME:-$HOME/.cache}/javachess
mkdir -p "$CACHE_DIR"

# shellcheck disable=SC2086
exec "$JAVA" -Xmx768m -XX:+ExitOnOutOfMemoryError \
  -XX:SharedArchiveFile="$CACHE_DIR/app-cds-desktop.jsa" -XX:+AutoCreateSharedArchive \
  ${JAVACHESS_OPTS:-} -jar "$JAR" "$@"
