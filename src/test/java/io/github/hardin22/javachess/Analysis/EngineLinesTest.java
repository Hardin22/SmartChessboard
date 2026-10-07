package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.InfoLine;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Engine.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineLinesTest {

    static final String START = MoveTextTest.START;
    static final String AFTER_E4 = MoveTextTest.AFTER_E4;

    /** Records requests; the test pushes updates through the last listener. */
    static final class FakeSource implements EngineLines.Source {
        final List<String> requests = new ArrayList<>();
        int stops;
        String unavailable;
        PositionAnalyzer.Listener listener;

        @Override
        public void analyze(String fen, int depth, int multiPv, PositionAnalyzer.Listener l) {
            requests.add(fen + " k=" + multiPv);
            listener = l;
        }

        @Override
        public void stop() {
            stops++;
        }

        @Override
        public String unavailableReason() {
            return unavailable;
        }
    }

    FakeSource source;
    EngineLines lines;

    @BeforeEach
    void setUp() {
        TestConfig.isolate();
        source = new FakeSource();
        lines = new EngineLines(source, Runnable::run, null, 0);
    }

    static InfoLine line(int multiPv, int depth, Score score, String... pv) {
        return new InfoLine(depth, depth, multiPv, score, InfoLine.Bound.EXACT, 1000, 0, 10, List.of(pv));
    }

    @Test
    void showsLinesWithItalianTextAndWhiteEvaluation() {
        lines.show(AFTER_E4);
        assertEquals(EngineLines.Status.SEARCHING, lines.statusProperty().get());
        assertEquals(List.of(AFTER_E4 + " k=2"), source.requests);

        // Black to move: scores are from Black's side, the panel shows them for White
        source.listener.onUpdate(new AnalysisUpdate(AFTER_E4, 18, List.of(
                line(1, 18, Score.cp(-30), "c7c5", "g1f3", "d7d6"),
                line(2, 18, Score.cp(-45), "e7e5", "g1f3")), true, null, 1000, 10));

        List<EngineLines.Line> l = lines.linesProperty().get();
        assertEquals(2, l.size());
        assertEquals("+0.30", l.get(0).eval());
        assertTrue(l.get(0).whiteBetter());
        assertEquals("1… c5", l.get(0).moveText());
        assertEquals("1… c5 2. Cf3 d6", l.get(0).text());
        assertEquals("c7c5", l.get(0).move());
        assertEquals("+0.45", l.get(1).eval());
        assertEquals(18, lines.depthProperty().get());
        assertEquals(EngineLines.Status.DONE, lines.statusProperty().get());
        assertEquals("+0.30", lines.evalTextProperty().get());
        assertEquals("c7c5", lines.bestMoveProperty().get());
        assertEquals(0.30, lines.whitePawnsProperty().get(), 1e-9);
    }

    @Test
    void mateScoresAndBlackAdvantage() {
        lines.show(START);
        source.listener.onUpdate(new AnalysisUpdate(START, 10, List.of(
                line(1, 10, Score.mate(-2), "f2f3"),
                line(2, 10, Score.cp(-250), "g2g4")), false, null, 1000, 10));
        List<EngineLines.Line> l = lines.linesProperty().get();
        assertEquals("−M2", l.get(0).eval());
        assertEquals(false, l.get(0).whiteBetter());
        assertEquals("−2.50", l.get(1).eval());
        assertEquals(EngineLines.Status.SEARCHING, lines.statusProperty().get());
    }

    @Test
    void updatesOfAnOlderPositionAreIgnored() {
        lines.show(START);
        PositionAnalyzer.Listener old = source.listener;
        lines.show(AFTER_E4);
        assertTrue(lines.linesProperty().get().isEmpty(), "lines cleared at once for the new position");
        old.onUpdate(new AnalysisUpdate(START, 20, List.of(line(1, 20, Score.cp(20), "e2e4")), true, null, 1, 1));
        assertTrue(lines.linesProperty().get().isEmpty());
        // an update for another fen through the new listener is ignored too
        source.listener.onUpdate(new AnalysisUpdate(START, 20, List.of(line(1, 20, Score.cp(20), "e2e4")), true,
                null, 1, 1));
        assertTrue(lines.linesProperty().get().isEmpty());
    }

    @Test
    void lineCountIsClampedSavedAndRestarts() {
        lines.show(START);
        lines.setLineCount(7);
        assertEquals(3, lines.lineCountProperty().get());
        assertEquals(START + " k=3", source.requests.get(source.requests.size() - 1));
        assertEquals("3", io.github.hardin22.javachess.Utils.ConfigManager.getProperty(EngineLines.LINES_KEY));
        // more lines from the engine than asked are not shown
        lines.setLineCount(1);
        source.listener.onUpdate(new AnalysisUpdate(START, 12, List.of(
                line(1, 12, Score.cp(20), "e2e4"), line(2, 12, Score.cp(15), "d2d4")), true, null, 1, 1));
        assertEquals(1, lines.linesProperty().get().size());
    }

    @Test
    void terminalPositionsNeedNoEngine() {
        String mated = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";
        lines.show(mated);
        assertEquals(EngineLines.Status.CHECKMATE, lines.statusProperty().get());
        assertEquals("Scacco matto", lines.messageProperty().get());
        assertEquals("0-1", lines.evalTextProperty().get());
        assertTrue(source.requests.isEmpty());

        String stalemate = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1";
        lines.show(stalemate);
        assertEquals(EngineLines.Status.STALEMATE, lines.statusProperty().get());
        assertEquals("½-½", lines.evalTextProperty().get());
    }

    @Test
    void unavailableEngineIsReported() {
        source.unavailable = "Stockfish non trovato";
        lines.show(START);
        assertEquals(EngineLines.Status.UNAVAILABLE, lines.statusProperty().get());
        assertEquals("Stockfish non trovato", lines.messageProperty().get());
        assertTrue(source.requests.isEmpty());
        // showing it again retries (the engine may have been installed meanwhile)
        source.unavailable = null;
        lines.show(START);
        assertEquals(EngineLines.Status.SEARCHING, lines.statusProperty().get());
    }

    @Test
    void disableAndStop() {
        lines.show(START);
        lines.setEnabled(false);
        assertEquals(EngineLines.Status.IDLE, lines.statusProperty().get());
        assertEquals(1, source.stops);
        lines.setEnabled(true);
        assertEquals(2, source.requests.size());
        lines.stop();
        assertNull(lines.fenProperty().get());
        assertEquals(EngineLines.Status.IDLE, lines.statusProperty().get());
    }

    @Test
    void samePositionTwiceDoesNotRestart() {
        lines.show(START);
        lines.show(START);
        assertEquals(1, source.requests.size());
        assertNull(lines.line(0));
    }
}
