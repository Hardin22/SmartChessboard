package io.github.hardin22.javachess.Engine;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import io.github.hardin22.javachess.Services.GameAnalyzer;
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
    void queenTakeThatAllowsMateIsABlunder() {
        StockfishTestSupport.requireStockfish();
        GameAnalyzer analyzer = new GameAnalyzer();
        // 1.e4 d5 2.exd5 Bd7 3.d4 a5 4.c4 f6 5.Qh5+ g6 6.Be2 gxh5?? 7.Bxh5#
        List<MoveAnalysis> a = analyzer.analyzeGame(
                "e2e4 d7d5 e4d5 c8d7 d2d4 a7a5 c2c4 f7f6 d1h5 g7g6 f1e2 g6h5 e2h5", 12, null);
        assertEquals(13, a.size());
        assertEquals(MoveClassification.BLUNDER, a.get(11).getClassification(), "6...gxh5 allows Bxh5#");
        assertNotEquals(MoveClassification.BLUNDER, a.get(12).getClassification(), "7.Bxh5# delivers mate");
        assertTrue(a.get(12).getScore() > 50_000, "White mated: White POV score must be huge");
    }
}
