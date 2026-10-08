#!/bin/bash
# Checks the integrated browser on a real Raspberry Pi 5 (or any arm64/x86 Linux with a screen), step by step,
# and writes a report to send back. Run it from the javaChess folder on the Pi, at the Pi's own screen or over SSH
# (it uses the Pi's screen, DISPLAY=:0):
#
#   scripts/pi-check-browser.sh            system checks + browser start-up + local test board
#   scripts/pi-check-browser.sh --sites    also opens lichess.org and chess.com analysis boards (no login needed)
#
# What it does NOT do: it never logs in, never plays or moves on a site and never touches your games, settings or
# saved logins: the app runs with a separate, temporary data folder. The only thing it keeps is the browser engine
# (~/.jcef-bundle-<version>, ~450 MB), which the app would download anyway the first time you open the browser.
#
# Result: PASS/FAIL lines on the screen and the folder printed at the end (report.txt, logs, pictures of the
# browser). If something fails, send report.txt (and the folder if asked).
set -uo pipefail
cd "$(dirname "$0")/.."

SITES=0
[ "${1:-}" = "--sites" ] && SITES=1
OUT=${PI_CHECK_OUT:-$HOME/javachess-browser-check-$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"
REPORT="$OUT/report.txt"
FAILS=0
STEP_TIMEOUT=${PI_CHECK_TIMEOUT:-240}   # seconds per app run (the first one also downloads ~150 MB)

say() { echo "$*" | tee -a "$REPORT"; }
pass() { say "PASS  $*"; }
fail() { say "FAIL  $*"; FAILS=$((FAILS + 1)); }
info() { say "      $*"; }

say "javaChess integrated browser check - $(date)"
say "folder: $OUT"
say ""

# ------------------------------------------------------------------ 1. the system
say "1. System"
ARCH=$(uname -m)
MODEL=$(tr -d '\0' < /proc/device-tree/model 2>/dev/null || echo "unknown model")
info "$MODEL, $(uname -sr), $ARCH"
[ -f /etc/os-release ] && info "$(. /etc/os-release && echo "$PRETTY_NAME")"
case "$ARCH" in
  aarch64|x86_64) pass "processor $ARCH is supported by the browser engine" ;;
  *) fail "processor $ARCH: the browser engine exists only for arm64 and x86_64" ;;
esac
if [ "$ARCH" = aarch64 ] && [ "$(getconf LONG_BIT)" != 64 ]; then
  fail "32-bit system: install Raspberry Pi OS 64-bit"
fi
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
JV=$("$JAVA" -version 2>&1 | head -1)
if echo "$JV" | grep -Eq '"(2[1-9]|[3-9][0-9])'; then pass "Java: $JV"; else fail "Java 21 or newer needed (found: ${JV:-none})"; fi
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
AVAIL_MB=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
info "memory: ${MEM_MB} MB, available ${AVAIL_MB} MB"
[ "$AVAIL_MB" -ge 1500 ] && pass "enough free memory for the browser (>= 1.5 GB)" \
  || fail "only ${AVAIL_MB} MB free: close other programs (the browser needs ~1-1.5 GB)"
DISK_MB=$(df -Pm "$HOME" | awk 'NR==2 {print $4}')
BUNDLE_NOW=$(ls -d "$HOME"/.jcef-bundle-*/libcef.so 2>/dev/null | head -1 | xargs -r dirname)
if [ -n "$BUNDLE_NOW" ]; then
  info "browser engine already on disk: $BUNDLE_NOW"
elif [ "${DISK_MB:-0}" -ge 1200 ]; then
  pass "free disk ${DISK_MB} MB (the engine needs ~450 MB)"
else
  fail "free disk ${DISK_MB:-?} MB: the browser engine needs ~450 MB plus room to unpack"
fi
export DISPLAY=${DISPLAY:-:0}
SCREEN_OK=1
if command -v xdpyinfo >/dev/null 2>&1; then
  if xdpyinfo >/dev/null 2>&1; then
    pass "screen $DISPLAY: $(xdpyinfo | awk '/dimensions/ {print $2}')"
  else
    fail "no screen on $DISPLAY (run it on the Pi's desktop session, or set DISPLAY)"
    SCREEN_OK=0
  fi
else
  info "screen $DISPLAY (xdpyinfo not installed: not checked)"
fi
info "session: ${XDG_SESSION_TYPE:-unknown}${WAYLAND_DISPLAY:+ (Wayland $WAYLAND_DISPLAY, the app uses XWayland)}"
if command -v curl >/dev/null 2>&1; then
  for host in https://repo.maven.apache.org https://lichess.org https://www.chess.com; do
    code=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "$host" || true)
    [ "${code:-000}" != 000 ] && pass "network: $host answers ($code)" || fail "network: $host does not answer"
  done
