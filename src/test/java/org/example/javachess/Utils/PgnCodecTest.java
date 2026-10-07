package org.example.javachess.Utils;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PgnCodecTest {

    private static Board board(String fen) {
        Board b = PgnCodec.boardFromFen(fen);
        assertNotNull(b, "valid fen expected: " + fen);
        return b;
    }

    private static String san(String fen, String uci) {
        Board b = board(fen);
        Move m = PgnCodec.fromUci(b, uci);
        assertNotNull(m, "legal move expected: " + uci);
        return PgnCodec.toSan(b, m);
    }

    @Test
    void sanOfBasicMoves() {
        assertEquals("e4", san(PgnCodec.START_FEN, "e2e4"));
        assertEquals("Nf3", san(PgnCodec.START_FEN, "g1f3"));
    }

    @Test
    void sanOfCastlingCapturesAndEnPassant() {
        assertEquals("O-O", san("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1"));
        assertEquals("O-O-O", san("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1", "e8c8"));
        assertEquals("exd5", san("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5"));
        assertEquals("exd6", san("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2", "e5d6"));
    }

    @Test
    void sanOfPromotionAndUnderpromotion() {
        assertEquals("a8=Q+", san("4k3/P7/8/8/8/8/8/4K3 w - - 0 1", "a7a8q"));
        assertEquals("a8=N", san("4k3/P7/8/8/8/8/8/4K3 w - - 0 1", "a7a8n"));
        // missing promotion letter defaults to a queen
        assertEquals("a8=Q+", san("4k3/P7/8/8/8/8/8/4K3 w - - 0 1", "a7a8"));
    }

    @Test
    void sanDisambiguation() {
        // knights on b1 and f3 can both reach d2
        assertEquals("Nbd2", san("4k3/8/8/8/8/5N2/8/1N2K3 w - - 0 1", "b1d2"));
        // rooks on a1 and a5 (same file): rank needed
        assertEquals("R1a3", san("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1", "a1a3"));
        // three queens: full square needed for the one sharing file and rank
        assertEquals("Qh1e4", san("2k5/8/8/8/7Q/8/K7/4Q2Q w - - 0 1", "h1e4"));
        assertEquals("Q4e4", san("2k5/8/8/8/7Q/8/K7/4Q2Q w - - 0 1", "h4e4"));
    }

    @Test
    void sanMate() {
        assertEquals("Qxf7#", san("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4", "h5f7"));
    }

    @Test
    void fromSanIsTolerant() {
        Board b = board(PgnCodec.START_FEN);
        assertEquals("g1f3", PgnCodec.toUci(PgnCodec.fromSan(b, "Nf3")));
        assertEquals("g1f3", PgnCodec.toUci(PgnCodec.fromSan(b, "Ngf3")));
        assertEquals("e2e4", PgnCodec.toUci(PgnCodec.fromSan(b, "e4!?")));
        Board c = board("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        assertEquals("e1g1", PgnCodec.toUci(PgnCodec.fromSan(c, "0-0")));
        Board p = board("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        assertEquals("a7a8q", PgnCodec.toUci(PgnCodec.fromSan(p, "a8Q")));
        assertNull(PgnCodec.fromSan(b, "Nf6"));
    }

    @Test
    void fenValidation() {
        assertTrue(PgnCodec.isValidFen(PgnCodec.START_FEN));
        assertTrue(PgnCodec.isValidFen("4k3/8/8/8/8/8/8/4K3 w - -"));
        assertFalse(PgnCodec.isValidFen(null));
        assertFalse(PgnCodec.isValidFen("8/8/8/8/8/8/8/8 w - - 0 1"), "no kings");
        assertFalse(PgnCodec.isValidFen("4k3/8/8/8/8/8/8/4KK2 w - - 0 1"), "two white kings");
        assertFalse(PgnCodec.isValidFen("P3k3/8/8/8/8/8/8/4K3 w - - 0 1"), "pawn on rank 8");
        assertFalse(PgnCodec.isValidFen("4k3/8/8/8/8/8/8/4K3x w - - 0 1"));
        assertFalse(PgnCodec.isValidFen("4k3/8/8/8/8/8/8/4K2 w - - 0 1"), "rank with 7 files");
        // black king in check with white to move: impossible position
        assertFalse(PgnCodec.isValidFen("4k3/4R3/8/8/8/8/8/4K3 w - - 0 1"));
    }

    @Test
    void replaySkipsNoiseAndStopsAtIllegalMove() {
        PgnCodec.Replay r = PgnCodec.replay(PgnCodec.START_FEN,
                List.of("1.", "e2e4", "Partita", "e7e5", "2.", "Nf3", "e1e3", "d7d5"));
        assertEquals(List.of("e2e4", "e7e5", "g1f3"), r.uciMoves());
        assertEquals("e1e3", r.rejectedToken());
        assertFalse(r.complete());
    }

    @Test
    void pgnRoundTrip() {
        List<String> moves = List.of("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7");
        String pgn = PgnCodec.toPgn(Map.of("White", "Me", "Black", "Bot"), PgnCodec.START_FEN, moves, "1-0");
        assertTrue(pgn.contains("[White \"Me\"]"));
        assertTrue(pgn.contains("1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0"), pgn);
        assertFalse(pgn.contains("[FEN"));
        List<PgnCodec.PgnGame> parsed = PgnCodec.parsePgn(pgn);
        assertEquals(1, parsed.size());
        assertEquals(moves, parsed.get(0).uciMoves());
        assertEquals("1-0", parsed.get(0).result());
        assertEquals("Me", parsed.get(0).tags().get("White"));
    }

    @Test
    void pgnWithCustomStartAndBlackToMove() {
        String fen = "4k3/8/8/8/8/8/4p3/K7 b - - 0 1";
        String pgn = PgnCodec.toPgn(Map.of(), fen, List.of("e2e1q", "a1b2"), "*");
        assertTrue(pgn.contains("[SetUp \"1\"]"));
        assertTrue(pgn.contains("[FEN \"" + fen + "\"]"), pgn);
        assertTrue(pgn.contains("1... e1=Q+ 2. Kb2 *"), pgn);
        PgnCodec.PgnGame g = PgnCodec.parsePgn(pgn).get(0);
        assertEquals(List.of("e2e1q", "a1b2"), g.uciMoves());
        assertEquals(fen, g.initialFen());
    }

    @Test
    void parsesMultipleGamesWithCommentsVariationsAndNags() {
        String text = """
                [Event "One"]
                [White "A \\"quoted\\""]
                [Black "B"]
                [Result "0-1"]

                1. e4 {best by test} e5 (1... c5 2. Nf3 (2. c3)) 2. Nf3 $1 Nc6 ; rest of line
                3. Bb5 a6 0-1

                [Event "Two"]
                [Result "1/2-1/2"]

                1.d4 d5 2.c4 1/2-1/2
                """;
        List<PgnCodec.PgnGame> games = PgnCodec.parsePgn(text);
        assertEquals(2, games.size());
        assertEquals(List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6"), games.get(0).uciMoves());
        assertEquals("A \"quoted\"", games.get(0).tags().get("White"));
        assertEquals("0-1", games.get(0).result());
        assertEquals(List.of("d2d4", "d7d5", "c2c4"), games.get(1).uciMoves());
        assertEquals("1/2-1/2", games.get(1).result());
    }

    @Test
    void longGamesAreWrappedAt80Columns() {
        List<String> moves = List.of("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8",
                "g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6");
        String pgn = PgnCodec.toPgn(Map.of(), null, moves, "1/2-1/2");
        for (String line : pgn.split("\n")) {
            assertTrue(line.length() <= 80, line);
        }
        assertEquals(moves, PgnCodec.parsePgn(pgn).get(0).uciMoves());
    }

    @Test
    void resultOfFinishedPositions() {
        assertEquals("1-0", PgnCodec.resultOf(board("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4")));
        assertEquals("1/2-1/2", PgnCodec.resultOf(board("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")));
        assertEquals("1/2-1/2", PgnCodec.resultOf(board("4k3/8/8/8/8/8/8/4K3 w - - 0 1")));
        assertNull(PgnCodec.resultOf(board(PgnCodec.START_FEN)));
    }
}
