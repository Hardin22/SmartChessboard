package io.github.hardin22.javachess.Engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Review engine pool on the fake engine (no Stockfish needed). */
class ReviewEnginesTest {

    static final String START = UciClientTest.START;

    ReviewEngines pool;

    ReviewEngines pool(int workers) {
        pool = new ReviewEngines(() -> UciClientTest.fake("normal"), () -> new EngineManager.ReviewPlan(workers, 1, 8),
                null);
        return pool;
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void searchesRunInParallelOnSeveralProcesses() throws Exception {
        ReviewEngines p = pool(3);
        try (ReviewEngines.Session s = p.open(false)) {
            assertEquals(3, s.workers());
            List<CompletableFuture<SearchResult>> fs = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 6; i++) {
                fs.add(s.search(START, List.of(), SearchLimits.depth(20)));
            }
            for (CompletableFuture<SearchResult> f : fs) {
                SearchResult r = f.get(30, TimeUnit.SECONDS);
                assertEquals("e2e4", r.bestMove());
                assertEquals(20, r.depth());
                assertFalse(r.stopped(), "a worker never interrupts its own search");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(3, p.liveClients().size());
            // 6 searches of ~200 ms on 3 workers: about 2 rounds, far from the 6 rounds of a single process
            assertTrue(ms < 6 * 200, "took " + ms + " ms");
        }
    }

    @Test
    void pinnedSearchesStayOnTheirWorker() throws Exception {
        ReviewEngines p = pool(2);
        try (ReviewEngines.Session s = p.open(false)) {
            var a = s.search(0, START, List.of("e2e4"), SearchLimits.depth(5).withMultiPv(2));
            var b = s.search(0, START, List.of(), SearchLimits.depth(5));
            assertEquals(2, a.get(30, TimeUnit.SECONDS).lines().size());
            assertNotNull(b.get(30, TimeUnit.SECONDS).bestMove());
        }
    }

    @Test
    void closingTheSessionCancelsItsQueuedSearches() throws Exception {
        ReviewEngines p = pool(1);
        ReviewEngines.Session s = p.open(false);
        var running = s.search(START, List.of(), SearchLimits.depth(1000));
        var queued = s.search(START, List.of(), SearchLimits.depth(5));
        Thread.sleep(100);
        s.close();
        assertTrue(running.get(10, TimeUnit.SECONDS).stopped());
        ExecutionException e = assertThrows(ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, e.getCause());
        assertThrows(ExecutionException.class, () -> s.search(START, List.of(), SearchLimits.depth(2)).get());
    }

    @Test
    void idleWorkersAreClosedOnlyWithoutSessions() throws Exception {
        ReviewEngines p = pool(2);
        ReviewEngines.Session s = p.open(false);
        s.search(START, List.of(), SearchLimits.depth(3)).get(30, TimeUnit.SECONDS);
        p.closeIfIdle(0);
        assertEquals(2, p.liveClients().size(), "a session is open");
        s.close();
        p.closeIfIdle(0);
        assertTrue(p.liveClients().isEmpty());
        try (ReviewEngines.Session again = p.open(false)) {
            assertEquals("e2e4", again.search(START, List.of(), SearchLimits.depth(3)).get(30, TimeUnit.SECONDS)
                    .bestMove(), "restarted lazily");
        }
    }

    @Test
    void reviewPlansUseTheSpareCores() {
        ProcessPlan pi5 = new ProcessPlan(7_900, "8 GB+", false, 128, 32, 2, 600_000, 4);
        EngineManager.Budget lite = EngineManager.Budget.lite(pi5);
        assertEquals(2, lite.threads(), "Pi 5: two analysis threads in a game");
        assertEquals(64, lite.hashMb());
        assertEquals(new EngineManager.ReviewPlan(3, 1, 64), lite.review());

        ProcessPlan pi4 = new ProcessPlan(906, "1 GB", true, 16, 16, 2, 120_000, 4);
        assertEquals(1, EngineManager.Budget.lite(pi4).threads());
        assertEquals(1, EngineManager.Budget.lite(pi4).review().workers());

        ProcessPlan mac = new ProcessPlan(24_000, "8 GB+", false, 128, 32, 4, 600_000, 10);
        assertEquals(6, EngineManager.Budget.full(mac).review().workers());
    }
}
