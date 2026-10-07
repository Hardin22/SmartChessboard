package org.example.javachess.Vision;

import com.github.bhlangonijr.chesslib.Board;
import org.example.javachess.Utils.PgnCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Legality-based correction, without the model (synthetic probabilities). */
class PositionResolverTest {

    private static BoardReading noisy(String placement, String wrongSquare, char seen, float confidence) {
        float[][][] p = BoardReading.certain(placement).probabilities();
        int f = wrongSquare.charAt(0) - 'a';
        int r = wrongSquare.charAt(1) - '1';
        char truth = BoardReading.parsePlacement(placement)[f][r];
        java.util.Arrays.fill(p[f][r], 0f);
        p[f][r][BoardReading.SYMBOLS.indexOf(seen)] = confidence;
        p[f][r][BoardReading.SYMBOLS.indexOf(truth)] = 1f - confidence;
        return new BoardReading(p, true, 0);
    }

    @Test
    void findsTheMoveDespiteAMisreadSquare() {
        Board start = PgnCodec.boardOrStart(PgnCodec.START_FEN);
        // after 1.e4 the classifier wrongly sees a bishop on g1 (it is a knight) with 70% confidence
        BoardReading r = noisy("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR", "g1", 'B', 0.7f);
        assertNotEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR", r.placement());
        PositionResolver.Resolution res = new PositionResolver().resolve(start, r, false);
        assertTrue(res.confident());
        assertEquals(1, res.moves().size());
        assertEquals("e2e4", PgnCodec.toUci(res.moves().get(0)));
        assertEquals(1, res.mismatches());
    }

    @Test
    void unchangedPositionIsRecognised() {
        Board start = PgnCodec.boardOrStart(PgnCodec.START_FEN);
        PositionResolver.Resolution res = new PositionResolver().resolve(start,
                BoardReading.certain("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"), false);
        assertTrue(res.unchanged());
        assertTrue(res.confident());
    }

    @Test
    void twoMovesBetweenFrames() {
        Board start = PgnCodec.boardOrStart(PgnCodec.START_FEN);
        PositionResolver.Resolution res = new PositionResolver().resolve(start,
                BoardReading.certain("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR"), true);
        assertEquals(2, res.moves().size());
        assertEquals("e2e4", PgnCodec.toUci(res.moves().get(0)));
        assertEquals("e7e5", PgnCodec.toUci(res.moves().get(1)));
    }

    @Test
    void unrelatedPositionIsNotConfident() {
        Board start = PgnCodec.boardOrStart(PgnCodec.START_FEN);
        PositionResolver.Resolution res = new PositionResolver().resolve(start,
                BoardReading.certain("8/8/4k3/3n3p/4K3/8/6p1/6N1"), false);
        assertFalse(res.confident());
    }

    @Test
    void placementRulesFixImpossibleReadings() {
        // two white kings (e1 certain, d1 a doubtful queen read as king) and a pawn on rank 8
        float[][][] p = BoardReading.certain("P7/7k/8/8/8/8/8/3QK3").probabilities();
        int d = 3;
        java.util.Arrays.fill(p[d][0], 0f);
        p[d][0][BoardReading.SYMBOLS.indexOf('K')] = 0.55f;
        p[d][0][BoardReading.SYMBOLS.indexOf('Q')] = 0.45f;
        java.util.Arrays.fill(p[0][7], 0f);
        p[0][7][BoardReading.SYMBOLS.indexOf('P')] = 0.6f;
        p[0][7][BoardReading.SYMBOLS.indexOf('Q')] = 0.4f;
        BoardReading raw = new BoardReading(p, true, 0);
        assertFalse(PgnCodec.isValidFen(raw.placement() + " w - - 0 1"));
        BoardReading fixed = raw.withPlacementRules();
        assertEquals("Q7/7k/8/8/8/8/8/3QK3", fixed.placement());
        assertTrue(PgnCodec.isValidFen(fixed.placement() + " w - - 0 1"));
        assertEquals(java.util.List.of("a8", "d1"), raw.uncertainSquares(0.7f));
    }
}
