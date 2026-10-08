package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Vision.BoardReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every situation of the browser and the status the user sees for it. */
class BrowserSessionTest {

    private final AtomicLong clock = new AtomicLong(10_000);
    private final List<String> commands = new ArrayList<>();
    private final List<BrowserStatus> shown = new ArrayList<>();
    private FakeSite site;
    private FakePhysicalBoard board;
    private BrowserSession session;

    @BeforeEach
    void setUp() {
        site = new FakeSite();
        board = new FakePhysicalBoard();
        session = new BrowserSession(Runnable::run, board, uci -> {
            site.opponentPlays(uci);
            return CompletableFuture.completedFuture(null);
        }, g -> commands.add("archive"), new BrowserSession.Commands() {
            @Override
            public void reload() {
                commands.add("reload");
            }

            @Override
            public void pageBack() {
                commands.add("back");
            }

            @Override
            public void retryEngine() {
                commands.add("retry");
            }

            @Override
            public void restartApp() {
                commands.add("restart");
            }

            @Override
            public void backHome() {
                commands.add("home");
            }

            @Override
            public void showBoard() {
                commands.add("show");
            }
        }, clock::get);
        session.addListener(shown::add);
    }

    private BrowserStatus.State state() {
        return session.status().state();
    }

    private void ready() {
        session.engineReady();
        session.setVisible(true);
    }

    private void showPage() {
        session.pageLoaded(site.url, 200);
        session.onSnapshot(BoardProbe.parse(site.json()));
    }

    private void positionFromPage() {
        session.onPosition(new BoardWatcher.PositionUpdate(BoardProbe.parse(site.json()),
                BoardReading.certain(site.placement()), BoardReading.certain(site.placement()),
                BoardWatcher.ReadMode.VISION));
    }

    @Test
    void startUpDownloadRestartAndFailures() {
        assertEquals(BrowserStatus.State.STARTING, state());
        session.engineStarting();
        assertEquals(BrowserStatus.State.STARTING, state());
        assertTrue(session.status().actions().contains(BrowserStatus.Action.BACK_HOME));
        session.engineDownloading(0.42);
        assertEquals(BrowserStatus.State.DOWNLOADING, state());
        assertTrue(session.status().title().contains("42%"), session.status().title());
        assertEquals(0.42, session.status().progress(), 1e-9);
        session.engineInstalling();
        assertEquals(BrowserStatus.State.INSTALLING, state());
        session.engineRestartRequired();
        assertEquals(BrowserStatus.State.RESTART_REQUIRED, state());
        assertEquals(BrowserStatus.Action.RESTART_APP, session.status().actions().get(0));
        session.perform(BrowserStatus.Action.RESTART_APP);
        assertEquals(List.of("restart"), commands);

        session.engineFailed(BrowserSession.Failure.NO_NETWORK);
        assertEquals(BrowserStatus.State.UNAVAILABLE, state());
        assertTrue(session.status().detail().contains("Internet"), session.status().detail());
        assertEquals(BrowserStatus.Action.RETRY, session.status().actions().get(0));
        session.perform(BrowserStatus.Action.RETRY);
        assertTrue(commands.contains("retry"));
    }

    @Test
    void aLoadWhoseEndNoticeIsLostEndsWhenThePageIsComplete() {
        // on macOS some of Chromium's notices to Java are lost (the JVM refuses calls from the main thread's
        // native stack): the probe's document.readyState stands in for "load finished"
        ready();
        site.url = "https://www.chess.com/login";
        site.boardShown = false;
        site.loginForm = true;
        site.documentReady = false;
        session.pageLoading(site.url);
        session.onSnapshot(BoardProbe.parse(site.json()));
        assertEquals(BrowserStatus.State.LOADING, state(), "still loading");
        site.documentReady = true;
        session.onSnapshot(BoardProbe.parse(site.json()));
        assertEquals(BrowserStatus.State.LOADING, state(), "a moment to let Chromium say it");
        clock.addAndGet(BrowserSession.LOAD_END_GRACE_MS);
        session.onSnapshot(BoardProbe.parse(site.json()));
        assertEquals(BrowserStatus.State.LOGIN, state());
    }

