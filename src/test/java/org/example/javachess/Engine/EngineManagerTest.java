package org.example.javachess.Engine;

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
        assertTrue(ms < 100, "select+botMove took " + ms + " ms on the caller thread");
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
        assertTrue(liteMs < EngineManager.Budget.lite().botMaxMovetimeMs() + 1_500, "lite bot took " + liteMs);
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
    void shutdownLeavesNoEngineProcesses() throws Exception {
        StockfishTestSupport.requireStockfish();
        manager = new EngineManager(false);
        manager.botMove(new Board().getFen(), 1).get(15, TimeUnit.SECONDS);
        manager.analysisClient().search(new Board().getFen(), SearchLimits.depth(5)).result().get(10, TimeUnit.SECONDS);
        assumeTrue(ProcessHandle.current().children().count() >= 2);
        manager.shutdown();
        long deadline = System.currentTimeMillis() + 3_000;
        while (ProcessHandle.current().children().anyMatch(ProcessHandle::isAlive) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, ProcessHandle.current().children().filter(ProcessHandle::isAlive).count());
    }
}
