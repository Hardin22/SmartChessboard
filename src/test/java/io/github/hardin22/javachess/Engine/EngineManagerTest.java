package io.github.hardin22.javachess.Engine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Profiles and hot engine switching with real engines (skipped when Stockfish is missing). */
class EngineManagerTest {

    EngineManager manager;

    @AfterEach
    void close() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    static String play(Board board, String uci) {
        Move m = new Move(uci, board.getSideToMove());
        assertTrue(board.legalMoves().contains(m), "illegal bot move " + uci + " in " + board.getFen());
        board.doMove(m);
        return board.getFen();
    }

    @Test
    void profilesReflectInstalledBinaries() {
        StockfishTestSupport.requireStockfish();
        manager = new EngineManager(false);
        assertEquals(5, manager.profiles().size());
        assertTrue(manager.profiles().stream().filter(p -> p.id().startsWith("stockfish")).allMatch(EngineProfile::available));
        boolean lc0 = EngineLocator.lc0().path().isPresent();
        assertEquals(lc0, manager.profiles().stream().filter(p -> p.id().equals("maia-1500")).findFirst().orElseThrow().available());
    }

    @Test
    void apiCallsNeverBlockTheCaller() {
        StockfishTestSupport.requireStockfish();
        manager = new EngineManager(false);
        long t0 = System.nanoTime();
        manager.select(EngineManager.STOCKFISH_LITE);
        CompletableFuture<String> move = manager.botMove(new Board().getFen(), 5);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 1_000, "select+botMove took " + ms + " ms on the caller thread (expected a few ms)");
        assertNotNull(move.join());
    }

    @Test
    void switchingEngineDuringAGameKeepsThePosition() throws Exception {
        StockfishTestSupport.requireStockfish();
        manager = new EngineManager(false);
        Board board = new Board();
        manager.select(EngineManager.STOCKFISH);
        play(board, "e2e4");
        String reply = manager.botMove(board.getFen(), 20).get(15, TimeUnit.SECONDS);
        play(board, reply);
        play(board, "g1f3");

        // switch while the bot is thinking: the request completes (old or new engine), the game goes on
        CompletableFuture<String> inFlight = manager.botMove(board.getFen(), 3);
        manager.select(EngineManager.STOCKFISH_LITE);
        play(board, inFlight.get(15, TimeUnit.SECONDS));
        assertEquals(EngineManager.STOCKFISH_LITE, manager.activeProfile().id());
        play(board, "d2d4");
        long t0 = System.nanoTime();
        play(board, manager.botMove(board.getFen(), 10).get(15, TimeUnit.SECONDS));
        long liteMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(liteMs < EngineManager.Budget.lite().botMaxMovetimeMs() + 10_000, "lite bot took " + liteMs);
        assertEquals(EngineStatus.State.READY, manager.statusProperty().get().state());

        boolean maia = manager.profiles().stream().anyMatch(p -> p.id().equals(EngineManager.MAIA_1500) && p.available());
        if (maia) {
            manager.select(EngineManager.MAIA_1500);
            play(board, "b1c3");
            t0 = System.nanoTime();
            String maiaMove = manager.botMove(board.getFen(), 0).get(40, TimeUnit.SECONDS);
            long firstMs = (System.nanoTime() - t0) / 1_000_000;
            play(board, maiaMove);
            assertEquals(EngineStatus.State.READY, manager.statusProperty().get().state());
            System.out.printf("maia first move (includes lc0 start + weights) %d ms%n", firstMs);
        }
        // back to Stockfish
        manager.select(EngineManager.STOCKFISH);
        play(board, "c1g5");
        play(board, manager.botMove(board.getFen(), 20).get(15, TimeUnit.SECONDS));
    }

    @Test
    void unavailableProfileReportsErrorAndKeepsActiveOne() {
        StockfishTestSupport.requireStockfish();
        manager = new EngineManager(false);
        EngineProfile before = manager.activeProfile();
        manager.select("does-not-exist");
        assertEquals(before, manager.activeProfile());
        manager.profiles().stream().filter(p -> !p.available()).findFirst().ifPresent(p -> {
            manager.select(p.id());
            assertEquals(before, manager.activeProfile());
            assertEquals(EngineStatus.State.ERROR, manager.statusProperty().get().state());
        });
    }

    @Test
    void oneGigabyteBoardRunsTheBotOnTheAnalysisProcess() throws Exception {
        StockfishTestSupport.requireStockfish();
        ProcessPlan oneGb = new ProcessPlan(906, "1 GB", true, 16, 16, 2, 120_000);
        manager = new EngineManager(false, oneGb);
        manager.select(EngineManager.STOCKFISH);
        Board board = new Board();
        PositionAnalyzer analyzer = manager.analyzer();
        analyzer.analyze(board.getFen(), 18, 1, null); // live analysis running while the bot is asked
        play(board, manager.botMove(board.getFen(), 1).get(60, TimeUnit.SECONDS));
        play(board, "e7e5".equals(board.legalMoves().get(0).toString()) ? "d7d5" : board.legalMoves().get(0).toString());
        play(board, manager.botMove(board.getFen(), 20).get(60, TimeUnit.SECONDS));
        assertEquals(1, manager.liveClients().size(), "only the analysis process");
        assertSame(manager.analysisClient(), manager.liveClients().get(0));
        // the live analysis resumes after the bot search
        String fen = board.getFen();
        analyzer.analyze(fen, 12, 1, null);
        long deadline = System.currentTimeMillis() + 60_000;
        while (analyzer.lastUpdate() == null || !analyzer.lastUpdate().finished()) {
            assertTrue(System.currentTimeMillis() < deadline, "live analysis did not finish");
            Thread.sleep(10);
        }
    }

    @Test
    void idleEnginesAreClosedAndRestartLazily() throws Exception {
        StockfishTestSupport.requireStockfish();
        ProcessPlan quick = new ProcessPlan(3_790, "4 GB", false, 16, 16, 1, 1_500);
        manager = new EngineManager(false, quick);
        manager.select(EngineManager.STOCKFISH);
        String fen = new Board().getFen();
        manager.botMove(fen, 5).get(60, TimeUnit.SECONDS);
        assertFalse(manager.liveClients().isEmpty(), "bot process alive right after its move");
        long deadline = System.currentTimeMillis() + 60_000;
        while (!manager.liveClients().isEmpty()) {
            assertTrue(System.currentTimeMillis() < deadline, "idle engines not closed");
            Thread.sleep(50);
        }
        assertNotNull(manager.botMove(fen, 5).get(60, TimeUnit.SECONDS), "bot restarts on demand");
    }

    @Test
    void shutdownLeavesNoEngineProcesses() throws Exception {
        StockfishTestSupport.requireStockfish();
        // only the processes started by this manager: other tests in the same JVM may still own engines
        java.util.Set<Long> existing = ProcessHandle.current().children().map(ProcessHandle::pid)
                .collect(java.util.stream.Collectors.toSet());
        manager = new EngineManager(false);
        manager.botMove(new Board().getFen(), 1).get(15, TimeUnit.SECONDS);
        manager.analysisClient().search(new Board().getFen(), SearchLimits.depth(5)).result().get(10, TimeUnit.SECONDS);
        java.util.function.Supplier<java.util.List<ProcessHandle>> mine = () -> ProcessHandle.current().children()
                .filter(p -> !existing.contains(p.pid())).filter(ProcessHandle::isAlive).toList();
        assumeTrue(mine.get().size() >= 2);
        manager.shutdown();
        long deadline = System.currentTimeMillis() + 3_000;
        while (!mine.get().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(java.util.List.of(), mine.get());
    }
}
