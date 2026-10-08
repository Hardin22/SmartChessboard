# Running javaChess on a Raspberry Pi

Target: Raspberry Pi 4 or 5 (arm64, 4 GB or more), Raspberry Pi OS Bookworm 64-bit with the desktop,
the 720x1920 board monitor, the Arduino board on USB.

## 1. System

```bash
sudo apt update
sudo apt install -y stockfish zstd unclutter     # engine, puzzle unpacking, hide the mouse pointer
sudo usermod -aG dialout "$USER"                  # access to /dev/ttyACM0 (log out and in again)
```

Java 21 (Bookworm ships 17): install Temurin 21 from the Adoptium apt repository (this is what the
Pi box below uses, see `docker/pi-sim/Dockerfile`):

```bash
sudo apt install -y wget gpg
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-21-jdk
```

or unpack the aarch64 tarball from adoptium.net and set `JAVA_HOME` (the scripts honour it). JavaFX is
inside the app jar, no separate install. Engines: `scripts/install-engines.sh` (official Stockfish arm64
build, checked) instead of the older apt package.

Integrated browser (chess.com / Lichess pages): on the first use it downloads its Chromium bundle
(~150 MB download, ~450 MB in `~/.jcef-bundle-<version>`); restart the app once afterwards (the app offers the
button), because on arm64 `run_pi.sh` must preload `libcef.so`. Raspberry Pi OS *desktop* already has the libraries
it needs; on *Lite* install `libnss3 libatk-bridge2.0-0 libcups2 libxkbcommon0 libxcomposite1 libxdamage1 libxrandr2
libgbm1`. `scripts/pi-check-browser.sh` checks all of it on the Pi and writes a report (see docs/browser.md).

Display: set the monitor orientation in *Screen Configuration* (or `wlr-randr --output HDMI-A-1 --transform 90`).
The app uses the whole screen and also works on a landscape 1920x720 or a desktop window.

## 2. Build and install

On the Pi, or in the Pi box (section 8), Maven picks the Linux arm64 JavaFX natives by itself. On a
Mac/PC the dependencies without classifier would bring the *build machine's* JavaFX natives (a jar built
on a Mac contains `libglass.dylib` and does not start on the Pi): `-Djavafx.platform=linux-aarch64` is
required there, and so is `clean` when switching platform (the shade plugin otherwise merges the previous
jar: the result carries both natives). `run_pi.sh` refuses a jar without Linux JavaFX libraries with a
clear message instead of JavaFX's "no suitable pipeline found".

```bash
./mvnw clean -Ppi -Djavafx.platform=linux-aarch64 -DskipTests package
mkdir -p ~/javachess && cp target/javaChess-1.0-SNAPSHOT.jar run_pi.sh ~/javachess/
```

The `pi` profile leaves out the OpenCV/ONNX native libraries of other platforms: the jar goes from
235 MB to 68 MB. Datasets are never packaged (`src/main/resources/data/**` is excluded from the build).

## 3. Puzzle database

Puzzles come from the Lichess database and live outside the jar:

```bash
cd ~/javachess
scripts/build-puzzle-db.sh --download data/puzzles.db     # or: scripts/build-puzzle-db.sh lichess_db_puzzle.csv.zst data/puzzles.db
```

(copy `scripts/build-puzzle-db.sh` next to the jar, or build the file on a faster machine and copy
`puzzles.db`; the format is the same everywhere). The app looks in `-Djavachess.puzzles=<file>`,
`data/puzzles.db`, then `~/.javachess/puzzles.db`.

| | old (`puzzles.csv` in the jar + `puzzles.ser`) | new (`puzzles.db`) |
|---|---|---|
| Disk | 1.05 GB CSV inside resources/jar + 360 MB index | 410 MB, outside the jar |
| Heap | CSV read line by line on every search; index ~hundreds of MB if loaded | ~10 MB (memory-mapped file) |
| Search "fork, 1500±200" | 50 ms | 0.05 ms |
| Search for a rare theme (bodenMate) | 17 s on the UI thread | 0.2 ms, off the UI thread |

`--min-popularity 0` keeps only well-liked puzzles and makes the file smaller. Without the database
the app still works from `data/puzzles.csv` (slower sampling).

## 4. Start

```bash
~/javachess/run_pi.sh
```

What `run_pi.sh` sets, and why:

| Option | Reason |
|---|---|
| `-Xmx512m -Xms64m` | the app uses ~60-120 MB of heap; puzzles are memory-mapped outside the heap |
| `-XX:+UseSerialGC` | smallest footprint and CPU overhead with a small heap; young pauses of a few ms |
| full tiered JIT (no `TieredStopAtLevel=1`) | the kiosk runs for hours: C2 pays back; C1-only only helps the first seconds |
| `-XX:SharedArchiveFile=~/.cache/javachess/app-cds.jsa -XX:+AutoCreateSharedArchive` | AppCDS: the first run writes the class archive, next starts load classes from it (on a Mac: first frame 1.30 s → 0.93 s; the gain is larger on the Pi) |
| `-Dprism.order=sw` | software rendering always works on the Pi; try `JAVACHESS_PRISM=es2,sw` for the GPU with automatic fallback |
| `-Djavafx.animation.pulse=60` | `JAVACHESS_FPS=30` halves the CPU spent on animations if needed; the old `javafx.animation.fullspeed=true` kept a core busy and was removed |
| `-XX:+ExitOnOutOfMemoryError` | let systemd restart the app instead of running half-dead |
| `-Djavachess.kiosk=true` | Esc does not leave full screen |
| `GDK_BACKEND=x11` | JavaFX uses GTK through X11/XWayland |

