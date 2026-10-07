# Contributing to javaChess

Thanks for helping! Bug reports, hardware notes, translations and code are all welcome.

## Reporting a bug

Open an issue with the *Bug report* template and attach `~/.javachess/logs/javachess.log`
(it never contains your token or passwords; check it anyway before posting). Say whether the physical board was
connected and which platform you use (Raspberry Pi model, macOS, Linux distribution).

## Development setup

1. Java 21 (any distribution) and, for engine features, Stockfish in your `PATH` or at `stockfish.path`.
2. `./mvnw test` must pass. Tests never touch your real `~/.javachess` (Maven points `javachess.home` to
   `target/test-home`).
3. Run the app with `scripts/dev-run.sh` (see the README for the developer switches). No Arduino is needed: moves can
   be played on screen.

## Guidelines

- **JavaFX thread**: no I/O, engine calls, serial access, `sleep` or heavy work on it. Use a background thread or
  executor and come back with `Platform.runLater`.
- **Logging**: use SLF4J (`private static final Logger log = LoggerFactory.getLogger(X.class)`), never
  `System.out` or `printStackTrace`. Never log tokens, passwords or cookie values.
- **Errors the user can act on** (network, missing engine, invalid token) are shown with
  `ErrorReporter.showError(...)` and a short message in Italian, like the rest of the UI.
- **Files** in the data folder are written with `AtomicFiles` and located with `AppPaths`.
- **Chess logic**: use chesslib and `PgnCodec` (UCI/SAN/FEN/PGN) instead of string manipulation; always validate
  moves against the legal move list.
- **Tests**: JUnit 5, no network (use a local fake server, see `LichessClientTest`), no real home folder. Tests that
  need Stockfish or a display must skip themselves (`Assumptions.assumeTrue`) when it is not available.
- Code style: see `.editorconfig` (4 spaces, 120 columns, UTF-8, LF). Keep changes focused; avoid mass reformatting.
- Commit messages in English, imperative mood ("Fix clock drift on move").

## Pull requests

1. Fork, create a branch, make your change with tests.
2. `./mvnw verify` locally; CI runs the same on Ubuntu with Java 21.
3. Describe what changed and how you tested it (include a screenshot for UI changes, a photo or video for hardware).

By contributing you agree that your contribution is licensed under GPL-3.0-or-later.
