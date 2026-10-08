package io.github.hardin22.javachess.Browser.trial;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Browser.BoardProbe;
import io.github.hardin22.javachess.Browser.BoardSnapshot;
import io.github.hardin22.javachess.Browser.CdpPageDriver;
import io.github.hardin22.javachess.Browser.JcefRuntime;
import io.github.hardin22.javachess.Browser.SetupPosition;
import io.github.hardin22.javachess.Utils.PgnCodec;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.BotMover;
import io.github.hardin22.javachess.Vision.PieceClassifier;
import io.github.hardin22.javachess.Vision.PositionResolver;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Field trial of the page reading on the real sites: full games against the sites' computer opponents (never
 * against people), as an anonymous guest in a separate browser profile. The trial's own moves are chosen by
 * Stockfish and played through {@link BotMover} (DevTools clicks); every position is read from the page
 * ({@link BoardProbe}) and by the vision model on a screenshot of the board, and checked against the game replayed
 * independently. Board pictures are saved with their position as vision fixtures.
 *
 * <pre>
 * java -Djavachess.jcef.dir=... -Djavachess.home=... -cp target/test-classes:target/classes:CP \
 *     io.github.hardin22.javachess.Browser.trial.BotGameTrial lichess|chesscom GAMES OUT_DIR STOCKFISH
 * </pre>
 * Not a unit test (needs the network, a display and minutes per game).
 */
public final class BotGameTrial {

    private final String site;
    private final Path out;
    private final CdpPageDriver page;
    private final BotMover mover;
    private final PieceClassifier classifier;
    private final Engine engine;
    private final JSONArray games = new JSONArray();
    private final JSONArray manifest = new JSONArray();
    private int fixtureCount;
    private int gameIndex;
    private int fixturePly;

    private BotGameTrial(String site, Path out, CdpPageDriver page, PieceClassifier classifier, Engine engine) {
        this.site = site;
        this.out = out;
        this.page = page;
        this.mover = new BotMover(page);
        this.classifier = classifier;
        this.engine = engine;
    }

    public static void main(String[] args) throws Exception {
        String site = args[0];
        int count = Integer.parseInt(args[1]);
        Path out = Path.of(args[2]);
        Files.createDirectories(out.resolve("fixtures"));
        Engine engine = new Engine(args[3]);
        CefApp app = JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
            }

