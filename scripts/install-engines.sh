#!/usr/bin/env bash
# Installs the chess engines used by javaChess into ./engines (no root needed except for apt fallbacks).
#
#   scripts/install-engines.sh            # Stockfish (latest official release)
#   scripts/install-engines.sh --lc0      # + lc0 built from source, for the Maia profiles (weights are in engines/maia)
#   scripts/install-engines.sh --force    # reinstall even if a working engine is already there
#
# Stockfish, in order of preference:
#   1. latest official GitHub release, asset "stockfish-linux-<arch>-universal.tar.gz" (Stockfish 19+ ships
#      universal binaries that pick the best code path, e.g. dotprod on the Pi 5), sha256 checked against
#      the digest published by the GitHub API;
#   2. Debian / Raspberry Pi OS package (apt, installs /usr/games/stockfish: older but works);
#   3. build from source (make profile-build, ARCH=armv8-dotprod on Pi 5 / armv8 on Pi 4).
# Every candidate must answer "uciok" before it is accepted.
#
# The app finds engines in this order: stockfish.path / lc0.path in config.properties -> engines/ -> PATH.
set -euo pipefail

cd "$(dirname "$0")/.."
ENGINES_DIR="$PWD/engines"
FORCE=0
WITH_LC0=0
for a in "$@"; do
  case "$a" in
    --force) FORCE=1 ;;
    --lc0) WITH_LC0=1 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;34m[engines]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[engines]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[engines]\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1; }

SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# uci_ok <binary>: true if the binary speaks UCI
uci_ok() {
  [ -x "$1" ] || return 1
  printf 'uci\nquit\n' | timeout 20 "$1" 2>/dev/null | grep -q '^uciok'
}

os="$(uname -s)"; arch="$(uname -m)"
case "$arch" in
  aarch64|arm64) SF_ARCH=arm64 ;;
  x86_64|amd64) SF_ARCH=x86-64 ;;
  riscv64) SF_ARCH=riscv64 ;;
  *) SF_ARCH="" ;;
esac
case "$os" in
  Linux) SF_OS=linux ;;
  Darwin) SF_OS=macos ;;
  *) SF_OS="" ;;
esac

pi_model="$(tr -d '\0' 2>/dev/null </proc/device-tree/model || true)"
[ -n "$pi_model" ] && log "board: $pi_model"
log "system: $os $arch"

# ---------------------------------------------------------------------------------------------
install_stockfish_release() {
  need curl || { warn "curl missing"; return 1; }
  need tar || { warn "tar missing"; return 1; }
  [ -n "$SF_OS" ] && [ -n "$SF_ARCH" ] || { warn "no official binary for $os/$arch"; return 1; }
  local asset="stockfish-${SF_OS}-${SF_ARCH}-universal.tar.gz"
  [ "$SF_OS" = macos ] && asset="stockfish-macos-universal.tar.gz"
  log "querying the latest Stockfish release on GitHub..."
  local api="$TMP/release.json"
  curl -fsSL --retry 3 -H 'Accept: application/vnd.github+json' \
    https://api.github.com/repos/official-stockfish/Stockfish/releases/latest -o "$api" || { warn "GitHub API unreachable"; return 1; }
  local meta
  meta="$(python3 - "$api" "$asset" <<'PY' 2>/dev/null || true
import json, sys
d = json.load(open(sys.argv[1]))
for a in d.get("assets", []):
    if a["name"] == sys.argv[2]:
        print(d["tag_name"], a["browser_download_url"], (a.get("digest") or "").replace("sha256:", ""))
PY
)"
  if [ -z "$meta" ]; then
    # no python3: fall back to grep
    local url
    url="$(grep -o "https://[^\"]*/${asset}" "$api" | head -1 || true)"
    [ -n "$url" ] || { warn "asset $asset not found in the latest release"; return 1; }
    meta="unknown $url "
  fi
  local tag url sha
  read -r tag url sha <<<"$meta"
  log "downloading $asset ($tag)"
  curl -fL --retry 3 --progress-bar "$url" -o "$TMP/$asset" || { warn "download failed"; return 1; }
  if [ -n "${sha:-}" ]; then
    local got
    got="$( (sha256sum "$TMP/$asset" 2>/dev/null || shasum -a 256 "$TMP/$asset") | awk '{print $1}')"
    [ "$got" = "$sha" ] || { warn "sha256 mismatch: expected $sha got $got"; return 1; }
    log "sha256 verified"
  else
    warn "no digest published for this asset: size/uci checks only"
  fi
  mkdir -p "$TMP/sf"
  tar -xzf "$TMP/$asset" -C "$TMP/sf"
  local bin
  bin="$(find "$TMP/sf" -type f -name 'stockfish*' ! -name '*.nnue' -perm -u+x | head -1)"
  [ -n "$bin" ] || bin="$(find "$TMP/sf" -type f -name 'stockfish-*' | head -1)"
  [ -n "$bin" ] || { warn "no binary in the archive"; return 1; }
  chmod +x "$bin"
  uci_ok "$bin" || { warn "downloaded binary does not run on this machine"; return 1; }
  mkdir -p "$ENGINES_DIR/stockfish"
  cp "$bin" "$ENGINES_DIR/stockfish/stockfish"
  echo "$tag" > "$ENGINES_DIR/stockfish/VERSION"
  log "installed $(printf 'uci\nquit\n' | "$ENGINES_DIR/stockfish/stockfish" | head -1)"
}

