package io.github.hardin22.javachess.Browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The session fed by the real watcher (page reading), as in the app: the synchronisation must get the position
 * whenever it starts again, also when the position on the page did not change. On 8 October 2026 chess.com's
 * /play/computer -> /play/computer/Martinfoot-BOT (same start position) left the app on "Leggo la scacchiera..."
 * for good: the watcher only reports positions that change, and nobody asked it to report the current one again.
 */
class SessionWithWatcherTest {

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(10_000);
    private final List<String> commands = new ArrayList<>();
    private FakeSite site;
    private FakePhysicalBoard board;
    private BrowserSession session;
    private BoardWatcher watcher;

    @BeforeEach
    void setUp() {
        site = new FakeSite();
        site.siteId = "chesscom";
        site.url = "https://www.chess.com/play/computer";
        site.pageHint = "game";
        board = new FakePhysicalBoard();
        BoardWatcher[] w = new BoardWatcher[1];
        session = new BrowserSession(Runnable::run, board, uci -> CompletableFuture.completedFuture(null),
                g -> { }, new BrowserSession.Commands() {
                    @Override
                    public void reload() {
                    }

                    @Override
                    public void pageBack() {
                    }

                    @Override
                    public void retryEngine() {
                    }

                    @Override
                    public void restartApp() {
                    }

                    @Override
                    public void backHome() {
                    }

                    @Override
                    public void showBoard() {
                        commands.add("show");
                    }

                    @Override
                    public void pageAccess(boolean allowed) {
                    }

                    @Override
                    public void readPositionAgain() {
                        commands.add("again");
                        w[0].forgetReported();
                    }
                }, clock::get);
        watcher = new BoardWatcher(site, null, exec, new BoardWatcher.Listener() {
            @Override
            public void onSnapshot(BoardSnapshot snapshot) {
                session.onSnapshot(snapshot);
            }

            @Override
            public void onPosition(BoardWatcher.PositionUpdate update) {
                session.onPosition(update);
            }

            @Override
            public void onProblem(BoardWatcher.Problem problem) {
                session.onProblem(problem);
            }
        }, BoardWatcher.ReadMode.PAGE);
        w[0] = watcher;
        session.engineReady();
        session.setVisible(true);
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    private void poll(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            watcher.pollOnce().get(5, TimeUnit.SECONDS);
            exec.submit(() -> { }).get(5, TimeUnit.SECONDS); // what the poll queued
            clock.addAndGet(300);
            session.tick();
        }
    }

    private BrowserStatus.State state() {
        return session.status().state();
    }

    @Test
    void choosingTheBotKeepsFollowingTheBoard() throws Exception {
        session.pageLoading(site.url);
        session.pageLoaded(site.url, 200);
        poll(4);
        assertEquals(BrowserStatus.State.SETUP, state(), "the board of /play/computer is read");
        board.setupDone();
        assertEquals(BrowserStatus.State.YOUR_TURN, state());

        // the user picks Martin: same page, new address, the same start position on the board
        site.url = "https://www.chess.com/play/computer/Martinfoot-BOT";
        session.addressChanged(site.url);
        poll(4);
        assertNotEquals(BrowserStatus.State.READING, state(), "read again at once, not 'Leggo la scacchiera' for good");
        assertEquals(BrowserStatus.State.SETUP, state());
    }

    @Test
    void disconnectAndConnectAgainReadsTheBoardAgain() throws Exception {
        session.pageLoading(site.url);
        session.pageLoaded(site.url, 200);
        poll(4);
        board.setupDone();
        assertEquals(BrowserStatus.State.YOUR_TURN, state());
        session.perform(BrowserStatus.Action.SYNC_STOP);
        assertEquals(BrowserStatus.State.PAUSED, state());
        session.perform(BrowserStatus.Action.SYNC_START);
        poll(4);
        assertEquals(BrowserStatus.State.SETUP, state(), "Collega works with an unchanged board");
    }

    @Test
    void aReadingThatDoesNotConcludeIsRetriedThenExplained() throws Exception {
        site.placementReadable = false; // the page shows a board but not its position
        session.pageLoading(site.url);
        session.pageLoaded(site.url, 200);
        poll(3);
        assertEquals(BrowserStatus.State.READING, state());
        commands.clear();
        poll(10); // 3 s
        assertEquals(List.of("again", "show"), commands.subList(0, 2), "asked again and the board shown");
        poll(20); // 9 s in all
        assertEquals(BrowserStatus.State.READING, state());
        org.junit.jupiter.api.Assertions.assertTrue(session.status().detail().contains("non mostra la posizione"),
                session.status().detail());
        assertEquals(List.of(BrowserStatus.Action.RESYNC, BrowserStatus.Action.RELOAD), session.status().actions());

        site.placementReadable = true; // the page shows it again: the game goes on by itself
        poll(3);
        assertEquals(BrowserStatus.State.SETUP, state());
    }
}