Diagnostics: `JAVACHESS_DEBUG=1 ./run_pi.sh` logs the rendering pipeline, the start-up time and, every
5 s, average/worst frame time and heap (`-Djavachess.metrics=true`).

## 5. Start automatically (kiosk)

```bash
mkdir -p ~/.config/systemd/user
cp deploy/javachess.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now javachess.service
journalctl --user -u javachess -f
```

Enable desktop auto-login in `raspi-config` (System Options → Boot / Auto Login). `unclutter -idle 1 &`
in the session autostart hides the pointer. On exit the app turns the LEDs off, closes the serial port
and stops the engine processes.

## 6. The board

The Arduino is found automatically (`/dev/ttyACM*`, `/dev/ttyUSB*`), can be unplugged and plugged back
while the app runs, and the app works without it. Firmware, wiring constants and the serial protocol:
[hardware-protocol.md](hardware-protocol.md). To try the app without the board:
`JAVACHESS_OPTS="-Djavachess.board=sim -Djavachess.simulator.window=true" ./run_pi.sh`.

## 7. Measured (MacBook, 720x1920 window on the board monitor)

| | before | after |
|---|---|---|
| First frame after JVM start (3 runs) | 1.64-2.04 s | 1.08-1.11 s; 0.84-1.34 s with all branches merged; 0.93 s with AppCDS |
| First frame with Pi-like flags (sw rendering, 4 CPUs, 512 MB, SerialGC, C1) | 1.52-1.71 s | 0.69-1.04 s |
| All main views ready | before the first frame (blocked it) | 2.1-2.6 s, built one per idle slot |
| Heap 5 s after start | 53 MB | 52 MB (no engine started at boot any more) |
| Worst frame during the first 5 s | 18-33 ms | 33-40 ms (idle preloading) |
| Exit (Stopping → JVM gone) | engines and timers left running, `System.exit` | ~10 ms, engines quit over UCI, no non-daemon thread left |
| LED event → serial frame (simulator) | 10 ms sleeps per LED, 70 ms per animation step | 0.13 ms average, 0.17 ms worst |

## 8. Pi box: testing without a Pi

`scripts/pi-sim.sh` runs the app in Docker as close to Raspberry Pi OS 64-bit as a Mac allows:
`linux/arm64` Debian bookworm (the base of Raspberry Pi OS), Temurin 21, Xvfb as the 720x1920 monitor,
software rendering, `--cpus=4 --memory=1g` (a small Pi 4 is the realistic worst case), the app started by
`run_pi.sh` with the jar built inside the box. Needs Docker with arm64 (Apple Silicon + OrbStack/Docker
Desktop, or an arm64 Linux host).

```bash
scripts/pi-sim.sh image          # once (~3 min)
scripts/pi-sim.sh sync           # copy the working tree into the box (no datasets, no config.properties)
scripts/pi-sim.sh package        # Pi jar built inside the box (~2 min the first time)
scripts/pi-sim.sh engines        # install-engines.sh + Stockfish nodes/s with 1-4 threads
scripts/pi-sim.sh test           # the whole test suite on linux/arm64
scripts/pi-sim.sh run -Djavachess.board=sim -Djavachess.devgame=pvc -Djavachess.sim.autoplay=10 \
    -Djavachess.snapshot=/out/game.png -Djavachess.snapshot.delayMs=30000 -Djavachess.snapshot.exit=true
scripts/pi-sim.sh all            # image + sync + package + HOME screenshot in $PI_SIM_OUT (/tmp/pi-sim)
```

What it is not: no GPU (V3D), no USB serial, and the CPU cores are the host's (several times faster than
a Cortex-A72/A76), so times are lower bounds; memory and Linux behaviour are realistic.

Verified in the box (7 Oct 2026):

| Check | Result |
|---|---|
| Natives in the Pi jar | JavaFX gtk3/prism_sw/es2 for Linux arm64, OpenCV `linux/ARMv8` (loads in 109 ms), ONNX Runtime `linux-aarch64` (77 ms), jSerialComm `Linux/armv8_64` |
| JCEF browser | `jcef-natives-linux-arm64` exists for 127.3.1; works (Lichess loads) with Chromium's libraries and `libcef.so` preloaded |
| First frame (run_pi.sh, AppCDS warm) | 0.59-0.95 s; window sized to the full 720x1920 screen even without a window manager |
| Bot game on the simulated board, 10 moves | moves detected, bot replies replicated, clean exit (no thread or process left) |
| Frames, new UI | HOME and game: ~46 fps, 21 ms average (mostly Xvfb copying 720x1920), worst 39-45 ms; old UI worst 53-213 ms |
| Memory during a bot game | container 630-740 MB of 1 GB: JVM ~400 MB RSS (heap in use 40-100 MB), each Stockfish process 290-380 MB (NNUE nets), Xvfb 85 MB; with the browser open ~930 MB |
| Stockfish (sf_19 arm64, host cores) | 1.08 M nps 1 thread, 1.90 M 2, 2.39 M 3, 3.33 M 4 |
| Tests (linux/arm64) | 225 run, 0 failures, 1 skipped (benchmark, only with `-Dbench=true`) |

Bugs this found and fixed: piece images referenced with the wrong case (`wK.png` vs `wk.png`, invisible on
macOS), window 720x1280 on a 720x1920 screen without a window manager, JCEF static-TLS failure on arm64,
two engine tests that failed when run with the whole suite.