fi
JAR=$(ls -t javaChess*.jar target/javaChess*.jar 2>/dev/null | grep -v original | head -1 || true)
[ -n "$JAR" ] && pass "app: $JAR" || fail "no javaChess jar here: build it (./mvnw clean -Ppi -DskipTests package)"
say ""
if [ -z "$JAR" ] || [ "$SCREEN_OK" = 0 ]; then
  say "Stopping: the next steps need $([ -z "$JAR" ] && echo "the app" || echo "a screen")."
  exit 1
fi

# ------------------------------------------------------------------ helpers
HOME_DIR="$OUT/home"     # temporary data folder: the user's ~/.javachess is never used
mkdir -p "$HOME_DIR"
PEAK_MB=0

# total memory (RSS) of the app and its Chromium processes
tree_rss_mb() {
  local pid=$1 pids
  pids=$(ps -e -o pid=,ppid= | awk -v root="$pid" '{parent[$1]=$2} END {
    for (p in parent) { q = p; while (q != "" && q != 0 && q != 1) { if (q == root) { print p; break } q = parent[q] } } }')
  [ -z "$pids" ] && { echo 0; return; }
  ps -o rss= -p $(echo $pids | tr ' ' ',') 2>/dev/null | awk '{s += $1} END {print int(s / 1024)}'
}

# run_app NAME URL WAIT_REGEX: starts the app on the browser screen, waits for a status line, takes pictures
run_app() {
  local name=$1 url=$2 want=$3 log="$OUT/$1.log" pid start t status
  rm -f "$OUT/$name"-*.png
  JAVACHESS_OPTS="-Djavachess.home=$HOME_DIR -Djavachess.view=BROWSER -Djavachess.browserUrl=$url \
-Djavachess.board=sim -Djavachess.browser.snapshot=$OUT/$name-1.png,$OUT/$name-2.png \
-Djavachess.browser.snapshot.delayMs=12000 -Djavachess.snapshot.exit=true ${PI_CHECK_OPTS:-}" \
    ./run_pi.sh > "$log" 2>&1 &
  pid=$!
  start=$(date +%s)
  status=""
  while kill -0 "$pid" 2>/dev/null; do
    t=$(( $(date +%s) - start ))
    mb=$(tree_rss_mb "$pid"); [ "${mb:-0}" -gt "$PEAK_MB" ] && PEAK_MB=$mb
    if grep -q "Browser status: RESTART_REQUIRED" "$log"; then status=restart; break; fi
    if grep -qE "Browser status: (UNAVAILABLE|OFFLINE|SITE_UNREACHABLE)" "$log"; then status=failed; break; fi
    if grep -qE "Exception in Application start|Unable to open DISPLAY|Error initializing QuantumRenderer|OutOfMemoryError" "$log"; then
      status=appfailed; break
    fi
    if [ "$t" -ge "$STEP_TIMEOUT" ]; then status=timeout; break; fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null; sleep 3; kill -9 "$pid" 2>/dev/null
  fi
  wait "$pid" 2>/dev/null; local code=$?
  [ -z "$status" ] && status=exited
  RUN_STATUS=$status RUN_CODE=$code RUN_SECONDS=$(( $(date +%s) - start ))
  RUN_SEEN=$(grep -oE "Browser status: [A-Z_]+" "$log" | awk '{print $3}' | uniq | tr '\n' ' ')
  RUN_WANTED=$(grep -cE "Browser status: ($want)" "$log")
  if ls hs_err_pid*.log >/dev/null 2>&1 || grep -q "SIGSEGV\|SIGABRT\|A fatal error has been detected" "$log"; then
    RUN_CRASH=1; mv hs_err_pid*.log "$OUT/" 2>/dev/null
  else
    RUN_CRASH=0
  fi
}

# ------------------------------------------------------------------ 2. the browser engine
say "2. Browser engine (first start downloads it, then the app restarts)"
TEST_PAGE="$(pwd)/src/test/resources/browser/e2e/board.html"
if [ -f "$TEST_PAGE" ]; then
  LOCAL_URL="file://$TEST_PAGE?site=lichess"
else
  LOCAL_URL="https://lichess.org/analysis"
  info "test page not found (no sources here): using lichess.org/analysis instead"
