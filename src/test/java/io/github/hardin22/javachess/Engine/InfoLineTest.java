package io.github.hardin22.javachess.Engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InfoLineTest {

    @Test
    void parsesStockfishLine() {
        InfoLine l = InfoLine.parse("info depth 18 seldepth 25 multipv 2 score cp -34 lowerbound nodes 123456 nps 987654 "
                + "hashfull 12 tbhits 0 time 125 pv e7e5 g1f3 b8c6").orElseThrow();
        assertEquals(18, l.depth());
        assertEquals(25, l.selDepth());
        assertEquals(2, l.multiPv());
        assertEquals(Score.cp(-34), l.score());
        assertEquals(InfoLine.Bound.LOWER, l.bound());
        assertEquals(123456, l.nodes());
        assertEquals(125, l.timeMs());
        assertEquals(List.of("e7e5", "g1f3", "b8c6"), l.pv());
        assertEquals("e7e5", l.move());
    }

    @Test
    void parsesMateAndWdlAndPromotion() {
        InfoLine l = InfoLine.parse("info depth 5 score mate -2 wdl 0 0 1000 pv a7a8q b8a8").orElseThrow();
        assertEquals(Score.mate(-2), l.score());
        assertEquals("a7a8q", l.move());
    }

    @Test
    void rejectsLinesWithoutPvOrScore() {
        assertTrue(InfoLine.parse("info depth 10 currmove e2e4 currmovenumber 1").isEmpty());
        assertTrue(InfoLine.parse("info string NNUE evaluation using nn-1111.nnue").isEmpty());
        assertTrue(InfoLine.parse("info depth 3 score cp 10").isEmpty());
        assertTrue(InfoLine.parse("info depth 3 pv e2e4").isEmpty());
        assertTrue(InfoLine.parse("info depth x score cp 1 pv e2e4").isEmpty());
        assertTrue(InfoLine.parse("info depth 0 score mate 0").isEmpty());
        assertTrue(InfoLine.parse("bestmove e2e4").isEmpty());
        assertTrue(InfoLine.parse(null).isEmpty());
    }

    @Test
    void pvStopsAtFirstNonMoveToken() {
        InfoLine l = InfoLine.parse("info depth 2 score cp 1 pv e2e4 e7e5 string junk").orElseThrow();
        assertEquals(List.of("e2e4", "e7e5"), l.pv());
    }

    @Test
    void goCommand() {
        assertEquals("go depth 12", SearchLimits.depth(12).toGoCommand());
        assertEquals("go infinite", SearchLimits.infinite().toGoCommand());
        assertEquals("go nodes 1000 searchmoves e2e4 e2e3",
                SearchLimits.nodes(1000).withSearchMoves(List.of("e2e4", "e2e3")).toGoCommand());
        assertEquals("go movetime 500", SearchLimits.movetime(500).toGoCommand());
    }

    @Test
    void scoreHelpers() {
        assertEquals("+0.35", Score.cp(35).format());
        assertEquals("M3", Score.mate(3).format());
        assertEquals("-M2", Score.mate(-2).format());
        assertEquals(997.0, Score.mate(3).legacyPawns());
        assertEquals(-998.0, Score.mate(-2).legacyPawns());
        assertEquals(Score.cp(-50), Score.cp(50).forWhite(false));
        assertEquals(0.5, Score.cp(0).winProbability(), 1e-9);
        assertEquals(1.0, Score.mate(5).winProbability());
        assertEquals(0.0, Score.mate(-1).winProbability());
    }
}
