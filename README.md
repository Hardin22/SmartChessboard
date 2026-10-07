<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo/javachess-logo-dark.svg">
    <img alt="javaChess" src="docs/logo/javachess-logo-light.svg" width="320">
  </picture>
</p>

<p align="center">
  <b>Software for a DIY smart chessboard</b> — play on a real board against Stockfish, Maia or people online,
  with sensors under every square and an LED that lights each one.
</p>

<p align="center">
  <a href="https://github.com/Hardin22/SmartChessboard/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/Hardin22/SmartChessboard/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: GPL v3" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/java-21-orange.svg">
</p>

javaChess is a JavaFX application that runs on a Raspberry Pi built into a chessboard. 64 Hall-effect sensors read
where the pieces are, 64 RGB LEDs show moves, hints and the opponent's replies, and a tall touch screen beside the
board shows the clocks, the evaluation and the controls. It also runs on a normal desktop (macOS, Linux) without any
hardware, which is how most development happens.

| Home | Game | Review | Puzzles |
|---|---|---|---|
| ![Home](docs/screenshots/home-dark.png) | ![Game](docs/screenshots/game-dark.png) | ![Review](docs/screenshots/review-dark.png) | ![Puzzles](docs/screenshots/puzzles-dark.png) |

## Features

- **Play the computer**: Stockfish (skill 0–20, adjustable thinking time) or the human-like
  [Maia](https://maiachess.com) networks (1100, 1500, 1900 Elo) through lc0.
- **Two players** on the same board, with clocks and increments.
- **Lichess online** through the official [Board API](https://lichess.org/api#tag/Board): seek a game, play it with the
  physical pieces; the opponent's moves light up on the board for you to replicate.
- **chess.com and lichess in the integrated browser** (JCEF): the screen is read by an on-device vision model, so the
  physical board stays in sync with games played on the website.
- **Puzzles** from the Lichess puzzle database, filtered by theme and rating.
- **Game review**: accuracy for both sides, move classification (best, excellent, inaccuracy, mistake, blunder...),
  evaluation graph, best-move arrows.
- **Archive** of every game, stored as standard PGN data that can be exported and imported.
- **LED coaching**: legal moves when a piece is lifted, quality of the destination squares, check and mate effects.
- **Themes** for board and pieces; works on a 720×1920 portrait display, a 1920×720 landscape one, or a desktop window.

## Hardware

The board is a grid of 64 Hall sensors read through four 16-channel multiplexers by an Arduino, which also drives a
WS2812B LED strip. The Arduino talks to the Raspberry Pi over USB serial at 115200 baud. The custom PCB is being
manufactured; the prototype below uses off-the-shelf modules.

### Bill of materials (indicative)

| Qty | Part | Notes |
|---|---|---|
| 1 | Raspberry Pi 4 or 5 (4–8 GB) | Runs the app. A desktop computer works too. |
| 1 | Display, 720×1920 (e.g. 8.8" IPS bar) or any HDMI screen | Touch is optional (mouse works). |
| 1 | Arduino Uno / Nano (ATmega328) | Firmware in [`arduinoscript.ino`](arduinoscript.ino). |
| 4 | CD74HC4067 16-channel analog multiplexer | One per 16 squares. |
| 64 | A3144 (or similar) unipolar Hall-effect sensor | Active low; the Arduino enables internal pull-ups. |
| 32 | Neodymium magnet, ~6×3 mm | One glued under each piece (same pole facing down). |
| 64 | WS2812B LED (strip, 30 or 60/m, or single pixels) | Wired in a "snake" from A1 to H1, then H2 to A2... |
| 1 | 5 V power supply, ≥ 4 A | 64 LEDs at full white draw ~3.8 A; the firmware caps brightness. |
| 1 | 330 Ω resistor, 1000 µF capacitor | On the LED data line / across the LED supply. |
| 1 | USB cable Arduino ↔ Raspberry Pi | Serial link and Arduino power. |

### Wiring

| Arduino pin | Connection |
|---|---|
| D8, D9, D10, D11 | S0, S1, S2, S3 of **all four** multiplexers (shared address lines) |
| D4, D5, D6, D7 | SIG of multiplexer 1, 2, 3, 4 |
| D12 | WS2812B data in (through the 330 Ω resistor) |
| 5 V / GND | Multiplexers, sensors; LEDs from the external 5 V supply with a common ground |

The square-to-channel map is the `mapScacchiera` table at the top of the firmware: multiplexers 1 and 2 cover ranks
1–4, multiplexers 3 and 4 ranks 5–8. Change it there if your board is wired differently.

### Firmware and serial protocol

Flash [`arduinoscript.ino`](arduinoscript.ino) with the Arduino IDE (library: *Adafruit NeoPixel*).
[`LedTest/`](LedTest) contains two sketches to check the LED strip on its own.

| Direction | Message | Meaning |
|---|---|---|
| Arduino → app | `READY` | Firmware started |
| Arduino → app | `+E4` / `-E4` | A piece was placed on / lifted from E4 |
| app → Arduino | `R` | Re-send the state of every square |
| app → Arduino | `L:E4:R:G:B` | Set the LED of E4 and show it |
| app → Arduino | `P:E4:R:G:B`, then `S` | Prepare several LEDs, then show them together |
| app → Arduino | `C` | Turn every LED off |

Without the board connected the app still starts and works on screen; for development a simulated board is
available (`-Djavachess.board=sim -Djavachess.simulator.window=true`).

## Installation

You need **Java 21** and **Stockfish**. Maia bots additionally need **lc0** (the network weights are in
`engines/maia/`).

### macOS

```bash
brew install openjdk@21 stockfish        # optional: brew install lc0
git clone https://github.com/Hardin22/SmartChessboard.git && cd SmartChessboard
./mvnw -DskipTests package
java -jar target/javaChess-1.0-SNAPSHOT.jar
```

### Linux / Raspberry Pi OS (64-bit)

```bash
sudo apt install openjdk-21-jdk stockfish git
sudo usermod -aG dialout "$USER"   # access to the Arduino serial port (log out and in again)
git clone https://github.com/Hardin22/SmartChessboard.git && cd SmartChessboard
./mvnw -DskipTests package         # build on the Pi itself: JavaFX jars are platform specific
./run_pi.sh                        # software rendering, 512 MB heap, full screen
```

Point the app at your engines in `~/.javachess/config.properties` if they are not in the default locations
(`stockfish.path=/usr/games/stockfish`, `lc0.path=/usr/local/bin/lc0`).

For a kiosk setup, start `run_pi.sh` from the desktop autostart (`~/.config/autostart/javachess.desktop`) and rotate
the display in *Screen Configuration* for a portrait monitor.

### Puzzles

Download the puzzle database from [database.lichess.org](https://database.lichess.org/#puzzles)
(`lichess_db_puzzle.csv.zst`, CC0), decompress it and put it in the `data/` folder next to the application.
The file is large (~1 GB uncompressed) and is never committed to the repository.

## Configuration

Everything the app stores lives in **`~/.javachess/`** (override with `-Djavachess.home=/path` or the
`JAVACHESS_HOME` environment variable):

| File / folder | Content |
|---|---|
| `config.properties` | Settings (mode 600: it can contain your Lichess token) |
| `archive.json` | Game archive (versioned JSON, every game also stored as PGN) |
| `backups/` | Automatic copies of the archive and of damaged files |
| `logs/javachess.log` | Log file, rotated daily (attach it to bug reports) |
| `jcef-cache/` | Integrated browser profile (cookies, chess.com / lichess login) |
| `cookies.json` | Cookies of the app's own HTTP requests |

Settings and archive written by older versions in the working directory are migrated automatically on first start
(the old archive is left untouched).

Most settings are changed from the *Settings* screen. Useful keys:

| Key | Default | Meaning |
|---|---|---|
| `stockfish.path`, `lc0.path` | platform dependent | Engine executables |
| `stockfish.threads`, `stockfish.hash` | 2, 64 | Engine resources (1–256 threads, MB of hash) |
| `game.bot.level` | 10 | Stockfish skill level (0–20) |
| `game.bot.movetime` | 2000 | Bot thinking time in ms |
| `game.default.duration`, `game.default.increment` | 10, 0 | Default clock for two-player games (minutes, seconds) |
| `game.depth`, `analysis.depth`, `move.eval.depth` | 18, 12, 8 | Search depth during games, review and LED hints |
| `game.evaluation`, `game.suggestions` | true | Show evaluation bar / best-move arrows |
| `hardware.led.brightness` | 100 | LED brightness in % |
| `lichess.username`, `lichess.token` | — | Lichess account and API token |

### Lichess

Use *Settings → Lichess → Connect account*: the app opens the Lichess authorization page (OAuth with PKCE, no
password involved) and stores the resulting token. Alternatively, create a personal API token with the
**`board:play`** scope at
<https://lichess.org/account/oauth/token/create?scopes[]=board:play&description=javaChess> and paste it in
*Settings → Lichess*. It is stored only in `~/.javachess/config.properties` (owner-only permissions) and never logged.
For development you can also export `JAVACHESS_LICHESS_TOKEN`. The Board API only allows rapid and classical time
controls (at least 8 minutes).

### chess.com

There is no public API for playing on chess.com: open it from the home screen and log in once in the integrated
browser. The session is kept in `~/.javachess/jcef-cache`. **Passwords are never stored by javaChess.**

## Development

```bash
./mvnw test                     # unit tests (engine tests are skipped when Stockfish is not installed)
./mvnw -DskipTests package      # runnable jar in target/
scripts/dev-run.sh              # run from the build directory, with developer switches:
scripts/dev-run.sh -Djavachess.windowed=1920x720 -Djavachess.view=REVIEW
scripts/dev-run.sh -Djavachess.screen=1 -Djavachess.snapshot=/tmp/home.png -Djavachess.snapshot.exit=true
```

| Switch | Effect |
|---|---|
| `-Djavachess.screen=N` | Open on screen N (0 = primary) |
| `-Djavachess.windowed=WxH` | Window of the given size instead of full screen |
| `-Djavachess.view=NAME` | Start on a view (HOME, PVC_SETUP, GAME, REVIEW, ARCHIVE, SETTINGS, ...) |
| `-Djavachess.snapshot=file.png` | Save a screenshot of the window after `javachess.snapshot.delayMs` ms |
| `-Djavachess.home=DIR` | Use another data folder (handy to test the first-start migration) |
| `-Djavachess.log.level=DEBUG` | More logging |
| `-Djavachess.vision.debug=true` | Write annotated vision frames to `~/.javachess/vision-debug/` |
| `-Djavachess.board=sim` | Simulated sensor board (add `-Djavachess.simulator.window=true` to show it) |

### Architecture

```
org.example.javachess
├── Application   entry point (App, Main), start-up (Bootstrap), developer switches (DevOptions)
├── Controllers   JavaFX controllers, one per FXML view in src/main/resources/UI; ArduinoController (serial)
├── Oggetti       game model and board widgets: AbstractGame → PvcGame, PvpGame, OnlineGame, PuzzleGame;
│                 ChessBoardUI, EvalBar, ArchivedGame, UCIEngine (UCI protocol)
├── Engine        engine selection contract used by the UI (EngineSelection, EngineProfile)
├── Services      EngineService, GameAnalyzer (review/accuracy), BoardStateManager (physical board state machine),
│                 GameArchiveService (archive + PGN), LichessClient / LichessGameManager (Board API),
│                 PuzzleService, VisionService (screen reading loop)
├── Vision        PieceClassifier (YOLOv8 ONNX model), BoardReading (per-square probabilities),
│                 PositionResolver (uses legal moves to correct misread squares), BotMover (clicks in the browser)
└── Utils         ConfigManager, AppPaths, AtomicFiles, PgnCodec (UCI/SAN/FEN/PGN), ErrorReporter, ...
```

- **Threads**: the JavaFX thread only updates the UI. Engines, serial I/O, network, vision and file writes run on
  background threads and report back with `Platform.runLater`.
- **Physical board**: `BoardStateManager` turns `+E4`/`-E4` events into moves, checks them against the rules
  (chesslib) and drives the LEDs for set-up, hints and the opponent's replies.
- **Vision**: the browser view is captured, the YOLOv8 model detects pieces, each square gets a probability for each
  piece, and `PositionResolver` picks the legal move that best explains the picture. Tests measure it on a real
  lichess screenshot and on synthetic boards in several themes and lighting conditions.
- **Data**: settings and archive are written atomically (temporary file + rename) with automatic backups.

The Java package is still `org.example.javachess` for historical reasons.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the [code of conduct](CODE_OF_CONDUCT.md).
Security issues: see [SECURITY.md](SECURITY.md).

Known issues and ideas:
- In online games played from Black's side, the board orientation and some labels are still from White's point of view.
- After a checkmate by Black the evaluation bar can jump before settling (the graph in the review is correct).

## License

javaChess is free software, released under the **GNU General Public License v3.0 or later** ([LICENSE](LICENSE)).
GPL-3.0 was chosen because the app is built around Stockfish, lc0 and the Maia networks (all GPL-3.0) and must stay
compatible with them, while all the libraries it links (Apache-2.0, MIT, BSD, EPL/LGPL, OFL, public domain) can be
combined with GPL-3.0 code.

Third-party components:

| Component | License |
|---|---|
| [chesslib](https://github.com/bhlangonijr/chesslib) | Apache-2.0 |
| OpenJFX | GPL-2.0 with Classpath Exception |
| [jcefmaven](https://github.com/jcefmaven/jcefmaven) / CEF / Chromium | Apache-2.0 / BSD-3-Clause |
| OpenCV (openpnp build), ONNX Runtime | Apache-2.0, MIT |
| Logback, SLF4J | EPL-1.0 / LGPL-2.1, MIT |
| jSerialComm, Ikonli, Unirest, org.json | Apache-2.0 / LGPL-3.0, Apache-2.0, MIT, public domain |
| Stockfish, lc0, Maia weights (`engines/maia/`) | GPL-3.0 |
| Vision model `src/main/resources/models/best.onnx` | trained with Ultralytics YOLOv8 (AGPL-3.0); dataset "2D Chessboard and Chess Pieces" (Roboflow Universe) |
| Lichess puzzle database | CC0 |
| Fonts (`src/main/resources/Font/`) | SIL Open Font License 1.1 |
