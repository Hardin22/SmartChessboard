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
        Map<String, String> opts = new java.util.LinkedHashMap<>();
        opts.put("Hash", "8");
        opts.put("Skill Level", "20");
        return new EngineSpec("fake-" + args[0], cmd, opts, null, 5_000);
    }

    @Test
    void depthSearchReturnsBestMoveAndLines() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            SearchResult r = c.search(START, SearchLimits.depth(5).withMultiPv(3)).result().get(30, TimeUnit.SECONDS);
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
            java.util.concurrent.CountDownLatch deepEnough = new java.util.concurrent.CountDownLatch(1);
            UciClient.SearchHandle first = c.search(START, List.of(), SearchLimits.infinite(), i -> {
                if (i.depth() >= 5) {
                    deepEnough.countDown();
                }
            });
            assertTrue(deepEnough.await(30, TimeUnit.SECONDS), "first search never reached depth 5");
            UciClient.SearchHandle second = c.search(START, List.of(), SearchLimits.depth(3), secondInfos::add);
            SearchResult r1 = first.result().get(30, TimeUnit.SECONDS);
            SearchResult r2 = second.result().get(30, TimeUnit.SECONDS);
            assertTrue(r1.stopped(), "first search must have been stopped");
            assertTrue(r1.depth() >= 5, "first search ran for a while");
            assertEquals(3, r2.depth());
            // second search saw only its own depths 1..3
            assertTrue(secondInfos.stream().allMatch(i -> i.depth() <= 3), secondInfos.toString());
            assertEquals(1, c.processesStarted());
        }
    }

    @Test
    void perSearchOptionOverrideIsRestoredAfterwards() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            SearchResult weak = c.search(START, List.of(), SearchLimits.depth(2), null, Map.of("Skill Level", "3"))
                    .result().get(30, TimeUnit.SECONDS);
            assertEquals("g1f3", weak.bestMove(), "override in effect");
            SearchResult normal = c.search(START, SearchLimits.depth(2)).result().get(30, TimeUnit.SECONDS);
            assertEquals("e2e4", normal.bestMove(), "base value restored for the next search");
        }
    }

    @Test
    void timeoutStopsSearchAndReturnsBestSoFar() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            c.start().get(30, TimeUnit.SECONDS);
            long t0 = System.nanoTime();
            SearchResult r = c.search(START, SearchLimits.infinite().withTimeout(200)).result().get(30, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(r.stopped());
            assertEquals("e2e4", r.bestMove());
            assertTrue(ms < UciClient.STOP_TIMEOUT_MS, "the cap stopped the infinite search (took " + ms + " ms)");
        }
    }

    @Test
    void cancelBeforeStartCompletesExceptionally() throws Exception {
        try (UciClient c = new UciClient(fake("normal"))) {
            UciClient.SearchHandle blocker = c.search(START, SearchLimits.infinite());
            UciClient.SearchHandle h = c.search(START, SearchLimits.depth(2));
            h.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, () -> h.result().get(30, TimeUnit.SECONDS));
            blocker.cancel();
            assertTrue(blocker.result().get(30, TimeUnit.SECONDS).stopped());
        }
    }

    @Test
    void crashFailsTheSearchAndNextRequestRestartsTheEngine(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("crashed");
        try (UciClient c = new UciClient(fake("crash-once", marker.toString()))) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> c.search(START, SearchLimits.depth(3)).result().get(30, TimeUnit.SECONDS));
            assertInstanceOf(EngineException.class, e.getCause());
            SearchResult r = c.search(START, SearchLimits.depth(3)).result().get(30, TimeUnit.SECONDS);
            assertEquals("e2e4", r.bestMove());
            assertEquals(2, c.processesStarted());
        }
    }

    @Test
    void engineThatAlwaysCrashesGivesUpAfterRestartBudget() throws Exception {
        try (UciClient c = new UciClient(fake("crash-on-go"))) {
            for (int i = 0; i < 6; i++) {
                assertThrows(ExecutionException.class,
                        () -> c.search(START, SearchLimits.depth(3)).result().get(30, TimeUnit.SECONDS));
            }
            assertTrue(c.processesStarted() <= 4, "restarts must be bounded, got " + c.processesStarted());
        }
    }

    @Test
    void engineIgnoringStopIsKilledAndRestarted() throws Exception {
        try (UciClient c = new UciClient(fake("ignore-stop"))) {
            c.start().get(30, TimeUnit.SECONDS);
            SearchResult r = c.search(START, SearchLimits.infinite().withTimeout(200)).result()
                    .get(UciClient.STOP_TIMEOUT_MS + 30_000, TimeUnit.MILLISECONDS);
            assertTrue(r.stopped());
            assertNotNull(r.best(), "partial lines are kept");
            // the client recovers with a fresh process
            SearchResult r2 = c.search(START, SearchLimits.depth(2)).result().get(30, TimeUnit.SECONDS);
            assertEquals(2, r2.depth());
            assertEquals(2, c.processesStarted());
        }
    }

    @Test
    void missingReadyOkTimesOut() {
        EngineSpec spec = fake("no-readyok").withReadyTimeout(500);
        try (UciClient c = new UciClient(spec)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> c.start().get(30, TimeUnit.SECONDS));
            assertTrue(e.getCause().getMessage().contains("readyok"), e.getCause().getMessage());
        }
    }

    @Test
    void malformedOutputIsIgnored() throws Exception {
        try (UciClient c = new UciClient(fake("garbage"))) {
            List<InfoLine> infos = new CopyOnWriteArrayList<>();
            SearchResult r = c.search(START, List.of(), SearchLimits.depth(2), infos::add).result()
                    .get(30, TimeUnit.SECONDS);
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
                    () -> c.search(START, SearchLimits.depth(1)).result().get(30, TimeUnit.SECONDS));
            assertInstanceOf(EngineException.class, e.getCause());
        }
    }

    @Test
    void closedClientRejectsSearchesAndKillsProcess() throws Exception {
        UciClient c = new UciClient(fake("normal"));
        c.start().get(30, TimeUnit.SECONDS);
        assertTrue(c.isAlive());
        UciClient.SearchHandle running = c.search(START, SearchLimits.infinite());
        c.close();
        assertFalse(c.isAlive());
        assertThrows(ExecutionException.class, () -> running.result().get(30, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> c.search(START, SearchLimits.depth(1)).result().get(30, TimeUnit.SECONDS));
    }

    @Test
    void rejectsFenWithNewline() {
        try (UciClient c = new UciClient(fake("normal"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> c.search(START + "\nquit", SearchLimits.depth(1)));
        }
    }
}