    @Test
    void aPageWhoseProcessEndedIsReopenedOnceThenOffered() {
        ready();
        session.pageLoading("https://lichess.org/analysis");
        session.pageLoaded("https://lichess.org/analysis", 200);
        commands.clear();
        session.pageLoadFailed("https://lichess.org/analysis", "RENDERER_TS_PROCESS_OOM");
        assertEquals(BrowserStatus.State.PAGE_CRASHED, state(), "not \"the site does not answer\"");
        assertEquals(List.of(BrowserStatus.Action.RELOAD), session.status().actions());
        assertEquals(List.of("reload"), commands, "reopened by itself");
        session.pageLoading("https://lichess.org/analysis");
        assertEquals(BrowserStatus.State.LOADING, state());
        clock.addAndGet(10_000);
        session.pageLoadFailed("https://lichess.org/analysis", "RENDERER_TS_PROCESS_CRASHED");
        assertEquals(List.of("reload"), commands, "again within a minute: the user decides");
        assertEquals(BrowserStatus.State.PAGE_CRASHED, state());
        clock.addAndGet(BrowserSession.CRASH_RELOAD_EVERY_MS);
        session.pageLoadFailed("https://lichess.org/analysis", "RENDERER_TS_PROCESS_CRASHED");
        assertEquals(List.of("reload", "reload"), commands);
    }

    @Test
    void loadingSlowSiteOfflineAndUnreachable() {
        ready();
        session.pageLoading("https://www.chess.com/login");
        assertEquals(BrowserStatus.State.LOADING, state());
        assertTrue(session.status().title().contains("Chess.com"), session.status().title());
        assertFalse(session.status().actions().contains(BrowserStatus.Action.RELOAD));
        clock.addAndGet(BrowserSession.SLOW_LOAD_MS + 1);
        session.tick();
        assertTrue(session.status().actions().contains(BrowserStatus.Action.RELOAD), "slow: offer reload");

        session.pageLoadFailed("https://www.chess.com/login", "ERR_INTERNET_DISCONNECTED");
        assertEquals(BrowserStatus.State.OFFLINE, state());
        session.pageLoadFailed("https://www.chess.com/login", "ERR_ABORTED");
        assertEquals(BrowserStatus.State.OFFLINE, state(), "a cancelled load is not an error");
        session.pageLoading("https://lichess.org/");
        assertEquals(BrowserStatus.State.LOADING, state(), "a new load clears the error");
        session.pageLoadFailed("https://lichess.org/", "ERR_CONNECTION_TIMED_OUT");
        assertEquals(BrowserStatus.State.SITE_UNREACHABLE, state());
        assertTrue(session.status().title().startsWith("Lichess"), session.status().title());
        session.pageLoading("https://lichess.org/");
        session.pageLoaded("https://lichess.org/", 503);
        assertEquals(BrowserStatus.State.SITE_UNREACHABLE, state());
        session.perform(BrowserStatus.Action.RELOAD);
        assertTrue(commands.contains("reload"));
    }

    @Test
    void verificationAndLogin() {
        ready();
        site.url = "https://www.chess.com/login";
        site.siteId = "chesscom";
        site.pageHint = "login";
        site.boardShown = false;
        site.challenge = true;
        showPage();
        assertEquals(BrowserStatus.State.VERIFY, state());
        site.challenge = false;
        site.loginForm = true;
        showPage();
        assertEquals(BrowserStatus.State.LOGIN, state());
        assertEquals("Accedi a Chess.com", session.status().title());
    }

    @Test
    void noGameThenAGameStartsTheSynchronisation() {
        ready();
        site.url = "https://lichess.org/";
        site.pageHint = "home";
        site.boardShown = false;
        showPage();
        assertEquals(BrowserStatus.State.NO_GAME, state());

        site.url = "https://lichess.org/abcd1234";
        site.pageHint = "game";
        site.boardShown = true;
        session.addressChanged(site.url);
        showPage();
        assertEquals(BrowserStatus.State.READING, state(), "game page: synchronisation started by itself");
        positionFromPage();
        assertEquals(BrowserStatus.State.SETUP, state());
        board.setupDone();
        assertEquals(BrowserStatus.State.YOUR_TURN, state());
        assertTrue(session.status().actions().contains(BrowserStatus.Action.SYNC_STOP));

        board.move("e2e4");
        positionFromPage();
        assertEquals(BrowserStatus.State.OPPONENT_TURN, state());
        assertTrue(session.status().detail().contains("e4"), session.status().detail());
        site.opponentPlays("c7c5");
        positionFromPage();
        assertEquals(BrowserStatus.State.REPLICATE, state());
        assertEquals("Mossa c5", session.status().title());
        assertTrue(session.status().detail().contains("c7") && session.status().detail().contains("c5"));
        board.replicated();
        assertEquals(BrowserStatus.State.YOUR_TURN, state());
    }

