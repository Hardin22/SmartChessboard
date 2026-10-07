# Security policy

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's
[private vulnerability reporting](https://github.com/Hardin22/SmartChessboard/security/advisories/new) instead.
You will get an answer within a few days.

## Secrets handled by the app

- The **Lichess API token** is stored only in `~/.javachess/config.properties`, readable by your user only (mode 600),
  and is never written to the log. Use a token with the `board:play` scope only; revoke it at
  <https://lichess.org/account/oauth/token> if the device is lost.
- **Passwords are never stored.** chess.com and lichess logins happen in the integrated browser, whose session lives in
  `~/.javachess/jcef-cache` (also owner-only).
- Never commit `config.properties`, `cookies.json` or anything from `~/.javachess` — `.gitignore` excludes them.
