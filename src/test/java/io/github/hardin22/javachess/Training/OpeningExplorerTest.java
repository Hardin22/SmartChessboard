package io.github.hardin22.javachess.Training;

import io.github.hardin22.javachess.Analysis.AnalysisTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpeningExplorerTest {

    final OpeningExplorer explorer = OpeningExplorer.standard();

    @Test
    void theStartingPositionIsLedByE4AndD4() {
        assertTrue(explorer.size() > 3000);
        List<OpeningExplorer.Candidate> moves = explorer.moves(AnalysisTree.START_FEN, OpeningExplorer.Rating.ALL);
        assertEquals("e2e4", moves.get(0).uci());
        assertEquals("d2d4", moves.get(1).uci());
        double sum = moves.stream().mapToDouble(OpeningExplorer.Candidate::share).sum();
        assertEquals(1.0, sum, 1e-9);
        assertTrue(moves.get(0).share() > 0.4);
    }

    @Test
    void movesUseItalianLettersAndPercentages() {
        String afterE4E5 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2";
        List<OpeningExplorer.Candidate> moves = explorer.moves(afterE4E5, OpeningExplorer.Rating.CLUB);
        assertEquals("g1f3", moves.get(0).uci());
        assertEquals("Cf3", moves.get(0).san());
        assertTrue(moves.get(0).percent().endsWith("%"));
        assertEquals("<1%", new OpeningExplorer.Candidate("a2a3", "a3", 1, 0.001).percent());
    }

    @Test
    void unknownPositionsHaveNoMoves() {
        assertTrue(explorer.moves("8/8/8/8/8/8/k7/7K w - - 0 1", OpeningExplorer.Rating.ALL).isEmpty());
        assertTrue(explorer.moves("not a fen", OpeningExplorer.Rating.ALL).isEmpty());
        assertEquals(0, explorer.games("8/8/8/8/8/8/k7/7K w - - 0 1", OpeningExplorer.Rating.EXPERT));
    }
}