    @Test
    void withoutTheBoardTheUserIsToldToMoveOnTheScreen() {
        board.connected = false;
        ready();
        showPage();
        positionFromPage();
        assertEquals(BrowserStatus.State.YOUR_TURN, state());
        assertTrue(session.status().detail().contains("schermo"), session.status().detail());
    }

    @Test
    void pausingAndResumingOnTheSamePage() {
        ready();
        showPage();
        positionFromPage();
        session.perform(BrowserStatus.Action.SYNC_STOP);
        assertEquals(BrowserStatus.State.PAUSED, state());
        showPage();
        assertEquals(BrowserStatus.State.PAUSED, state(), "not restarted by itself on the same page");
        session.perform(BrowserStatus.Action.SYNC_START);
        assertEquals(BrowserStatus.State.READING, state());
    }

    @Test
    void pagesThatOnlyOfferTheSynchronisation() {
        ready();
        site.url = "https://lichess.org/training";
        site.pageHint = "puzzle";
        showPage();
        assertEquals(BrowserStatus.State.BOARD_FOUND, state());
        assertEquals(List.of(BrowserStatus.Action.SYNC_START), session.status().actions());
        site.url = "https://lichess.org/editor";
        site.pageHint = "editor";
        showPage();
        assertEquals(BrowserStatus.State.EDITOR, state());
        site.url = "https://lichess.org/";
        site.pageHint = "home";
        site.rect = new BoardSnapshot.Rect(0, 900, 300, 300); // the TV thumbnail
        showPage();
        assertEquals(BrowserStatus.State.NO_GAME, state());
    }

    @Test
    void readingProblemsDuringTheGame() {
        ready();
        showPage();
        positionFromPage();
        board.setupDone();
        session.onProblem(BoardWatcher.Problem.BOARD_OUT_OF_VIEW);
        assertEquals(BrowserStatus.State.BOARD_HIDDEN, state());
        assertEquals(BrowserStatus.Action.SHOW_BOARD, session.status().actions().get(0));
        session.perform(BrowserStatus.Action.SHOW_BOARD);
        assertTrue(commands.contains("show"));
        session.onProblem(BoardWatcher.Problem.VISION_NO_BOARD);
        assertTrue(session.status().detail().contains("copre"), session.status().detail());
        session.onProblem(BoardWatcher.Problem.VISION_UNAVAILABLE);
        assertEquals(BrowserStatus.State.YOUR_TURN, state(), "the page's markup stands in silently");
        session.onProblem(null);
        assertEquals(BrowserStatus.State.YOUR_TURN, state());
    }

    @Test
    void leavingThePageOrTheBrowserStopsTheSynchronisation() {
        ready();
        showPage();
        positionFromPage();
        board.setupDone();
        session.addressChanged("https://lichess.org/zzzz9999");
        assertTrue(board.calls.contains("detach"), "another game page: the old synchronisation ends");
        site.url = "https://lichess.org/zzzz9999";
        showPage();
        assertEquals(BrowserStatus.State.READING, state());
        session.setVisible(false);
        assertEquals(2, board.count("detach"));
    }

    @Test
    void statusesAreOnlyPublishedWhenTheyChange() {
        ready();
        showPage();
        int count = shown.size();
        showPage();
        showPage();
        assertEquals(count, shown.size());
    }

    @Test
    void everyActionHasALabel() {
        for (BrowserStatus.Action a : BrowserStatus.Action.values()) {
            assertFalse(a.label().startsWith("browser."), a.name());
        }
    }
}
