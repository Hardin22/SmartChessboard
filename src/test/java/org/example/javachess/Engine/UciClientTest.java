package org.example.javachess.Engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Protocol tests against {@link FakeUciEngine} running as a real child process. */
class UciClientTest {

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    static EngineSpec fake(String... args) {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                FakeUciEngine.class.getName()));
        cmd.addAll(List.of(args));
        return new EngineSpec("fake-" + args[0], cmd, Map.of("Hash", "8"), null, 5_000);
    }

    @Test
    void depthSearchReturnsBestMoveAndLines() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            SearchResult r = c.search(START, SearchLimits.depth(5).withMultiPv(3)).result().get(10, TimeUnit.SECONDS);
            assertEquals("e2e4", r.bestMove());
            assertEquals("e7e5", r.ponder());
            assertEquals(3, r.lines().size());
            assertEquals(5, r.depth());
            assertEquals(30, r.best().score().value());
            assertEquals("d2d4", r.lines().get(1).move());
            assertFalse(r.stopped());
            assertTrue(c.engineName().startsWith("FakeEngine"));
            assertTrue(c.hasOption("multipv"));
        }
    }

    @Test
    void newSearchStopsThePreviousOneAndOutputIsNotMixed() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            List<InfoLine> secondInfos = new CopyOnWriteArrayList<>();
            UciClient.SearchHandle first = c.search(START, SearchLimits.infinite());
            Thread.sleep(150);
            UciClient.SearchHandle second = c.search(START, List.of(), SearchLimits.depth(3), secondInfos::add);
            SearchResult r1 = first.result().get(5, TimeUnit.SECONDS);
            SearchResult r2 = second.result().get(5, TimeUnit.SECONDS);
            assertTrue(r1.stopped(), "first search must have been stopped");
            assertTrue(r1.depth() > 3, "first search ran for a while");
            assertEquals(3, r2.depth());
            // second search saw only its own depths 1..3
            assertTrue(secondInfos.stream().allMatch(i -> i.depth() <= 3), secondInfos.toString());
            assertEquals(1, c.processesStarted());
        }
    }

    @Test
    void timeoutStopsSearchAndReturnsBestSoFar() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            long t0 = System.nanoTime();
            SearchResult r = c.search(START, SearchLimits.infinite().withTimeout(200)).result().get(5, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(r.stopped());
            assertEquals("e2e4", r.bestMove());
            assertTrue(ms < 2_000, "took " + ms);
        }
    }

    @Test
    void cancelBeforeStartCompletesExceptionally() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            UciClient.SearchHandle blocker = c.search(START, SearchLimits.infinite());
            UciClient.SearchHandle h = c.search(START, SearchLimits.depth(2));
            h.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, () -> h.result().get(5, TimeUnit.SECONDS));
            blocker.cancel();
            assertTrue(blocker.result().get(5, TimeUnit.SECONDS).stopped());
        }
    }

    @Test
    void crashFailsTheSearchAndNextRequestRestartsTheEngine(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("crashed");
        try (UciClient c = new UciClient(fake("crash-once", marker.toString()))) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> c.search(START, SearchLimits.depth(3)).result().get(10, TimeUnit.SECONDS));
            assertInstanceOf(EngineException.class, e.getCause());
            SearchResult r = c.search(START, SearchLimits.depth(3)).result().get(10, TimeUnit.SECONDS);
            assertEquals("e2e4", r.bestMove());
            assertEquals(2, c.processesStarted());
        }
    }

    @Test
    void engineThatAlwaysCrashesGivesUpAfterRestartBudget() throws Exception {
        try (UciClient c = new UciClient(fake("crash-on-go"))) {
            for (int i = 0; i < 6; i++) {
                assertThrows(ExecutionException.class,
                        () -> c.search(START, SearchLimits.depth(3)).result().get(10, TimeUnit.SECONDS));
            }
            assertTrue(c.processesStarted() <= 4, "restarts must be bounded, got " + c.processesStarted());
        }
    }

    @Test
    void engineIgnoringStopIsKilledAndRestarted() throws Exception {
        try (UciClient c = new UciClient(fake("ignore-stop"))) {
            long t0 = System.nanoTime();
            SearchResult r = c.search(START, SearchLimits.infinite().withTimeout(200)).result()
                    .get(UciClient.STOP_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(r.stopped());
            assertNotNull(r.best(), "partial lines are kept");
            assertTrue(ms < UciClient.STOP_TIMEOUT_MS + 2_000, "took " + ms);
            // the client recovers with a fresh process
            SearchResult r2 = c.search(START, SearchLimits.depth(2)).result().get(10, TimeUnit.SECONDS);
            assertEquals(2, r2.depth());
            assertEquals(2, c.processesStarted());
        }
    }

    @Test
    void missingReadyOkTimesOut() {
        EngineSpec spec = fake("no-readyok").withReadyTimeout(500);
        try (UciClient c = new UciClient(spec)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> c.start().get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause().getMessage().contains("readyok"), e.getCause().getMessage());
        }
    }

    @Test
    void malformedOutputIsIgnored() throws Exception {
        try (UciClient c = new UciClient(fake("garbage"))) {
            List<InfoLine> infos = new CopyOnWriteArrayList<>();
            SearchResult r = c.search(START, List.of(), SearchLimits.depth(2), infos::add).result()
                    .get(10, TimeUnit.SECONDS);
            assertEquals("e2e4", r.bestMove());
            assertEquals(2, r.depth());
            assertEquals(2, infos.size(), "only the two valid info lines: " + infos);
        }
    }

    @Test
    void missingBinaryFailsCleanly() {
        EngineSpec spec = new EngineSpec("missing", List.of("/nonexistent/engine"), Map.of(), null, 1_000);
        try (UciClient c = new UciClient(spec)) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> c.search(START, SearchLimits.depth(1)).result().get(5, TimeUnit.SECONDS));
            assertInstanceOf(EngineException.class, e.getCause());
        }
    }

    @Test
    void closedClientRejectsSearchesAndKillsProcess() throws Exception {
        UciClient c = new UciClient(fake("normal"));
        c.start().get(10, TimeUnit.SECONDS);
        assertTrue(c.isAlive());
        UciClient.SearchHandle running = c.search(START, SearchLimits.infinite());
        Thread.sleep(50);
        c.close();
        assertFalse(c.isAlive());
        assertThrows(ExecutionException.class, () -> running.result().get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> c.search(START, SearchLimits.depth(1)).result().get(5, TimeUnit.SECONDS));
    }

    @Test
    void rejectsFenWithNewline() {
        try (UciClient c = new UciClient(fake("normal"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> c.search(START + "\nquit", SearchLimits.depth(1)));
        }
    }
}
