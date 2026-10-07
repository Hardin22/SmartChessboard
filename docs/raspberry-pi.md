# Running javaChess on a Raspberry Pi

Target: Raspberry Pi 4 or 5 (arm64, 4 GB or more), Raspberry Pi OS Bookworm 64-bit with the desktop,
the 720x1920 board monitor, the Arduino board on USB.

## 1. System

```bash
sudo apt update
sudo apt install -y stockfish zstd unclutter     # engine, puzzle unpacking, hide the mouse pointer
sudo usermod -aG dialout "$USER"                  # access to /dev/ttyACM0 (log out and in again)
```

Java 21 (Bookworm ships 17): download the Temurin 21 JDK or JRE for *aarch64 Linux* from
adoptium.net, unpack it in `/opt/jdk-21` and either put `/opt/jdk-21/bin` on the `PATH` or set
`JAVA_HOME=/opt/jdk-21` (the scripts honour it). JavaFX is inside the app jar, no separate install.

Display: set the monitor orientation in *Screen Configuration* (or `wlr-randr --output HDMI-A-1 --transform 90`).
The app uses the whole screen and also works on a landscape 1920x720 or a desktop window.

## 2. Build and install

On the Pi (or on a Mac/PC for the Pi, `-Djavafx.platform` selects the JavaFX natives):

```bash
./mvnw -Ppi -Djavafx.platform=linux-aarch64 -DskipTests package
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
