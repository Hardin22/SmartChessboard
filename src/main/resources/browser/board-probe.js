// Reads the state of a chess.com / lichess page for javaChess (Browser/BoardProbe). Evaluated with the DevTools
// protocol (Runtime.evaluate, returnByValue): the value of this expression is a plain JSON object.
// Read-only: it never changes the page. Coordinates are CSS pixels relative to the viewport.
(() => {
  const FILES = 'abcdefgh';
  const out = {
    v: 1,
    url: location.href,
    // the page finished loading (the app also learns it from Chromium, but that notice can be lost)
    ready: document.readyState === 'complete',
    title: document.title || '',
    site: 'other',
    page: '',
    // the visible area, without scroll bars
    viewport: { w: document.documentElement.clientWidth || window.innerWidth,
                h: document.documentElement.clientHeight || window.innerHeight },
    dpr: window.devicePixelRatio || 1,
    challenge: false,
    login: false,
    loggedIn: null,
    board: null
  };
  const host = location.hostname;
  if (/(^|\.)lichess\.org$/.test(host)) out.site = 'lichess';
  else if (/(^|\.)chess\.com$/.test(host)) out.site = 'chesscom';
  else if (document.querySelector('[data-javachess-site]')) {
    out.site = document.querySelector('[data-javachess-site]').getAttribute('data-javachess-site');
  }

  const visible = (el) => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05;
  };
  const text = (el) => (el && el.textContent ? el.textContent.replace(/\s+/g, ' ').trim() : '');

  // Cloudflare / bot verification pages
  const bodyText = document.body ? (document.body.innerText || '').slice(0, 2000) : '';
  if (/just a moment|un momento|performing security verification|verify you are human|verifica (che sei|di essere) un/i
        .test(out.title + ' ' + bodyText)
      || document.querySelector('#challenge-form, #cf-challenge-running, iframe[src*="challenges.cloudflare.com"]')) {
    out.challenge = true;
  }

  // a visible password field = a login form
  const pw = [...document.querySelectorAll('input[type=password]')].find(visible);
  out.login = !!pw;

  const largest = (els) => {
    let best = null, area = 0;
    for (const el of els) {
      const r = el.getBoundingClientRect();
      const a = r.width * r.height;
      if (a > area && visible(el)) { best = el; area = a; }
    }
    return best;
  };
  const sq = (f, r) => FILES[f] + (r + 1);

  if (out.site === 'lichess') {
    const main = document.querySelector('main');
    if (main) {
      const c = main.classList;
      out.page = c.contains('round') ? 'game' : c.contains('analyse') ? 'analysis' : c.contains('puzzle') ? 'puzzle'
        : c.contains('lobby') ? 'home' : c.contains('editor') ? 'editor' : c.contains('auth') ? 'login'
        : c.contains('tv') ? 'watch' : '';
    }
    out.loggedIn = document.querySelector('#user_tag') ? true : (document.querySelector('.signin') ? false : null);
    const board = largest(document.querySelectorAll('cg-board'));
    if (board) {
      const r = board.getBoundingClientRect();
      const wrap = board.closest('.cg-wrap');
      const flipped = !!(wrap && wrap.classList.contains('orientation-black'));
      // transforms are in the board's layout pixels (before CSS zoom/transform)
      const size = (board.clientWidth || r.width) / 8;
      const at = (el) => {
        const m = /translate\(\s*(-?[\d.]+)px\s*,\s*(-?[\d.]+)px/.exec(el.style.transform || '');
        if (!m || size <= 0) return null;
        const col = Math.round(parseFloat(m[1]) / size), row = Math.round(parseFloat(m[2]) / size);
        if (col < 0 || col > 7 || row < 0 || row > 7) return null;
        return flipped ? [7 - col, row] : [col, 7 - row];
      };
      const grid = [...Array(8)].map(() => Array(8).fill(''));
      let animating = false, count = 0, bad = false;
      for (const p of board.querySelectorAll('piece')) {
        const c = p.classList;
        if (c.contains('ghost') || c.contains('fading')) continue;
        if (c.contains('dragging') || c.contains('anim')) animating = true;
        const color = c.contains('white') ? 'w' : c.contains('black') ? 'b' : '';
        const type = ['pawn', 'knight', 'bishop', 'rook', 'queen', 'king'].find((t) => c.contains(t));
        if (!color || !type) { bad = true; continue; }
        const pos = at(p);
        if (!pos) { animating = true; continue; } // between squares: moving
        const letter = { pawn: 'p', knight: 'n', bishop: 'b', rook: 'r', queen: 'q', king: 'k' }[type];
        if (grid[pos[1]][pos[0]]) animating = true; // two pieces on a square: a capture being animated
        grid[pos[1]][pos[0]] = color === 'w' ? letter.toUpperCase() : letter;
        count++;
      }
      const last = [...board.querySelectorAll('square.last-move')].map(at).filter(Boolean).map((p) => sq(p[0], p[1]));
      let turn = null;
      const running = document.querySelector('.rclock.running');
      if (running) {
        const bottom = running.classList.contains('rclock-bottom');
        turn = bottom === flipped ? 'b' : 'w';
      }
      const moves = [...document.querySelectorAll('rm6 l4x kwdb, l4x kwdb')].map(text).filter(Boolean);
      let result = null;
      const res = document.querySelector('.result-wrap .result');
      if (res && /^(1-0|0-1|½-½|1\/2-1\/2)$/.test(text(res))) result = text(res).replace('½-½', '1/2-1/2');
      out.board = {
        x: r.x, y: r.y, w: r.width, h: r.height, flipped, animating, pieces: count,
        placement: bad || count === 0 ? null : grid, lastMove: last, turn,
        moves: moves.length ? moves : null, result,
        status: text(document.querySelector('.result-wrap .status')) || null
      };
    }
  } else if (out.site === 'chesscom') {
    const p = location.pathname;
    out.page = /^\/(game|live)\b/.test(p) || /^\/play\b/.test(p) ? 'game' : /^\/analysis\b/.test(p) ? 'analysis'
      : /^\/(puzzles?|puzzle-rush|daily-chess-puzzle)\b/.test(p) ? 'puzzle' : /^\/(login|register|login_and_go)\b/.test(p) ? 'login'
      : /^\/(home)?\/?$/.test(p) ? 'home' : '';
    out.loggedIn = document.querySelector('.home-username-link, [data-user-id], .nav-user-avatar, a.user-username-component') ? true
      : (document.querySelector('a[href*="/login"]') ? false : null);
    const board = largest(document.querySelectorAll('wc-chess-board, chess-board'));
    if (board) {
      const r = board.getBoundingClientRect();
      const flipped = board.classList.contains('flipped');
      const grid = [...Array(8)].map(() => Array(8).fill(''));
      let animating = false, count = 0, bad = false;
      const squareOf = (el) => {
        for (const c of el.classList) {
          const m = /^square-(\d)(\d)$/.exec(c);
          if (m) return [Number(m[1]) - 1, Number(m[2]) - 1];
        }
        return null;
      };
      for (const el of board.querySelectorAll('.piece')) {
        if (el.closest('.element-pool')) continue; // recycled elements, not on the board
        const c = el.classList;
        if (c.contains('dragging')) animating = true;
        // a piece being animated already carries its destination square, with an inline transform until it lands
        if (el.style && el.style.transform && el.style.transform !== 'none') animating = true;
        let code = null;
        for (const k of c) if (/^[wb][prnbqk]$/.test(k)) code = k;
        const pos = squareOf(el);
        if (!code || !pos || pos[0] > 7 || pos[1] > 7) { bad = true; continue; }
        if (grid[pos[1]][pos[0]]) animating = true; // capture: the taken piece is still there
        grid[pos[1]][pos[0]] = code[0] === 'w' ? code[1].toUpperCase() : code[1];
        count++;
      }
      const last = [...board.querySelectorAll('.highlight')].map(squareOf).filter(Boolean).map((q) => sq(q[0], q[1]));
      let turn = null;
      const active = document.querySelector('.clock-component.clock-player-turn');
      if (active) {
        const bottom = active.classList.contains('clock-bottom');
        turn = bottom === flipped ? 'b' : 'w';
      }
      const moves = [...document.querySelectorAll('wc-simple-move-list .node, .move-list .node, .main-line-row .node')]
        .map((n) => {
          const fig = n.querySelector('[data-figurine]');
          return ((fig ? fig.getAttribute('data-figurine') : '') + text(n)).trim();
        }).filter(Boolean);
      let result = null;
      const res = document.querySelector('.game-over-modal-content .header-title-component, .game-result');
      const m = res ? /(1-0|0-1|½-½|1\/2-1\/2)/.exec(text(res)) : null;
      if (m) result = m[1].replace('½-½', '1/2-1/2');
      out.board = {
        x: r.x, y: r.y, w: r.width, h: r.height, flipped, animating, pieces: count,
        placement: bad || count === 0 ? null : grid, lastMove: last, turn,
        moves: moves.length ? moves : null, result, status: null
      };
    }
  } else {
    // unknown site: only the largest square element that looks like a board (vision reads it)
    const board = largest(document.querySelectorAll('cg-board, wc-chess-board, chess-board, [data-javachess-board]'));
    if (board) {
      const r = board.getBoundingClientRect();
      out.board = { x: r.x, y: r.y, w: r.width, h: r.height,
        flipped: board.getAttribute('data-orientation') === 'black', animating: false, pieces: 0,
        placement: null, lastMove: [], turn: null, moves: null, result: null, status: null };
    }
  }
  return out;
})()
