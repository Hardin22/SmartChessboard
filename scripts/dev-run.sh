#!/bin/bash
# Avvia l'app in sviluppo con le opzioni di DevOptions.
# Esempio (monitor 720x1920 = schermo 1, screenshot della vista SETTINGS e uscita):
#   scripts/dev-run.sh -Djavachess.screen=1 -Djavachess.view=SETTINGS \
#       -Djavachess.snapshot=/tmp/settings.png -Djavachess.snapshot.exit=true
set -e
cd "$(dirname "$0")/.."
./mvnw -q -DskipTests compile
if [ ! -f target/classpath.txt ] || [ pom.xml -nt target/classpath.txt ]; then
  ./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
fi
exec java -cp "target/classes:$(cat target/classpath.txt)" "$@" io.github.hardin22.javachess.Application.Main
