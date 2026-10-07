package io.github.hardin22.javachess.Analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisTreeTest {

    static final List<String> ITALIAN = List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5");

    @Test
    void mainLineNavigation() {
        AnalysisTree t = new AnalysisTree(null, ITALIAN);
        assertEquals(6, t.mainLineLength());
        assertTrue(t.current().isRoot());
        assertFalse(t.canGoBack());
        assertTrue(t.forward());
        assertEquals("e2e4", t.current().uci());
        assertEquals("1. e4", t.current().numberedMove());
        t.last();
        assertEquals("f8c5", t.current().uci());
        assertEquals("3… Ac5", t.current().numberedMove());
        assertFalse(t.forward());
        t.goToMainLine(3);
        assertEquals("g1f3", t.current().uci());
        assertEquals(3, t.current().ply());
        t.first();
        assertTrue(t.current().isRoot());
        assertEquals(List.of(), t.pathUci());
    }

    @Test
    void illegalMovesStopTheMainLine() {
        AnalysisTree t = new AnalysisTree(null, List.of("e2e4", "e7e5", "e1e3", "g8f6"));
        assertEquals(2, t.mainLineLength());
    }

    @Test
    void playingTheGameMoveStaysOnTheMainLine() {
        AnalysisTree t = new AnalysisTree(null, ITALIAN);
        t.goToMainLine(2);
        AnalysisTree.Node n = t.play("g1f3");
        assertSame(t.mainLineNode(3), n);
        assertTrue(t.isInMainLine());
        assertFalse(t.hasVariations());
    }

    @Test
    void variationsAndReturnToTheGame() {
        AnalysisTree t = new AnalysisTree(null, ITALIAN);
        t.goToMainLine(2); // after 1... e5
        assertNotNull(t.play("d2d4"));
        assertFalse(t.isInMainLine());
        assertNotNull(t.play("e5d4"));
        assertEquals(List.of("d2d4", "e5d4"), t.variationPath().stream().map(AnalysisTree.Node::uci).toList());
        assertSame(t.mainLineNode(2), t.branchPoint());
        assertEquals(List.of("e2e4", "e7e5", "d2d4", "e5d4"), t.pathUci());
        assertTrue(t.hasVariations());

        // the main line still goes on from the branch point
        t.returnToMainLine();
        assertSame(t.mainLineNode(2), t.current());
        assertTrue(t.forward());
        assertEquals("g1f3", t.current().uci());

        // playing the same variation again reuses it
        t.goToMainLine(2);
        AnalysisTree.Node again = t.play("d2d4");
        assertEquals(2, t.mainLineNode(2).children().size());
        assertTrue(again.children().size() == 1);
    }

    @Test
    void illegalMoveKeepsTheCursor() {
        AnalysisTree t = new AnalysisTree(null, ITALIAN);
        assertNull(t.play("e2e5"));
        assertTrue(t.current().isRoot());
        assertNull(t.play("nonsense"));
    }

    @Test
    void autoQueenPromotion() {
        AnalysisTree t = new AnalysisTree("8/4P3/8/8/8/8/k7/7K w - - 0 1", List.of());
        AnalysisTree.Node n = t.play("e7e8");
        assertEquals("e7e8q", n.uci());
        assertEquals("e8=Q", n.san());
        // under-promotion kept
        t.first();
        assertEquals("e7e8n", t.play("e7e8n").uci());
    }

    @Test
    void playLinePutsTheCursorOnItsFirstMove() {
        AnalysisTree t = new AnalysisTree(null, ITALIAN);
        t.goToMainLine(4); // after 2... Nc6
        AnalysisTree.Node first = t.playLine(List.of("d2d4", "e5d4", "f3d4", "zzzz", "g8f6"));
        assertEquals("d2d4", first.uci());
        assertSame(first, t.current());
        t.last();
        assertEquals("f3d4", t.current().uci(), "line stops at the first illegal move");
        assertNull(t.playLine(List.of("a1a8")));
        assertNull(t.playLine(List.of()));
    }

    @Test
    void deleteVariation() {
        AnalysisTree t = new AnalysisTree(null, ITALIAN);
        t.goToMainLine(2);
        t.play("d2d4");
        t.play("e5d4");
        t.play("c2c3"); // Danish
        // sub-variation inside the variation
        t.back();
        t.play("g1f3");
        assertFalse(t.isInMainLine());
        assertTrue(t.deleteVariation()); // deletes only 3. Nf3 (sub-variation)
        assertEquals("e5d4", t.current().uci());
        assertEquals(1, t.current().children().size());
        t.forward();
        assertTrue(t.deleteVariation()); // deletes the whole 2. d4 line
        assertSame(t.mainLineNode(2), t.current());
        assertEquals(1, t.current().children().size());
        assertFalse(t.deleteVariation(), "the game itself cannot be deleted");
        assertFalse(t.hasVariations());
    }

    @Test
    void continuingAfterTheEndOfTheGameIsAVariation() {
        AnalysisTree t = new AnalysisTree(null, List.of("e2e4"));
        t.last();
        t.play("e7e5");
        assertFalse(t.isInMainLine());
        assertTrue(t.hasVariations());
        assertTrue(t.deleteVariation());
        assertSame(t.mainLineNode(1), t.current());
    }

    @Test
    void movetextWithVariations() {
        AnalysisTree t = new AnalysisTree(null, List.of("e2e4", "e7e5", "g1f3"));
        t.goToMainLine(1);
        t.play("c7c5");
        t.play("g1f3");
        t.goToMainLine(2);
        t.play("f2f4");
        assertEquals("1. e4 e5 (1... c5 2. Nf3) 2. Nf3 (2. f4)", t.movetext());
    }

    @Test
    void customStartAndForeignNodes() {
        String fen = "4k3/8/8/8/8/8/4P3/4K3 b - - 0 40";
        AnalysisTree t = new AnalysisTree(fen, List.of("e8d7", "e2e4"));
        assertEquals("40… Rd7", t.mainLineNode(1).numberedMove());
        assertEquals("41. e4", t.mainLineNode(2).numberedMove());
        AnalysisTree other = new AnalysisTree(null, ITALIAN);
        assertThrows(IllegalArgumentException.class, () -> t.goTo(other.mainLineNode(1)));
    }
}