install_stockfish_apt() {
  [ "$os" = Linux ] && need apt-get || return 1
  log "trying the distribution package (apt)..."
  $SUDO apt-get update -qq && $SUDO apt-get install -y -qq stockfish || return 1
  local bin
  bin="$(command -v stockfish || echo /usr/games/stockfish)"
  uci_ok "$bin" || return 1
  log "installed $bin ($(printf 'uci\nquit\n' | "$bin" | head -1)); the app finds it on PATH or in /usr/games"
}

install_stockfish_source() {
  need git && need make && (need g++ || need clang++) || { warn "git/make/g++ missing (sudo apt install git build-essential)"; return 1; }
  local sfarch=native
  if [ "$SF_ARCH" = arm64 ]; then
    if grep -qw asimddp /proc/cpuinfo 2>/dev/null; then sfarch=armv8-dotprod; else sfarch=armv8; fi
  fi
  log "building Stockfish from source (ARCH=$sfarch, takes a few minutes on a Pi)..."
  git clone --depth 1 https://github.com/official-stockfish/Stockfish.git "$TMP/Stockfish" || return 1
  (cd "$TMP/Stockfish/src" && make -j"$(nproc 2>/dev/null || echo 4)" profile-build ARCH="$sfarch" >/dev/null) || return 1
  uci_ok "$TMP/Stockfish/src/stockfish" || return 1
  mkdir -p "$ENGINES_DIR/stockfish"
  cp "$TMP/Stockfish/src/stockfish" "$ENGINES_DIR/stockfish/stockfish"
  echo "source-$(cd "$TMP/Stockfish" && git rev-parse --short HEAD)" > "$ENGINES_DIR/stockfish/VERSION"
  log "built and installed engines/stockfish/stockfish"
}

if [ "$FORCE" = 0 ] && uci_ok "$ENGINES_DIR/stockfish/stockfish"; then
  log "Stockfish already installed: $(printf 'uci\nquit\n' | "$ENGINES_DIR/stockfish/stockfish" | head -1) (--force to reinstall)"
else
  install_stockfish_release || install_stockfish_apt || install_stockfish_source \
    || die "could not install Stockfish. Install it manually and set stockfish.path in config.properties."
fi

# ---------------------------------------------------------------------------------------------
# lc0 (optional): needed only by the "Maia" profiles. There are no official Linux binaries,
# so it is built from source with the BLAS CPU backend (OpenBLAS).
install_lc0() {
  if [ "$FORCE" = 0 ] && uci_ok "$ENGINES_DIR/lc0/lc0"; then
    log "lc0 already installed"; return 0
  fi
  if [ "$os" = Darwin ]; then
    need brew && brew install lc0 && log "lc0 installed with Homebrew" && return 0
    return 1
  fi
  if need apt-get; then
    log "installing lc0 build dependencies (sudo)..."
    $SUDO apt-get update -qq
    $SUDO apt-get install -y -qq git ninja-build meson pkg-config g++ libopenblas-dev zlib1g-dev || return 1
  fi
  local tag
  tag="$(curl -fsSL https://api.github.com/repos/LeelaChessZero/lc0/releases/latest | grep -o '"tag_name": *"[^"]*"' | cut -d'"' -f4 || true)"
  [ -n "$tag" ] || tag=master
  log "building lc0 $tag (BLAS backend; 10-20 minutes on a Pi)..."
  git clone --depth 1 --branch "$tag" --recurse-submodules https://github.com/LeelaChessZero/lc0.git "$TMP/lc0" || return 1
  (cd "$TMP/lc0" && ./build.sh release -Dblas=true -Dopenblas=true -Dgtest=false >/dev/null) || return 1
  mkdir -p "$ENGINES_DIR/lc0"
  cp "$TMP/lc0/build/release/lc0" "$ENGINES_DIR/lc0/lc0"
  uci_ok "$ENGINES_DIR/lc0/lc0" || return 1
  echo "$tag" > "$ENGINES_DIR/lc0/VERSION"
  log "installed engines/lc0/lc0"
}

if [ "$WITH_LC0" = 1 ]; then
  install_lc0 || warn "lc0 not installed: the Maia profiles stay disabled (Stockfish profiles work)."
  for elo in 1100 1500 1900; do
    w="$ENGINES_DIR/maia/maia-$elo.pb.gz"
    if [ ! -s "$w" ]; then
      log "downloading Maia $elo weights"
      mkdir -p "$ENGINES_DIR/maia"
      curl -fL --retry 3 -o "$w" \
        "https://github.com/CSSLab/maia-chess/releases/download/v1.0/maia-$elo.pb.gz" || warn "Maia $elo weights not downloaded"
    fi
  done
fi

log "done. Engines in $ENGINES_DIR:"
ls -1 "$ENGINES_DIR"/*/ 2>/dev/null | sed 's/^/  /' || true
