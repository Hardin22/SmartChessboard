# The integrated browser (chess.com and lichess)

The board can follow a game played on **chess.com** or **lichess.org** in the app's integrated browser: the
moves made by hand on the physical board are played on the page, and the moves that appear on the page (the
opponent's) are shown on the board's LEDs for the player to reproduce.

This page explains how to use it, what each message means and what to do when something goes wrong; the second
half describes how it works and how it is tested.

## Using it

1. On the home screen tap **Online** and choose **Chess.com** or **Lichess**. The first time, the browser engine
   is downloaded (see [First start](#first-start)).
2. If the site asks for it, **log in** on the page. The session is kept: next time you are already in. If you
   want, the app can also remember your username and password (see [Saved logins](#saved-logins)).
3. **Open a game** on the site (play online, against the computer, or open the analysis board). As soon as a game
   board is on the page the physical board connects by itself: the bar above the page says
   *Sistema i pezzi* and the LEDs show where pieces are missing or extra.
4. When the pieces match the screen: *Tocca a te* → move a piece on the board; the app plays it on the page.
   *Tocca all'avversario* → wait. When the opponent has moved: *Mossa Cf6 — Ripetila sulla scacchiera: da g8 a f6*
   → move that piece on the board as the LEDs show.
5. **Home** (top left) goes back to the app; a game in progress is saved in the archive as interrupted.

Without the physical board connected the browser works the same: the bar says *Scacchiera non collegata: muovi
sullo schermo* and you play on the screen.

The bar above the page always shows **what is happening** (a coloured title), **what to do** (one sentence) and,
when useful, a **button** (Collega, Scollega, Risincronizza, Ricarica, Mostra scacchiera...). The fixed buttons are
*Home*, *Indietro* (previous page) and *Ricarica* (reload the page).

### Messages and what to do

| On screen | Meaning | What to do |
|---|---|---|
| Avvio del browser… | The browser engine is starting (a few seconds). | Wait. |
| Scarico il browser 42% | First start: the engine (~100 MB) is being downloaded. | Wait; Internet is needed once. |
| Browser installato — Riavvia l'app | Raspberry Pi, first start: the engine is installed and is loaded at the next start. | Tap **Riavvia l'app** (or close and reopen the app). |
| Browser non disponibile | The engine could not start; the sentence says why (no Internet for the first download, disk full, unsupported computer...). | Tap **Riprova** after fixing the cause. Details are in the log. |
| Apro Chess.com… / Il sito è lento | The page is loading. | Wait, or tap **Ricarica**. |
| Nessuna connessione | No network. | Check the Wi-Fi or the cable, then **Ricarica**. |
| Chess.com non risponde | The site is down or not reachable now. | Try again later (**Ricarica**). |
| Verifica di sicurezza | The site wants to check you are a person (CAPTCHA). | Tick the box on the page yourself; the app continues by itself. |
| Accedi a Chess.com | The login page. | Log in on the page (once). |
| Inserisco le credenziali salvate… | The saved login is being typed for you. | Wait. |
| Accesso non riuscito | The saved login was refused (password changed?). | Log in by hand; tap **Dimentica l'accesso** to remove the old one. |
| Ricordare l'accesso? | You logged in by hand. | **Ricorda** saves it on this device; **No, grazie** forgets it. |
| Apri una partita | No game on this page. | Start or open a game on the site. |
| Scacchiera trovata | A board that is not a game (puzzle, TV...). | **Collega** to follow it with the physical board. |
| Leggo la scacchiera… | Reading the position on the page. | Keep the board visible. |
| Sistema i pezzi | The physical board must match the screen. | Place the pieces as the LEDs show. |
| Tocca a te | Your move. | Move on the physical board. |
| Gioco e4… | Your move is being played on the page. | Wait (under a second). |
| Tocca all'avversario | Waiting for the opponent. | Wait. |
| Mossa Cf6 | The opponent (or you, on the screen) moved. | Reproduce it on the board (LEDs). |
| Mossa non accettata | The site did not take your move (not your turn, a popup over the board...). | Put the piece back where it was and try again, or move on the screen. |
| Non leggo bene la scacchiera | The screen shows a position no legal move explains. | Make sure the board is fully visible; **Risincronizza** sets the board up again from the screen. |
| Scacchiera non visibile | The board is scrolled away or covered. | Tap **Mostra scacchiera**, or close what covers it. |
| Scacchiera scollegata | You tapped **Scollega**. | **Collega** to follow the page again. |
| Partita finita: 1-0 | The game is over; real games are saved in the archive. | — |

## First start

The browser engine is Chromium (JCEF). It is not part of the app: on the first use it is downloaded from Maven
Central into `~/.jcef-bundle-<version>` (about 100 MB to download, 270 MB on macOS, 450 MB on the Raspberry Pi).

On the **Raspberry Pi** (arm64 Linux) Chromium's library must be loaded before Java starts ("cannot allocate memory
in static TLS block"): `run_pi.sh` does it when the bundle exists. So the very first time the app downloads the
engine, says *Browser installato — Riavvia l'app*, and the **Riavvia l'app** button restarts it (through systemd
when the app runs as the `javachess` service, otherwise by running `run_pi.sh` again). From then on the browser
opens directly.

Old bundles of previous versions (`~/.jcef-bundle-v141`, other `~/.jcef-bundle-*`) can be deleted to free space.

### Checking it on a Raspberry Pi

```bash
cd ~/javachess
JAVACHESS_OPTS="-Djavachess.view=BROWSER -Djavachess.browserUrl=https://lichess.org/analysis" ./run_pi.sh
```

The first run downloads the engine and asks for the restart; run the same command again: lichess opens, the bar
says *Tocca a te* (*Scacchiera non collegata...* without the board). `journalctl --user -u javachess` or
`~/.javachess/logs/` contain the details (`Browser status: ...` lines).

## Saved logins

The browser keeps its own session (cookies in `~/.javachess/jcef-cache`), so you usually log in once. When a
session expires the login page comes back; the app can then type the login for you:

- it is saved **only after you agree** (*Ricordare l'accesso?* after a login typed by hand);
- it is kept in the **macOS Keychain**, in the **Linux keyring** (Secret Service, e.g. GNOME Keyring) when there is
  one, otherwise in `~/.javachess/credentials`, readable only by your user (mode 600);
- it is never written in `config.properties`, in the logs or on a command line, and it is typed only on the real
  chess.com / lichess.org login pages over https, once per page: if the site refuses it the app stops and tells you;
- **Dimentica l'accesso** (on the failure message) or the settings remove it.

## Settings

`~/.javachess/config.properties`:

| Key | Values | Meaning |
|---|---|---|
| `browser.reader` | `page` (default), `vision`, `vision-only` | Where the position is read from, see below. |

## How it works

```
 Home ─► BrowserController ─► JcefRuntime (Chromium, lazy, download, restart on the Pi)
                │                 │
                │                 └─► BrowserWindow: one Swing window over the app (BrowserBar + page)
                │                          │  page events (load, errors, address, pop-ups)
                ▼                          ▼
          BrowserSession ◄──────── BoardWatcher ◄── PageDriver (DevTools protocol)
          (state machine)          (page markup + vision)      ▲
                │                                              │ trusted clicks / typing
                ├─► OnlineGameSync ─► PhysicalBoard (BoardStateManager: sensors, LEDs)
                │        └──────────► BotMover ────────────────┘
                └─► LoginAssistant ─► CredentialStore (Keychain / Secret Service / file)
```

- **Page access without the screen.** Everything goes through Chromium's DevTools protocol (`CdpPageDriver`):
  `Runtime.evaluate` reads the page, `Page.captureScreenshot` pictures the board (Chromium's own pixels: it works
  whatever covers the window and needs no screen-recording permission), `Input.dispatchMouseEvent` and
  `Input.insertText` click and type like a person (the page receives *trusted* events) without moving the mouse
  pointer. The previous version captured the whole screen with `java.awt.Robot` (blank without the macOS
  permission, unreliable under Wayland) and moved the real mouse.
- **Reading the page** (`BoardProbe`, `/browser/board-probe.js`): a read-only script returns the board's rectangle,
  orientation, the pieces (chess.com `wc-chess-board .piece.wp.square-52`, lichess `cg-board piece` placed by CSS
  transforms), the last-move highlights, the running clock, the move list, the result, login forms and bot checks.
  Pieces being animated or dragged (and the two pieces of a capture in progress) mark the board as moving.
- **Reading the picture** (`VisionService`): the board's picture is read by a reader *calibrated on the site's own
  theme* (`TemplateReader`: it learns the pieces from positions known for sure — the page's markup, or the start
  position recognised by occupancy — and remembers each square's empty look), combined with the ONNX model
  (`PieceClassifier`) when unsure; before calibration, the model alone. `VisionTracker` skips moving frames and
  reports each stable position once.
- **Two readings** (`BoardWatcher`): every ~300 ms the page is probed and, when vision is used, the board pictured.
  Each reading counts once it is the same on two polls. With `browser.reader=page` (default) the page's markup
  leads and vision cross-checks it (and takes over automatically when the markup cannot be read); with `vision`
  vision leads and the page confirms each move (when they disagree for 1.5 s the page wins); `vision-only` never
  uses the markup for positions.
- **Synchronisation** (`OnlineGameSync`): the first position becomes the setup target; moves made on the board are
  played by `BotMover` and stay pending until the page shows them (sent again after 3 s, then reported as not
  accepted and taken back on the LEDs); moves seen on the page are recognised among the legal continuations
  (`PositionResolver`, tolerant to a few misread squares) and replicated on the board; a position no move explains
  is a take-back when it matches an earlier position, otherwise the board is set up again. Only real games (a game
  page, from the standard start, at least 6 plies) are archived.
- **One state machine** (`BrowserSession`) turns everything (engine start-up, page loads and errors, what the page
  shows, reading problems, the synchronisation) into one `BrowserStatus`: title, sentence, tone, progress, actions.
  The bar above the page (`BrowserBar`, Swing) and the JavaFX browser view show it.

### Why the page markup is the default

Field trials (below) on both sites, against the computer opponents, in the Raspberry-Pi-like box: the markup was
right on every position, with no blocking or warning from the sites, and it costs 3-6 ms per read. Vision is kept as
the cross-check and fallback (and for other sites): it is calibrated on the theme in use because the sites' themes
(every chess.com bot has its own) defeat a generic model.

## Field trials (October 2026)

Run in the Raspberry-Pi-like box (`docker/pi-sim`: Debian arm64, Xvfb 720x1920, off-screen rendering, JCEF 146), as
an anonymous guest in a separate browser profile, **only against the sites' computer opponents**: lichess "play
against the computer" level 1 and chess.com's bots (Martin). The trial's own moves were chosen by a deliberately
weak Stockfish and played by `BotMover` through DevTools; every position was checked against the game replayed
independently.

| | Games | Plies | Missed moves | Ghost moves | Wrong positions | Own move on the page |
|---|---|---|---|---|---|---|
| lichess, page reading | 3 | 140 | 0 | 0 | 0 | 0.32-0.35 s average |
| chess.com, page reading | 8 | 341 | 0 | 0 | 0 | 0.48-0.54 s average |
| lichess, `vision-only`, the app's whole pipeline, White and Black | 2 | 161 | 0 | 0 | 0 (once more than 4 s late) | |
| chess.com, `vision-only`, the app's whole pipeline, White and Black (flipped) | 2 | 125 | 0 | 0 | 0 | |
| lichess from a position: promotion h8=Q, en passant exd6# | 2 | 7 | 0 | 0 | 0 | |
| chess.com from a position: promotion g8=Q+, en passant exd6# | 2 | 60 | 0 | 0 | 0 | |

- No CAPTCHA, warning, refused move or interrupted game on either site with JCEF 146 (with the old Chromium 127
  chess.com answered 403 with a Cloudflare check, 3 times out of 3).
- Clicks and typing sent through DevTools reach pages as **trusted** input (`isTrusted`), which a test page that
  ignores synthetic events confirms; the probe only reads the page and never changes it.
- chess.com draws a capture by giving the moving piece its destination class (with a transform) while the taken
  piece is still there; the probe treats this as an animation (found in the trials, fixed, covered by tests).
- `vision-only` games ran through the app's own pipeline (vision → synchronisation → `BotMover`), the page markup
  used only by the trial to check. All 290 reads came from the reader calibrated on the board in use (none from
  the generic model), each game needed a single setup, and no move was reported twice. The lichess games ran before
  the confirmation window for vision was raised to 5 s and had one false "not accepted"; the chess.com games,
  after it, had none.
- Every chess.com bot has its own board and piece theme ("forest" for Martin): the generic vision model read 0 of
  42 of those positions, the calibrated reader 42 of 42.

### Vision battery

| Pictures | Calibrated reader (exact boards / squares) | Model alone |
|---|---|---|
| lichess live games, default theme (140) | 100% / 100% | 98.6% / 99.98% |
| lichess, 14 board themes, both orientations (84) | 100% / 100% | 90.5% / 97.8% |
| chess.com bot theme, live games (42) | 100% / 100% | 0% / 69.7% |
| chess.com image renderer, 27 themes x 27 piece sets (108) | 88.0% / 99.3% (89.8% fused with the model) | 44.4% / 95.3% |
| Moves between consecutive real pictures (181) | 181 right, 0 wrong | 139 right |

The remaining errors are extreme renderer themes (black pieces on dark stone, pale pawns on newspaper print). The
start position is recognised by occupancy alone in 71 of 75 pictures, with no false start in 433 others.

## Testing

- Unit and integration tests (no browser): `./mvnw test -DskipE2E=true` — page classification, probe answers
  recorded on the real sites, setup positions, the vision tracker, the watcher with a fake site, the synchronisation
  in every game situation (also with the real board manager, the simulated sensor board and BotMover), the state
  machine and its messages (fitting a 720 px screen), saved logins, the start-up logic.
- Real Chromium on a local page that imitates both sites' markup and, like them, ignores untrusted events:
  `./mvnw test -DskipE2E=true -DskipJcefE2E=false -Dtest=BrowserJcefE2E` (needs a display; add
  `-Djavachess.jcef.dir=...` to reuse a downloaded engine).
- Vision battery: `VisionBatteryTest` (committed core set) and an extended set downloaded by `BatteryDownload` (the
  sites' own board renders in many themes and piece sets, kept out of the repository) or captured in the trials.
- Field trials against the sites' computer opponents (never people), as an anonymous guest:
  `BotGameTrial` (test sources), e.g. in the arm64 box.

## Troubleshooting for developers

- `-Djavachess.browser.snapshot=out.png` writes a picture of the browser window (bar + page).
- `-Djavachess.vision.debug=true` writes the vision model's detections to `~/.javachess/vision-debug/`.
- Chromium's own log: `~/.javachess/logs/chromium.log`.
- `-Djavachess.jcef.dir=DIR` uses another engine folder; `-Djavachess.browser.osr=true|false` forces off-screen /
  windowed rendering.
