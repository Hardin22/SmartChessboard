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

    /** Board facts of the B-E4 cards (SPEC v2.1 excludes from regain >= offered + 4; Deep Blue's Bf5 is a known FP). */
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
    void aKingMarchIntoAFullBoardIsBrilliant() {
        // Short - Timman 1991, 34.Kg5!! (chess.com Brilliant): the king walks to the fifth rank with queens and rooks
        // on, mate in 6; the second best move keeps only +2.48
        ReviewInput kg5 = rated(oneMove("2b1rrk1/2pR1p2/1pq1pQp1/p3P2p/P1PR1K1P/5N2/2P2PP1/8 w - - 6 34", "f4g5",
                Eval.whiteMates(6), "f4g5", Eval.cp(248), "d7e7", Eval.whiteMates(5)), 2500, 2500);
        assertEquals(MoveClassification.BRILLIANT, label(kg5, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.BRILLIANT, label(kg5, Tuning.DEFAULT.with("brilliantKingMarchPieces", 0)));
    }

    @Test
    void aSacrificeAnsweredByACheckingCaptureElsewhereIsNotBrilliant() {
        // Spassky - Bronstein 1960, 16.Nxf7 (chess.com Great): the best answer leaves the knight and takes the rook
        // with check, 16...exf1=Q+
        String fen = "r1bqrnk1/ppp1bpp1/3N3p/2P5/3P4/3Q1N2/PPB1p1PP/R4RK1 w - - 2 16";
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen, Eval.cp(248), List.of(
                new EngineLine("d6f7", Eval.cp(248), List.of("d6f7", "e2f1q", "a1f1", "d8d5"), 20),
                new EngineLine("d6e8", Eval.cp(-474), List.of("d6e8"), 20)), 20, 0, false));
        ps.add(after(fen, "d6f7", Eval.cp(320)));
        ReviewInput nxf7 = rated(new ReviewInput(fen, List.of("d6f7"), ps, OpeningBook.NONE, RATING, RATING), 2500,
                2500);
        assertEquals(MoveClassification.GREAT, label(nxf7, Tuning.DEFAULT));
        assertEquals(MoveClassification.BRILLIANT, label(nxf7, Tuning.DEFAULT.with("brilliantNoCheckingCounter", 0)));
    }

    @Test
    void aPawnGivenWithCheckToDragTheKingOutIsBrilliant() {
        // Kasparov - Topalov 1999, 33.c3+!! Kxc3 (chess.com Brilliant): from +0.61 to +3.14, the king is dragged out
        String fen = "3r3r/1R3p1p/Q5p1/1p6/1kq5/5PPB/2P4P/1K6 w - - 0 33";
        String fen1 = play(fen, "c2c3");
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen, Eval.cp(61), List.of(new EngineLine("h3d7", Eval.cp(61), List.of("h3d7"), 20)),
                20, 0, false));
        ps.add(new PositionEval(fen1, Eval.cp(314), List.of(new EngineLine("b4c3", Eval.cp(314),
                List.of("b4c3", "a6a1", "c3d2"), 20)), 20, 0, false));
        ReviewInput c3 = rated(new ReviewInput(fen, List.of("c2c3"), ps, OpeningBook.NONE, RATING, RATING), 2500, 2500);
        assertEquals(MoveClassification.BRILLIANT, label(c3, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.BRILLIANT, label(c3, Tuning.DEFAULT.with("brilliantPawnCheckSac", 0)));
    }

    @Test
    void aDeclinedOfferWonBackAtOnceAndGainingNothingIsNotBrilliant() {
        // Topalov - Shirov 1998, 26...Nb4 (chess.com Great): cxb4 would lose the piece back at once, the engine's line
        // declines (27.Qxa4 Nxa2 28.Qxa2) and wins nothing
        String fen = "r2rb1k1/5pbp/2p3p1/p1qnPP2/p2N4/2P4P/B1QB2P1/4RR1K b - - 2 26";
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen, Eval.cp(-35), List.of(
                new EngineLine("d5b4", Eval.cp(-35), List.of("d5b4", "c2a4", "b4a2", "a4a2", "g7e5", "d2c1", "d8d5",
                        "a2b3"), 20),
                new EngineLine("a4a3", Eval.cp(183), List.of("a4a3"), 20)), 20, 0, false));
        ps.add(after(fen, "d5b4", Eval.cp(-2)));
        ReviewInput nb4 = rated(new ReviewInput(fen, List.of("d5b4"), ps, OpeningBook.NONE, RATING, RATING), 2500,
                2500);
        assertEquals(MoveClassification.GREAT, label(nb4, Tuning.DEFAULT));
        assertEquals(MoveClassification.BRILLIANT, label(nb4, Tuning.DEFAULT.with("brilliantNoEmptyOffer", 0)));
    }

    @Test
    void aPieceTakenBackAtOnceByADiscoveredAttackIsNotASacrifice() {
        // live_184350007554 ply 40, 20...Nc3 (user, 22:20: never Brilliant): 21.Qxc3 Qxf3, the knight move uncovered
        // the queen on the undefended Nf3; the material is level after the line
        String fen = "3r1rk1/1pQ2pp1/p5bp/3q4/1P2n1P1/P3BN1P/5P2/R4RK1 b - - 0 20";
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen, Eval.cp(-160), List.of(
                new EngineLine("e4c3", Eval.cp(-160), List.of("e4c3", "c7c3", "d5f3", "e3d4", "g6d3", "d4g7", "d8c8",
                        "c3f6", "f3f6", "g7f6"), 20),
                new EngineLine("e4d6", Eval.cp(-134), List.of("e4d6"), 20)), 20, 0, false));
        ps.add(after(fen, "e4c3", Eval.cp(-171)));
        ReviewInput nc3 = rated(new ReviewInput(fen, List.of("e4c3"), ps, OpeningBook.NONE, RATING, RATING), 2399,
                2366);
        assertNotEquals(MoveClassification.BRILLIANT, label(nc3, Tuning.DEFAULT));
        assertEquals(MoveClassification.BRILLIANT, label(nc3, Tuning.DEFAULT.with("brilliantNoDiscoveredTrade", 0)));
    }

    @Test
    void anOfferThatWinsMoreMaterialAtOnceIsNotASacrifice() {
        // Deep Blue - Kasparov 1997 g6, 17.Bf5 (chess.com Excellent): 17...exf5 18.Rxe7 wins the queen for the bishop
        // and the rook, the line ends a pawn up: a tactic, not a sacrifice
        String fen = "r1k2b1r/p2nq1p1/2b1p1Bp/1p1n4/3P4/3Q1NB1/1PP2PPP/R3R1K1 w - - 2 17";
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen, Eval.cp(421), List.of(
                new EngineLine("g6f5", Eval.cp(421), List.of("g6f5", "e6f5", "e1e7", "d5e7", "d3c3", "c8d8", "g3d6",
                        "c6e4", "c3a5", "d8e8"), 20),
                new EngineLine("g6e4", Eval.cp(273), List.of("g6e4"), 20)), 20, 0, false));
        ps.add(after(fen, "g6f5", Eval.cp(421)));
        ReviewInput bf5 = rated(new ReviewInput(fen, List.of("g6f5"), ps, OpeningBook.NONE, RATING, RATING), 2500,
                2500);
        assertNotEquals(MoveClassification.BRILLIANT, label(bf5, Tuning.DEFAULT));
        assertEquals(MoveClassification.BRILLIANT, label(bf5, Tuning.DEFAULT.with("brilliantNoShamSacrifice", 0)));
    }

    @Test
    void givingUpThePieceToStartAMateIsBrilliantEvenWhenWinning() {
        // Capablanca - Marshall 1918, 36.Bxf7+! Rxf7 37.b8=Q+ (chess.com Brilliant): +8.75 anyway, but the bishop is
        // given to force mate in 6
        String fen = "5rk1/1P3pp1/R6p/3B4/6P1/2B1rQ2/2K3P1/6q1 w - - 1 36";
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen, Eval.whiteMates(6), List.of(
                new EngineLine("d5f7", Eval.whiteMates(6), List.of("d5f7", "f8f7", "b7b8q", "e3e8", "b8e8", "g8h7",
                        "f3e4", "f7f5", "e4f5", "g7g6"), 20),
                new EngineLine("c3d4", Eval.cp(875), List.of("c3d4"), 20)), 20, 0, false));
        ps.add(after(fen, "d5f7", Eval.whiteMates(5)));
        ReviewInput bxf7 = rated(new ReviewInput(fen, List.of("d5f7"), ps, OpeningBook.NONE, RATING, RATING), 2500,
                2500);
        assertEquals(MoveClassification.BRILLIANT, label(bxf7, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.BRILLIANT, label(bxf7, Tuning.DEFAULT.with("brilliantMateSacrifice", 0)));
    }

    @Test
    void aQuietMoveLeavingAHeavierPieceEnPriseIsBrilliant() {
        // live_145773198260 ply 31 (White 1103): 16.c5 leaves the rook d6 attacked by the knight f5: chess.com
        // Brilliant; the pawn moved is worth less than the rook left, and c5 attacks nothing as big
        ReviewInput c5 = rated(oneMove("r4rk1/pp3qpp/2pR4/3p1nB1/2PP4/8/PP2QPPP/RN4K1 w - - 1 16", "c4c5", Eval.cp(40),
                "c4c5", Eval.cp(-7), "e2e3", Eval.cp(40)), 1103, 1103);
        assertEquals(MoveClassification.BRILLIANT, label(c5, Tuning.DEFAULT));
        assertEquals(MoveClassification.BEST, label(c5, Tuning.DEFAULT.with("brilliantHeavyLeft", 0)));
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
        // daily_1017236144 ply 23, 12.fxg6: the capture keeps the material, chess.com Best. v2.1 (a capture only when
        // it punishes a blunder) excludes it; v2.2 admits exchanges to be made now, and v2.5 excludes it again as a
        // pawn exchange that punishes no error (greatPawnTradeOppLoss)
        String fen = "r1bqkb1r/pp2n2p/3p2p1/4pPPQ/4p2P/8/PPP2PB1/R1B1K1NR w KQkq - 0 12";
        ReviewInput in = oneMove(fen, "f5g6", Eval.cp(401), "f5g6", Eval.cp(-245), "h5d1", Eval.cp(401));
        assertNotEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatPawnTradeOppLoss", 0)));
        Tuning v21 = Tuning.DEFAULT.with("greatCaptureRule", 0).with("greatPawnTradeOppLoss", 0);
        assertNotEquals(MoveClassification.GREAT, label(in, v21));
        assertEquals(MoveClassification.GREAT, label(in, v21.with("greatCaptureOppLoss", 0)));
    }

    @Test
    void gE2AlreadyWinningIsNotGreat() {
        // daily_1014523396 ply 25, 13.e5 after 12...Rc8?: White already at +5.4 (win chance 0.95)
        ReviewInput in = twoMoves("r2qk2r/1b1ppp2/ppn2n1p/5Np1/4P3/2PQ2B1/P1P1BPPP/R3K2R b KQkq - 2 12",
                "a8c8", Eval.cp(191), "d7d5", "e4e5", Eval.cp(543), "e4e5", Eval.cp(312), "h2h4", Eval.cp(543));
        assertNotEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
        // v2.1: winning before and after the second best move, and not the only move
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatGap", 0.10)));
    }

    @Test
    void gE3NotTheOnlyGoodMoveIsNotGreat() {
        // live_123574758978 ply 40, 20...Rc3+ after 20.Bf3?: the second best move is only ~0.11 worse
        ReviewInput in = twoMoves("2r2rk1/1p3ppp/p7/3p4/4n1P1/PP2K3/4BP1P/R6R w - - 0 20",
                "e2f3", Eval.cp(-239), "f2f3", "c8c3", Eval.cp(-618), "c8c3", Eval.cp(-371), "f8e8", Eval.cp(-618));
        assertNotEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatGap", 0.10)));
    }


    @Test
    void gPlus1QuietForcingCheckInAWonAttackIsGreat() {
        // Botvinnik - Capablanca 1938, 32.Qg5+ at +8.3, chess.com Great: G+1 holds above the ordinary Great range
        ReviewInput botvinnik = oneMove("8/p5kp/1p2Pn2/3pQ2p/2pP4/qnP5/6PP/6K1 w - - 0 32", "e5g5", Eval.cp(827),
                "e5g5", Eval.cp(-682), "h2h3", Eval.cp(827));
        Tuning narrow = Tuning.DEFAULT.with("greatRule", 1).with("greatMaxEp", 0.90);
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
    }

    @Test
    void aPieceForPawnsIsASacrifice() {
        // Morphy - Duke/Count 1858 (Opera Game), 10.Nxb5: knight for two pawns, chess.com Brilliant
        ReviewInput nxb5 = oneMove("rn2kb1r/p3qppp/2p2n2/1p2p1B1/2B1P3/1QN5/PPP2PPP/R3K2R w KQkq b6 0 10", "c3b5",
                Eval.cp(484), "c3b5", Eval.cp(115), "g5f6", Eval.cp(484));
        assertEquals(MoveClassification.BRILLIANT, label(nxb5, Tuning.DEFAULT));
        // Reshevsky - Petrosian 1953, 30.Rxd3: the exchange for a bishop and a pawn, chess.com Brilliant
        ReviewInput rxd3 = oneMove("3rq1k1/6pp/4p3/pp1nP3/P1pP4/2Pb1R2/1B4PP/4RQK1 w - - 4 30", "f3d3", Eval.cp(0),
                "f3d3", Eval.cp(-165), "f1f2", Eval.cp(0));
        assertEquals(MoveClassification.BRILLIANT, label(rxd3, Tuning.DEFAULT));
    }

    @Test
    void greatChangesTheOutcome() {
        // live_173843114164 ply 34, 17...c4: equal with it, losing with the second best move (chess.com Great)
        ReviewInput c4 = rated(twoMoves("2rq1rk1/p2bb1pp/4pn2/1pp2p2/3P1N2/1P2P1P1/P3QPBP/R1B2RK1 w - - 1 17", "c1b2",
                Eval.cp(90), "d4c5", "c5c4", Eval.cp(-33), "c5c4", Eval.cp(117), "c5d4", Eval.cp(-33)), 2513, 2617);
        assertEquals(MoveClassification.GREAT, label(c4, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(c4, Tuning.DEFAULT.with("greatClassGap", 1).with("greatQuietCp", 0)));
        // daily_1011205894 ply 56, 28...Rd2 (chess.com Great)
        ReviewInput rd2 = rated(oneMove("2kbR3/1pp2N1p/p5p1/5p2/1P6/1KP2P2/P5rP/8 b - - 1 28", "g2d2", Eval.cp(0),
                "g2d2", Eval.cp(228), "c8d7", Eval.cp(0)), 1301, 1202);
        assertEquals(MoveClassification.GREAT, label(rd2, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(rd2, Tuning.DEFAULT.with("greatClassGap", 1).with("greatQuietCp", 0)));
    }

    @Test
    void aCaptureWinningMaterialIsGreatOnlyUnder1000() {
        // live_138835439112 ply 25, 13.dxc5 after 12...Nc6?? by a 459-rated player (chess.com Great)
        ReviewInput dxc5 = rated(twoMoves("rnbqk2r/pp5p/4p1p1/2b2p2/2BP4/5Q2/PPP2PPP/R1B2RK1 b kq - 1 12", "b8c6",
                Eval.cp(-315), "d8d4", "d4c5", Eval.cp(453), "d4c5", Eval.cp(-85), "d4d5", Eval.cp(453)), 459, 482);
        assertEquals(MoveClassification.GREAT, label(dxc5, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(dxc5, Tuning.DEFAULT.with("greatFreeMaterialRating", 0)
                .with("greatBeginner", 0))); // the punished blunder alone makes it Great too
    }

    @Test
    void anExchangeToMakeNowCanBeGreatTakingFreeMaterialIsNot() {
        // SPEC v2.2, live_174045499582 (players ~1860): 8...Bxc3+ must be played now (chess.com Great) ...
        ReviewInput bxc3 = rated(twoMoves("rn1qk2r/p4ppp/1pp1pn2/2Pp1b2/1b1P4/2N1PN1P/PP3PP1/R1BQKB1R w KQkq - 0 8",
                "a2a3", Eval.cp(-40), "c5b6", "b4c3", Eval.cp(-103), "b4c3", Eval.cp(418), "b4c5", Eval.cp(-103)),
                1875, 1857);
        assertEquals(MoveClassification.GREAT, label(bxc3, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(bxc3, Tuning.DEFAULT.with("greatCaptureRule", 0)));
        // ... while 14.Bxa6 takes the knight Black just left hanging: chess.com Best
        ReviewInput bxa6 = rated(twoMoves("rn2k2r/p2q1ppp/2p1pnb1/3p4/1Q1N4/P1P1P2P/4BPP1/R1B1K2R b KQkq - 4 13",
                "b8a6", Eval.cp(-237), "f6e4", "e2a6", Eval.cp(623), "e2a6", Eval.cp(-52), "b4a5", Eval.cp(623)),
                1875, 1857);
        assertNotEquals(MoveClassification.GREAT, label(bxa6, Tuning.DEFAULT));
        // (14.Bxa6 also takes the knight that has just attacked the queen in a won position: greatNoQueenAttackerTaken)
        assertEquals(MoveClassification.GREAT, label(bxa6, Tuning.DEFAULT.with("greatCaptureRule", 0)
                .with("greatNoQueenAttackerTaken", 0)));
    }

    // ------------------------------------------------------------------ SPEC v2.3

    @Test
    void theEnginesMoveGivingUpThePieceForGoodIsBrilliantEvenWhenWinning() {
        // daily_1014523396 ply 35, 18.Bxf7!! at +7.3 (chess.com Brilliant): the bishop is gone for a pawn
        ReviewInput bxf7 = rated(oneMove("2r2knr/1b1ppp2/pqn4p/1p2PN1B/7R/2PQ2B1/P1P2PP1/3RK3 w - - 2 18", "h5f7",
                Eval.cp(730), "h5f7", Eval.cp(737), "f5d6", Eval.cp(730)), 2439, 2230);
        assertEquals(MoveClassification.BRILLIANT, label(bxf7, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.BRILLIANT, label(bxf7, Tuning.DEFAULT.with("brilliantTopException", 0)));
        // live_173843114164 ply 57, 29.Rxd6 at +9.8: takes the bishop, the line wins material back (chess.com Best)
        ReviewInput rxd6 = rated(oneMove("2r5/pq2k1pp/2bb4/4pp2/2R5/2Q1P1P1/PB3P1P/3R2K1 w - - 3 29", "d1d6",
                Eval.cp(982), "d1d6", Eval.cp(666), "e3e4", Eval.cp(982)), 2513, 2617);
        assertNotEquals(MoveClassification.BRILLIANT, label(rxd6, Tuning.DEFAULT));
    }

    @Test
    void aSacrificeLosingUnder003IsBrilliantEvenIfNotTheEnginesMove() {
        // live_123574758978 ply 18, 9...Nxd4 (chess.com Brilliant), 0.029 worse than the engine's move
        ReviewInput nxd4 = rated(oneMove("r3k1nr/pp3ppp/2n1p3/q2p4/1b1P2b1/2N2N2/PPPB1PPP/R2QKB1R b KQkq - 5 9", "c6d4",
                Eval.cp(-119), "g4f3", Eval.cp(-100), "g8e7", Eval.cp(-73)), 934, 929);
        assertEquals(MoveClassification.BRILLIANT, label(nxd4, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.BRILLIANT, label(nxd4, Tuning.DEFAULT.with("brilliantNonTopLoss", 0.01)));
    }

    @Test
    void anAnswerToCheckThatDoesNotMoveTheKingCanBeGreat() {
        // live_166861688890 ply 25, 13.Qxa5 after 12...Qa5+ (chess.com Great)
        ReviewInput qxa5 = rated(twoMoves("r1bk3r/pppp1ppp/2n5/3QP3/2B5/q3PN2/P1P2PPP/1R2K2R b K - 0 12", "a3a5",
                Eval.cp(112), "a3c3", "d5a5", Eval.cp(283), "d5a5", Eval.cp(85), "e1e2", Eval.cp(283)), 1509, 1535);
        assertEquals(MoveClassification.GREAT, label(qxa5, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(qxa5, Tuning.DEFAULT.with("greatInCheck", 0)));
        // live_127051221683 ply 52, 26...Kh8 after 26.Qxf5+: a king escape (chess.com Best), even with a mate behind
        ReviewInput kh8 = rated(twoMoves("2r3r1/2p3pk/p1b4p/1p2Pp2/7q/2P3RP/PPQ2P1K/6R1 w - - 0 26", "c2f5",
                Eval.cp(33), "c2f5", "h7h8", Eval.cp(28), "h7h8", Eval.whiteMates(7), "g7g6", Eval.cp(28)), 1331, 1411);
        assertNotEquals(MoveClassification.GREAT, label(kh8, Tuning.DEFAULT));
    }

    @Test
    void pickingTheOneGoodFlightSquareIsGreat() {
        // Anderssen - Kieseritzky 1851, 20.Ke2 after 19...Qxa1+ (chess.com Great): the second best move is another
        // king move, 20.Kg2 -1.50 (the Kh8 above is Best: its alternative is an interposition)
        ReviewInput ke2 = rated(oneMove("rnb1k1nr/p2p1ppp/3B4/1p1NPN1P/6P1/3P1Q2/P1P5/q4Kb1 w kq - 0 20", "f1e2",
                Eval.cp(204), "f1e2", Eval.cp(-150), "f1g2", Eval.cp(203)), 2500, 2500);
        assertEquals(MoveClassification.GREAT, label(ke2, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(ke2, Tuning.DEFAULT.with("greatKingFlightGap", 0)));
    }

    @Test
    void pushingTheSamePassedPawnAgainIsNotGreat() {
        // live_123602894080 plies 121-123: 61.e4 (chess.com Great) Kxf2 62.e5 (chess.com Best) in a pawn race where
        // every other move loses
        String fen = "8/8/8/7p/7P/1K2Pp2/4kP2/8 w - - 1 61";
        String fen1 = play(fen, "e3e4");
        String fen2 = play(fen1, "e2f2");
        List<PositionEval> ps = new ArrayList<>();
        ps.add(withLines(fen, Eval.cp(-21), "e3e4", Eval.cp(-824), "b3c4"));
        ps.add(withLines(fen1, Eval.cp(-21), "e2f2", Eval.cp(0), "e2f1"));
        ps.add(withLines(fen2, Eval.cp(-23), "e4e5", Eval.cp(-834), "b3c3"));
        ps.add(after(fen2, "e4e5", Eval.cp(-24)));
        ReviewInput in = new ReviewInput(fen, List.of("e3e4", "e2f2", "e4e5"), ps, OpeningBook.NONE, 1317, 1379);
        GameReview r = ReviewClassifier.classifyGame(in, Tuning.DEFAULT);
        assertEquals(MoveClassification.GREAT, r.moves().get(0).label());
        assertEquals(MoveClassification.BEST, r.moves().get(2).label());
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatPawnFollowUp", 0)));
    }

    @Test
    void aQuietMoveKeepingTheWinIsGreatEvenInAWonPosition() {
        // Carlsen - Ernst 2004, 27.Qe5+ at +13.8: the second best move 27.Rxg6+ only draws (chess.com Great). The
        // position is above the MultiPV range: the reviewer now asks for the second line of a quiet piece move there
        String fen = "5r2/pp2QPk1/6r1/q1p5/3P4/6R1/PPP2PP1/1K6 w - - 5 27";
        ReviewInput qe5 = rated(oneMove(fen, "e7e5", Eval.cp(1383), "e7e5", Eval.cp(0), "g3g6", Eval.cp(1393)),
                2500, 2500);
        assertEquals(MoveClassification.GREAT, label(qe5, Tuning.DEFAULT));
        List<PositionEval> mainOnly = List.of(new PositionEval(fen, Eval.cp(1383),
                List.of(new EngineLine("e7e5", Eval.cp(1383), List.of("e7e5"), 20)), 20, 0, false),
                qe5.positions().get(1));
        assertTrue(ReviewClassifier.needsSecondLine(new ReviewInput(fen, List.of("e7e5"), mainOnly, OpeningBook.NONE,
                2500, 2500)).get(0));
    }

    @Test
    void aPawnExchangeIsGreatOnlyWhenItPunishesAnError() {
        // live_184350007554 ply 52, 26...hxg4 after the sound 26.Kg2 (chess.com Best): the only move, but a pawn
        // exchange that does not punish anything
        ReviewInput hxg4 = rated(oneMove("5rk1/1p3pp1/p5b1/4B2p/1P4P1/P4P1r/6K1/R4R2 b - - 1 26", "h5g4",
                Eval.cp(-357), "h5g4", Eval.cp(225), "f8e8", Eval.cp(-385)), 2399, 2366);
        assertNotEquals(MoveClassification.GREAT, label(hxg4, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(hxg4, Tuning.DEFAULT.with("greatPawnTradeOppLoss", 0)));
    }

    @Test
    void aQuietMoveKeepingTheBalanceIsGreatWhenTheAlternativeIsAPawnWorse() {
        // live_145773198260 ply 33, 17.Qd2 (chess.com Great): the second best move is -1.29, a quiet only-move
        ReviewInput qd2 = rated(oneMove("r4rk1/pp3qpp/2pR4/2Pp2B1/3n4/8/PP2QPPP/RN4K1 w - - 0 17", "e2d2",
                Eval.cp(88), "e2d2", Eval.cp(-129), "e2f1", Eval.cp(88)), 1103, 1109);
        assertEquals(MoveClassification.GREAT, label(qd2, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(qd2, Tuning.DEFAULT.with("greatQuietCp", 0)));
    }

    @Test
    void lessThanAPawnBetterDoesNotChangeTheOutcome() {
        // Capablanca - Marshall 1918, 15.d4 at +1.10, second best +0.17 (chess.com Best): judged as masters, the steep
        // curve makes it a class change, but 93 centipawns do not change the outcome
        ReviewInput d4 = rated(oneMove("r1b2rk1/2p2ppp/p2b4/1p6/6nq/1BP2Q1P/PP1P1PP1/RNB1R1K1 w - - 3 15", "d2d4",
                Eval.cp(110), "d2d4", Eval.cp(17), "e1e4", Eval.cp(110)), 2500, 2500);
        assertNotEquals(MoveClassification.GREAT, label(d4, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(d4, Tuning.DEFAULT.with("greatClassCp", 0)
                .with("greatQuietCpFloor", 0)));
    }

    @Test
    void aQuietMoveStartingAMateIsGreatWhenTheAlternativeDoesNotWin() {
        // live_171977517802 ply 86, 43...d2 starts a mate in 9, the second best move only draws (chess.com Great)
        ReviewInput d2 = rated(oneMove("8/pb3k2/1p6/5Pp1/3B2P1/1P1pp1K1/P7/8 b - - 1 43", "d3d2", Eval.blackMates(9),
                "d3d2", Eval.cp(0), "e3e2", Eval.blackMates(9)), 2555, 2565);
        assertEquals(MoveClassification.GREAT, label(d2, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(d2, Tuning.DEFAULT.with("greatStartsMate", 0)));
        // Anderssen - Dufresne 1852, 22.Bf5+ (the Evergreen): a check without capture into mate in 3, the second best
        // move loses (chess.com Great)
        ReviewInput bf5 = rated(oneMove("1r4r1/pbpknp1p/1b3P2/8/8/B1PB1q2/P4PPP/3R2K1 w - - 0 22", "d3f5",
                Eval.whiteMates(3), "d3f5", Eval.cp(-965), "d3e2", Eval.whiteMates(3)), 2500, 2500);
        assertEquals(MoveClassification.GREAT, label(bf5, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(bf5, Tuning.DEFAULT.with("greatStartsMate", 0)));
        // daily_1027855652 ply 63, 32.Rh6+ mates in 2, the second best move keeps +1.78 (chess.com Great)
        ReviewInput rh6 = rated(oneMove("2r1r2k/p5n1/1pp2NR1/3pPq1p/3P1P1P/8/PPP1Q3/2K5 w - - 1 32", "g6h6",
                Eval.whiteMates(2), "g6h6", Eval.cp(178), "e2d3", Eval.whiteMates(1)), 1709, 1652);
        assertEquals(MoveClassification.GREAT, label(rh6, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(rh6, Tuning.DEFAULT.with("greatStartsMateAltCp", 150)));
    }

    @Test
    void aMatePunishingABlunderIsGreatUnder1000() {
        // live_184334494256 ply 9 (648 vs 777): 4...d6?? allows 5.Qxf7#, chess.com Great
        String fen0 = "r1bqkbnr/pppp1p1p/2n3p1/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR b KQkq - 1 4";
        String fen1 = play(fen0, "d7d6");
        List<PositionEval> ps = List.of(
                new PositionEval(fen0, Eval.cp(-25), List.of(new EngineLine("g8f6", Eval.cp(-25), List.of("g8f6"), 20)),
                        20, 0, false),
                withLines(fen1, Eval.whiteMates(1), "f3f7", Eval.cp(180), "c4f7"),
                PositionEval.terminal(play(fen1, "f3f7"), Eval.whiteMates(0)));
        ReviewInput qxf7 = new ReviewInput(fen0, List.of("d7d6", "f3f7"), ps, OpeningBook.NONE, 648, 777);
        assertEquals(MoveClassification.GREAT, label(qxf7, Tuning.DEFAULT));
        assertEquals(MoveClassification.BEST, label(qxf7, Tuning.DEFAULT.with("greatBeginner", 0)));
        // the same mate between stronger players stays Best (live_184180120868 ply 7, 2660: 4.Qxf7# after a blunder)
        assertEquals(MoveClassification.BEST, label(rated(qxf7, 1500, 1500), Tuning.DEFAULT));
    }

    @Test
    void punishingAnOutOfBookErrorRightAfterTheBookCanBeGreat() {
        // daily_1017137676: 1.e4 c5 2.Nf3 Nc6 3.d4 e6? (chess.com Mistake) 4.d5: the only move keeping the advantage
        // (+2.21, second best 5.Nc3 +0.37) is chess.com Great although 3...e6 was played from a book position
        List<String> moves = List.of("e2e4", "c7c5", "g1f3", "b8c6", "d2d4", "e7e6", "d4d5");
        String[] best = {"e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "d4d5", "c6a5"};
        int[] cp = {29, 23, 36, 24, 30, 31, 221, 247};
        List<PositionEval> ps = new ArrayList<>();
        String fen = new Board().getFen();
        for (int i = 0; i <= moves.size(); i++) {
            Eval e = Eval.cp(cp[i]);
            ps.add(i == 6 ? withLines(fen, e, best[i], Eval.cp(37), "b1c3")
                    : new PositionEval(fen, e, List.of(new EngineLine(best[i], e, List.of(best[i]), 20)), 20, 0, false));
            if (i < moves.size()) {
                fen = play(fen, moves.get(i));
            }
        }
        ReviewInput in = new ReviewInput(new Board().getFen(), moves, ps, OpeningBook.standard(), 1298, 1151);
        assertEquals(MoveClassification.MISTAKE, ReviewClassifier.classifyGame(in).moves().get(5).label());
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("theoryNeedsBookMove", 0)));
    }

    @Test
    void aBeginnerPunishingABlunderIsGreat() {
        // live_184291917020, 4.Qg4?? Bxg4 (Black 604, chess.com Great): the queen taken, no second line needed
        ReviewInput bxg4 = game("rnb1kbnr/ppp1pppp/8/8/3qN3/8/PPPP1PPP/R1BQKBNR w KQkq - 1 4", 600, 604,
                List.of("d1g4", "c8g4"), List.of(Eval.cp(69), Eval.cp(-956), Eval.cp(-889)), List.of("e4c3", "c8g4"));
        assertEquals(MoveClassification.GREAT, label(bxg4, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(bxg4, Tuning.DEFAULT.with("greatBeginner", 0)));
        // the same move by a 1500 player is Best
        assertEquals(MoveClassification.BEST, label(rated(bxg4, 1500, 1500), Tuning.DEFAULT));
    }

    @Test
    void aBeginnerTakingWhatAMistakeLeftEnPriseIsGreat() {
        // live_141789599574 ply 61, 30...Ke6 (a Miss: Black had mate in 4) 31.Qxb1 (White 682, chess.com Great): the
        // queen taken, although 31.d5+ wins it too (SF16 d22 +594 / +560)
        ReviewInput qxb1 = rated(twoMoves("b7/p4k1Q/1p3p2/8/2nP4/2P1P1PK/P4P1P/1q6 b - - 1 30", "f7e6",
                Eval.blackMates(4), "b1h7", "h7b1", Eval.cp(737), "h7b1", Eval.cp(684), "d4d5", Eval.cp(733)), 682, 616);
        assertEquals(MoveClassification.GREAT, label(qxb1, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(qxb1, Tuning.DEFAULT.with("greatBeginner", 0)));
        assertEquals(MoveClassification.BEST, label(rated(qxb1, 1100, 1100), Tuning.DEFAULT));
    }

    @Test
    void theBeginnerRuleNeedsAKnownRating() {
        // lead directive (CLAIMS 21:05): without a rating the review is strict, and a 2500 player gets no beginner Great
        String fen0 = "r1bqkbnr/pppp1p1p/2n3p1/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR b KQkq - 1 4";
        String fen1 = play(fen0, "d7d6");
        ReviewInput qxf7 = new ReviewInput(fen0, List.of("d7d6", "f3f7"), List.of(
                new PositionEval(fen0, Eval.cp(-25), List.of(new EngineLine("g8f6", Eval.cp(-25), List.of("g8f6"), 20)),
                        20, 0, false),
                withLines(fen1, Eval.whiteMates(1), "f3f7", Eval.cp(180), "c4f7"),
                PositionEval.terminal(play(fen1, "f3f7"), Eval.whiteMates(0))), OpeningBook.NONE, 648, 777);
        ReviewInput bxg4 = game("rnb1kbnr/ppp1pppp/8/8/3qN3/8/PPPP1PPP/R1BQKBNR w KQkq - 1 4", 600, 604,
                List.of("d1g4", "c8g4"), List.of(Eval.cp(69), Eval.cp(-956), Eval.cp(-889)), List.of("e4c3", "c8g4"));
        ReviewInput qxb1 = rated(twoMoves("b7/p4k1Q/1p3p2/8/2nP4/2P1P1PK/P4P1P/1q6 b - - 1 30", "f7e6",
                Eval.blackMates(4), "b1h7", "h7b1", Eval.cp(737), "h7b1", Eval.cp(684), "d4d5", Eval.cp(733)), 682, 616);
        ReviewInput rxf3 = rated(twoMoves("5rk1/1p3ppp/p7/3p4/4n1P1/PPr1KB2/5P1P/R6R w - - 2 21", "e3d4", Eval.cp(-602),
                "e3e2", "c3f3", Eval.cp(-794), "c3f3", Eval.cp(-328), "c3b3", Eval.cp(-794)), 934, 929);
        for (ReviewInput in : List.of(qxf7, bxg4, qxb1, rxf3)) {
            assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT));
            assertNotEquals(MoveClassification.GREAT, label(rated(in, 0, 0), Tuning.DEFAULT));
            assertNotEquals(MoveClassification.GREAT, label(rated(in, 2500, 2500), Tuning.DEFAULT));
        }
    }

    @Test
    void recoveringFromOwnBlunderIsNotGreat() {
        // live_180008683178, 7.Qc3?? Be7? (Bb4 wins the queen) 8.Qxg7 (White 260, chess.com Best): Black's error only
        // missed the punishment of White's blunder, chess.com calls it a Miss
        ReviewInput qxg7 = game("r1bqkb1r/pppp1ppp/2n5/8/3QP3/3B4/PPP2PPP/RNB1K2R w KQkq - 3 7", 260, 2464,
                List.of("d4c3", "f8e7", "c3g7"), List.of(Eval.cp(49), Eval.cp(-774), Eval.cp(195), Eval.cp(195)),
                List.of("d4e3", "f8b4", "c3g7"));
        assertEquals(MoveClassification.BEST, label(qxg7, Tuning.DEFAULT));
    }

    @Test
    void underThousandACaptureTheSecondBestCannotReplaceIsGreat() {
        // live_123574758978 ply 42: 21.Kd4? Rxf3 (-7.94, second best Rb3 -3.28: gap 0.19 at 929, still winning without
        // it, so no outcome class change): chess.com Great
        ReviewInput rxf3 = rated(twoMoves("5rk1/1p3ppp/p7/3p4/4n1P1/PPr1KB2/5P1P/R6R w - - 2 21", "e3d4", Eval.cp(-602),
                "e3e2", "c3f3", Eval.cp(-794), "c3f3", Eval.cp(-328), "c3b3", Eval.cp(-794)), 934, 929);
        assertEquals(MoveClassification.GREAT, label(rxf3, Tuning.DEFAULT));
        assertEquals(MoveClassification.BEST, label(rxf3, Tuning.DEFAULT.with("greatBeginner", 0)));
        // live_174367977638 ply 36: 17...Nxe3+ 18.Kf2 (no error) Nxd1+ only collects the queen the fork won: Best
        ReviewInput nxd1 = withSecond(game("r2qk1r1/1pp2p2/p1np3p/4p3/2B1P1n1/2PPBNP1/PP4K1/R2Q3R b q - 0 17", 763, 756,
                List.of("g4e3", "g2f2", "e3d1"), List.of(Eval.cp(-773), Eval.cp(-802), Eval.cp(-818), Eval.cp(-810)),
                List.of("g4e3", "g2h2", "e3d1")), 2, Eval.cp(-264), "e3c4");
        assertEquals(MoveClassification.BEST, label(nxd1, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(nxd1, Tuning.DEFAULT.with("greatNoCollect", 0)));
    }

    @Test
    void collectingWhatTheForkWonIsNotGreat() {
        // live_174290567620 ply 24: 11...Nxc2+ (Great) 12.Ke2 Nxa1 (Black 589) takes the rook the fork won: Best
        ReviewInput nxa1 = withSecond(game("r1bqk2r/ppp2ppp/3p4/2P1p3/3nP1P1/P1N2NQP/2PP4/R1B1KB2 b Qkq - 0 11", 613, 589,
                List.of("d4c2", "e1e2", "c2a1"), List.of(Eval.cp(-457), Eval.cp(-439), Eval.cp(-666), Eval.cp(-655)),
                List.of("d4c2", "e1d1", "c2a1")), 2, Eval.cp(-13), "c7c6");
        assertEquals(MoveClassification.BEST, label(nxa1, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(nxa1, Tuning.DEFAULT.with("greatNoCollect", 0)));
    }

    /** {@code in} with a second line in position {@code i}. */
    private static ReviewInput withSecond(ReviewInput in, int i, Eval second, String secondMove) {
        List<PositionEval> ps = new ArrayList<>(in.positions());
        PositionEval p = ps.get(i);
        ps.set(i, withLines(p.fen(), p.eval(), p.bestMove(), second, secondMove));
        return new ReviewInput(in.initialFen(), in.uciMoves(), ps, in.book(), in.whiteRating(), in.blackRating());
    }

    @Test
    void takingAFreeCheckingPieceIsNotGreat() {
        // live_170725680910 ply 76, 38.Re8+?! Rxe8 (Black 1520, chess.com Best): the checking rook is simply en prise
        ReviewInput rxe8 = rated(oneMove("3rR1k1/p2r2p1/1p6/6p1/P7/6P1/8/3R2K1 b - - 1 38", "d8e8", Eval.cp(-378),
                "d8e8", Eval.cp(646), "g8h7", Eval.cp(-397)), 1660, 1520);
        assertEquals(MoveClassification.BEST, label(rxe8, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(rxe8, Tuning.DEFAULT.with("greatInCheckFreeMaterial", 0)));
    }

    @Test
    void aMateStartPunishingAnErrorIsGreatAlthoughTheAlternativeWins() {
        // live_117364247477 ply 41 (1229): 20...Qxa2?? (instead of 20...Bxb1) allows 21.Rb8+ Ne8 22.Rxe8#; the second
        // best move 21.e4 also wins (+5.38) but chess.com calls the mate Great
        ReviewInput rb8 = rated(twoMoves("6k1/p4ppp/2pBpn2/2Pp1b2/3P4/4PP2/Pq2BKPP/1R5R b - - 5 20", "b2a2",
                Eval.cp(-728), "f5b1", "b1b8", Eval.whiteMates(2), "b1b8", Eval.cp(538), "e3e4", Eval.whiteMates(1)),
                1229, 1187);
        assertEquals(MoveClassification.GREAT, label(rb8, Tuning.DEFAULT));
        assertEquals(MoveClassification.BEST, label(rb8, Tuning.DEFAULT.with("greatStartsMatePunish", 0)));
    }

    @Test
    void aCaptureCashingInTheMoversOwnGreatIsNotGreat() {
        // live_174521739268 plies 5-8 (1303 vs 1293): 3.e5?! dxe5! (Great) 4.dxe5 Qxd1+ is Best for chess.com: the
        // queen trade only cashes in the Great 3...dxe5
        String fen = "rnbqkb1r/ppp1pppp/3p1n2/8/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 1 3";
        List<String> moves = List.of("e4e5", "d6e5", "d4e5", "d8d1");
        List<PositionEval> ps = new ArrayList<>();
        String f = fen;
        Eval[] best = {Eval.cp(47), Eval.cp(-169), Eval.cp(-154), Eval.cp(-149)};
        String[] bestMove = {"b1c3", "d6e5", "d4e5", "d8d1"};
        Eval[] second = {Eval.cp(41), Eval.cp(65), Eval.cp(-208), Eval.cp(48)};
        String[] secondMove = {"f1d3", "f6d5", "g1f3", "f6d5"};
        for (int i = 0; i < moves.size(); i++) {
            ps.add(withLines(f, best[i], bestMove[i], second[i], secondMove[i]));
            f = play(f, moves.get(i));
        }
        ps.add(new PositionEval(f, Eval.cp(-164), List.of(new EngineLine("e1d1", Eval.cp(-164), List.of("e1d1"), 20)),
                20, 0, false));
        ReviewInput in = new ReviewInput(fen, moves, ps, OpeningBook.NONE, 1303, 1293);
        GameReview r = ReviewClassifier.classifyGame(in, Tuning.DEFAULT);
        assertEquals(MoveClassification.GREAT, r.moves().get(1).label());
        assertEquals(MoveClassification.BEST, r.moves().get(3).label());
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatNoCashIn", 0)));
    }

    @Test
    void aKingEscapeKeepingTheMateIsGreatWhenTheOtherEscapeDraws() {
        // Botvinnik - Capablanca 1938, 38.Kxh5 (after 37...Qe4+): mate in 13, 38.Kg5 only draws, chess.com Great
        ReviewInput kxh5 = rated(oneMove("6k1/p3P2p/1p3Q2/3p3p/2pPq2K/1nP5/6PP/8 w - - 7 38", "h4h5",
                Eval.whiteMates(13), "h4h5", Eval.cp(0), "h4g5", Eval.whiteMates(12)), 0, 0);
        assertEquals(MoveClassification.GREAT, label(kxh5, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(kxh5, Tuning.DEFAULT.with("greatStartsMateKing", 0)));
    }

    @Test
    void aBishopDrivenBackByAPawnPushIsNotGreat() {
        // live_173864617688 ply 28 (2007): 14.g4 attacks Bh5, 14...Bg6 is the only move (f5 -0.70) but chess.com Best
        ReviewInput bg6 = rated(twoMoves("r4rk1/1pq1npp1/p1nbp2p/3p3b/3P4/2PBBN1P/PPQN1PP1/R3R1K1 w - - 0 14", "g2g4",
                Eval.cp(-77), "d3e2", "h5g6", Eval.cp(-129), "h5g6", Eval.cp(70), "f7f5", Eval.cp(-140)), 1909, 2007);
        assertEquals(MoveClassification.BEST, label(bg6, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(bg6, Tuning.DEFAULT.with("greatKickedBishop", 0)
                .with("greatNoOnlyEscape", 0))); // Bg6 is also its only safe square (G-E5)
    }

    @Test
    void bE9GivingUpTheLastPieceForAPawnEndingIsNotBrilliant() {
        // live_174388155128 ply 119, 60.Nxf4 Kxf4 and the king and pawn ending is won (chess.com Best; SF16 d22 has
        // Nb4, Ne1, Nc1, Nc5 and Nxf4 within 9 cp)
        ReviewInput nxf4 = rated(oneMove("8/8/8/8/4kp2/p2N4/K4P1P/8 w - - 11 60", "d3f4", Eval.cp(658), "d3e1",
                Eval.cp(640), "d3c1", Eval.cp(557)), 2009, 1982);
        assertNotEquals(MoveClassification.BRILLIANT, label(nxf4, Tuning.DEFAULT));
        assertEquals(MoveClassification.BRILLIANT, label(nxf4, Tuning.DEFAULT.with("brilliantNoLiquidation", 0)));
    }

    @Test
    void bE10ARecaptureIsASacrificeOnlyWhenThePieceItselfIsLost() {
        // live_173981415730 ply 38, 19.exd4 Nxd4 (+7.8 for Black, chess.com Best): cxd4 Rxd4 gives a knight for two
        // pawns, counted as a sacrifice only by the v2.1 piece rule
        ReviewInput nxd4 = rated(twoMoves("3r1rk1/2p2ppp/bpn5/2q5/3pP1n1/pPP1P3/P1BQ2PP/1NK3RR w - - 0 19", "e3d4",
                Eval.cp(-786), "e3d4", "c6d4", Eval.cp(-784), "c6d4", Eval.cp(-428), "c5e7", Eval.cp(-784)),
                1927, 1957);
        assertNotEquals(MoveClassification.BRILLIANT, label(nxd4, Tuning.DEFAULT));
        assertEquals(MoveClassification.BRILLIANT, label(nxd4, Tuning.DEFAULT.with("brilliantRecaptureNet", 0)));
    }

    @Test
    void gE5TheOnlySafeSquareOfAnAttackedPieceIsNotGreat() {
        // live_174024200644 ply 28, 14...Ba7: the bishop attacked by c5 has no other safe square (chess.com Best), every
        // other move loses it (Bxc5 Bxc5 +6.45)
        ReviewInput ba7 = rated(oneMove("rnb2r2/1pp1nkpp/1b2pp2/p1P5/P3P3/BP3NP1/5PBP/RN1R2K1 b - - 0 14", "b6a7",
                Eval.cp(35), "b6a7", Eval.cp(645), "b6c5", Eval.cp(39)), 2641, 2714);
        assertNotEquals(MoveClassification.GREAT, label(ba7, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(ba7, Tuning.DEFAULT.with("greatNoOnlyEscape", 0)));
    }

    @Test
    void anExchangePunishingTheOpponentsErrorIsGreat() {
        // Torre - Lasker 1925, 19.Bxg5? Nxd3 (chess.com Great): the knight takes the bishop now, 19...f6 would be -0.25
        ReviewInput nxd3 = rated(twoMoves("r3rnk1/pbq2ppp/3pp3/6bQ/1n1P4/N2B4/PP3PPP/2BRR1K1 w - - 0 19", "c1g5",
                Eval.cp(-31), "h5g5", "b4d3", Eval.cp(-173), "b4d3", Eval.cp(-25), "f7f6", Eval.cp(-205)), 2500, 2500);
        assertEquals(MoveClassification.GREAT, label(nxd3, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(nxd3, Tuning.DEFAULT.with("greatCapturePunishLoss", 0)));
        // live_184435729088 ply 11, 5...Nb4? 6.Bxe6 (chess.com Best): the bishop on c4 was en prise to Be6 itself
        ReviewInput bxe6 = rated(twoMoves("rn1qkb1r/ppp1pppp/4b3/3n4/2B5/5Q2/PPPPNPPP/RNB1K2R b KQkq - 3 5", "d5b4",
                Eval.cp(8), "b8c6", "c4e6", Eval.cp(292), "c4e6", Eval.cp(-6), "c4b3", Eval.cp(300)), 1901, 1960);
        assertNotEquals(MoveClassification.GREAT, label(bxe6, Tuning.DEFAULT));
    }

    @Test
    void movingAnAttackedPawnIsGreatOnlyAsAClearOnlyMove() {
        // Ivanchuk - Yusupov 1991 g9, 12...e3 (chess.com Best): the attacked e4 pawn goes forward, 12...Nf8 is -1.5
        ReviewInput e3 = rated(oneMove("r1b1r1k1/pp1nqpbp/2pp1np1/6N1/2PPp3/BPN3P1/P1Q1PPBP/R2R2K1 b - - 1 12",
                "e4e3", Eval.cp(33), "e4e3", Eval.cp(183), "d7f8", Eval.cp(33)), 2500, 2500);
        assertNotEquals(MoveClassification.GREAT, label(e3, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(e3, Tuning.DEFAULT.with("greatPawnEscapeGap", 0)));
    }

    @Test
    void takingTheCheckerToStartAMateIsGreat() {
        // live_174024200644 ply 120, 60.b8=Q+ Qxb8+: takes the new queen with check, mate in 11, king moves only draw
        // (SF16 d22 B#13 vs 0; chess.com Great)
        ReviewInput qxb8 = rated(oneMove("1Q6/K7/8/1k6/7p/6q1/8/8 b - - 0 60", "g3b8", Eval.blackMates(11), "g3b8",
                Eval.cp(-51), "b5c4", Eval.blackMates(11)), 2641, 2714);
        assertEquals(MoveClassification.GREAT, label(qxb8, Tuning.DEFAULT));
        assertNotEquals(MoveClassification.GREAT, label(qxb8, Tuning.DEFAULT.with("greatStartsMateInCheck", 0)));
    }

    @Test
    void leavingThePreviousSacrificeEnPriseAgainRenewsIt() {
        // Bai Jinshi - Ding Liren 2017, 20...Rd4 21.h3 h5 (chess.com Brilliant twice): the rook stays en prise to exd4
        ReviewInput g = game("r1br2k1/pp3p2/2n4p/4N1p1/1bP5/2n1PKB1/P1Q2PPP/5B1R b - - 4 20", 2500, 2500,
                List.of("d8d4", "h2h3", "h6h5"), List.of(Eval.cp(-477), Eval.cp(-379), Eval.cp(-409), Eval.cp(-409)),
                List.of("h6h5", "h2h3", "h6h5"));
        List<PositionEval> ps = new ArrayList<>(g.positions());
        ps.set(2, withLines(ps.get(2).fen(), Eval.cp(-409), "h6h5", Eval.cp(-161), "b7b6"));
        ReviewInput h5 = new ReviewInput(g.initialFen(), g.uciMoves(), ps, g.book(), 2500, 2500);
        assertEquals(MoveClassification.BRILLIANT, label(h5, Tuning.DEFAULT));
        // B-TI (brilliantHeavyLeft) covers it: a pawn move leaving the rook already attacked en prise
        assertNotEquals(MoveClassification.BRILLIANT, label(h5, Tuning.DEFAULT.with("brilliantHeavyLeft", 0)));
    }

    @Test
    void anAttackedPieceTradingItselfOffToHoldTheBalanceIsNotGreat() {
        // live_184567962764 ply 85, 43.Rxd7 Kxd7 = 0.00 (chess.com Best): the rook was attacked by the d7 rook, every
        // other move loses it (Kc4 -6.66)
        ReviewInput rxd7 = rated(oneMove("8/3r4/1p2k1p1/1K2p3/1P3pPp/3RbP1P/3N4/8 w - - 2 43", "d3d7", Eval.cp(0),
                "d3d7", Eval.cp(-666), "b5c4", Eval.cp(0)), 2526, 626);
        assertNotEquals(MoveClassification.GREAT, label(rxd7, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(rxd7, Tuning.DEFAULT.with("greatForcedTradeCp", 0)));
    }

    @Test
    void cashingInRightAfterTheOwnBrilliantIsNotGreat() {
        // D. Byrne - Fischer 1956: 11...Na4!! (Brilliant) 12.Qa3 Nxc3: taking the knight the Brilliant aimed at is the
        // obvious follow-up (chess.com Best), however bad the second best move (Bxf3 +0.18)
        ReviewInput in = withSecond(withSecond(game("r2q1rk1/pp2ppbp/1np2np1/2Q3B1/3PP1b1/2N2N2/PP3PPP/3RKB1R b K - 6 11",
                0, 0, List.of("b6a4", "c5a3", "a4c3"),
                List.of(Eval.cp(-286), Eval.cp(-307), Eval.cp(-288), Eval.cp(-263)), List.of("b6a4", "c5a3", "a4c3")),
                0, Eval.cp(-25), "b6d7"), 2, Eval.cp(18), "g4f3");
        assertEquals(MoveClassification.BRILLIANT, ReviewClassifier.classifyGame(in).moves().get(0).label());
        assertEquals(MoveClassification.BEST, label(in, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(in, Tuning.DEFAULT.with("greatNoCashInBrilliant", 0)));
    }

    @Test
    void aPawnPushEscortedByItsKingAgainstTheBareKingIsNotGreat() {
        // live_170725680910 ply 167: Kh6 g6 against Kg8, 84.g7 wins (mate in 10) and Kg5 draws, but the king covers g7:
        // plain technique, chess.com Best (its Greats are 83.Kh6 and 85.Kh7)
        ReviewInput g7 = rated(oneMove("6k1/8/6PK/8/8/8/8/8 w - - 7 84", "g6g7", Eval.whiteMates(10), "g6g7",
                Eval.cp(0), "h6g5", Eval.whiteMates(9)), 1660, 1652);
        assertEquals(MoveClassification.BEST, label(g7, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(g7, Tuning.DEFAULT.with("greatNoEscortedPush", 0)));
        // live_174388155128 ply 125: 63.f4 with the king far away (the pawns must defend themselves): still Great
        ReviewInput f4 = rated(oneMove("8/8/8/8/7k/K6P/5P2/8 w - - 1 63", "f2f4", Eval.cp(668), "f2f4", Eval.cp(0),
                "a3b3", Eval.cp(668)), 2009, 1982);
        assertEquals(MoveClassification.GREAT, label(f4, Tuning.DEFAULT));
    }

    @Test
    void takingThePieceThatJustAttackedTheQueenInAWonPositionIsNotGreat() {
        // Zukertort - Blackburne 1883: 23...Ne4 hits Qd2, 24.Bxe4 (+4.09, second Qe1 -0.91) removes it: chess.com Best
        ReviewInput bxe4 = twoMoves("2r3k1/pbr1q2p/1p2pnp1/3p1P2/3P4/1P1BR3/PB1Q2PP/5RK1 b - - 0 23", "f6e4",
                Eval.cp(409), "g6f5", "d3e4", Eval.cp(409), "d3e4", Eval.cp(-91), "d2e1", Eval.cp(409));
        bxe4 = rated(bxe4, 0, 0);
        assertEquals(MoveClassification.BEST, label(bxe4, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(bxe4, Tuning.DEFAULT.with("greatNoQueenAttackerTaken", 0)));
    }

    @Test
    void aQuietBishopMoveInABishopAgainstPawnsEndingIsNotGreat() {
        // Spassky - Fischer 1972 g1, 44.Bf2 (Lite +3.28, second Bg1 +1.27): SF16 d22 has four equivalent bishop moves,
        // chess.com Best
        ReviewInput bf2 = rated(oneMove("8/1p4p1/pP2p3/7K/P3k3/4B3/8/8 w - - 4 44", "e3f2", Eval.cp(328), "e3f2",
                Eval.cp(127), "e3g1", Eval.cp(328)), 0, 0);
        assertEquals(MoveClassification.BEST, label(bf2, Tuning.DEFAULT));
        assertEquals(MoveClassification.GREAT, label(bf2, Tuning.DEFAULT.with("greatNoBishopEndingMove", 0)));
    }

    private static ReviewInput rated(ReviewInput in, int white, int black) {
        return new ReviewInput(in.initialFen(), in.uciMoves(), in.positions(), in.book(), white, black);
    }

    // ------------------------------------------------------------------ helpers

    /** Players' rating of the positions without one (the cases were measured at this rating). */
    private static final int RATING = 1500;

    private static void assertFake(String fen, String uci, boolean white) {
        ReviewClassifier.Sacrifice s = ReviewClassifier.Sacrifice.of(board(fen), uci, white);
        assertTrue(s.offered() >= 2, uci + " offers " + s.offered());
        assertTrue(s.regain() >= s.offered() + 2, uci + " wins back " + s.regain() + " for " + s.offered());
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
        return new ReviewInput(fen, List.of(uci), ps, OpeningBook.NONE, RATING, RATING);
    }

    /** The opponent's move {@code prev} (evaluated {@code e0}, engine's move {@code best0}) then {@code uci}. */
    private static ReviewInput twoMoves(String fen0, String prev, Eval e0, String best0, String uci, Eval best,
                                        String bestMove, Eval second, String secondMove, Eval after) {
        String fen1 = play(fen0, prev);
        List<PositionEval> ps = new ArrayList<>();
        ps.add(new PositionEval(fen0, e0, List.of(new EngineLine(best0, e0, List.of(best0), 20)), 20, 0, false));
        ps.add(withLines(fen1, best, bestMove, second, secondMove));
        ps.add(after(fen1, uci, after));
        return new ReviewInput(fen0, List.of(prev, uci), ps, OpeningBook.NONE, RATING, RATING);
    }

    /** A game from {@code fen0}: evals.get(i) and bests.get(i) are the search of position i (main line only). */
    private static ReviewInput game(String fen0, int white, int black, List<String> moves, List<Eval> evals,
                                    List<String> bests) {
        List<PositionEval> ps = new ArrayList<>();
        String fen = fen0;
        for (int i = 0; i < moves.size(); i++) {
            ps.add(new PositionEval(fen, evals.get(i), List.of(new EngineLine(bests.get(i), evals.get(i),
                    List.of(bests.get(i)), 20)), 20, 0, false));
            fen = play(fen, moves.get(i));
        }
        Eval last = evals.get(moves.size());
        if (last.isCheckmate()) {
            ps.add(PositionEval.terminal(fen, last));
        } else {
            String reply = board(fen).legalMoves().get(0).toString();
            ps.add(new PositionEval(fen, last, List.of(new EngineLine(reply, last, List.of(reply), 20)), 20, 0, false));
        }
        return new ReviewInput(fen0, moves, ps, OpeningBook.NONE, white, black);
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