fi
run_app start "$LOCAL_URL" "SETUP|YOUR_TURN|OPPONENT_TURN|BOARD_FOUND"
info "statuses: $RUN_SEEN(${RUN_SECONDS} s)"
if [ "$RUN_STATUS" = restart ]; then
  pass "engine downloaded and installed: the app asks for a restart (expected the first time on the Pi)"
  grep -m1 "Starting the integrated browser" "$OUT/start.log" | sed 's/^/      /' | tee -a "$REPORT"
  run_app start2 "$LOCAL_URL" "SETUP|YOUR_TURN|OPPONENT_TURN|BOARD_FOUND"
  info "after the restart: $RUN_SEEN(${RUN_SECONDS} s)"
  [ "$RUN_STATUS" = restart ] && fail "still asks for a restart: libcef.so is not preloaded (see start2.log, run_pi.sh)"
fi
[ "$RUN_STATUS" = failed ] && fail "the browser could not start: $(grep -m1 -E 'Cannot start|Browser status: (UNAVAILABLE|OFFLINE|SITE_UNREACHABLE)' "$OUT"/start*.log | cut -c1-200)"
[ "$RUN_STATUS" = appfailed ] && fail "the app itself did not start: $(grep -m1 -E 'Exception in Application start|Unable to open DISPLAY|Error initializing QuantumRenderer|OutOfMemoryError' "$OUT"/start*.log | cut -c1-200)"
[ "$RUN_STATUS" = timeout ] && fail "no answer within ${STEP_TIMEOUT} s (slow network? PI_CHECK_TIMEOUT=600 to wait longer)"
[ "$RUN_CRASH" = 1 ] && fail "the app crashed (hs_err files copied to the folder)"
LOCAL_OK=0
if [ "$RUN_STATUS" != restart ] && [ "$RUN_WANTED" -gt 0 ]; then
  pass "Chromium started and showed the page (exit status $RUN_CODE)"
  LOCAL_OK=1
fi
say ""

# ------------------------------------------------------------------ 3. reading a board, synchronising
say "3. Reading the board on the test page"
if [ "$LOCAL_OK" = 1 ]; then
  LAST=$(ls -t "$OUT"/start*.log | head -1)
  grep -q "Browser status: SETUP\|Browser status: YOUR_TURN" "$LAST" \
    && pass "the position was read and the (simulated) board set up: $(grep -oE 'Board setup: [^ ]+' "$LAST" | head -1)" \
    || fail "the page opened but its board was not read (see $(basename "$LAST"))"
  grep -q "Vision calibrated\|Vision learned" "$LAST" && pass "vision learned the board's pieces" \
    || info "vision did not calibrate (only used as a cross-check by default)"
  ls "$OUT"/start*-2.png >/dev/null 2>&1 && pass "pictures of the browser: $(cd "$OUT" && ls start*-*.png | tr '\n' ' ')" \
    || fail "no picture of the browser window was written"
  [ "$RUN_CODE" = 0 ] && pass "the app closed cleanly" || fail "the app ended with status $RUN_CODE"
else
  info "skipped (the browser did not start)"
fi
say ""

# ------------------------------------------------------------------ 4. the real sites (optional)
if [ "$SITES" = 1 ] && [ "$LOCAL_OK" = 1 ]; then
  say "4. The real sites (analysis boards, no login)"
  for site in "lichess https://lichess.org/analysis" "chesscom https://www.chess.com/analysis"; do
    set -- $site
    run_app "$1" "$2" "SETUP|YOUR_TURN|OPPONENT_TURN|BOARD_FOUND"
    info "$1: $RUN_SEEN(${RUN_SECONDS} s)"
    if [ "$RUN_WANTED" -gt 0 ] && [ "$RUN_CRASH" = 0 ]; then
      pass "$1: page opened and its board read"
    elif grep -q "Browser status: VERIFY" "$OUT/$1.log"; then
      fail "$1: the site asked for a human check (CAPTCHA): open it by hand once in the app and tick the box"
    else
      fail "$1: the board was not read (see $1.log and $1-*.png)"
    fi
  done
  say ""
fi

# ------------------------------------------------------------------ summary
say "Memory: peak ${PEAK_MB} MB for the app and Chromium together (of ${MEM_MB} MB)"
[ "$PEAK_MB" -gt 0 ] && [ "$PEAK_MB" -gt $((MEM_MB * 70 / 100)) ] && fail "the browser used more than 70% of the memory"
say ""
if [ "$FAILS" = 0 ]; then
  say "RESULT: PASS - the integrated browser works on this computer."
else
  say "RESULT: $FAILS problem(s). Send $REPORT (and the folder $OUT if asked)."
fi
[ "$FAILS" = 0 ]