            @Override
            public void installing() {
            }
        }).get(15, TimeUnit.MINUTES);
        CefBrowser[] browser = new CefBrowser[1];
        boolean osr = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
        SwingUtilities.invokeAndWait(() -> {
            CefClient client = app.createClient();
            browser[0] = client.createBrowser("about:blank", osr, false);
            JFrame frame = new JFrame("trial");
            frame.add(browser[0].getUIComponent());
            frame.setBounds(0, 0, Integer.getInteger("trial.width", 720), Integer.getInteger("trial.height", 1280));
            frame.setVisible(true);
        });
        Thread.sleep(2000);
        CdpPageDriver page = new CdpPageDriver(browser[0]);
        BotGameTrial trial = new BotGameTrial(site, out, page, new PieceClassifier(PieceClassifier.DEFAULT_MODEL),
                engine);
        String pipeline = System.getProperty("trial.pipeline"); // vision-only | vision | page: the app's own classes
        for (int g = 0; g < count; g++) {
            Side mine = g % 2 == 0 ? Side.WHITE : Side.BLACK;
            JSONObject game;
            try {
                game = pipeline != null ? trial.playWithPipeline(mine, g,
                        io.github.hardin22.javachess.Browser.BoardWatcher.ReadMode.parse(pipeline))
                        : trial.playGame(mine, g);
            } catch (Exception e) {
                game = new JSONObject().put("error", e.toString());
                e.printStackTrace();
                trial.screenshot("error-" + g);
            }
            trial.games.put(game);
            Files.writeString(out.resolve("report-" + site + ".json"), trial.games.toString(1));
            System.out.println("GAME " + g + " " + game);
        }
        engine.close();
        System.out.println("DONE " + site);
        System.exit(0);
    }

    // ------------------------------------------------------------------ one game

    private JSONObject playGame(Side mine, int index) throws Exception {
        long started = System.currentTimeMillis();
        gameIndex = index;
        fixturePly = 0;
        startGame(mine);
        // a fresh game: a legal position (normally the start, or the requested one), no result shown
        String startFen = trialFen();
        Board startBoard = new Board();
        if (startFen != null) {
            startBoard.loadFromFen(startFen);
        }
        BoardSnapshot first = waitFor(s -> s.board() != null && s.board().placement() != null
                && "game".equals(s.pageHint()) && s.board().result() == null
                && (startFen != null || SetupPosition.build(s.board().placement(), s.board(), s.page(), Side.WHITE)
                != null)
                && (startFen != null ? sameOrNext(startBoard, s.board().placement())
                : s.board().placement().startsWith("rnbqkbnr/pppppppp")), 60_000, "a new game");
        Side bottom = first.board().bottomSide();
        System.out.println("Game " + index + " at " + first.url() + ", playing " + bottom);
        Board known = startBoard.clone();
        String lastPlacement = first.board().placement();
        if (!lastPlacement.equals(SetupPosition.placement(known))) {
            // the bot already moved
            PositionResolver.Resolution r = new PositionResolver().resolve(known, BoardReading.certain(lastPlacement),
                    false);
            if (r.confident() && r.moves().size() == 1) {
                known.doMove(r.moves().get(0));
            } else {
                known = SetupPosition.build(lastPlacement, first.board(), first.page(), Side.BLACK).board();
            }
        }
        JSONObject stats = new JSONObject().put("site", site).put("url", first.url()).put("side", bottom.name());
        int plies = 0;
        int ownMoves = 0;
        int botMoves = 0;
        int missed = 0;
        int ghosts = 0;
        int wrongPlacement = 0;
        long ownLatencyTotal = 0;
        long ownLatencyMax = 0;
        long probeTimeTotal = 0;
        int probes = 0;
        List<String> anomalies = new ArrayList<>();
        List<String> special = new ArrayList<>();
        long lastChange = System.currentTimeMillis();
        String result = null;
        checkVision(first, known, stats);
        while (true) {
            long t0 = System.nanoTime();
            BoardSnapshot s = probe();
            probeTimeTotal += (System.nanoTime() - t0) / 1_000_000;
            probes++;
            if (s.challenge()) {
                anomalies.add("challenge at ply " + plies);
            }
            if (s.board() == null) {
                if (System.currentTimeMillis() - lastChange > 20_000) {
                    anomalies.add("board gone at ply " + plies);
                    break;
                }
                Thread.sleep(200);
                continue;
            }
            if (s.board().result() != null) {
                result = s.board().result();
            }
            if (PgnCodec.resultOf(known) != null) {
                result = result != null ? result : PgnCodec.resultOf(known);
            }
            if (result != null) {
                break;
            }
            String placement = s.board().placement();
            boolean animating = s.board().animating();
            if (placement == null) {
                anomalies.add("placement unreadable at ply " + plies);
                Thread.sleep(200);
                continue;
            }
            if (!placement.equals(lastPlacement) && !animating) {
                // something moved: it must be exactly one legal move of the side to move (the bot)
                BoardSnapshot again = probe();
                if (again.board() == null || !placement.equals(again.board().placement())) {
                    continue; // not stable yet
                }
                PositionResolver.Resolution res = new PositionResolver().resolve(known, BoardReading.certain(placement),
                        false);
                if (res.confident() && res.moves().size() == 1) {
                    Move m = res.moves().get(0);
                    String san = PgnCodec.toSan(known, m);
                    note(special, known, m, san);
                    known.doMove(m);
                    botMoves++;
                    plies++;
                } else {
                    PositionResolver.Resolution two = new PositionResolver().resolve(known,
                            BoardReading.certain(placement), true);
                    if (two.confident() && two.moves().size() == 2) {
                        missed++; // two moves between two reads: one was not seen on its own
                        for (Move m : two.moves()) {
                            note(special, known, m, PgnCodec.toSan(known, m));
                            known.doMove(m);
                            plies++;
                        }
                    } else {
                        SetupPosition.Result rebuilt = SetupPosition.build(placement, s.board(), s.page(),
                                known.getSideToMove().flip());
                        if (rebuilt == null) {
                            // not a legal position: e.g. the "Checkmate" badge chess.com draws over the mated king
                            anomalies.add("illegal position shown at ply " + plies + ": " + placement);
                            dumpPieces("illegal ply " + plies + " known " + known.getFen() + " lastMove "
                                    + s.board().lastMove());
                            if (PgnCodec.resultOf(known) == null && System.currentTimeMillis() - lastChange > 10_000) {
                                break;
                            }
                            Thread.sleep(300);
                            continue;
                        }
                        ghosts++;
                        anomalies.add("unexplained position at ply " + plies + ": " + placement);
                        dumpPieces("unexplained ply " + plies + " known " + known.getFen() + " lastMove "
                                + s.board().lastMove() + " animating " + s.board().animating());
                        known = rebuilt.board();
                    }
                }
                lastPlacement = placement;
                lastChange = System.currentTimeMillis();
                if (!placement.equals(SetupPosition.placement(known))) {
                    wrongPlacement++;
                }
                checkVision(s, known, stats);
                continue;
            }
            if (known.getSideToMove() == bottom && !animating) {
                String uci = Boolean.getBoolean("trial.special") ? special(known) : null;
                String scripted = System.getProperty("trial.firstMove");
                if (scripted != null && ownMoves == 0) {
                    uci = scripted; // a forcing first move (e.g. a check that leaves the bot a single reply)
                }
                if (uci == null) {
                    uci = engine.bestMove(known.getFen());
                }
                Move m = PgnCodec.fromUci(known, uci);
                String expected;
                Board after = known.clone();
                after.doMove(m);
                expected = SetupPosition.placement(after);
                long sent = System.currentTimeMillis();
                mover.play(PgnCodec.toUci(m)).get(10, TimeUnit.SECONDS);
                BoardSnapshot shown = null;
                for (int i = 0; i < 100; i++) {
                    BoardSnapshot x = probe();
                    if (x.board() != null && expected.equals(x.board().placement())) {
                        shown = x;
                        break;
                    }
                    if (x.board() != null && x.board().placement() != null) {
                        // the bot may already have answered: our move plus one of its moves
                        PositionResolver.Resolution r = new PositionResolver().resolve(after,
                                BoardReading.certain(x.board().placement()), false);
                        if (r.confident() && r.moves().size() == 1) {
                            shown = x;
                            break;
                        }
                    }
                    Thread.sleep(50);
                }
                if (shown == null) {
                    anomalies.add("own move " + uci + " not shown at ply " + plies);
                    screenshot("notshown-" + index + "-" + plies);
                    // try once more, like the app does
                    mover.play(PgnCodec.toUci(m)).get(10, TimeUnit.SECONDS);
                    Thread.sleep(2000);
                    BoardSnapshot x = probe();
                    if (x.board() == null || !expected.equals(x.board().placement())) {
                        anomalies.add("own move " + uci + " refused");
                        break;
                    }
                    shown = x;
                }
                long latency = System.currentTimeMillis() - sent;
                ownLatencyTotal += latency;
                ownLatencyMax = Math.max(ownLatencyMax, latency);
                note(special, known, m, PgnCodec.toSan(known, m));
                known.doMove(m);
                ownMoves++;
                plies++;
                lastPlacement = expected;
                lastChange = System.currentTimeMillis();
                checkVision(shown, known, stats);
                continue;
            }
            if (System.currentTimeMillis() - lastChange > 90_000) {
                anomalies.add("no move for 90 s at ply " + plies);
                screenshot("stuck-" + index + "-" + plies);
                break;
            }
            Thread.sleep(100);
        }
        screenshot("end-" + site + "-" + index);
        try {
            // how the site shows the end of the game (for the probe's result detection)
            String end = page.evaluate("(() => { const b = document.querySelector('wc-chess-board, cg-board');"
                    + " const inBoard = b ? [...b.querySelectorAll('*')].filter(e => !e.classList.contains('piece')"
                    + " && !/^(square|piece)$/i.test(e.tagName)).slice(0, 25).map(e => e.tagName + '.' + e.className"
                    + " + ':' + (e.textContent || '').trim().slice(0, 20)) : [];"
                    + " const texts = [...document.querySelectorAll('[class*=game-over], [class*=result], .status')]"
                    + ".slice(0, 15).map(e => e.className + ':' + (e.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 60));"
                    + " return {inBoard, texts}; })()").get(10, TimeUnit.SECONDS);
            System.out.println("END DOM " + site + " " + index + " " + end);
        } catch (Exception e) {
            System.out.println("END DOM failed " + e);
        }
        return stats.put("plies", plies).put("ownMoves", ownMoves).put("botMoves", botMoves).put("missed", missed)
                .put("ghosts", ghosts).put("placementMismatches", wrongPlacement).put("result", String.valueOf(result))
                .put("ownMoveShownMsAvg", ownMoves == 0 ? 0 : ownLatencyTotal / ownMoves)
                .put("ownMoveShownMsMax", ownLatencyMax).put("probeMsAvg", probes == 0 ? 0 : probeTimeTotal / probes)
                .put("special", new JSONArray(special)).put("anomalies", new JSONArray(anomalies))
                .put("seconds", (System.currentTimeMillis() - started) / 1000);
    }

    /**
     * A game followed by the app's own pipeline: {@code BoardWatcher} (reading mode as given) feeding
     * {@code OnlineGameSync}, our moves made "on the board" (a board without sensors, like the app without the
     * PCB) and played on the page by BotMover. The page's markup is read independently as the truth.
     */
    private JSONObject playWithPipeline(Side mine, int index,
                                        io.github.hardin22.javachess.Browser.BoardWatcher.ReadMode mode)
            throws Exception {
        long started = System.currentTimeMillis();
        gameIndex = index;
        startGame(mine);
        waitFor(s -> s.board() != null && s.board().placement() != null && "game".equals(s.pageHint())
                && s.board().result() == null
                && s.board().placement().startsWith("rnbqkbnr/pppppppp"), 60_000, "a new game");
        java.util.concurrent.ScheduledExecutorService watchThread = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor();
        java.util.concurrent.ExecutorService owner = java.util.concurrent.Executors.newSingleThreadExecutor();
        io.github.hardin22.javachess.Services.VisionService vision = new io.github.hardin22.javachess.Services
                .VisionService();
        java.util.concurrent.atomic.AtomicReference<io.github.hardin22.javachess.Browser.PhysicalBoard.Listener> hands =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.hardin22.javachess.Browser.PhysicalBoard noSensors = new io.github.hardin22.javachess.Browser
                .PhysicalBoard() {
            @Override
            public boolean isConnected() {
                return false;
            }

            @Override
            public void attach(Listener l) {
                hands.set(l);
            }

            @Override
            public void detach() {
            }

            @Override
            public void setup(String fen) {
                hands.get().onSetupComplete();
            }

            @Override
            public void play(Board position, Side movingSide) {
            }

            @Override
            public void setPosition(Board position) {
            }

            @Override
            public void replicate(Board after, String from, String to) {
                hands.get().onReplicated();
            }
        };
        List<String> phases = new java.util.concurrent.CopyOnWriteArrayList<>();
        io.github.hardin22.javachess.Browser.OnlineGameSync sync = new io.github.hardin22.javachess.Browser
                .OnlineGameSync(noSensors, mover::play, g -> { }, st -> phases.add(st.phase().name()),
                System::currentTimeMillis, owner);
        io.github.hardin22.javachess.Browser.BoardWatcher watcher = new io.github.hardin22.javachess.Browser
                .BoardWatcher(page, vision, watchThread, new io.github.hardin22.javachess.Browser.BoardWatcher.Listener() {
            @Override
            public void onSnapshot(BoardSnapshot snapshot) {
                owner.execute(sync::tick);
            }

            @Override
            public void onPosition(io.github.hardin22.javachess.Browser.BoardWatcher.PositionUpdate update) {
                owner.execute(() -> sync.onPosition(update));
            }

            @Override
            public void onProblem(io.github.hardin22.javachess.Browser.BoardWatcher.Problem problem) {
                if (problem != null) {
                    phases.add("PROBLEM_" + problem);
                }
            }
        }, mode);
        BoardSnapshot first = probe();
        owner.submit(() -> sync.start(io.github.hardin22.javachess.Browser.OnlineGameSync.Mode.PLAY,
                first.page())).get();
        watcher.start();
        int checks = 0;
        int diverged = 0;
        int ownMoves = 0;
        long lastProgress = System.currentTimeMillis();
        int lastPlies = 0;
        String result = null;
        List<String> anomalies = new ArrayList<>();
        try {
            while (System.currentTimeMillis() - lastProgress < 90_000) {
                Thread.sleep(250);
                io.github.hardin22.javachess.Browser.OnlineGameSync.State st = owner.submit(sync::state).get();
                Board followed = owner.submit(sync::position).get();
                if (st.plies() != lastPlies) {
                    lastPlies = st.plies();
                    lastProgress = System.currentTimeMillis();
                }
                BoardSnapshot truth = probe();
                if (truth.board() != null && truth.board().result() != null) {
                    result = truth.board().result();
                }
                if (st.phase() == io.github.hardin22.javachess.Browser.OnlineGameSync.Phase.GAME_OVER
                        || result != null) {
                    result = result != null ? result : st.result();
                    break;
                }
                if (st.phase() == io.github.hardin22.javachess.Browser.OnlineGameSync.Phase.MY_TURN) {
                    String uci = engine.bestMove(followed.getFen());
                    owner.submit(() -> hands.get().onPhysicalMove(uci.substring(0, 2).toUpperCase(),
                            uci.substring(2, 4).toUpperCase())).get();
                    ownMoves++;
                    continue;
                }
                boolean settled = st.phase() == io.github.hardin22.javachess.Browser.OnlineGameSync.Phase.OPPONENT_TURN
                        && truth.board() != null && !truth.board().animating() && truth.board().placement() != null;
                if (settled && !SetupPosition.placement(followed).equals(truth.board().placement())) {
                    // the page moved on: vision needs still frames to see it; give the pipeline 4 s to catch up
                    String page = truth.board().placement();
                    String followedNow = SetupPosition.placement(followed);
                    long until = System.currentTimeMillis() + 4000;
                    while (System.currentTimeMillis() < until && !followedNow.equals(page)) {
                        Thread.sleep(200);
                        followedNow = SetupPosition.placement(owner.submit(sync::position).get());
                        BoardSnapshot again = probe();
                        if (again.board() != null && again.board().placement() != null
                                && !again.board().placement().equals(page)) {
                            page = again.board().placement(); // the page moved again (our move): start over
                            until = System.currentTimeMillis() + 4000;
                        }
                    }
                    checks++;
                    if (!followedNow.equals(page)) {
                        diverged++;
                        // how late: keep watching (the game goes on only when the opponent's move is followed)
                        long lateSince = until - 4000;
                        while (System.currentTimeMillis() - lateSince < 30_000 && !followedNow.equals(page)) {
                            Thread.sleep(200);
                            followedNow = SetupPosition.placement(owner.submit(sync::position).get());
                        }
                        String late = followedNow.equals(page)
                                ? "followed after " + (System.currentTimeMillis() - lateSince) + " ms" : "not followed";
                        System.out.println("LATE " + java.time.LocalTime.now() + " ply " + st.plies() + " " + late);
                        anomalies.add("ply " + st.plies() + ": followed " + followedNow + " page " + page + " (" + late
                                + ")");
                    }
                } else if (settled) {
                    checks++;
                }
            }
        } finally {
            watcher.stop();
            owner.submit(sync::stop).get();
            watchThread.shutdownNow();
            owner.shutdownNow();
        }
        screenshot("end-" + site + "-" + mode + "-" + index);
        long uncertain = phases.stream().filter("UNCERTAIN"::equals).count();
        long setups = phases.stream().filter("SETUP"::equals).count();
        long notAccepted = phases.stream().filter("NOT_ACCEPTED"::equals).count();
        int[] reads = vision.readerStats();
        vision.close();
        return new JSONObject().put("site", site).put("pipeline", mode.name()).put("side", mine.name())
                .put("plies", lastPlies).put("ownMoves", ownMoves).put("result", String.valueOf(result))
                .put("checks", checks).put("diverged", diverged).put("uncertainPhases", uncertain)
                .put("setups", setups).put("notAccepted", notAccepted)
                .put("calibratedReads", reads[0]).put("modelReads", reads[1])
                .put("visionCalibrated", vision.isCalibrated())
                .put("anomalies", new JSONArray(anomalies.subList(0, Math.min(10, anomalies.size()))))
                .put("seconds", (System.currentTimeMillis() - started) / 1000);
    }

    /** -Dtrial.fen, with '_' for the spaces (shell-friendly). */
    private static String trialFen() {
        String fen = System.getProperty("trial.fen");
        return fen == null ? null : fen.replace('_', ' ');
    }

    /** True when the placement is the board's, or the board's after one legal move. */
    private static boolean sameOrNext(Board board, String placement) {
        if (SetupPosition.placement(board).equals(placement)) {
            return true;
        }
        for (Move m : com.github.bhlangonijr.chesslib.move.MoveGenerator.generateLegalMoves(board)) {
            Board b = board.clone();
            b.doMove(m);
            if (SetupPosition.placement(b).equals(placement)) {
                return true;
            }
        }
        return false;
    }

    /** An en passant capture or a promotion when one is legal (to try them on the real sites), else null. */
    private static String special(Board board) {
        String promotion = null;
        for (Move m : com.github.bhlangonijr.chesslib.move.MoveGenerator.generateLegalMoves(board)) {
            boolean pawn = board.getPiece(m.getFrom()).getPieceType() == com.github.bhlangonijr.chesslib.PieceType.PAWN;
            // chesslib: getEnPassant() is where the capturing pawn lands, getEnPassantTarget() the captured pawn
            if (pawn && m.getTo() == board.getEnPassant() && board.getEnPassant()
                    != com.github.bhlangonijr.chesslib.Square.NONE) {
                return PgnCodec.toUci(m);
            }
            if (m.getPromotion() != null && m.getPromotion() != com.github.bhlangonijr.chesslib.Piece.NONE
                    && m.getPromotion().getPieceType() == com.github.bhlangonijr.chesslib.PieceType.QUEEN) {
                promotion = PgnCodec.toUci(m);
            }
        }
        return promotion;
    }

    private static void note(List<String> special, Board before, Move m, String san) {
        if (san.startsWith("O-O")) {
            special.add("castling " + san);
        }
        if (m.getPromotion() != null && m.getPromotion() != com.github.bhlangonijr.chesslib.Piece.NONE) {
            special.add("promotion " + san);
        }
        if (before.getEnPassant() != com.github.bhlangonijr.chesslib.Square.NONE
                && m.getTo() == before.getEnPassant() && san.contains("x")
                && before.getPiece(m.getFrom()).getPieceType() == com.github.bhlangonijr.chesslib.PieceType.PAWN) {
            special.add("en passant " + san);
        }
    }

    /** Vision on the same position: a screenshot of the board read by the model, compared with the page. */
    private void checkVision(BoardSnapshot s, Board known, JSONObject stats) {
        try {
            BoardSnapshot.BoardView b = s.board();
            if (b != null && !b.rect().mostlyInside(s.viewportWidth(), s.viewportHeight())) {
                // the page scrolled the board away (chess.com follows the move list): bring it back, like the app
                page.evaluate(BotMover.SCROLL_BOARD_INTO_VIEW).get(5, TimeUnit.SECONDS);
                Thread.sleep(400);
                s = probe();
                b = s.board();
                stats.put("scrolledBack", stats.optInt("scrolledBack") + 1);
            }
            if (b == null || !b.rect().mostlyInside(s.viewportWidth(), s.viewportHeight()) || b.animating()
                    || b.placement() == null) {
                stats.put("visionSkipped", stats.optInt("visionSkipped") + 1);
                if (stats.optInt("visionSkipped") <= 3) {
                    System.out.println("SKIP " + (b == null ? "no board" : b.rect() + " animating " + b.animating()
                            + " placement " + b.placement()) + " viewport " + s.viewportWidth() + "x"
                            + s.viewportHeight());
                }
                return;
            }
            BufferedImage img = io.github.hardin22.javachess.Browser.BoardPicture.take(page, s).get(10, TimeUnit.SECONDS);
            long t0 = System.nanoTime();
            BoardReading r = classifier.read(img, b.flipped(), null).withPlacementRules();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // truth: the page at this moment (the bot may already have answered); the trial checks the page
            String truth = b.placement();
            Board shownBoard = new Board();
            shownBoard.loadFromFen(truth + " w - - 0 1");
            int wrong = PositionResolver.differences(shownBoard, r).size();
            stats.put("visionChecked", stats.optInt("visionChecked") + 1);
            stats.put("visionExact", stats.optInt("visionExact") + (wrong == 0 ? 1 : 0));
            stats.put("visionSquaresWrong", stats.optInt("visionSquaresWrong") + wrong);
            stats.put("visionMsTotal", stats.optLong("visionMsTotal") + ms);
            String name = String.format("%s-%03d", site, fixtureCount++);
            ImageIO.write(img, "png", out.resolve("fixtures").resolve(name + ".png").toFile());
            manifest.put(new JSONObject().put("file", name + ".png").put("placement", truth)
                    .put("fen", truth.equals(SetupPosition.placement(known)) ? known.getFen() : truth + " w - - 0 1")
                    .put("flipped", b.flipped()).put("site", site)
                    .put("theme", "default").put("pieces", "default").put("category", "live game")
                    .put("lastMove", new JSONArray(b.lastMove())).put("game", site + "-" + gameIndex)
                    .put("ply", fixturePly).put("group", site + "-" + gameIndex)
                    .put("calibration", fixturePly == 0).put("visionModel", r.placement())
                    .put("visionWrongSquares", wrong).put("width", img.getWidth()));
            fixturePly++;
            Files.writeString(out.resolve("fixtures").resolve("manifest.json"), manifest.toString(1));
        } catch (Exception e) {
            stats.put("visionErrors", stats.optInt("visionErrors") + 1);
            stats.put("visionLastError", e.toString());
        }
    }

    /** Reads the page, retrying when it does not answer for a moment (the app's watcher does the same). */
    private BoardSnapshot probe() throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return BoardProbe.read(page).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                System.out.println("probe retry " + attempt + ": " + e);
                Thread.sleep(1000);
            }
        }
        throw last;
    }

    /** Logs the board's piece elements (class, inline style) to understand transient readings. */
    private void dumpPieces(String what) {
        try {
            String dom = page.evaluate("(() => { const b = document.querySelector('wc-chess-board, cg-board');"
                    + " if (!b) return 'no board'; return [...b.children].map(e => e.tagName + '.' + e.className"
                    + " + (e.getAttribute('style') ? '{' + e.getAttribute('style') + '}' : '')).join(' | '); })()")
                    .get(5, TimeUnit.SECONDS);
            System.out.println("DOM " + what + " :: " + dom);
        } catch (Exception e) {
            System.out.println("DOM dump failed " + e);
        }
    }

    // ------------------------------------------------------------------ starting a game against the computer

    private void startGame(Side mine) throws Exception {
        String fen = trialFen();
        if (site.equals("lichess")) {
            navigate(fen == null ? "https://lichess.org/"
                    : "https://lichess.org/?fen=" + java.net.URLEncoder.encode(fen, java.nio.charset.StandardCharsets.UTF_8)
                    + "#ai");
            waitFor(s -> s.title().toLowerCase(Locale.ROOT).contains("lichess"), 30_000, "lichess home");
            // the lobby's "play with the computer" dialog: level 1, our colour
            String colour = mine == Side.WHITE ? "white" : "black";
            for (int attempt = 0; attempt < 20; attempt++) {
                String r = page.evaluate(LICHESS_START.replace("COLOUR", colour)).get(10, TimeUnit.SECONDS);
                System.out.println("lichess start: " + r);
                if (r.contains("started")) {
                    return;
                }
                Thread.sleep(1000);
            }
            screenshot("lichess-start-failed");
            throw new IllegalStateException("Could not start a game against the computer on lichess");
        }
        navigate(fen == null ? "https://www.chess.com/play/computer"
                : "https://www.chess.com/play/computer?fen=" + java.net.URLEncoder.encode(fen,
                java.nio.charset.StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < 40; attempt++) {
            String r;
            try {
                r = page.evaluate(CHESSCOM_START.replace("COLOUR", mine == Side.WHITE ? "white" : "black"))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                r = "no answer: " + e;
            }
            System.out.println("chess.com start: " + r);
            if (r.contains("started")) {
                return;
            }
            Thread.sleep(1500);
        }
        screenshot("chesscom-start-failed");
        throw new IllegalStateException("Could not start a game against the computer on chess.com");
    }

    /** lichess lobby: "play against the computer" dialog, strength 1, our side, play. */
    private static final String LICHESS_START = "(() => {"
            + " const dialog = document.querySelector('dialog .game-setup');"
            + " if (!dialog) {"
            + "   const open = document.querySelector('.lobby__start__button--ai')"
            + "     || [...document.querySelectorAll('button')].find(b => /computer/i.test(b.textContent));"
            + "   if (open) { open.click(); return 'opened'; }"
            + "   return 'no lobby button';"
            + " }"
            + " const level = dialog.querySelector('#sf_level_1');"
            + " if (level) level.click();"
            + " const side = dialog.querySelector('#color-picker-COLOUR');"
            + " if (!side) return 'no side';"
            + " side.click();"
            + " const submit = [...dialog.querySelectorAll('button')].filter(b => /computer/i.test(b.textContent)).pop();"
            + " if (!submit) return 'no submit';"
            + " submit.click();"
            + " return 'started';"
            + "})()";

    /**
     * chess.com's guest flow on /play/computer: close sign-up prompts, pick the first (weakest) bot, choose the
     * colour, press Play. Button texts may be Italian (browser locale) or English.
     */
    private static final String CHESSCOM_START = "(() => {"
            + " const visible = (e) => e && e.getBoundingClientRect().width > 0;"
            + " const btns = [...document.querySelectorAll('button, a')].filter(visible);"
            + " const byText = (re) => btns.find(b => re.test((b.textContent || '').replace(/\\s+/g, ' ').trim()));"
            + " const reject = byText(/^(reject all|rifiuta tutto)$/i);"
            + " if (reject) { reject.click(); return 'cookies rejected'; }"
            + " const start = byText(/^(start|inizia)$/i);"
            + " if (start) { start.click(); return 'intro closed'; }"
            + " const fresh = byText(/^(new game|nuova partita|rematch|rivincita)$/i);"
            + " if (fresh && /game over|checkmate|wins|vince|draw|patta/i.test(document.body.innerText || '')) { fresh.click(); return 'new game'; }"
            + " const side = [...document.querySelectorAll('.play-side-selector-option')]"
            + "   .find(e => (e.getAttribute('style') || '').includes('/COLOUR'));"
            + " if (side && visible(side) && side.getAttribute('data-playing-as-selected') !== 'true') {"
            + "   side.click(); return 'side chosen'; }"
            + " const play = document.querySelector('.bot-selection-cta-button-button') || byText(/^(play|gioca)$/i);"
            + " if (play && visible(play)) { play.click(); return 'pressed play'; }"
            + " const ingame = [...document.querySelectorAll('button')].filter(visible).some(b => /resign|abbandon/i.test((b.getAttribute('aria-label') || '') + (b.title || '')))"
            + "   || /Starting Position|Posizione iniziale/i.test(document.body.innerText || '');"
            + " if (document.querySelector('wc-chess-board') && ingame) return 'started';"
            + " return 'waiting: ' + btns.slice(0, 14).map(b => (b.textContent || '').trim().slice(0, 16)).join('|');"
            + "})()";

    private void navigate(String url) throws Exception {
        page.evaluate("location.href = " + JSONObject.quote(url)).get(10, TimeUnit.SECONDS);
        Thread.sleep(3000);
    }

    private BoardSnapshot waitFor(java.util.function.Predicate<BoardSnapshot> condition, long ms, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try {
                BoardSnapshot s = probe();
                if (condition.test(s)) {
                    return s;
                }
            } catch (Exception e) {
                // loading
            }
            Thread.sleep(500);
        }
        screenshot("timeout-" + what.replace(' ', '-'));
        throw new IllegalStateException("Timed out waiting for " + what);
    }

    private void screenshot(String name) {
        try {
            BufferedImage img = page.screenshot(null).get(10, TimeUnit.SECONDS);
            ImageIO.write(img, "png", out.resolve(name + ".png").toFile());
        } catch (Exception e) {
            System.out.println("screenshot failed: " + e);
        }
    }

    // ------------------------------------------------------------------ Stockfish for the trial's own moves

    static final class Engine implements AutoCloseable {
        private final Process process;
        private final PrintWriter in;
        private final BufferedReader outReader;

        Engine(String path) throws Exception {
            process = new ProcessBuilder(path).redirectErrorStream(true).start();
            in = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
            outReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            in.println("uci");
            in.println("setoption name Threads value 1");
            // weak, human-like play by default: the trial is about reading the page, not about winning
            in.println("setoption name Skill Level value " + Integer.getInteger("trial.skill", 3));
            in.println("isready");
            String line;
            while ((line = outReader.readLine()) != null && !line.equals("readyok")) {
                // skip
            }
        }

        String bestMove(String fen) throws Exception {
            in.println("position fen " + fen);
            in.println("go movetime " + Integer.getInteger("trial.movetime", 100));
            String line;
            while ((line = outReader.readLine()) != null) {
                if (line.startsWith("bestmove")) {
                    return line.split(" ")[1];
                }
            }
            throw new IllegalStateException("Stockfish stopped");
        }

        @Override
        public void close() {
            in.println("quit");
            process.destroy();
        }
    }

}
