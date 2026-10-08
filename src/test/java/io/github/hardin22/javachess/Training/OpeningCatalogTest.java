package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpeningCatalogTest {

    @Test
    void everyOpeningIsKnownToTheBookAndPlayedInRealGames() {
        Set<String> ids = new HashSet<>();
        for (OpeningCatalog.Opening o : OpeningCatalog.all()) {
            assertTrue(ids.add(o.id()), "duplicate id " + o.id());
            assertFalse(o.moves().isEmpty());
            assertEquals(o.white(), o.moves().size() % 2 == 1, o.id() + ": the line ends with the trained side's move");
            Board b = new Board();
            for (String uci : o.moves()) {
                Move m = new Move(uci, b.getSideToMove());
                assertTrue(b.legalMoves().contains(m), o.id() + " " + uci);
                b.doMove(m);
            }
            assertTrue(OpeningExplorer.standard().games(b.getFen(), OpeningExplorer.Rating.CLUB) > 100, o.id());
            assertTrue(o.bookName().isPresent(), o.id() + " has a book name");
        }
        assertTrue(OpeningCatalog.forSide(true).size() >= 8);
        assertTrue(OpeningCatalog.forSide(false).size() >= 8);
    }

    @Test
    void linesAreWrittenWithItalianLetters() {
        OpeningCatalog.Opening italian = OpeningCatalog.byId("italiana").orElseThrow();
        assertEquals("1. e4 e5 2. Cf3 Cc6 3. Ac4", italian.san());
        assertTrue(italian.bookName().orElseThrow().contains("Partita Italiana"), italian.bookName().get());
    }
}
