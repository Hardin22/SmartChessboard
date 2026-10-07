package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier.Tuning;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC v1.9 rules of Brilliant and Great, and the rule fixes of the same round. The exclusions of Brilliant and Great, one real false positive each ({@code notes/fp/CATALOG.md}; chess.com
 * Stockfish 16 depth 22 gave Best/Excellent there). Each test also switches its exclusion off to show it is the reason,
 * and two real chess.com Brilliant / Great moves stay labelled.
 */
class BrilliantGreatExclusionsTest {

    // ------------------------------------------------------------------ Brilliant

    @Test
    void bE1PieceAlreadyEnPriseIsNotSacrificedAgain() {
        // Nezhmetdinov - Chernikov 1962, 13.Nxe2 after 12.Qxf6!!: the queen was already en prise
        assertTrue(ReviewClassifier.Sacrifice.of(board("r1b2rk1/pp1ppp1p/5Qp1/q7/4P3/1BN1B3/PPP1nPPP/R4RK1 w - - 1 13"),
                "c3e2", true).value() < 2);
        // Anderssen - Kieseritzky 1851 (Immortal Game), 11.Rg1: Bb5 hanging since move 5
        assertTrue(ReviewClassifier.Sacrifice.of(board("rnb1kb1r/p2p1ppp/2p2n2/1B3Nq1/4PpP1/3P4/PPP4P/RNBQ1K1R w kq - 1 11"),
                "h1g1", true).value() < 2);
    }

    @Test
    void bE4FakeSacrificeWinsMoreBackAtOnce() {
        // Deep Blue - Kasparov 1997 g6, 17.Bf5: ...exf5 Rxe7 wins the queen
        assertFake("r1k2b1r/p2nq1p1/2b1p1Bp/1p1n4/3P4/3Q1NB1/1PP2PPP/R3R1K1 w - - 2 17", "g6f5", true);
        // Fischer - Spassky 1972 g6, 18.Nd4: ...cxd4 opens the c-file
        assertFake("2r3k1/r2nqpp1/p3b2p/2pp4/8/Q3PN2/PP2BPPP/2R2RK1 w - - 4 18", "f3d4", true);
        // Steinitz - von Bardeleben 1895, 21.Ng5+: discovered attack on Qd7
        assertFake("r1r5/pp1qnk1p/4Npp1/3p4/6Q1/8/PP3PPP/2R1R1K1 w - - 0 21", "e6g5", true);
    }

    @Test
    void bE2AlreadyMatingIsNotBrilliant() {
        // live_184329331280 ply 47, 24.Bb7+: mate in 2 with the best move, mate in 3 with this one
        String fen = "r5nr/k1q3p1/Bn1b1p2/QRpPp2p/8/5NBP/1P3PP1/R5K1 w - - 7 24";
        ReviewInput in = oneMove(fen, "a6b7", Eval.whiteMates(2), "a6c8", Eval.whiteMates(3), "a6b7",
                Eval.whiteMates(3));
        assertNotEquals(MoveClassification.BRILLIANT, label(in, Tuning.DEFAULT));
    }

    @Test
    void bE3NonTopMoveMustLoseAlmostNothing() {
        // live_174300462740 ply 54, 27...Nb4: gives back a knight in a won position, 0.03 worse than the engine's move
        String fen = "3rr2k/pQp3pp/3bqp2/8/8/3R2P1/P1nB1P1P/2R3K1 b - - 3 27";
        ReviewInput in = oneMove(fen, "c2b4", Eval.cp(-794), "e6e2", Eval.cp(-720), "e6a2", Eval.cp(-696));
        assertNotEquals(MoveClassification.BRILLIANT, label(in, Tuning.DEFAULT));
        // the alternative is +7.2 for Black: B-E8 (winning anyway) excludes it as well
        assertEquals(MoveClassification.BRILLIANT, label(in, Tuning.DEFAULT.with("brilliantNonTopLoss", 0.05)
                .with("brilliantWinningCp", 100_000)));
    }

    @Test
    void realBrilliantStaysBrilliant() {
        // live_146777979394 ply 11, 6.Bxf7+ (chess.com Brilliant with both engines)
        String fen = "r1bqk1nr/pppp1ppp/2n5/2b5/2B1P3/2p2N2/PP3PPP/RNBQK2R w KQkq - 0 6";
        ReviewInput in = oneMove(fen, "c4f7", Eval.cp(98), "c4f7", Eval.cp(0), "d1b3", Eval.cp(98));
        assertEquals(MoveClassification.BRILLIANT, label(in, Tuning.DEFAULT));
    }

