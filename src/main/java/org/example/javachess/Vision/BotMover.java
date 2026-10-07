package org.example.javachess.Vision;

import org.cef.browser.CefBrowser;

public class BotMover {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BotMover.class);
    private CefBrowser browser;

    public BotMover() {
        // No Robot needed anymore
    }

    public void setBrowser(CefBrowser browser) {
        this.browser = browser;
    }

    public void makeMove(String move, boolean isFlipped) {
        if (browser == null || move == null || move.length() < 4) {
            log.warn("[Bot] Browser not linked or invalid move.");
            return;
        }

        // Ensure lowercase
        move = move.toLowerCase();
        String from = move.substring(0, 2);
        String to = move.substring(2, 4);
        String promotion = move.length() > 4 ? move.substring(4, 5) : "";

        String url = browser.getURL();

        if (url.contains("chess.com")) {
            makeMoveChessCom(from, to, isFlipped);
        } else if (url.contains("lichess.org")) {
            makeMoveLichess(from, to, promotion, isFlipped);
        } else {
            log.warn("[Bot] Unknown site: " + url);
        }
    }

    private void makeMoveChessCom(String from, String to, boolean isFlipped) {
        log.info("[Bot] Chess.com Geometric Move: " + from + " -> " + to + " | Flipped: " + isFlipped);

        String script = "(function() {" +
                "  console.log('[Bot] Attempting GEOMETRIC move: " + from + " -> " + to + "');" +
                "  " +
                "  function getBoard() {" +
                "    return document.querySelector('chess-board') || document.querySelector('#board-layout-chessboard');"
                +
                "  }" +
                "  " +
                "  function clickAt(board, square) {" +
                "    var rect = board.getBoundingClientRect();" +
                "    var file = square.charCodeAt(0) - 97; /* a=0, h=7 */" +
                "    var rank = parseInt(square.charAt(1)) - 1; /* 1=0, 8=7 */" +
                "    " +
                "    var flipped = " + isFlipped + ";" +
                "    if (flipped) {" +
                "       /* file = 7 - file; REMOVED REDUNDANT FLIP */" +
                "       /* rank is NOT inverted for visual calculation because Rank 1 is at Top on flipped board */" +
                "       /* rank = rank; */" +
                "    }" +
                "    " +
                "    var squareW = rect.width / 8;" +
                "    var squareH = rect.height / 8;" +
                "    " +
                "    var x, y;" +
                "    if (!flipped) {" +
                "       x = rect.left + (file * squareW) + (squareW / 2);" +
                "       y = rect.top + ((7 - rank) * squareH) + (squareH / 2);" +
                "    } else {" +
                "       x = rect.left + ((7 - file) * squareW) + (squareW / 2);" +
                "       y = rect.top + (rank * squareH) + (squareH / 2);" +
                "    }" +
                "    " +
                "    console.log('[Bot] Clicking ' + square + ' at ' + x + ',' + y + ' (Flipped: ' + flipped + ')');" +
                "    " +
                "    /* Visual Debug */" +
                "    var debugDot = document.createElement('div');" +
                "    debugDot.style.position = 'fixed';" +
                "    debugDot.style.left = (x - 5) + 'px';" +
                "    debugDot.style.top = (y - 5) + 'px';" +
                "    debugDot.style.width = '10px';" +
                "    debugDot.style.height = '10px';" +
                "    debugDot.style.backgroundColor = 'red';" +
                "    debugDot.style.borderRadius = '50%';" +
                "    debugDot.style.zIndex = '9999';" +
                "    debugDot.style.pointerEvents = 'none';" +
                "    document.body.appendChild(debugDot);" +
                "    setTimeout(function() { debugDot.remove(); }, 1000);" +
                "    " +
                "    var opts = {bubbles: true, cancelable: true, view: window, clientX: x, clientY: y};" +
                "    " +
                "    var target = document.elementFromPoint(x, y);" +
                "    if (target) {" +
                "       target.dispatchEvent(new MouseEvent('pointerdown', opts));" +
                "       target.dispatchEvent(new MouseEvent('mousedown', opts));" +
                "       target.dispatchEvent(new MouseEvent('mouseup', opts));" +
                "       target.dispatchEvent(new MouseEvent('click', opts));" +
                "       target.dispatchEvent(new MouseEvent('pointerup', opts));" +
                "       return true;" +
                "    }" +
                "    return false;" +
                "  }" +
                "  " +
                "  var board = getBoard();" +
                "  if (board) {" +
                "    if (clickAt(board, '" + from + "')) {" +
                "       setTimeout(function() { clickAt(board, '" + to + "'); }, 200);" +
                "    }" +
                "  } else {" +
                "    console.error('[Bot] Board not found!');" +
                "  }" +
                "})();";

        browser.executeJavaScript(script, browser.getURL(), 0);
    }

    private void makeMoveLichess(String from, String to, String promotion, boolean isFlipped) {
        log.info("[Bot] Lichess Native Move: " + from + " -> " + to + " | Flipped: " + isFlipped);

        // 1. Inject JS to get Board Coordinates and trigger Java callback via Title
        // Change
        String script = "(function() {" +
                "  var board = document.querySelector('cg-board');" +
                "  if (board) {" +
                "    var rect = board.getBoundingClientRect();" +
                "    var isFlipped = " + isFlipped + ";" +
                "    var titleData = 'LICHESS_MOVE:' + rect.left + ',' + rect.top + ',' + rect.width + ',' + rect.height + ',' + isFlipped + ',' + '"
                + from + "' + ',' + '" + to + "';" +
                "    document.title = titleData;" +
                "  } else {" +
                "    console.error('[Bot] Board not found for native move');" +
                "  }" +
                "})();";

        browser.executeJavaScript(script, browser.getURL(), 0);
    }

    // This method should be called once during initialization
    public void registerDisplayHandler() {
        browser.getClient().addDisplayHandler(new org.cef.handler.CefDisplayHandlerAdapter() {
            @Override
            public void onTitleChange(CefBrowser browser, String title) {
                if (title != null) {
                    if (title.startsWith("LICHESS_MOVE:")) {
                        handleLichessMove(title);
                    } else if (title.startsWith("ORIENTATION:")) {
                        handleOrientation(title);
                    }
                }
            }
        });
    }

    private void handleOrientation(String title) {
        try {
            // Format: ORIENTATION:true/false
            String val = title.substring("ORIENTATION:".length());
            boolean isFlipped = Boolean.parseBoolean(val);
            log.info("[Bot] Orientation Detected: " + (isFlipped ? "FLIPPED" : "STANDARD"));

            // Notify Vision Service (via BrowserController, but we don't have direct access
            // here easily)
            // Actually, we can expose a listener or callback.
            // But wait, BotMover is used by BrowserController.
            // Let's add a callback to BotMover.
            if (onOrientationChanged != null) {
                onOrientationChanged.accept(isFlipped);
            }

        } catch (Exception e) {
            log.error("Unexpected error", e);
        }
    }

    private java.util.function.Consumer<Boolean> onOrientationChanged;

    public void setOnOrientationChanged(java.util.function.Consumer<Boolean> callback) {
        this.onOrientationChanged = callback;
    }

    public void checkOrientation() {
        String script = "(function() {" +
                "  var isLichess = window.location.href.includes('lichess');" +
                "  var isChessCom = window.location.href.includes('chess.com');" +
                "  var flipped = false;" +
                "  if (isLichess) {" +
                "     flipped = document.body.classList.contains('orientation-black') || document.querySelector('.cg-wrap.orientation-black') !== null;"
                +
                "  } else if (isChessCom) {" +
                "     var board = document.querySelector('chess-board') || document.querySelector('#board-layout-chessboard');"
                +
                "     if (board && (board.classList.contains('flipped') || board.classList.contains('flip-board'))) flipped = true;"
                +
                "     if (document.querySelector('.flipped') !== null) flipped = true;" +
                "  }" +
                "  document.title = 'ORIENTATION:' + flipped;" +
                "})();";
        browser.executeJavaScript(script, browser.getURL(), 0);
    }

    private void handleLichessMove(String titleData) {
        try {
            // Format: LICHESS_MOVE:left,top,width,height,isFlipped,from,to
            String data = titleData.substring("LICHESS_MOVE:".length());
            String[] parts = data.split(",");

            float left = Float.parseFloat(parts[0]);
            float top = Float.parseFloat(parts[1]);
            float width = Float.parseFloat(parts[2]);
            float height = Float.parseFloat(parts[3]);
            boolean isFlipped = Boolean.parseBoolean(parts[4]);
            String from = parts[5];
            String to = parts[6];

            clickSquareNative(left, top, width, height, isFlipped, from);

            // Small delay between clicks
            new Thread(() -> {
                try {
                    Thread.sleep(100); // 100ms delay
                    clickSquareNative(left, top, width, height, isFlipped, to);
                } catch (InterruptedException e) {
                    log.error("Unexpected error", e);
                }
            }).start();

        } catch (Exception e) {
            log.warn("[Bot] Error parsing move data: " + e.getMessage());
        }
    }

    private void clickSquareNative(float boardLeft, float boardTop, float boardWidth, float boardHeight,
            boolean isFlipped, String square) {
        int file = square.charAt(0) - 'a'; // 0-7
        int rank = square.charAt(1) - '1'; // 0-7
        log.info("[Bot] Calculating Click: Square=" + square + " Flipped=" + isFlipped + " Raw(f,r)=(" + file
                + "," + rank + ")");

        if (isFlipped) {
            file = 7 - file;
            // rank = rank; // Rank 1 (index 0) is at top (y=0) for flipped board
        } else {
            rank = 7 - rank; // Rank 8 is at top (y=0) for standard board
        }
        log.info("[Bot] Visual Logic: Target(col,row)=(" + file + "," + rank + ")");

        float squareW = boardWidth / 8;
        float squareH = boardHeight / 8;

        // Relative to Browser Content
        int relX = (int) (boardLeft + (file * squareW) + (squareW / 2));
        int relY = (int) (boardTop + (rank * squareH) + (squareH / 2));

        log.info("[Bot] Target Relative: " + square + " (" + relX + "," + relY + ")");

        // Convert to Screen Coordinates using the Browser Component
        try {
            java.awt.Component view = browser.getUIComponent();
            if (view != null && view.isShowing()) {
                java.awt.Point loc = view.getLocationOnScreen();
                int screenX = loc.x + relX;
                int screenY = loc.y + relY;

                log.info("[Bot] Robot Click: " + square + " Screen(" + screenX + "," + screenY + ")");

                java.awt.Robot robot = new java.awt.Robot();
                robot.mouseMove(screenX, screenY);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                }
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);

            } else {
                log.warn("[Bot] Browser component not showing, cannot calculate screen coordinates.");
            }
        } catch (Exception e) {
            log.error("Unexpected error", e);
        }
    }

    private String toNumeric(String square) {
        char file = square.charAt(0);
        char rank = square.charAt(1);
        int col = file - 'a' + 1;
        return "" + col + rank;
    }
}
