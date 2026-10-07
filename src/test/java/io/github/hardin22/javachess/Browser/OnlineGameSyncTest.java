package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Utils.PgnCodec;
import io.github.hardin22.javachess.Vision.BoardReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synchronisation between the page and the physical board, driven by hand: the site ({@link FakeSite}), the
 * user's hands ({@link FakePhysicalBoard}) and the clock are all under the test's control, so every situation of a
 * real game (including misreadings and a site that ignores a move) is reproduced exactly.
 */
class OnlineGameSyncTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";

    private FakeSite site;
    private FakePhysicalBoard board;
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<ArchivedGame> archived = new ArrayList<>();
    private final List<OnlineGameSync.State> states = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private OnlineGameSync sync;

    @BeforeEach
    void setUp() {
        site = new FakeSite();
        board = new FakePhysicalBoard();
        sync = new OnlineGameSync(board, uci -> {
            sent.add(uci);
            if (site.acceptsClicks) {
                site.opponentPlays(uci); // the site takes the move (BotMover is tested separately)
            }
            return CompletableFuture.completedFuture(null);
        }, archived::add, states::add, clock::get, Runnable::run);
    }

    // ------------------------------------------------------------------ helpers

    private BoardSnapshot snapshot() {
        return BoardProbe.parse(site.json());
    }

    /** Both readings agree with the site. */
    private void see() {
        see(BoardWatcher.ReadMode.VISION, site.placement(), true);
    }

    private void see(BoardWatcher.ReadMode mode, String vision, boolean page) {
        sync.onPosition(new BoardWatcher.PositionUpdate(snapshot(), vision == null ? null : BoardReading.certain(vision),
                page ? BoardReading.certain(site.placement()) : null, mode));
    }

    private void startGame(boolean flipped) {
        site.flipped = flipped;
        sync.start(OnlineGameSync.Mode.PLAY, PageInfo.of(site.url).withHint("game"));
        see();
        assertEquals(OnlineGameSync.Phase.SETUP, sync.phase());
        assertEquals(PgnCodec.START_FEN, board.setupFen);
        board.setupDone();
    }

    private void later(long ms) {
        clock.addAndGet(ms);
        sync.tick();
    }

    // ------------------------------------------------------------------ a normal game

    @Test
    void aGameAsWhite() {
        startGame(false);
        assertEquals(OnlineGameSync.Phase.MY_TURN, sync.phase());
        assertEquals(Side.WHITE, board.movingSide, "only white is detected on the board");

        board.move("e2e4");
        assertEquals(OnlineGameSync.Phase.SENDING, sync.phase());
        assertEquals(List.of("e2e4"), sent);
        see(); // the page shows the move
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
        assertEquals(List.of("e2e4"), sync.moves());
        assertEquals(0, board.count("replicate"), "our own move is not replicated");

        site.opponentPlays("e7e5");
        see();
        assertEquals(OnlineGameSync.Phase.REPLICATE, sync.phase());
        assertEquals("e7e5", board.replication);
        assertEquals("e5", sync.state().lastMove());
        see(); // the same position read again: nothing happens
        assertEquals(1, board.count("replicate"), "a move is replicated once");

        board.replicated();
        assertEquals(OnlineGameSync.Phase.MY_TURN, sync.phase());
        assertEquals(List.of("e2e4", "e7e5"), sync.moves());
    }

    @Test
    void theBoardOrientationGivesTheUsersSide() {
        startGame(true);
        assertEquals(Side.BLACK, board.movingSide);
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
        site.opponentPlays("d2d4");
        see();
        board.replicated();
        assertEquals(OnlineGameSync.Phase.MY_TURN, sync.phase());
        board.move("d7d5");
        see();
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
    }

    @Test
    void theSiteTurningTheBoardBeforeTheFirstMoveChangesSide() {
        startGame(false);
        assertEquals(Side.WHITE, board.movingSide);
        site.flipped = true; // chess.com flips the board when the game starts and we are black
        see();
        assertEquals(Side.BLACK, board.movingSide);
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
    }

    // ------------------------------------------------------------------ moves the site does not take

    @Test
    void aMoveTheSiteDoesNotShowIsSentAgainThenTakenBack() {
        startGame(false);
        site.acceptsClicks = false;
        board.move("e2e4");
        later(1000);
        see(); // still the start position
        assertEquals(OnlineGameSync.Phase.SENDING, sync.phase());
        later(2100);
        assertEquals(List.of("e2e4", "e2e4"), sent, "sent again after 3 s");
        later(3100);
        assertEquals(OnlineGameSync.Phase.NOT_ACCEPTED, sync.phase());
        assertEquals("e2", sync.state().detail());
        assertTrue(board.calls.get(board.calls.size() - 1).startsWith("position " + PgnCodec.START_FEN),
                "the LEDs ask to take the move back");

        site.acceptsClicks = true; // e.g. the popup that blocked the board was closed
        board.move("d2d4");
        see();
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
        assertEquals(List.of("d2d4"), sync.moves());
    }

    @Test
    void aMoveTheSiteShowsLateIsStillTaken() {
        startGame(false);
        site.acceptsClicks = false;
        board.move("e2e4");
        later(3100);
        later(3100);
        assertEquals(OnlineGameSync.Phase.NOT_ACCEPTED, sync.phase());
        site.opponentPlays("e2e4"); // the site finally shows it
        see();
        assertEquals(OnlineGameSync.Phase.REPLICATE, sync.phase(), "the board is asked to show it again");
        assertEquals(List.of("e2e4"), sync.moves());
    }

    @Test
    void movesOnTheBoardOutOfTurnAreTakenBack() {
        startGame(true); // black, white to move
        board.move("e7e5");
        assertTrue(sent.isEmpty());
        assertTrue(board.calls.get(board.calls.size() - 1).startsWith("position "));
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
    }

    @Test
    void myMoveAndTheReplyInOneReading() {
        startGame(false);
        site.acceptsClicks = true;
        board.move("e2e4");
        site.opponentPlays("c7c5"); // bullet: the reply comes before the next reading
        see();
        assertEquals(List.of("e2e4", "c7c5"), sync.moves());
        assertEquals("c7c5", board.replication);
        assertEquals(OnlineGameSync.Phase.REPLICATE, sync.phase());
    }

    @Test
    void aMoveMadeOnTheScreenIsReplicatedOnTheBoard() {
        startGame(false);
        site.opponentPlays("g1f3"); // the user tapped the screen
        see();
        assertEquals("g1f3", board.replication);
        assertEquals(OnlineGameSync.Phase.REPLICATE, sync.phase());
        board.replicated();
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
    }

    // ------------------------------------------------------------------ two readings

    @Test
    void aGhostMoveSeenByVisionIsNeverApplied() {
        startGame(false);
        board.move("e2e4");
        see();
        String ghost = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR"; // vision "sees" e7e5
        see(BoardWatcher.ReadMode.VISION, ghost, true);
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
        later(OnlineGameSync.DISAGREE_MS + 100); // still disagreeing: the page wins
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
        assertEquals(List.of("e2e4"), sync.moves());
        assertEquals(0, board.count("replicate"));
        assertEquals(1, sync.pageFallbacks());
    }

    @Test
    void aMoveSeenFirstOnThePageWaitsForVision() {
        startGame(false);
        board.move("e2e4");
        see();
        String before = site.placement();
        site.opponentPlays("e7e5");
        see(BoardWatcher.ReadMode.VISION, before, true); // vision still has the old picture
        assertEquals(0, board.count("replicate"));
        later(500);
        see(); // vision catches up
        assertEquals(1, board.count("replicate"));
        assertEquals(0, sync.pageFallbacks());
    }

    @Test
    void whenVisionKeepsMissingAMoveThePageStandsIn() {
        startGame(false);
        board.move("e2e4");
        see();
        String before = site.placement();
        site.opponentPlays("e7e5");
        see(BoardWatcher.ReadMode.VISION, before, true);
        later(OnlineGameSync.DISAGREE_MS + 100);
        assertEquals(1, board.count("replicate"));
        assertEquals("e7e5", board.replication);
    }

    /** A vision reading of {@code placement} that is unsure about one square (60% what it shows, 40% {@code alt}). */
    private static BoardReading unsure(String placement, String square, char alt) {
        float[][][] p = BoardReading.certain(placement).probabilities();
        int f = square.charAt(0) - 'a';
        int r = square.charAt(1) - '1';
        char shown = BoardReading.certain(placement).pieceAt(f, r);
        java.util.Arrays.fill(p[f][r], 0f);
        p[f][r][BoardReading.SYMBOLS.indexOf(shown)] = 0.6f;
        p[f][r][BoardReading.SYMBOLS.indexOf(alt)] = 0.4f;
        return new BoardReading(p, true, 1);
    }

    @Test
    void visionMisreadingASquareStillGivesTheRightMove() {
        startGame(false);
        board.move("e2e4");
        see();
        site.opponentPlays("e7e5");
        // vision does not see the a2 pawn well (a misread square far from the move): the move is still e7e5
        String misread = site.placement().replace("PPPP1PPP", "1PPP1PPP");
        sync.onPosition(new BoardWatcher.PositionUpdate(snapshot(), unsure(misread, "a2", 'P'),
                BoardReading.certain(site.placement()), BoardWatcher.ReadMode.VISION));
        assertEquals("e7e5", board.replication);
        assertEquals(0, sync.pageFallbacks());
    }

    @Test
    void anUndecidedVisionReadingLetsThePageDecideAfterAWhile() {
        startGame(false);
        board.move("e2e4");
        see();
        site.opponentPlays("e7e5");
        // a certain misreading of a2: "e7e5" and "e7e5 then a2-a3" explain it almost equally well
        see(BoardWatcher.ReadMode.VISION, site.placement().replace("PPPP1PPP", "1PPP1PPP"), true);
        assertNull(board.replication, "no move while the readings do not agree");
        later(OnlineGameSync.DISAGREE_MS + 100);
        assertEquals("e7e5", board.replication);
        assertEquals(List.of("e2e4", "e7e5"), sync.moves());
    }

    @Test
    void pageModeFollowsThePageAtOnce() {
        startGame(false);
        board.move("e2e4");
        see(BoardWatcher.ReadMode.PAGE, null, true);
        site.opponentPlays("e7e5");
        see(BoardWatcher.ReadMode.PAGE, START, true); // vision disagrees: only logged
        assertEquals("e7e5", board.replication);
    }

    @Test
    void visionOnlyWaitsBeforeResynchronising() {
        startGame(false);
        String garbage = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKB1R"; // a knight vanished: no move explains it
        see(BoardWatcher.ReadMode.VISION_ONLY, garbage, false);
        assertEquals(OnlineGameSync.Phase.UNCERTAIN, sync.phase());
        assertEquals("g1", sync.state().detail());
        see(BoardWatcher.ReadMode.VISION_ONLY, START, false); // the next reading is right: recovered
        assertEquals(OnlineGameSync.Phase.MY_TURN, sync.phase());
        for (int i = 0; i < OnlineGameSync.UNRESOLVED_BEFORE_RESYNC; i++) {
            see(BoardWatcher.ReadMode.VISION_ONLY, garbage, false);
        }
        // the same reading again and again: vision is believed, the board is set up as it says
        assertEquals(OnlineGameSync.Phase.SETUP, sync.phase());
        assertTrue(board.setupFen.startsWith(garbage));

        sync.start(OnlineGameSync.Mode.PLAY, PageInfo.of(site.url).withHint("game"));
        see(BoardWatcher.ReadMode.VISION_ONLY, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKKNR", false);
        assertEquals(OnlineGameSync.Phase.UNCERTAIN, sync.phase(), "two white kings: never set up");
    }

    // ------------------------------------------------------------------ recovery

    @Test
    void aTakeBackOnThePageKeepsTheHistory() {
        startGame(false);
        board.move("e2e4");
        see();
        site.opponentPlays("e7e5");
        see();
        board.replicated();
        board.move("g1f3");
        see();
        site.position("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"); // two plies taken back
        see();
        assertEquals(OnlineGameSync.Phase.SETUP, sync.phase());
        board.setupDone();
        assertEquals(List.of("e2e4"), sync.moves());
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
    }

    @Test
    void anUnexplainedPositionOnThePageIsSetUpAgain() {
        startGame(false);
        site.position("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");
        see();
        assertEquals(OnlineGameSync.Phase.SETUP, sync.phase());
        assertTrue(board.setupFen.startsWith("4k3/8/8/8/8/8/4P3/4K3"));
    }

    @Test
    void withoutSensorsEverythingCompletesAtOnce() {
        board.connected = false;
        sync.start(OnlineGameSync.Mode.PLAY, PageInfo.of(site.url).withHint("game"));
        see();
        assertEquals(OnlineGameSync.Phase.MY_TURN, sync.phase());
        assertFalse(sync.state().boardConnected());
        site.opponentPlays("e2e4"); // the user plays on the screen
        see();
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, sync.phase());
    }

    // ------------------------------------------------------------------ end and archive

    private void scholarsMate() {
        board.move("e2e4");
        see();
        site.opponentPlays("e7e5");
        see();
        board.replicated();
        board.move("f1c4");
        see();
        site.opponentPlays("b8c6");
        see();
        board.replicated();
        board.move("d1h5");
        see();
        site.opponentPlays("g8f6");
        see();
        board.replicated();
        board.move("h5f7");
        see();
    }

    @Test
    void mateEndsAndArchivesARealGame() {
        startGame(false);
        scholarsMate();
        assertEquals(OnlineGameSync.Phase.GAME_OVER, sync.phase());
        assertEquals("1-0", sync.state().result());
        assertEquals(1, archived.size());
        ArchivedGame g = archived.get(0);
        assertEquals("1-0", g.result());
        assertEquals(ArchivedGame.GameMode.BROWSER, g.mode());
        assertEquals("Lichess (browser)", g.label());
        assertEquals(PgnCodec.START_FEN, g.initialFen());
        assertEquals(7, g.movesUci().size());
        sync.stop();
        assertEquals(1, archived.size(), "archived once");
    }

    @Test
    void theResultShownByThePageEndsTheGame() {
        startGame(false);
        board.move("e2e4");
        see();
        site.result = "0-1"; // resigned or flagged
        see();
        assertEquals(OnlineGameSync.Phase.GAME_OVER, sync.phase());
        assertEquals("0-1", sync.state().result());
        assertTrue(archived.isEmpty(), "too short to archive");
    }

    @Test
    void anInterruptedGameIsArchivedWhenItIsLongEnough() {
        startGame(false);
        for (String[] pair : new String[][]{{"e2e4", "e7e5"}, {"g1f3", "b8c6"}, {"f1b5", "a7a6"}}) {
            board.move(pair[0]);
            see();
            site.opponentPlays(pair[1]);
            see();
            board.replicated();
        }
        sync.stop();
        assertEquals(1, archived.size());
        assertEquals("*", archived.get(0).result());
        assertEquals("Interrotta", archived.get(0).termination());
    }

    @Test
    void analysisPuzzlesAndMidGamePositionsAreNotArchived() {
        // analysis board: both sides on the board, never archived
        site.url = "https://lichess.org/analysis";
        site.pageHint = "analysis";
        sync.start(OnlineGameSync.Mode.ANALYSIS, PageInfo.of(site.url).withHint("analysis"));
        see();
        board.setupDone();
        assertNull(board.movingSide, "both sides move on an analysis board");
        for (String m : List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4")) {
            board.move(m);
            see();
        }
        assertEquals(7, sync.moves().size());
        sync.stop();
        assertTrue(archived.isEmpty());

        // a puzzle (chess.com /puzzles): a game-like page, but not a game
        site.position(PgnCodec.START_FEN);
        site.url = "https://www.chess.com/puzzles/rated";
        site.siteId = "chesscom";
        site.pageHint = "puzzle";
        sync.start(OnlineGameSync.Mode.PLAY, PageInfo.of(site.url).withHint("puzzle"));
        see();
        board.setupDone();
        for (String[] pair : new String[][]{{"e2e4", "e7e5"}, {"g1f3", "b8c6"}, {"f1b5", "a7a6"}}) {
            board.move(pair[0]);
            see();
            site.opponentPlays(pair[1]);
            see();
            board.replicated();
        }
        sync.stop();
        assertTrue(archived.isEmpty(), "puzzles are not games");

        // a game page joined in the middle (like archive entry 206): unknown history, not archived
        site.url = "https://www.chess.com/game/live/1";
        site.pageHint = "game";
        site.position("6rk/6p1/p2n3p/1p1p3P/1P3PQN/1NP3R1/4r1PK/8 w - - 0 1");
        sync.start(OnlineGameSync.Mode.PLAY, PageInfo.of(site.url).withHint("game"));
        see();
        board.setupDone();
        board.move("h4g6");
        see();
        site.opponentPlays("h8h7");
        see();
        board.replicated();
        board.move("g4e6");
        see();
        sync.stop();
        assertTrue(archived.isEmpty(), "a position joined midway is not a game record");
    }

    @Test
    void aNewGameAfterTheEndIsSetUp() {
        startGame(false);
        scholarsMate();
        assertEquals(OnlineGameSync.Phase.GAME_OVER, sync.phase());
        site.position(PgnCodec.START_FEN);
        site.lastMove = List.of();
        see();
        assertEquals(OnlineGameSync.Phase.SETUP, sync.phase());
        assertEquals(1, archived.size());
    }

    @Test
    void stopDetachesTheBoardAndIgnoresLateEvents() {
        startGame(false);
        sync.stop();
        assertTrue(board.calls.contains("detach"));
        int before = states.size();
        board.move("e2e4"); // a late event from the previous session
        see();
        assertEquals(before, states.size());
        assertTrue(sent.isEmpty());
    }
}
