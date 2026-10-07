#!/bin/bash
# Builds the compact puzzle database (data/puzzles.db, ~410 MB for 5.7 M puzzles) from the Lichess CSV.
#
#   scripts/build-puzzle-db.sh                          # data/puzzles.csv -> data/puzzles.db
#   scripts/build-puzzle-db.sh lichess_db_puzzle.csv.zst ~/.javachess/puzzles.db
#   scripts/build-puzzle-db.sh --download               # fetch the latest CSV from database.lichess.org (~270 MB)
#   scripts/build-puzzle-db.sh in.csv out.db --min-popularity 0   # smaller file: only well-rated puzzles
#
# The app looks for the database in -Djavachess.puzzles=<file>, data/puzzles.db, ~/.javachess/puzzles.db.
# Needs Java 21 and ~520 MB of RAM; about 1 minute on a Mac, a few minutes on a Raspberry Pi 5.
set -euo pipefail
cd "$(dirname "$0")/.."

IN=${1:-data/puzzles.csv}
OUT=${2:-data/puzzles.db}
shift $(( $# > 2 ? 2 : $# ))

if [ "$IN" = "--download" ]; then
  IN=${TMPDIR:-/tmp}/lichess_db_puzzle.csv.zst
  curl -L --fail -o "$IN" https://database.lichess.org/lichess_db_puzzle.csv.zst
fi
if [[ "$IN" == *.zst ]]; then
  command -v zstd >/dev/null || { echo "zstd is needed to unpack $IN (apt install zstd / brew install zstd)" >&2; exit 1; }
  zstd -d -f "$IN" -o "${IN%.zst}"
  IN="${IN%.zst}"
fi
[ -f "$IN" ] || { echo "Puzzle CSV not found: $IN" >&2; exit 1; }

JAR=${JAVACHESS_JAR:-$(ls -t javaChess*.jar target/javaChess*.jar 2>/dev/null | grep -v original | head -1 || true)}
if [ -n "$JAR" ]; then
  CP="$JAR"
else
  ./mvnw -q -DskipTests compile
  ./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
  CP="target/classes:$(cat target/classpath.txt)"
fi
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
exec "$JAVA" -Xmx512m -XX:+UseSerialGC -cp "$CP" org.example.javachess.Services.PuzzleIndexer "$IN" "$OUT" "$@"
