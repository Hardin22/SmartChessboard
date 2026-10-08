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
| La pagina si è interrotta | Chromium had to close the page (out of memory, a crash). The page is reopened by itself once; if it happens again within a minute the message stays. | Tap **Ricarica**. |
| Verifica di sicurezza | The site wants to check you are a person (Cloudflare). The app does not touch the page meanwhile. | Tick the box on the page yourself; the app continues by itself. |
| Accedi a Chess.com | The login page. The app does not touch the page meanwhile. | Log in on the page (once), or tap **Usa l'accesso salvato** if you saved one in the settings. |
| Inserisco le credenziali salvate… | The saved login is being typed for you (you tapped the button). | Wait. |
| Accesso non riuscito | The saved login was refused (password changed?). | Log in by hand; tap **Dimentica l'accesso** to remove the old one. |
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

`scripts/pi-check-browser.sh` checks the browser on the real Pi 5 step by step and writes a report. Run it from
the javaChess folder, on the Pi's desktop or over SSH (it uses the Pi's screen):

```bash
cd ~/javachess
scripts/pi-check-browser.sh            # system, engine download + restart, local test board
scripts/pi-check-browser.sh --sites    # also lichess.org and chess.com analysis boards (no login)
```

1. **System**: 64-bit arm, Java 21, free memory (≥ 1.5 GB) and disk (≈ 450 MB for the engine), the screen, the
   network (Maven Central for the download, lichess, chess.com) and the app's jar.
2. **Browser engine**: opens the browser on a local test page; the first time the engine is downloaded and the app
   asks for the restart (expected), then the script starts it again: Chromium must start and show the page.
3. **Reading the board**: the test page's position must be read, the (simulated) board set up, vision calibrated,
   pictures of the browser written, and the app must close with status 0.
4. With `--sites`: the analysis boards of both sites must be read (a CAPTCHA is reported as such).

The app runs with a temporary data folder (your games, settings and saved logins are never touched), never logs in
and never moves on a site. It prints PASS/FAIL lines, the peak memory of the app and Chromium together, and the
folder with `report.txt`, the logs and the pictures: send `report.txt` when something fails. `PI_CHECK_TIMEOUT=600`
waits longer on a slow network.

### Context menu, copy and paste

