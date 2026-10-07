package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Engine.Score;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MoveTextTest {

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    @Test
    void italianLetters() {
        assertEquals("Cf3", MoveText.italian("Nf3"));
        assertEquals("Axb5+", MoveText.italian("Bxb5+"));
        assertEquals("Tfe1", MoveText.italian("Rfe1"));
        assertEquals("Dh5#", MoveText.italian("Qh5#"));
        assertEquals("Rg1", MoveText.italian("Kg1"));
        assertEquals("e8=D", MoveText.italian("e8=Q"));
        assertEquals("bxa1=C+", MoveText.italian("bxa1=N+"));
        assertEquals("O-O-O", MoveText.italian("O-O-O"));
        assertEquals("exd5", MoveText.italian("exd5"));
    }

    @Test
    void numberedLinesFromWhiteAndBlack() {
        assertEquals("1. e4 e5 2. Cf3 Cc6", MoveText.line(START, List.of("e2e4", "e7e5", "g1f3", "b8c6"), 10));
        assertEquals("1… e5 2. Cf3", MoveText.line(AFTER_E4, List.of("e7e5", "g1f3"), 10));
        assertEquals("1. e4 e5", MoveText.line(START, List.of("e2e4", "e7e5", "g1f3"), 2));
        // stops at the first illegal move
        assertEquals("1. e4", MoveText.line(START, List.of("e2e4", "e2e4"), 10));
    }

    @Test
    void numberedSingleMove() {
        assertEquals("1. Cf3", MoveText.numbered(START, "g1f3"));
        assertEquals("1… c5", MoveText.numbered(AFTER_E4, "c7c5"));
    }

    @Test
    void castlingAndPromotionInSan() {
        String castle = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1";
        assertEquals(List.of("O-O"), MoveText.san(castle, List.of("e1g1"), 1));
        String promo = "8/4P3/8/8/8/8/k7/7K w - - 0 1";
        assertEquals("e8=D", MoveText.italian(MoveText.sanOf(promo, "e7e8q")));
        // a four-letter promotion is read as a queen
        assertEquals("e8=Q", MoveText.sanOf(promo, "e7e8"));
    }

    @Test
    void legalMoveLookup() {
        com.github.bhlangonijr.chesslib.Board b = new com.github.bhlangonijr.chesslib.Board();
        assertEquals("e2e4", MoveText.legal(b, "e2e4").toString());
        assertNull(MoveText.legal(b, "e2e5"));
        assertNull(MoveText.legal(b, "zz"));
    }

    @Test
    void evaluations() {
        assertEquals("+0.35", MoveText.eval(Score.cp(35)));
        assertEquals("−1.20", MoveText.eval(Score.cp(-120)));
        assertEquals("0.00", MoveText.eval(Score.cp(0)));
        assertEquals("M3", MoveText.eval(Score.mate(3)));
        assertEquals("−M2", MoveText.eval(Score.mate(-2)));
        assertEquals("0-1", MoveText.eval(Score.mate(0)));
        assertEquals("1-0", MoveText.eval(Score.mateDelivered()));
        assertEquals("", MoveText.eval(null));
    }

    @Test
    void fenFields() {
        assertEquals(1, MoveText.moveNumber(START));
        assertEquals(1, MoveText.moveNumber("8/8/8/8/8/8/8/8 w"));
        assertEquals("1. ", MoveText.number(START));
        assertEquals("1… ", MoveText.number(AFTER_E4));
    }
}