    @Test
    void decidedPositionsAndWorsePositionsAreJudgedInCentipawnsWhateverTheRating() {
        // famous games without ratings (default 1500: a flat curve), chess.com Best
        // Tal - Larsen 1965 g10, 34.Bc5: White is +7.9 anyway (B-E8)
        ReviewInput bc5 = oneMove("6k1/1b4pp/p2q4/3PR1P1/3BQ2P/1PP5/1P1K4/5r2 w - - 1 34", "d4c5", Eval.cp(792),
                "b3b4", Eval.cp(780), "e5e8", Eval.cp(815));
        // Kasparov - Topalov 1999, 24.Rxd4: White stands -0.22 after it (B-E7)
        ReviewInput rxd4 = oneMove("b2r3r/k4p1p/p2q1np1/NppP4/3p1Q2/P4PPB/1PP4P/1K1RR3 w - - 1 24", "d1d4",
                Eval.cp(-22), "d1d4", Eval.cp(-86), "a5c6", Eval.cp(-22));
        for (ReviewInput in : List.of(bc5, rxd4)) {
            assertNotEquals(MoveClassification.BRILLIANT, label(in, Tuning.DEFAULT));
        }
        Tuning noGuards = Tuning.DEFAULT.with("brilliantWinningCp", 100_000).with("brilliantMinCpAfter", -100_000);
        assertEquals(MoveClassification.BRILLIANT, label(bc5, noGuards));
        assertEquals(MoveClassification.BRILLIANT, label(rxd4, noGuards));
    }

    // ------------------------------------------------------------------ Great

    @Test
    void gE1CaptureIsNotGreat() {
        // daily_1017236144 ply 23, 12.fxg6: the capture keeps the material, chess.com Best
        String fen = "r1bqkb1r/pp2n2p/3p2p1/4pPPQ/4p2P/8/PPP2PB1/R1B1K1NR w KQkq - 0 12";
        ReviewInput in = oneMove(fen, "f5g6", Eval.cp(401), "f5g6", Eval.cp(-245), "h5d1", Eval.cp(401));
        assertNotEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatNoCapture", 0)));
    }

