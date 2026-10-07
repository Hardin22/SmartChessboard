package org.example.javachess.Engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Live analysis plumbing on the fake engine (no Stockfish needed). */
class PositionAnalyzerTest {

    static final String START = UciClientTest.START;
    static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    UciClient engine;
    PositionAnalyzer analyzer;
    final EngineManager.Budget budget = StockfishTestSupport.budget(3, 6);

    @BeforeEach
    void setUp() {
        engine = new UciClient(UciClientTest.fake("normal"));
        analyzer = new PositionAnalyzer(() -> engine, () -> budget);
    }

    @AfterEach
    void tearDown() {
        analyzer.stop();
        engine.close();
    }

    static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(cond.getAsBoolean(), "condition not met in time");
    }

    @Test
    void deliversCoalescedUpdatesUntilFinished() throws Exception {
        List<AnalysisUpdate> updates = new CopyOnWriteArrayList<>();
        analyzer.analyze(START, 8, 3, updates::add);
        await(() -> updates.stream().anyMatch(AnalysisUpdate::finished));
        AnalysisUpdate last = updates.get(updates.size() - 1);
        assertTrue(last.finished());
        assertEquals(8, last.depth());
        assertEquals(3, last.lines().size(), "one line per multipv");
        assertEquals("e2e4", last.bestMove());
        assertTrue(updates.size() <= 9, "coalesced: " + updates.size());
        for (int i = 1; i < updates.size(); i++) {
            assertTrue(updates.get(i).depth() >= updates.get(i - 1).depth(), "depth never goes back");
        }
        assertTrue(last.formatLine(0).startsWith("[0.30] 1)e4"), last.formatLine(0));
    }

    @Test
    void newPositionSilencesTheOldOne() throws Exception {
        List<AnalysisUpdate> first = new CopyOnWriteArrayList<>();
        List<AnalysisUpdate> second = new CopyOnWriteArrayList<>();
        analyzer.analyze(START, 0, 1, first::add); // depth raised to the coach confirm depth (6)
        analyzer.analyze(AFTER_E4, 6, 1, second::add);
        await(() -> second.stream().anyMatch(AnalysisUpdate::finished));
        Thread.sleep(100);
        assertTrue(first.stream().allMatch(u -> u.fen().equals(START)));
        assertTrue(second.stream().allMatch(u -> u.fen().equals(AFTER_E4)));
        assertFalse(first.stream().anyMatch(AnalysisUpdate::finished), "old request must not finish after replacement");
    }

    @Test
    void sameRequestIsNoOpButReplaysToNewListener() throws Exception {
        List<AnalysisUpdate> a = new CopyOnWriteArrayList<>();
        analyzer.analyze(START, 6, 1, a::add);
        await(() -> a.stream().anyMatch(AnalysisUpdate::finished));
        int processes = engine.processesStarted();
        List<AnalysisUpdate> b = new CopyOnWriteArrayList<>();
        analyzer.analyze(START, 6, 1, b::add);
        await(() -> !b.isEmpty());
        assertTrue(b.get(0).finished(), "replayed final result");
        assertEquals(processes, engine.processesStarted());
    }

    @Test
    void terminalPositionsNeedNoEngine() throws Exception {
        List<AnalysisUpdate> u = new CopyOnWriteArrayList<>();
        // fool's mate: White is checkmated
        analyzer.analyze("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3", 10, 1, u::add);
        await(() -> !u.isEmpty());
        assertEquals(Score.mate(0), u.get(0).terminalScore());
        assertTrue(u.get(0).whitePawns() < -900, "eval bar shows White mated");
        u.clear();
        analyzer.analyze("k7/8/1Q6/8/8/8/8/2K5 b - - 0 1", 10, 1, u::add); // stalemate
        await(() -> !u.isEmpty());
        assertEquals(Score.cp(0), u.get(0).terminalScore());
    }

    @Test
    void scoringInterruptsAndThenResumesTheLiveAnalysis() throws Exception {
        List<AnalysisUpdate> u = new CopyOnWriteArrayList<>();
        analyzer.analyze(START, 30, 1, u::add); // long search on the fake engine (10 ms per depth)
        Thread.sleep(80);
        SearchResult r = analyzer.scoreMoves(START, List.of("g1f3", "b1c3"), 5_000, 300).get(5, TimeUnit.SECONDS);
        assertNotNull(r.best());
        await(() -> u.stream().anyMatch(AnalysisUpdate::finished));
        assertEquals(30, u.get(u.size() - 1).depth(), "live analysis resumed and completed");
    }
}
