package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Utils.PgnCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupPositionTest {

    static BoardSnapshot.BoardView view(String placement, List<String> lastMove, Side turn, List<String> moves) {
        return new BoardSnapshot.BoardView(new BoardSnapshot.Rect(0, 0, 400, 400), false, false, 32, placement,
                lastMove, turn, moves, null, null);
    }

    @Test
    void theMoveListGivesTheExactPositionAndHistory() {
        String placement = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R";
        SetupPosition.Result r = SetupPosition.build(placement,
                view(placement, List.of(), null, List.of("e4", "e5", "Nf3", "Nc6")), PageInfo.of(""), Side.BLACK);
        assertEquals("moves", r.turnSource());
        assertEquals(Side.WHITE, r.board().getSideToMove());
        assertEquals(PgnCodec.START_FEN, r.initialFen());
        assertEquals(List.of("e2e4", "e7e5", "g1f3", "b8c6"), r.moves());
    }

    @Test
    void aMoveListThatDoesNotMatchIsIgnored() {
        String placement = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR";
        SetupPosition.Result r = SetupPosition.build(placement,
                view(placement, List.of("e7", "e5"), null, List.of("d4", "d5")), PageInfo.of(""), Side.WHITE);
        assertEquals("last move", r.turnSource());
        assertTrue(r.moves().isEmpty());
        assertEquals(Side.WHITE, r.board().getSideToMove());
    }

    @Test
    void theAnalysisUrlGivesTurnAndRights() {
        String placement = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R";
        PageInfo page = PageInfo.of(
                "https://lichess.org/analysis/standard/rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R_b_KQkq_-_1_2");
        SetupPosition.Result r = SetupPosition.build(placement, view(placement, List.of(), null, null), page,
                Side.WHITE);
        assertEquals("url", r.turnSource());
        assertEquals(Side.BLACK, r.board().getSideToMove());
    }

    @Test
    void theRunningClockGivesTheTurn() {
        String placement = "6kr/Q4r1p/4p3/1P1pP3/P4q1p/8/5PP1/R5K1";
        SetupPosition.Result r = SetupPosition.build(placement, view(placement, List.of("f7", "f8"), Side.WHITE, null),
                PageInfo.of(""), Side.BLACK);
        assertEquals("clock", r.turnSource());
        assertEquals(Side.WHITE, r.board().getSideToMove());
    }

    @Test
    void theLastMoveHighlightGivesTheTurnInAnyOrder() {
        String afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR";
        for (List<String> squares : List.of(List.of("e2", "e4"), List.of("e4", "e2"))) {
            SetupPosition.Result r = SetupPosition.build(afterE4, view(afterE4, squares, null, null), PageInfo.of(""),
                    Side.WHITE);
            assertEquals(Side.BLACK, r.board().getSideToMove(), squares.toString());
            assertEquals("e3", r.board().getEnPassant().name().toLowerCase(), "double step: en passant square");
        }
    }

    @Test
    void castlingHighlightsBothOwnPieces() {
        String castled = "rnbqk2r/pppp1ppp/5n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1";
        SetupPosition.Result r = SetupPosition.build(castled, view(castled, List.of("e1", "g1", "h1", "f1"), null,
                null), PageInfo.of(""), Side.WHITE);
        assertEquals(Side.BLACK, r.board().getSideToMove());
        assertTrue(r.board().getFen().contains(" kq ") || r.board().getFen().contains(" kq"),
                "white lost castling rights, black keeps them: " + r.board().getFen());
    }

    @Test
    void startPositionIsWhiteToMoveAndOtherwiseTheDefault() {
        assertEquals(Side.WHITE, SetupPosition.build(SetupPosition.START_PLACEMENT, null, null, Side.BLACK).board()
                .getSideToMove());
        String midgame = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R";
        assertEquals(Side.BLACK, SetupPosition.build(midgame, view(midgame, List.of(), null, null), PageInfo.of(""),
                Side.BLACK).board().getSideToMove());
    }

    @Test
    void aKingInCheckMovesWhenNothingElseTells() {
        String placement = "6R1/1k1p3P/8/KN2P3/8/5B2/8/8"; // black king in check from the bishop
        SetupPosition.Result r = SetupPosition.build(placement, view(placement, List.of(), null, null),
                PageInfo.of(""), Side.WHITE);
        assertEquals(Side.BLACK, r.board().getSideToMove());
        assertEquals("check", r.turnSource());
    }

    @Test
    void misreadingsWithImpossibleMaterialAreRejected() {
        // what an uncalibrated model read on the chess.com bot theme: four white bishops with eight pawns
        assertNull(SetupPosition.build("knbq1bnq/pppp1ppp/3P4/4p2P/8/1PPP3P/3PB1BP/BNBQKBNQ", null, null, Side.WHITE));
        assertTrue(SetupPosition.plausibleMaterial("q3k2q/8/8/8/8/8/8/Q3K2Q"), "promoted queens without pawns");
        assertTrue(SetupPosition.plausibleMaterial(SetupPosition.START_PLACEMENT));
        assertFalse(SetupPosition.plausibleMaterial("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNQ"), "two queens, 8 pawns");
    }

    @Test
    void impossiblePositionsAreRejected() {
        assertNull(SetupPosition.build("8/8/8/8/8/8/8/8", null, null, Side.WHITE), "no kings");
        assertNull(SetupPosition.build("4k3/8/8/8/8/8/8/3KK3", null, null, Side.WHITE), "two white kings");
        assertNull(SetupPosition.build("P3k3/8/8/8/8/8/8/4K3", null, null, Side.WHITE), "pawn on the last rank");
        assertNull(SetupPosition.build(null, null, null, Side.WHITE));
    }
}