- **No native context menu.** A right click (or a long press) asks Chromium for its context menu; on macOS that is a
  modal `NSMenu`, and with the app's borderless window it could open where nobody saw it while the app waited in it
  (the user's freeze of 8 October 2026 on chess.com's login). The browser's context menu handler clears the menu, as
  JCEF documents ("model: Can be cleared to show no context menu",
  [CefContextMenuHandler](https://github.com/chromiumembedded/java-cef/blob/master/java/org/cef/handler/CefContextMenuHandler.java)).
- **macOS: Cmd+C/V/X/A/Z.** CEF expects the application's main menu to carry the Edit shortcuts ("Shortcuts are
  handled via the application top menu on Mac", CEF forum
  [Copy (Cmd+C) and Paste (Cmd+V) do not work](https://magpcss.org/ceforum/viewtopic.php?f=6&t=12561)); the app's
  menu is JavaFX's, without Edit, so Cmd+V did nothing. `CefKeyboardHandler.onPreKeyEvent` turns them into the frame's
  `copy()/paste()/cut()/selectAll()/undo()/redo()` (`EditShortcuts`).
- **Linux (the Raspberry Pi), off-screen rendering:** the keys reach Chromium through the AWT component, and JCEF
  passed Ctrl+C/V/A on without their meaning, did not call the keyboard handler, and turned a lone Shift or Ctrl into
  an empty text input that deleted the selected text (so Ctrl+A then Ctrl+C emptied a field). A standard AWT
  `KeyEventDispatcher` for the browser's component runs the frame commands, keeps lone modifier keys away from
  Chromium (their state still comes with the next key), and the component lets Tab move between the page's fields
  (`setFocusTraversalKeysEnabled(false)`). Checked in the Pi box with real X input (`xdotool`, `xclip`, `PasteCheck`):
  Ctrl+V pastes, Ctrl+A selects, Shift/Ctrl alone keep the selection, Tab moves to the password, capitals are typed
  right, a right click opens no menu and the page keeps answering. Not confirmed there: copying *from* the page to
  the system clipboard.

### Raspberry Pi: never the desktop

The browser's window lies over the app's full-screen window, which stays underneath all the time: opening the
browser, going Home and opening it again never uncover anything else. Checked in the Pi box with Raspberry Pi OS's
compositor (labwc 0.8.4 from archive.raspberrypi.com, XWayland, `docker/pi-kiosk`): the screen captured with `grim`
about 30 times a second, a magenta background standing in for the desktop, with and without Pi OS's panel
(`wf-panel-pi`), and with the app started by `run_pi.sh` from the kiosk autostart of docs/raspberry-pi.md: in about
2900 frames over 10 openings and closings no frame showed the background or the panel once the app was on screen
(they show only before the app's first window, i.e. while the Pi boots). The browser's first frame
on a page still loading is its bar over a dark page.

What would show the desktop: the app's restart after the first download of the engine (avoided by
`run_pi.sh --install-browser` at set-up) and the boot itself; the kiosk session of docs/raspberry-pi.md (only the
app, on a black background) covers both.

## Saved logins

The browser keeps its own session (cookies in `~/.javachess/jcef-cache`), so you usually log in once. When a
session expires the login page comes back. A login saved in the settings can be typed for you:

- only when you tap **Usa l'accesso salvato** on the login page (never by itself: see
  [Login and verification pages](#login-and-verification-pages));
- it is kept in the **macOS Keychain**, in the **Linux keyring** (Secret Service, e.g. GNOME Keyring) when there is
  one, otherwise in `~/.javachess/credentials`, readable only by your user (mode 600);
- it is never written in `config.properties`, in the logs or on a command line, and it is typed only on the real
  chess.com / lichess.org login pages over https: if the site refuses it the app tells you;
- **Dimentica l'accesso** (on the failure message) or the settings remove it.

### Login and verification pages

Sites protect their login with bot checks (chess.com uses Cloudflare Turnstile: the "Verify you are human" box).
On those pages the app **keeps its hands off**: no reading of the page, no script, no DevTools session, so the
check sees only you. The app knows them without touching the page: login pages from their address, Cloudflare's
verification from its title ("Just a moment…", "Solo un momento…") or from a single reading; it starts reading again
when the address changes, a new load starts, the title changes, or (on a verification it did not see end) after
30 s. Saving a login typed on the page is therefore no longer offered: logins are saved from the settings.

If the box still says something is wrong, try the same page in Safari or Chrome **on the same network**: when it
fails there too, the site distrusts the network (IP address), not the app; when it works there, tell us.

## Settings

`~/.javachess/config.properties`:

| Key | Values | Meaning |
|---|---|---|
| `browser.reader` | `page` (default), `vision`, `vision-only` | Where the position is read from, see below. Applies from the next opening of the browser. |

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

### Starting Chromium (the documented order)

JCEF's own documentation: `CefApp.startup(args)` "must be called at the beginning of the main() method to perform
platform-specific startup initialization. On Linux this initializes Xlib multithreading and on macOS this dynamically
loads the CEF framework" ([CefApp.java](https://github.com/chromiumembedded/java-cef/blob/master/java/org/cef/CefApp.java);
JCEF's sample [MainFrame](https://github.com/chromiumembedded/java-cef/blob/master/java/tests/detailed/MainFrame.java)
calls it on the first line of `main`). jcefmaven calls it only inside `CefAppBuilder.build()`, i.e. at the first
opening of the browser, while the app's threads are running. On macOS that is fatal now and then: loading the
framework makes Chromium's PartitionAlloc the process's default malloc zone, and a `free()` on another thread at that
moment aborts the process (the crashes of 8 October 2026 in JavaFX's renderer and in the JIT compiler). `App.main`
now does the documented step first (`JcefRuntime.startup`, about 25 ms, on macOS and Linux); the rest of the
initialisation is jcefmaven's (`CefAppBuilder.install`, then what `CefInitializer` does minus the second `startup`).
On macOS the very first download of the engine asks for a restart, so that the step always runs at the start.

### Shutting Chromium down

On macOS Chromium is **never disposed**: CEF's shutdown "should be called on the main application thread"
([cef_app.h](https://bitbucket.org/chromiumembedded/cef/src/master/include/cef_app.h)), but JCEF's `CefApp.dispose()`
runs it on the Swing thread, which on macOS is not the main thread (the main thread belongs to JavaFX here): it
closes Cocoa windows outside the main thread, and macOS 27 aborts the process for that ("Must only be used from the
main thread"). The
jcefmaven library even registers a JVM shutdown hook that does it, so any process that had opened the browser could
crash on exit now and then (4 crashes in the test runs of the night of 7-8 October 2026). `JcefRuntime` starts
Chromium without that hook (`CefInitializer` instead of `CefAppBuilder.build()`) and its own hook only writes the
cookies to disk, then lets Chromium end with the process; on Linux it still disposes Chromium. When macOS asks the
app to quit through Chromium (Cmd+Q) the app quits its own way (`JcefRuntime.setQuitHandler`).

Cookies (the login) are written to disk by Chromium only every ~30 s, so the app asks for it after every page load
and when the user leaves the browser screen: a login survives even if the app is closed right after it. The flush is
never waited for on the macOS main thread (JavaFX's thread there) nor on the Swing thread, which have to deliver it.

A crash has a second cost on macOS: until someone answers the "reopen the windows?" alert that follows it, every new
`java` process that starts AWT by itself hangs (`NSPersistentUIRestorer promptToIgnorePersistentStateWithCrashHistory`).
The app is not affected (JavaFX starts first), test JVMs are: surefire sets `-Dapple.awt.UIElement=true`, which also
keeps their Dock icons away. `JcefShutdownJcefE2E` starts Chromium in child JVMs and checks every way out (quit with
the page open, without any shutdown call, after hiding the window, after closing the page) ends with status 0 and no
crash report.

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
| lichess, `vision-only`, the app's whole pipeline, White and Black | 6 | 415 | 0 | 0 | 0 (4 times more than 4 s late, then followed) | 1.2-1.6 s seen by vision |
| lichess, `vision-only`, 8 Oct, before the DevTools fix | 4 | 188 | 0 | 0 | 0 (2 times 5-7 s late: a lost DevTools answer) | |
| lichess, `vision-only`, 8 Oct, with the DevTools fix | 6 | 413 | 0 | 0 | 0 (vision at most 1.2 s behind the page) | |
| chess.com, `vision-only`, the app's whole pipeline, White and Black (flipped) | 4 | 188 | 0 | 0 | 0 | |
| lichess from a position: promotion h8=Q, en passant exd6# | 2 | 7 | 0 | 0 | 0 | |
| chess.com from a position: promotion g8=Q+, en passant exd6# | 2 | 60 | 0 | 0 | 0 | |

- No CAPTCHA, warning, refused move or interrupted game on either site with JCEF 146 (with the old Chromium 127
  chess.com answered 403 with a Cloudflare check, 3 times out of 3).
- Clicks and typing sent through DevTools reach pages as **trusted** input (`isTrusted`), which a test page that
  ignores synthetic events confirms; the probe only reads the page and never changes it.
- chess.com draws a capture by giving the moving piece its destination class (with a transform) while the taken
  piece is still there; the probe treats this as an animation (found in the trials, fixed, covered by tests).
- `vision-only` games ran through the app's own pipeline (vision → synchronisation → `BotMover`), the page markup
  used only by the trial to check. All 612 reads came from the reader calibrated on the board in use (none from
  the generic model), each game needed a single setup, and no move was reported twice. The first lichess games ran
  before the confirmation window for vision was raised to 5 s and had one false "not accepted"; none since. Vision
  needs still pictures, so it follows a move about a second after the page; 4 times in 415 lichess plies it took
  more than 4 s. Cause found on 8 October: a DevTools call (the picture of the board) whose answer JCEF lost, see
  the summary at the end; with the fix vision was never more than 1.2 s behind the page in 413 plies.
- Every chess.com bot has its own board and piece theme ("forest" for Martin): the generic vision model read 0 of
  42 of those positions, the calibrated reader 42 of 42.

### Vision battery

| Pictures | Calibrated reader (exact boards / squares) | Model alone |
|---|---|---|
| lichess live games, default theme (140) | 100% / 100% | 98.6% / 99.98% |
| lichess, 14 board themes, both orientations (84) | 100% / 100% | 90.5% / 97.8% |
| chess.com bot theme, live games (42) | 100% / 100% | 0% / 69.7% |
| chess.com image renderer, 26 board themes x 26 piece sets, both orientations (104) | 95.2% / 99.9% (97.1% fused with the model) | 44.2% / 95.4% |
| Moves between consecutive real pictures (181) | 181 right, 0 wrong | 139 right |

Squares never seen empty since the calibration (the back ranks, at first) are judged on every pixel against the
learned pieces, so pieces that barely stand out from a textured square (black on dark stone, pale on newspaper
hatching) are found too. The 5 remaining errors are extreme renderer themes: a piece in a corner of "metal" (under
its vignette), a pale "gothic" king on newspaper hatching on a board turned the other way from the calibration, a
translucent "glass" queen read with the wrong colour. The start position is recognised by occupancy alone in 74 of
78 pictures, with no false start in 435 others.

## Testing

- Unit and integration tests (no browser): `./mvnw test -DskipE2E=true` — page classification, probe answers
  recorded on the real sites, setup positions, the vision tracker, the calibrated reader on a drawn stone-like
  board, the watcher with a fake site, the synchronisation in every game situation (also with the real board
  manager, the simulated sensor board and BotMover), the state machine and its messages (fitting a 720 px screen),
  saved logins, the start-up logic.
- Real Chromium on a local page that imitates both sites' markup and, like them, ignores untrusted events, and the
  ways Chromium ends: `./mvnw test -DskipE2E=true -DskipJcefE2E=false -Dsurefire.failIfNoSpecifiedTests=false
  -Dtest='*JcefE2E'` (needs a display; add `-Djavachess.jcef.dir=...` to reuse a downloaded engine).
- Vision battery: `VisionBatteryTest` (committed core set) and an extended set downloaded by `BatteryDownload` (the
  sites' own board renders in many themes and piece sets, kept out of the repository) or captured in the trials.
- Field trials against the sites' computer opponents (never people), as an anonymous guest:
  `BotGameTrial` (test sources), e.g. in the arm64 box.

## Troubleshooting for developers

- `-Djavachess.browser.snapshot=out.png` writes a picture of the browser window (bar + page).
- `-Djavachess.vision.debug=true` writes the vision model's detections to `~/.javachess/vision-debug/`.
- Chromium's own log: `~/.javachess/logs/chromium.log`.
- `Vision showed the page's position N ms after the page; last polls: ...` (WARN, vision reading modes): vision was
  more than 3 s behind the page's markup; each line of the trace is one poll (time to read the page, to take the
  picture, what the vision tracker said: `MOVING` = the picture was still changing).
- A Java test or tool that hangs at start-up on macOS after a crash: see [Shutting Chromium down](#shutting-chromium-down)
  (`-Dapple.awt.UIElement=true`, or answer the "reopen windows" alert).
- Saved logins: with `-Djavachess.home` (tests, trials, screenshots) only the file in that folder is used, never the
  Keychain / Secret Service; `-Djavachess.credentials=system|file` overrides it.
- `-Djavachess.browser.gpu=true|false`: Chromium with or without the GPU (default: without, everywhere; WebGL comes
  from SwiftShader, `-Djavachess.browser.webgl=false` turns it off). `-Djavachess.browser.preload=false` does not
  load the framework at start-up on macOS (only for experiments: see the lesson in the third round below).
- `-Djavachess.demo=browser-cycle` (with `javachess.demo.urls`, `javachess.demo.rounds`): opens the browser, goes
  Home, opens it again, as `AppBrowserFullScreenJcefE2E` does in full screen.
- `-Djavachess.jcef.dir=DIR` uses another engine folder; `-Djavachess.browser.osr=true|false` forces off-screen /
  windowed rendering.

## Second round (8 October 2026, morning): summary

What changed and why:

- **No more JVM crashes on macOS** (4 crashes of test JVMs in the night, also possible when closing the app):
  jcefmaven's start-up registers a shutdown hook that disposes Chromium, which on macOS 27 aborts the process.
  Chromium now starts without it and is never disposed on macOS (see [Shutting Chromium down](#shutting-chromium-down)).
  Test JVMs run with `-Dapple.awt.UIElement=true` so that the macOS alert after an old crash cannot hang them.
- **Vision delays of 5-7 s on lichess**: a race in JCEF's DevTools client lost answers that arrived before the
  message id, and the call waited for its 5 s timeout (found with the new lag trace of `BoardWatcher`). DevTools
  calls now go through `DevToolsAccess` + `DevToolsReplies`, which keep early answers.
- **Linux off-screen "Exception in thread AWT-EventQueue-0" lines** (1-4 at each start): `-Xlog:exceptions`
  shows they are `StackOverflowError`s raised by the JVM's stack check when Chromium, on the Swing thread, calls
  back into Java while running on a native stack the JVM does not recognise, during the page's first frames; the
  JVM cannot even print their stack trace, hence the bare lines. The thread stack size makes no difference (default,
  `-Xss4m`, `-Xss8m`: 1-4 lines each, 4 runs each), so nothing can be done from Java; the page always renders and is
  read (all trials below). Harmless, left as they are.
- **Clear messages**: a page whose process ended (out of memory, crash) says *La pagina si è interrotta* and is
  reopened once by itself, instead of "<site> non risponde"; the expected "no network" start-up failure is no
  longer logged as an error with a stack trace.
- **Fixes reported by QA**: the browser window no longer appears over a game started while Chromium was still
  starting; tests, trials and screenshots (`-Djavachess.home`) never read or remove the real saved logins (only
  the file in their folder); the flaky LED test reads the board's current frame.
- **Real Pi 5 check**: `scripts/pi-check-browser.sh` (see [Checking it on a Raspberry Pi](#checking-it-on-a-raspberry-pi)),
  tried in the Pi box as a fresh Pi (engine downloaded in 19 s, restart, local board, both sites' analysis boards
  read: PASS).
- `browser.reader` changed in the settings applies from the next opening of the browser (no restart).

Field trials of this round (Pi box, 4 CPUs, 6 GB, only the sites' computer opponents, anonymous guest):

| | Games | Plies | Missed / ghost moves | Wrong positions | Moves not accepted | DevTools calls lost |
|---|---|---|---|---|---|---|
| lichess, `vision-only`, before the DevTools fix | 4 | 188 | 0 | 0 (vision 5.4 and 7.1 s late twice) | 0 | 2 |
| lichess, `vision-only`, with the fix | 6 | 413 | 0 | 0 (vision at most 1.2 s late) | 0 | 0 |
| chess.com, page reading (default) | 4 | 180 | 0 | 0 | 0 | 0 |
| chess.com, `vision-only` | 2 | 95 | 0 | 0 (vision at most 1.4 s late) | 0 | 0 |

**Memory** (the app and Chromium together, measured by the check script and in the trials): lichess about
1.5 GB, chess.com up to 2.3 GB (its pages are heavy; with a 3 GB memory limit the box's kernel once killed
Chromium's page process, which now shows *La pagina si è interrotta* and reopens it). A Pi 5 with 4 or 8 GB is
fine; 2 GB is not enough for chess.com.

**Tests**: 691 unit and integration tests, 0 failures (`./mvnw test -DskipE2E=true`, after merging origin/main 5e7e5d5); real Chromium suite
(`*JcefE2E`, 13 tests, plus QA's `AppBrowserJcefE2E`) 8 times on macOS, 0 crashes.

**Open items**

- Run `scripts/pi-check-browser.sh --sites` on the real Pi 5 (only the box was available).
- The bare "Exception in thread AWT-EventQueue-0" lines on Linux off-screen rendering (harmless, see above).
- Vision on the most extreme chess.com renderer themes: 5 of 375 battery pictures keep one misread square
  ("metal" and "glass" corners under the vignette, a pale "gothic" king on "newspaper"); during a game the rules
  of chess absorb a single misread square, and the page markup is the default reader anyway.

## Third round (8 October 2026, midday)

- **Crash when opening the browser again over the app in full screen (macOS)**: AWT's exclusive full screen
  (`GraphicsDevice.setFullScreenWindow`) over the app's JavaFX full-screen window made AppKit raise exceptions on the
  main thread (`-[NSWindow setStyleMask:]` on the user's Mac at the second opening, an unknown selector here at the
  first), which end the process and cannot be caught. The browser window no longer uses it: on macOS the app's
  window leaves full screen while the browser is shown (and gets it back on Home), and the browser window covers the
  screen minus the menu bar. `AppBrowserFullScreenJcefE2E` runs the real app full screen on the last screen and opens
  the browser 5 times; also checked by hand with chess.com/login and lichess.org.
- **Cloudflare's box failing on chess.com**: the app's Chromium has no WebGL (GPU disabled) and the app read the
  login page every 300 ms. Login and verification pages are now left alone (see above): measured on the real chess.com login, 0 DevTools calls while it is shown. Whether the network's
  reputation also plays a part can only be told by trying the box in Safari/Chrome on the same network.
- On macOS a few of Chromium's notices to Java are refused by the JVM (the bare "Exception in thread JavaFX
  Application Thread" lines, same cause as the Linux ones): the end of a page load is now also learned from the
  page's `document.readyState`, or from `CefBrowser.isLoading` on pages the app does not read.
- **Lesson: on macOS the Chromium framework must be loaded while the process is quiet.** Loading it (its static
  initialisers) makes PartitionAlloc the process's default malloc zone, and for an instant the system's zone is not
  registered: a `free()` on any other thread at that instant aborts the process. Loaded at the first opening of the
  browser, it raced with JavaFX's renderer (user's crash 12:27, which happened with the GPU on and was first blamed on
  it) and with the JIT compiler (12:36, GPU off). `CefLoadRace` reproduces it (17 crashes in 20 runs, 9 in 10) and
  shows the fix (framework loaded first: 30 of 30 fine). Now `App.main` loads it on macOS before anything else runs
  (`JcefRuntime.startup`, about 25 ms) and the first download asks for a restart. A test that passes once
  proves little for such races: they need many runs with load on purpose.
- **WebGL without the GPU**: Chromium's software WebGL (SwiftShader, `--enable-unsafe-swiftshader`) runs in its GPU
  helper process, not in the app's; the GPU itself stays off. Pages see a "SwiftShader" WebGL renderer.

