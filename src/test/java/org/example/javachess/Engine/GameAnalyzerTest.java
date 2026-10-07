package org.example.javachess.Engine;

import org.example.javachess.Oggetti.MoveAnalysis;
import org.example.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.example.javachess.Services.GameAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Full game review on the shared review engine (skipped without Stockfish). */
class GameAnalyzerTest {

    @Test
    void foolsMateIsScoredFromTheRightSide() {
        StockfishTestSupport.requireStockfish();
        GameAnalyzer analyzer = new GameAnalyzer();
        List<MoveAnalysis> a = analyzer.analyzeGame("1. f2f3 e7e5 2. g2g4 d8h4 0-1", 12, null);
        assertEquals(4, a.size());
        MoveAnalysis g4 = a.get(2);
        MoveAnalysis qh4 = a.get(3);
        assertEquals(MoveClassification.BLUNDER, g4.getClassification(), "g4 allows mate in one");
        assertNotEquals(MoveClassification.BLUNDER, qh4.getClassification());
        assertTrue(qh4.isMate());
        assertTrue(qh4.getScore() < -50_000, "Black mates: score from White's POV must be very negative, was "
                + qh4.getScore());
        assertTrue(analyzer.calculateAccuracy(a, false) > analyzer.calculateAccuracy(a, true));
    }

    @Test
    void initialFenAndIllegalTailAreHandled() {
        StockfishTestSupport.requireStockfish();
        GameAnalyzer analyzer = new GameAnalyzer();
        String fen = "k7/8/2K5/8/8/8/8/1Q6 w - - 0 1";
        List<MoveAnalysis> a = analyzer.analyzeGame(fen, List.of("b1b6", "Partita", "interrotta."), 12, null);
        assertEquals(1, a.size(), "stops at the first unreadable token");
        assertEquals(MoveClassification.BLUNDER, a.get(0).getClassification(), "stalemate throws the win");
    }

    @Test
    void secondReviewReusesTheEngineProcess() {
        StockfishTestSupport.requireStockfish();
        GameAnalyzer analyzer = new GameAnalyzer();
        analyzer.analyzeGame("e2e4 e7e5", 8, null);
        UciClient first = EngineManager.get().acquireReviewClient();
        EngineManager.get().releaseReviewClient();
        int started = first.processesStarted();
        analyzer.analyzeGame("d2d4 d7d5", 8, null);
        assertSame(first, EngineManager.get().acquireReviewClient());
        EngineManager.get().releaseReviewClient();
        assertEquals(started, first.processesStarted(), "no new process per review");
    }
}