    @Test
    void gE2AlreadyWinningIsNotGreat() {
        // daily_1014523396 ply 25, 13.e5 after 12...Rc8?: White already at +5.4 (win chance 0.95)
        ReviewInput in = twoMoves("r2qk2r/1b1ppp2/ppn2n1p/5Np1/4P3/2PQ2B1/P1P1BPPP/R3K2R b KQkq - 2 12",
                "a8c8", Eval.cp(191), "d7d5", "e4e5", Eval.cp(543), "e4e5", Eval.cp(312), "h2h4", Eval.cp(543));
        assertNotEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatMaxEp", 0.98)
                .with("greatPunishGap", 0.10)));
    }

    @Test
    void gE3NotTheOnlyGoodMoveIsNotGreat() {
        // live_123574758978 ply 40, 20...Rc3+ after 20.Bf3?: the second best move is only ~0.11 worse
        ReviewInput in = twoMoves("2r2rk1/1p3ppp/p7/3p4/4n1P1/PP2K3/4BP1P/R6R w - - 0 20",
                "e2f3", Eval.cp(-239), "f2f3", "c8c3", Eval.cp(-618), "c8c3", Eval.cp(-371), "f8e8", Eval.cp(-618));
        assertNotEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatPunishGap", 0.10)));
    }

    @Test
    void realGreatStaysGreat() {
        // daily_1011205894 ply 48, 24...Rd2+ after 24.Nxe5? (chess.com Great)
        ReviewInput in = twoMoves("2kr4/1pp2p1p/p3p1p1/4n1b1/1PN5/2P2P2/P1K3PP/4R3 w - - 0 24",
                "c4e5", Eval.cp(-176), "e1e5", "d8d2", Eval.cp(-441), "d8d2", Eval.cp(-146), "g5f4", Eval.cp(-441));
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
    }

    @Test
    void gPlus1QuietForcingCheckInAWonAttackIsGreat() {
        // Botvinnik - Capablanca 1938, 32.Qg5+ at +8.3, chess.com Great: G+1 holds above the ordinary Great range
        ReviewInput botvinnik = oneMove("8/p5kp/1p2Pn2/3pQ2p/2pP4/qnP5/6PP/6K1 w - - 0 32", "e5g5", Eval.cp(827),
                "e5g5", Eval.cp(-682), "h2h3", Eval.cp(827));
        Tuning narrow = Tuning.DEFAULT.with("greatMaxEp", 0.90);
        assertEquals(MoveClassification.GREAT, label(botvinnik, narrow));
        assertNotEquals(MoveClassification.GREAT, label(botvinnik, narrow.with("greatForcingCheck", 0)));
        // Torre - Lasker 1925, 28.Rg7+ (the windmill) and D. Byrne - Fischer 1956, 19...Ne2+: chess.com Great
        assertEquals(MoveClassification.GREAT, label(oneMove("r3rnk1/pb3R2/3ppB1p/7q/1P1P4/4N3/P4PPP/4R1K1 w - - 1 28",
                "f7g7", Eval.cp(662), "f7g7", Eval.cp(-656), "f7b7", Eval.cp(662)), Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(oneMove("r3r1k1/pp3pbp/1Bp3p1/8/2bP4/Q1n2N2/P4PPP/3R2KR b - - 1 19",
                "c3e2", Eval.cp(-693), "c3e2", Eval.cp(99), "c3d1", Eval.cp(-693)), Tuning.DEFAULT));
    }

    // ------------------------------------------------------------------ rule fixes (SPEC v1.9 §4)

    @Test
    void ratingsBelowTheFloorAreJudgedAsTheFloor() {
        Tuning t = Tuning.DEFAULT;
        assertEquals(t.slope(800), t.slope(128), 1e-12); // live_174559261938: a 128-rated player
        assertTrue(t.slope(1200) > t.slope(800));
    }

    @Test
    void losingAFurtherPawnAndAHalfFromALostPositionIsAMistake() {
        // live_138835439112 ply 41, 21.Re7 at -7.8: -9.3 after it (chess.com Mistake), the win chance barely moves
        ReviewInput in = oneMove("r2r2k1/pp5p/6p1/2P2p2/3B4/8/qP3PPP/3RR1K1 w - - 0 21", "e1e7", Eval.cp(-781),
                "d1a1", Eval.cp(-800), "d4e3", Eval.cp(-934));
        assertEquals(MoveClassification.MISTAKE, label(in, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.MISTAKE, label(in, Tuning.DEFAULT.with("lostDrop", 0)));
    }

    @Test
    void allowingAMuchFasterMateIsAnInaccuracy() {
        // chess.com: M-7 -> M-2 (Kd8), M-5 -> M-2, M-6 -> M-2 are Inaccuracies; shortening by 1-2 moves is Excellent
        assertEquals(MoveClassification.INACCURACY,
                ReviewClassifier.fast(Eval.blackMates(7), Eval.blackMates(2), true, false).label());
        assertEquals(MoveClassification.INACCURACY,
                ReviewClassifier.fast(Eval.blackMates(5), Eval.blackMates(2), true, false).label());
        assertEquals(MoveClassification.EXCELLENT,
                ReviewClassifier.fast(Eval.blackMates(4), Eval.blackMates(2), true, false).label());
        assertEquals(MoveClassification.EXCELLENT,
                ReviewClassifier.fast(Eval.blackMates(6), Eval.blackMates(4), true, false).label());
    }

    @Test
    void mateRulesFromTheChessComLabels() {
        // live_173843114164 ply 68, 34...Kd7: mate in 3 against becomes mate in 1 (chess.com Inaccuracy)
        ReviewInput kd7 = oneMove("2r5/pqk3pp/4R3/4Qp2/8/4P1P1/PB3P1P/6K1 b - - 4 34", "c7d7", Eval.whiteMates(3),
                "c7d8", Eval.whiteMates(3), "c7b8", Eval.whiteMates(1));
        assertEquals(MoveClassification.INACCURACY, label(kd7, Tuning.DEFAULT));
        assertEquals(MoveClassification.INACCURACY,
                ReviewClassifier.fast(Eval.whiteMates(3), Eval.whiteMates(1), false, false).label());
        // live_184567962764 ply 132, 66...g3: mate in 2 kept as mate in 3 (chess.com Good, not Miss)
        ReviewInput g3 = oneMove("4K3/8/4k3/8/6p1/8/8/4q3 b - - 1 66", "g4g3", Eval.blackMates(2), "e1b4",
                Eval.blackMates(3), "e1e2", Eval.blackMates(3));
        assertEquals(MoveClassification.GOOD, label(g3, Tuning.DEFAULT));
    }

    // ------------------------------------------------------------------ recall round (PHASE3 §16, notes/fn)

    @Test
    void brilliantRecoveredByTheFinerExclusions() {
        // live_145692245792 ply 33, 17.Rxf6: wins back 3 at once if taken, less than sacrifice + 4 (chess.com Brilliant)
        ReviewInput rxf6 = rated(oneMove("r1b1k3/ppp2Npr/4pn1p/3q4/8/P1P5/1PQB1RPP/R5K1 w q - 3 17", "f2f6",
                Eval.cp(565), "f2f6", Eval.cp(533), "a1e1", Eval.cp(565)), 1117, 1096);
        assertEquals(MoveClassification.BRILLIANT, label(rxf6, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.BRILLIANT, label(rxf6, Tuning.DEFAULT.with("fakeRegain", 2)));
        // live_164828037706 ply 23, 12.Bg5: not the engine's move but loses less than 0.03 (chess.com Brilliant)
        ReviewInput bg5 = rated(oneMove("r2q1rk1/pb1nbppp/1p6/2pp3n/3P1B2/P1N1PN2/1P2BPPP/2RQ1RK1 w - - 4 12", "f4g5",
                Eval.cp(13), "f4e5", Eval.cp(10), "f4g3", Eval.cp(4)), 2238, 2245);
        assertEquals(MoveClassification.BRILLIANT, label(bg5, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.BRILLIANT, label(bg5, Tuning.DEFAULT.with("brilliantNonTopLoss", 0.01)));
    }

    @Test
    void greatAtTheLowerGap() {
        // live_145773198260 ply 33, 17.Qd2: the second best move is ~0.15 worse (chess.com Great)
        ReviewInput qd2 = rated(oneMove("r4rk1/pp3qpp/2pR4/2Pp2B1/3n4/8/PP2QPPP/RN4K1 w - - 0 17", "e2d2",
                Eval.cp(88), "e2d2", Eval.cp(-129), "e2f1", Eval.cp(88)), 1103, 1109);
        assertEquals(MoveClassification.GREAT, label(qd2, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(qd2, Tuning.DEFAULT.with("greatGap", 0.25)));
        // daily_1011205894 ply 56, 28...Rd2 (chess.com Great)
        ReviewInput rd2 = rated(oneMove("2kbR3/1pp2N1p/p5p1/5p2/1P6/1KP2P2/P5rP/8 b - - 1 28", "g2d2", Eval.cp(0),
                "g2d2", Eval.cp(228), "c8d7", Eval.cp(0)), 1301, 1202);
        assertEquals(MoveClassification.GREAT, label(rd2, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(rd2, Tuning.DEFAULT.with("greatGap", 0.25)));
    }

    private static ReviewInput rated(ReviewInput in, int white, int black) {
        return new ReviewInput(in.initialFen(), in.uciMoves(), in.positions(), in.book(), white, black);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertFake(String fen, String uci, boolean white) {
        ReviewClassifier.Sacrifice s = ReviewClassifier.Sacrifice.of(board(fen), uci, white);
        assertTrue(s.value() >= 2, uci + " offers " + s.value());
        assertTrue(s.regain() >= s.value() + 2, uci + " wins back " + s.regain() + " for " + s.value());
    }

    private static MoveClassification label(ReviewInput in, Tuning t) {
        GameReview r = ReviewClassifier.classifyGame(in, t);
        return r.moves().get(r.moves().size() - 1).label();
    }

    /** One move from {@code fen}: best and second line of the position before, best eval of the position after. */
    private static ReviewInput oneMove(String fen, String uci, Eval best, String bestMove, Eval second,
                                       String secondMove, Eval after) {
        List<PositionEval> ps = new ArrayList<>();
        ps.add(withLines(fen, best, bestMove, second, secondMove));
        ps.add(after(fen, uci, after));
        return new ReviewInput(fen, List.of(uci), ps, OpeningBook.NONE);
    }

    /** The opponent's move {@code prev} (evaluated {@code e0}, engine's move {@code best0}) then {@code uci}. */
    private static ReviewInput twoMoves(String fen0, String prev, Eval e0, String best0, String uci, Eval best,
                                        String bestMove, Eval second, String secondMove, Eval after) {
        String fen1 = play(fen0, prev);
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen0, e0, List.of(new EngineLine(best0, e0, List.of(best0), 20)), 20, 0, false));
        ps.add(withLines(fen1, best, bestMove, second, secondMove));
        ps.add(after(fen1, uci, after));
        return new ReviewInput(fen0, List.of(prev, uci), ps, OpeningBook.NONE);
    }

    private static PositionEval withLines(String fen, Eval best, String bestMove, Eval second, String secondMove) {
        return new PositionEval(fen, best, List.of(new EngineLine(bestMove, best, List.of(bestMove), 20),
                new EngineLine(secondMove, second, List.of(secondMove), 20)), 20, 0, false);
    }

    private static PositionEval after(String fen, String uci, Eval after) {
        String fen1 = play(fen, uci);
        String reply = board(fen1).legalMoves().get(0).toString();
        return new PositionEval(fen1, after, List.of(new EngineLine(reply, after, List.of(reply), 20)), 20, 0, false);
    }

    private static String play(String fen, String uci) {
        Board b = board(fen);
        Move m = Tactics.find(b, uci);
        assertNotNull(m, uci + " illegal in " + fen);
        b.doMove(m);
        return b.getFen();
    }

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }
}
