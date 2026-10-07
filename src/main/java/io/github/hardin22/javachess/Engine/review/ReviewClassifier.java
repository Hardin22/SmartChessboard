package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Engine.MoveQuality;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Move classification shared by the game review ({@link #classifyGame}, full: Book, Forced, Brilliant, Great, Miss)
 * and the board LEDs ({@link #fast}, no MultiPV and no history). Pure functions: no engine, no I/O. Rules and numbers
 * follow {@code research/SPEC.md} §4-§5.
 *
 * <p>Every evaluation is an {@link Eval} (White's point of view, explicit mates), so the side to move, the sign of
 * a score and "mate 0" can no longer be confused. A move that delivers checkmate is always Best (or better); a move
 * that allows a forced mate is never better than an Inaccuracy, and a Blunder from a position that was not lost.</p>
 */
public final class ReviewClassifier {

    /**
     * The calibrated numbers of the full classification. {@link #DEFAULT} is the product; the calibration tools try
     * variants with {@link #with} (by name) or {@code -Djavachess.review.<name>=<value>}.
     */
    public static final class Tuning {

        public static final Tuning DEFAULT = new Tuning(Map.of());

        private final Map<String, Double> overrides;

        /** Win chance loss thresholds (0..1): a label applies below its bound. */
        final double excellentMax;
        final double goodMax;
        final double inaccuracyMax;
        final double mistakeMax;
        /** Second best line this good (cp, mover POV) means the position was winning anyway: no Great/Brilliant. */
        final double winningAnywayCp;
        /** Brilliant/Great: the mover must not stand worse than this after the move (chess.com allows about equal). */
        final double criticalMinEp;
        /** v1.9: a Brilliant that is not the engine's move loses at most this much (B-E3). */
        final double brilliantNonTopLoss;
        /** v1.9: accepting the sacrifice must not let the mover win back the sacrifice plus this much at once (B-E4). */
        final double fakeRegain;
        /** v1.9: no Brilliant or Great right after a book position (G-E4). */
        final boolean noSpecialInTheory;
        /** Phase 4: G-E4 only when the opponent's move into that position was a Book move itself (not an error). */
        final boolean theoryNeedsBookMove;
        /** v1.9 G+1: a quiet forcing check in a won attack is Great ... */
        final boolean greatForcingCheck;
        /** ... from at least this win chance ... */
        final double forcingCheckMinEp;
        /** ... when the second best move is at least this much worse. */
        final double forcingCheckGap;
        /** Ratings below this are judged as this (chess.com's curve does not flatten further). */
        final double ratingFloor;
        /** B-E8: no Brilliant when the alternative wins by at least this many centipawns (any rating). */
        final double brilliantWinningCp;
        /** B-E7: no Brilliant when the mover stands below this many centipawns after the move (any rating). */
        final double brilliantMinCpAfter;
        /**
         * Captures and Great. v2.1 rule (greatRule 2): 2 = SPEC v2.2, an exchange that must be made now (no free
         * material, or a player under {@link #greatFreeMaterialRating}), otherwise a capture punishing a blunder.
         * v1.9 rule: 0 = no capture, 1 = all but a pawn taking a pawn.
         */
        final int greatCaptureRule;
        /** Great rule: 2 = SPEC v2.1 (outcome class change or only move; captures only punishing a blunder). */
        final int greatRule;
        /** v2.1: win chance limits of the outcome classes losing / equal / winning. */
        final double outcomeLow;
        final double outcomeHigh;
        /** v2.1: the smallest gap that counts when the move changes the outcome class. */
        final double greatClassGap;
        /** v2.1: a capture is Great only after an opponent's move losing this much ... */
        final double greatCaptureOppLoss;
        /** ... and with the second best move this much worse. */
        final double greatCaptureGap;
        /** v2.2 (greatCaptureRule 2): taking free material is Great only for players under this rating. */
        final double greatFreeMaterialRating;
        /**
         * Phase 4 (0 = off): a checkmate right after the opponent's move lost at least this much win chance is Great for
         * a player under {@link #greatFreeMaterialRating}.
         */
        final double greatMatePunish;
        /** v2.1: a piece or the exchange given for pawns counts as a sacrifice of 2 (Brilliant). */
        final boolean pieceSacrifice;
        /**
         * v2.3: the engine's move escapes the 'winning anyway' tests of Brilliant when, not in check, it gives up the
         * moved piece and the played line wins back at most {@link #brilliantTopRegain} pawns within 6 plies.
         */
        final boolean brilliantTopException;
        final double brilliantTopRegain;
        /** v2.3 R9: a quiet move starting a forced mate is Great when the alternative does not win. */
        final boolean greatStartsMate;
        /**
         * Phase 4: a player under this rating (0 = off) who punishes the opponent's Blunder with the engine's move, not a
         * recapture, and stands winning (win chance above {@link #greatPunishMinEp}) gets Great (the mates in one are
         * {@link #greatMatePunish}).
         */
        final double greatPunishRating;
        final double greatPunishMinEp;
        /** v2.3: the 'winning anyway' tests of Brilliant only for a move that is not the engine's choice. */
        final boolean brilliantAltNonTopOnly;
        /** v2.3: players under this rating get Great for a capture from {@link #greatCaptureGapLow}. */
        final double greatLowRating;
        final double greatCaptureGapLow;
        /** v2.3: an outcome class change needs the second line at least this many cp worse. */
        final double greatClassCp;
        /** v2.3 quiet branch (0 = off): second line at most this many cp, gap and cp gap at least ... */
        final double greatQuietCp;
        final double greatQuietGap;
        final double greatQuietCpFloor;
        /** v2.3: a move out of check can be Great with at least this gap ... */
        final double greatInCheckGap;
        /** ... from at most this win chance. */
        final double greatInCheckMaxEp;
        /** v1.9: no capture is Great (G-E1). */
        final boolean greatNoCapture;
        /**
         * Brilliant rule: 2 = SPEC v1.9 (v1.8 with the false-positive exclusions), 1 = material left en prise by static
         * exchange (SPEC v1.8), 0 = WintrChess piece shapes.
         */
        final int brilliantRule;
        /** v1.8: material (pawns) the move leaves to the opponent, at least. */
        final double sacMin;
        /** v1.8: at most this win chance loss ... */
        final double brilliantMaxLoss;
        /** ... the mover at least this win chance after the move ... */
        final double brilliantMinEpAfter;
        /** ... and the alternative (second line, or best line for another move) not above this, unless it mates. */
        final double brilliantMaxAlt;
        /** Brilliant may also come from a Good move (a sacrifice our shallower search undervalues), SPEC v1.5. */
        final boolean brilliantFromGood;
        /** Great: the second best move loses at least this much win chance. */
        final double greatGap;
        /** Great after an opponent's error (oppLoss >= the Miss threshold): smaller gap to the second best move. */
        final double greatPunishGap;
        /** Great only from about equal to clearly better positions (chess.com: 10th-90th percentile 0.49-0.94). */
        final double greatMinEp;
        final double greatMaxEp;
        /** Great also for a move out of check (the only good answer to a check). */
        final boolean greatInCheck;
        /** Not Great: taking a hanging piece, a plain recapture. */
        final boolean greatFilters;
        /** ... but taking the piece the opponent just blundered (its move lost at least this) can be Great. */
        final double greatTakesBlunder;
        /** Great after an opponent's error: the opponent's previous move lost at least this much. */
        final double greatOpponentLoss;
        /** Miss: the opponent's previous move lost at least this much. */
        final double missOpponentLoss;
        /** Miss rule for moves between two centipawn scores: 1 = SPEC v1.8, 0 = v1.6 (material and opportunity). */
        final int missRule;
        /** v1.8: the missed move loses at least this much ... */
        final double missMinLoss;
        /** ... from at least this win chance. */
        final double missMinEp;
        /** Miss whatever the move gives away (but mate) after an opponent's error losing at least this much. */
        final double missAnyway;
        /** Miss: the mover ends no worse than before the opponent's error, within this tolerance. */
        final double missNoWorse;
        /** From this win chance loss a move is a Blunder even without losing material (chess.com labels). */
        final double blunderAnywayLoss;
        /** A Blunder must lose at least this much material (pawns) along the line, or allow mate. */
        final double blunderMaterial;
        /** A terminal draw reached from this win chance or more gives the game away (Blunder, never Miss). */
        final double giveAwayDrawEp;
        /** Book: plies without a named position that can still lead back into one. */
        final int bookMaxGap;
        /** Book labels stop after this many plies. */
        final int bookMaxPly;
        /** A move that loses at least {@code lostDrop} cp (0 = off) from {@code lostEval} cp or worse is a Mistake. */
        final double lostDrop;
        final double lostEval;
        /** Book also covers the unnamed positions on the way to a named opening. */
        final boolean bookTheory;
        /** Book also covers positions reached by at least this many games of 2000+ players (0 = off). */
        final double bookPopular;
        /** Book also covers this many plies after the last named position when each loses less than ... */
        final int bookExtend;
        /** ... this win chance. */
        final double bookExtendLoss;
        /**
         * Win chance curve: logistic slope per centipawn for an unknown or 1500 rating ... (chess.com's expected
         * points depend on the rating: on its labels the best slope is ~0.0015 under 1000 and ~0.007 over 2000)
         */
        final double slope;
        /** ... multiplied by exp(slopeRating * (rating - 1500) / 1000) ... */
        final double slopeRating;
        /**
         * ... with this rating for a player whose rating is unknown (a local human, an untagged import): chess.com judges
         * a PGN without Elo as strong players (35 famous games: agreement best at 2500, 67.1% exact vs 65.3% at 1500).
         */
        final double defaultRating;

        private Tuning(Map<String, Double> overrides) {
            this.overrides = Map.copyOf(overrides);
            excellentMax = get("excellent", 0.02);
            goodMax = get("good", 0.05);
            inaccuracyMax = get("inaccuracy", 0.10);
            mistakeMax = get("mistake", 0.20);
            winningAnywayCp = get("winningAnywayCp", 700);
            criticalMinEp = get("criticalMinEp", 0.40);
            brilliantFromGood = get("brilliantFromGood", 1) != 0;
            brilliantRule = (int) get("brilliantRule", 2);
            brilliantNonTopLoss = get("brilliantNonTopLoss", 0.03);
            fakeRegain = get("fakeRegain", 5);
            greatNoCapture = get("greatNoCapture", 1) != 0;
            greatRule = (int) get("greatRule", 2);
            brilliantAltNonTopOnly = get("brilliantAltNonTopOnly", 0) != 0;
            greatStartsMate = get("greatStartsMate", 1) != 0;
            greatPunishRating = get("greatPunishRating", 1000);
            greatPunishMinEp = get("greatPunishMinEp", 0.60);
            brilliantTopException = get("brilliantTopException", 1) != 0;
            brilliantTopRegain = get("brilliantTopRegain", 1);
            greatInCheckGap = get("greatInCheckGap", 0.10);
            greatLowRating = get("greatLowRating", 1500);
            greatCaptureGapLow = get("greatCaptureGapLow", 0.15);
            greatClassCp = get("greatClassCp", 100);
            greatQuietCp = get("greatQuietCp", 200);
            greatQuietGap = get("greatQuietGap", 0.10);
            greatQuietCpFloor = get("greatQuietCpFloor", 150);
            greatInCheckMaxEp = get("greatInCheckMaxEp", 0.90);
            pieceSacrifice = get("pieceSacrifice", 1) != 0;
            outcomeLow = get("outcomeLow", 0.40);
            outcomeHigh = get("outcomeHigh", 0.60);
            greatClassGap = get("greatClassGap", 0.10);
            greatCaptureOppLoss = get("greatCaptureOppLoss", 0.10);
            greatCaptureGap = get("greatCaptureGap", 0.30);
            greatFreeMaterialRating = get("greatFreeMaterialRating", 1000);
            greatMatePunish = get("greatMatePunish", 0.20);
            greatCaptureRule = (int) get("greatCaptureRule", 2);
            brilliantWinningCp = get("brilliantWinningCp", 700);
            brilliantMinCpAfter = get("brilliantMinCpAfter", -15);
            greatForcingCheck = get("greatForcingCheck", 1) != 0;
            forcingCheckMinEp = get("forcingCheckMinEp", 0.85);
            forcingCheckGap = get("forcingCheckGap", 0.25);
            ratingFloor = get("ratingFloor", 800);
            noSpecialInTheory = get("noSpecialInTheory", 1) != 0;
            theoryNeedsBookMove = get("theoryNeedsBookMove", 1) != 0;
            sacMin = get("sacMin", 2);
            brilliantMaxLoss = get("brilliantMaxLoss", 0.03);
            brilliantMinEpAfter = get("brilliantMinEpAfter", 0.48);
            brilliantMaxAlt = get("brilliantMaxAlt", 0.97);
            greatGap = get("greatGap", 0.25);
            greatPunishGap = get("greatPunishGap", 0.15);
            greatMinEp = get("greatMinEp", 0.40);
            greatMaxEp = get("greatMaxEp", 0.98);
            greatFilters = get("greatFilters", 1) != 0;
            greatInCheck = get("greatInCheck", 1) != 0;
            greatTakesBlunder = get("greatTakesBlunder", 0.10);
            missOpponentLoss = get("missOpponentLoss", 0.08);
            greatOpponentLoss = get("greatOpponentLoss", 0.05);
            missNoWorse = get("missNoWorse", 0.10);
            missAnyway = get("missAnyway", 0.20);
            missRule = (int) get("missRule", 1);
            missMinLoss = get("missMinLoss", 0.08);
            missMinEp = get("missMinEp", 0.40);
            blunderAnywayLoss = get("blunderAnyway", 0.30);
            blunderMaterial = get("blunderMaterial", 2);
            giveAwayDrawEp = get("giveAwayDrawEp", 0.6);
            bookMaxGap = (int) get("bookMaxGap", 4);
            bookMaxPly = (int) get("bookMaxPly", 20);
            lostDrop = get("lostDrop", 150);
            lostEval = get("lostEval", 600);
            bookTheory = get("bookTheory", 1) != 0;
            bookExtend = (int) get("bookExtend", 0);
            bookPopular = get("bookPopular", 300);
            bookExtendLoss = get("bookExtendLoss", 0.02);
            slope = get("slope", 0.0035);
            slopeRating = get("slopeRating", 0.5);
            defaultRating = get("defaultRating", 2500);
        }

        private double get(String name, double def) {
            Double v = overrides.get(name);
            if (v != null) {
                return v;
            }
            try {
                return Double.parseDouble(System.getProperty("javachess.review." + name, String.valueOf(def)));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        /** Win chance slope for a player of this rating (0 = unknown). */
        double slope(int rating) {
            double r = rating > 0 ? Math.max(rating, ratingFloor) : defaultRating;
            return slope * Math.exp(slopeRating * (r - 1500) / 1000.0);
        }

        /** This tuning with one number changed (names as in {@code -Djavachess.review.<name>}). */
        public Tuning with(String name, double value) {
            Map<String, Double> m = new HashMap<>(overrides);
            m.put(name, value);
            return new Tuning(m);
        }

        @Override
        public String toString() {
            return overrides.isEmpty() ? "default" : new TreeMap<>(overrides).toString();
        }
    }

    /** Win chance loss thresholds of the product (0..1): a label applies below its bound. */
    public static final double EXCELLENT_MAX = Tuning.DEFAULT.excellentMax;
    public static final double GOOD_MAX = Tuning.DEFAULT.goodMax;
    public static final double INACCURACY_MAX = Tuning.DEFAULT.inaccuracyMax;
    public static final double MISTAKE_MAX = Tuning.DEFAULT.mistakeMax;

    /** Rating of the win chance curve of the board LEDs ({@link #fast}): a club player. */
    static final int LED_RATING = 1500;

    /** MultiPV 2 is only worth it in this win chance range (outside, no Great/Brilliant is possible). */
    static final double SECOND_LINE_MIN_EP = 0.20;
    static final double SECOND_LINE_MAX_EP = 0.97;

    private ReviewClassifier() {
    }

    // ------------------------------------------------------------------------------------------
    // Fast (LEDs)

    /**
     * Verdict of a move without MultiPV (board LEDs).
     *
     * @param best         best evaluation of the position before the move (White POV)
     * @param played       evaluation after the played move (White POV): the {@link Eval#fromUci converted} score of
     *                     the resulting position, its {@link Eval#terminal terminal} result, or the score of the
     *                     played move's line in the position before
     * @param whiteMoved   true when White played the move
     * @param playedIsBest true when the played move is the engine's best move
     */
    public static FastVerdict fast(Eval best, Eval played, boolean whiteMoved, boolean playedIsBest) {
        // the LEDs keep the curve of a 1500 player (the review's default for unknown ratings follows chess.com instead)
        double k = Tuning.DEFAULT.slope(LED_RATING);
        double wb = ep(best, whiteMoved, k);
        double wa = ep(played, whiteMoved, k);
        return new FastVerdict(baseLabel(best, played, whiteMoved, playedIsBest, false, Tuning.DEFAULT, k), wb, wa,
                Math.max(0, wb - wa));
    }

    /** LED quality of a review label. */
    public static MoveQuality toQuality(MoveClassification label) {
        return switch (label) {
            case BRILLIANT, GREAT, BEST, BOOK_MOVE, FORCED -> MoveQuality.BEST;
            case EXCELLENT, GOOD -> MoveQuality.GOOD;
            case INACCURACY -> MoveQuality.INACCURACY;
            case MISTAKE, MISS -> MoveQuality.MISTAKE;
            case BLUNDER -> MoveQuality.BLUNDER;
        };
    }

    /**
     * Best .. Blunder from the two evaluations with the mate rules (SPEC §4 R1, R3-R7). Shared by the fast and the
     * full classification.
     */
    static MoveClassification baseLabel(Eval best, Eval played, boolean me, boolean isTop) {
        return baseLabel(best, played, me, isTop, false, Tuning.DEFAULT, Tuning.DEFAULT.slope(LED_RATING));
    }

    /**
     * @param review true for the game review (SPEC v1.6 mate rules fitted on chess.com labels, may answer MISS);
     *               false for the LEDs (no Miss: a lost mate is judged by how much is left)
     */
    static MoveClassification baseLabel(Eval best, Eval played, boolean me, boolean isTop, boolean review,
                                        Tuning t, double k) {
        if (played.isCheckmate() && played.isMateFor(me)) {
            return MoveClassification.BEST; // R1: delivering mate
        }
        if (isTop) {
            return MoveClassification.BEST; // R3
        }
        double loss = Math.max(0, ep(best, me, k) - ep(played, me, k));
        // R4: mate -> mate
        if (best.isMateFor(me) && played.isMateFor(me)) {
            int n = best.mateIn();
            int d = played.mateIn() - (n - 1);
            if (d <= 0) {
                return MoveClassification.BEST;
            }
            if (review && n == 2 && d == 2) {
                return MoveClassification.GOOD; // mate in 2 -> mate in 3 (chess.com 4/4: Good)
            }
            if (review && n <= 2 && d >= 2) {
                return MoveClassification.MISS; // a mate in 1-2 missed (chess.com: M1 -> M3 is a Miss)
            }
            return d <= 4 ? MoveClassification.EXCELLENT : MoveClassification.GOOD;
        }
        if (best.isMateAgainst(me) && played.isMateAgainst(me)) {
            int n = best.mateIn();
            int m = played.mateIn();
            if (m >= n) {
                return MoveClassification.BEST;
            }
            // a much faster mate (to mate in 1-2, 3+ moves sooner) is an Inaccuracy for chess.com (M-7 -> M-2 Kd8)
            boolean muchFaster = (m == 1 && n >= 3) || (m <= 2 && n - m >= 3);
            return muchFaster ? MoveClassification.INACCURACY : MoveClassification.EXCELLENT;
        }
        if (best.isMateFor(me) && played.isMateAgainst(me)) {
            return MoveClassification.BLUNDER;
        }
        // R5: forced mate lost
        if (best.isMateFor(me) && !played.isMate()) {
            int c = played.cpFor(me);
            if (review) {
                return best.mateIn() >= 4 && c >= 800 ? MoveClassification.GOOD : MoveClassification.MISS;
            }
            return c >= 800 ? MoveClassification.EXCELLENT : c >= 400 ? MoveClassification.GOOD
                    : c >= 200 ? MoveClassification.INACCURACY : c > 0 ? MoveClassification.MISTAKE
                    : MoveClassification.BLUNDER;
        }
        // R6: forced mate conceded: a fixed label by how the mover stood and how close the mate is
        if (!best.isMateAgainst(me) && played.isMateAgainst(me)) {
            double before = ep(best, me, k);
            if (before >= 0.20) {
                return MoveClassification.BLUNDER;
            }
            if (before >= 0.05 || played.mateIn() <= 3) {
                return MoveClassification.MISTAKE;
            }
            return MoveClassification.INACCURACY;
        }
        return labelForLoss(loss, t); // R7
    }

    /** Win chance (0..1) of the mover {@code me} with the logistic slope {@code k} (mates: 0 or 1). */
    static double ep(Eval e, boolean me, double k) {
        if (e.isMate()) {
            return e.isMateFor(me) ? 1.0 : 0.0;
        }
        return 1.0 / (1.0 + Math.exp(-k * e.cpFor(me)));
    }

    /** Label for a win chance loss (0..1). */
    public static MoveClassification labelForLoss(double loss) {
        return labelForLoss(loss, Tuning.DEFAULT);
    }

    static MoveClassification labelForLoss(double loss, Tuning t) {
        if (loss <= 0.0) {
            return MoveClassification.BEST;
        }
        if (loss < t.excellentMax) {
            return MoveClassification.EXCELLENT;
        }
        if (loss < t.goodMax) {
            return MoveClassification.GOOD;
        }
        if (loss < t.inaccuracyMax) {
            return MoveClassification.INACCURACY;
        }
        if (loss < t.mistakeMax) {
            return MoveClassification.MISTAKE;
        }
        return MoveClassification.BLUNDER;
    }

    private static int severity(MoveClassification c) {
        return switch (c) {
            case BRILLIANT, GREAT, BEST, BOOK_MOVE, FORCED -> 0;
            case EXCELLENT -> 1;
            case GOOD -> 2;
            case INACCURACY -> 3;
            case MISTAKE, MISS -> 4;
            case BLUNDER -> 5;
        };
    }

    private static MoveClassification worst(MoveClassification a, MoveClassification b) {
        return severity(a) >= severity(b) ? a : b;
    }

    // ------------------------------------------------------------------------------------------
    // Full (game review)

    /**
     * Positions (index i = position before move i) whose second best line is needed to decide Great / Brilliant:
     * the played move is the engine's best, the mover is not in check, and the position is neither lost nor won
     * already. The reviewer re-searches only those with MultiPV 2.
     */
    public static BitSet needsSecondLine(ReviewInput in) {
        GameReplay replay = GameReplay.of(in.initialFen(), in.uciMoves());
        BitSet need = new BitSet(in.uciMoves().size());
        for (int i = 0; i < replay.uci().size(); i++) {
            PositionEval before = in.positions().get(i);
            if (before.terminal() || before.secondBest() != null || replay.legalMoveCounts().get(i) <= 1) {
                continue;
            }
            String uci = replay.uci().get(i);
            if (!uci.equals(before.bestMove())) {
                continue;
            }
            boolean me = before.whiteToMove();
            Board b = board(replay.fens().get(i));
            if (Tuning.DEFAULT.brilliantRule >= 1 && !before.eval().isMateAgainst(me)
                    && (Tuning.DEFAULT.brilliantRule == 2 ? Sacrifice.of(b, uci, me).value()
                    : sacrifice(b, uci, me)) >= Tuning.DEFAULT.sacMin) {
                need.set(i); // a sacrifice: Brilliant unless the second best move was as good (also in check)
                continue;
            }
            if ((b.isKingAttacked() && !Tuning.DEFAULT.greatInCheck) || uci.endsWith("q")) {
                continue;
            }
            double ep = before.eval().winChance(me);
            boolean greatRange = ep >= SECOND_LINE_MIN_EP && ep <= SECOND_LINE_MAX_EP && !before.eval().isMate();
            // R9: a quiet move starting a forced mate is Great only if the second best move does not win
            boolean startsMate = Tuning.DEFAULT.greatStartsMate && before.eval().isMateFor(me)
                    && before.eval().mateIn() > 1 && !b.isKingAttacked() && !Tactics.isCapture(b, uci);
            // a sacrifice can be Brilliant even when it mates: only the second line tells if it was needed
            if (greatRange || startsMate || (!before.eval().isMateAgainst(me) && brilliant(b, uci, me))) {
                need.set(i);
            }
        }
        return need;
    }

    /** Classifies every move of the game and computes the accuracy of both players. */
    public static GameReview classifyGame(ReviewInput in) {
        return classifyGame(in, Tuning.DEFAULT);
    }

    /** {@link #classifyGame(ReviewInput)} with other calibration numbers (calibration tools). */
    public static GameReview classifyGame(ReviewInput in, Tuning t) {
        GameReplay replay = GameReplay.of(in.initialFen(), in.uciMoves());
        int n = replay.uci().size();
        List<PositionEval> pos = in.positions();
        double[] epBefore = new double[n];
        double[] epAfter = new double[n];
        double kWhite = t.slope(in.whiteRating());
        double kBlack = t.slope(in.blackRating());
        Eval[] played = new Eval[n];
        for (int i = 0; i < n; i++) {
            PositionEval p0 = pos.get(i);
            boolean me = p0.whiteToMove();
            played[i] = playedEval(p0, pos.get(i + 1), replay.uci().get(i));
            double k = me ? kWhite : kBlack;
            epBefore[i] = ep(p0.eval(), me, k);
            epAfter[i] = ep(played[i], me, k);
        }
        double[][] acc = Accuracy.perMove(in.initialFen(), pos);

        // Book: every move up to the last named opening position the game reaches, allowing short gaps (move
        // orders that transpose back into a named line are theory too)
        int bookEnd = -1;
        String opening = null;
        for (int i = 0; i < Math.min(n, t.bookMaxPly); i++) {
            String fen = replay.fens().get(i + 1);
            String name = in.book().nameAfter(fen).orElse(null);
            if (name != null) {
                opening = name;
            }
            if (name != null || (t.bookTheory && in.book().isTheory(fen))
                    || (t.bookPopular > 0 && in.book().popularity(fen) >= t.bookPopular)) {
                bookEnd = i;
            } else if (i - bookEnd > t.bookMaxGap) {
                break;
            }
        }
        // chess.com's book is larger than the lichess openings: the few accurate moves right after the last book
        // position are theory too (43 labelled games: Book ends 1-4 plies after ours in 30 of them)
        int theoryEnd = bookEnd;
        while (bookEnd >= 0 && theoryEnd + 1 < Math.min(n, t.bookMaxPly) && theoryEnd - bookEnd < t.bookExtend
                && replay.legalMoveCounts().get(theoryEnd + 1) > 1
                && epBefore[theoryEnd + 1] - epAfter[theoryEnd + 1] < t.bookExtendLoss) {
            theoryEnd++;
        }

        List<MoveReview> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String uci = replay.uci().get(i);
            PositionEval p0 = pos.get(i);
            boolean me = p0.whiteToMove();
            Eval best = p0.eval();
            boolean isTop = uci.equals(p0.bestMove());

            MoveClassification label = null;
            // named traps (Fool's Mate...) are in the opening list: a book move never allows or gives mate, nor
            // throws away a Mistake's worth of win chance
            if (i <= theoryEnd && !played[i].isMate() && epBefore[i] - epAfter[i] < t.inaccuracyMax) {
                label = MoveClassification.BOOK_MOVE;
            }
            boolean mates = played[i].isCheckmate() && played[i].isMateFor(me);
            if (label == null && !mates && replay.legalMoveCounts().get(i) == 1) {
                label = MoveClassification.FORCED;
            }
            if (label == null) {
                label = baseLabel(best, played[i], me, isTop, true, t, me ? kWhite : kBlack);
                if (label == MoveClassification.BEST && !isTop && !best.isMate() && !played[i].isMate()) {
                    // chess.com keeps Best for the engine's move: an equivalent alternative is Excellent
                    label = MoveClassification.EXCELLENT;
                }
                boolean drawn = pos.get(i + 1).terminal() && !played[i].isMate();
                if (drawn && epBefore[i] >= t.giveAwayDrawEp) {
                    label = MoveClassification.BLUNDER; // stalemate (or dead draw) from a winning position
                }
                if (t.lostDrop > 0 && !isTop && severity(label) < severity(MoveClassification.MISTAKE) && !best.isMate()
                        && !played[i].isMate() && best.cpFor(me) <= -t.lostEval
                        && best.cpFor(me) - played[i].cpFor(me) >= t.lostDrop) {
                    // chess.com calls a Mistake what makes a lost position clearly worse, although the win chance
                    // hardly changes (43 labelled games: 21 of 29 moves losing 2+ pawns from -5 or worse)
                    label = MoveClassification.MISTAKE;
                }
                boolean cpToCp = !best.isMate() && !played[i].isMate();
                if (t.missRule == 1 && cpToCp && !isTop && i > 0) {
                    // SPEC v1.8: a clear loss (Inaccuracy or worse) right after the opponent's error, ending no
                    // worse than before that error, from a position that was not bad: Miss, whatever it gives away
                    double oppLoss = Math.max(0, epBefore[i - 1] - epAfter[i - 1]);
                    double epBeforeOpp = ep(pos.get(i - 1).eval(), me, me ? kWhite : kBlack);
                    double loss = epBefore[i] - epAfter[i];
                    if (severity(label) >= severity(MoveClassification.INACCURACY) && oppLoss >= t.missOpponentLoss
                            && loss >= t.missMinLoss && epAfter[i] >= epBeforeOpp - t.missNoWorse
                            && epBefore[i] >= t.missMinEp) {
                        label = MoveClassification.MISS;
                    } else if (label == MoveClassification.BLUNDER && epBefore[i] - epAfter[i] < t.blunderAnywayLoss
                            && !givesSomethingAway(replay.fens().get(i), uci, p0, pos.get(i + 1), played[i], me,
                            epBefore[i], t)) {
                        label = MoveClassification.MISTAKE;
                    }
                } else if (label == MoveClassification.MISTAKE || label == MoveClassification.BLUNDER) {
                    boolean gives = givesSomethingAway(replay.fens().get(i), uci, p0, pos.get(i + 1), played[i], me,
                            epBefore[i], t);
                    double oppLoss = i > 0 ? Math.max(0, epBefore[i - 1] - epAfter[i - 1]) : 0;
                    if (!gives && missOpportunity(i, pos, played, epBefore, epAfter, me, t, me ? kWhite : kBlack)) {
                        label = MoveClassification.MISS;
                    } else if (oppLoss >= t.missAnyway && !played[i].isMateAgainst(me)) {
                        // after a big error of the opponent chess.com calls the failure to punish it a Miss, even
                        // when the move also gives material away
                        label = MoveClassification.MISS;
                    } else if (label == MoveClassification.BLUNDER && !gives
                            && epBefore[i] - epAfter[i] < t.blunderAnywayLoss) {
                        label = MoveClassification.MISTAKE;
                    }
                }
                boolean nearBest = label == MoveClassification.BEST || label == MoveClassification.EXCELLENT
                        || (t.brilliantFromGood && label == MoveClassification.GOOD);
                // G-E4: a move played from an opening book position is known theory, never Brilliant or Great
                // (Phase 4) ... unless the opponent left the book with an error: punishing it is not theory
                // (daily_1017137676 3...e6? 4.d5: chess.com Great)
                boolean fromTheory = t.noSpecialInTheory && i > 0 && i - 1 <= theoryEnd
                        && (!t.theoryNeedsBookMove || out.get(i - 1).label() == MoveClassification.BOOK_MOVE);
                int rating = me ? in.whiteRating() : in.blackRating();
                if (mates && label == MoveClassification.BEST && !fromTheory && t.greatMatePunish > 0 && i > 0
                        && (rating > 0 ? rating : t.defaultRating) < t.greatFreeMaterialRating
                        && epBefore[i - 1] - epAfter[i - 1] >= t.greatMatePunish) {
                    // chess.com is "more generous with new players": under 1000 the mate that punishes the opponent's
                    // blunder is Great (177 games: 6 of 6 mates after an error >= 0.20, 0 of 14 other mates under 1000)
                    label = MoveClassification.GREAT;
                }
                if (nearBest && !mates && !fromTheory) {
                    double oppLoss = i > 0 ? Math.max(0, epBefore[i - 1] - epAfter[i - 1]) : 0;
                    MoveClassification special = special(label, isTop, i, replay, p0, pos.get(i + 1), played[i],
                            epBefore[i], epAfter[i], me, oppLoss, t, me ? kWhite : kBlack,
                            rating);
                    if (special != null) {
                        label = special;
                    }
                }
                if (label == MoveClassification.BEST && isTop && !mates && !fromTheory
                        && punishesBlunder(i, replay, out, epBefore[i], me ? in.whiteRating() : in.blackRating(), t)) {
                    label = MoveClassification.GREAT;
                }
            }
            EngineLine bestLine = p0.best();
            out.add(new MoveReview(i, uci, replay.sans().get(i), replay.fens().get(i), me, label, best, played[i],
                    p0.bestMove(), bestLine == null ? List.of() : bestLine.pv(), epBefore[i], epAfter[i],
                    acc[0][i]));
        }
        return new GameReview(in.initialFen(), out, pos, Accuracy.forPlayer(out, acc, true),
                Accuracy.forPlayer(out, acc, false), opening, GameReview.Stats.NONE);
    }

    /**
     * Evaluation after the played move (SPEC §4): its own line in the MultiPV search of the position before when
     * there is one, else the best line of the next position, or the next position's terminal result.
     */
    static Eval playedEval(PositionEval before, PositionEval after, String uci) {
        if (after.terminal()) {
            return after.eval();
        }
        EngineLine own = before.lineFor(uci);
        return own != null ? own.eval() : after.eval();
    }

    /** SPEC §5.4: the Mistake/Blunder failed to punish the opponent's error (it becomes a Miss). */
    private static boolean missOpportunity(int i, List<PositionEval> pos, Eval[] played, double[] epBefore,
                                           double[] epAfter, boolean me, Tuning t, double k) {
        Eval best = pos.get(i).eval();
        if (i > 0) {
            double oppLoss = Math.max(0, epBefore[i - 1] - epAfter[i - 1]);
            boolean opportunity = oppLoss >= t.missOpponentLoss || best.isMateFor(me);
            double epBeforeOpp = ep(pos.get(i - 1).eval(), me, k);
            if (opportunity && epAfter[i] >= epBeforeOpp - t.missNoWorse) {
                return true;
            }
        }
        return best.isMateFor(me) && !played[i].isMate() && played[i].cpFor(me) > 0 && !pos.get(i + 1).terminal();
    }

    /** The played move followed by the engine's best play: its own MultiPV line, or the next position's line. */
    static List<String> playedLine(String uci, PositionEval p0, PositionEval p1) {
        EngineLine own = p0.lineFor(uci);
        if (own != null) {
            return own.pv();
        }
        List<String> line = new ArrayList<>();
        line.add(uci);
        if (p1.best() != null) {
            line.addAll(p1.best().pv());
        }
        return line;
    }

    /** SPEC §5.5: the move loses material along the line, allows mate, or throws the game away into a draw. */
    private static boolean givesSomethingAway(String fen, String uci, PositionEval p0, PositionEval p1,
                                                     Eval played, boolean me, double epBefore,
                                              Tuning t) {
        if (played.isMateAgainst(me)) {
            return true;
        }
        if (p1.terminal() && !played.isMate() && epBefore >= t.giveAwayDrawEp) {
            return true; // stalemate or dead draw from a won position
        }
        Side side = me ? Side.WHITE : Side.BLACK;
        List<String> playedLine = playedLine(uci, p0, p1);
        EngineLine bestLine = p0.best();
        if (bestLine == null) {
            return true;
        }
        int mPlayed = Tactics.materialAfter(fen, playedLine, side, 6);
        int mBest = Tactics.materialAfter(fen, bestLine.pv(), side, 6);
        return mBest - mPlayed >= t.blunderMaterial;
    }

    /**
     * SPEC §5.2-5.3: Brilliant or Great, null when neither applies. The alternative that must not be winning anyway
     * is the second line when the played move is the engine's choice, the engine's choice otherwise (a sacrifice the
     * engine found only at a deeper search than ours).
     */
    private static MoveClassification special(MoveClassification label, boolean isTop, int i, GameReplay replay,
                                              PositionEval p0, PositionEval p1, Eval played, double epBefore,
                                              double epAfter, boolean me, double oppLoss,
                                              Tuning t, double k, int rating) {
        EngineLine second = p0.secondBest();
        Eval alternative = isTop ? (second == null ? null : second.eval()) : p0.eval();
        Board b0 = board(replay.fens().get(i));
        String uci = replay.uci().get(i);
        if (t.brilliantRule == 2 && brilliantV19(b0, uci, me, isTop, alternative, played, epBefore, epAfter, t, k,
                playedLine(uci, p0, p1))) {
            return MoveClassification.BRILLIANT;
        }
        if (t.brilliantRule == 1 && brilliantBySee(b0, uci, me, alternative, played, epBefore, epAfter, t, k)) {
            return MoveClassification.BRILLIANT;
        }
        if (alternative == null) {
            return null; // no MultiPV here: the reviewer judged it not worth it
        }
        if (!candidate(b0, uci, alternative, epAfter, me, t)) {
            return null;
        }
        if (t.brilliantRule == 0 && brilliant(b0, uci, me)) {
            return MoveClassification.BRILLIANT;
        }
        if (t.greatStartsMate && label == MoveClassification.BEST && isTop && startsMate(b0, uci, played, second, me)) {
            return MoveClassification.GREAT; // R9
        }
        if (label != MoveClassification.BEST || !isTop || played.isMateFor(me)) {
            return null;
        }
        if (t.greatForcingCheck && forcingCheck(b0, uci, i, replay, second, epBefore, epAfter, me, t, k)) {
            return MoveClassification.GREAT; // G+1
        }
        if (t.greatRule == 2) {
            return greatV21(b0, uci, i, replay, p0.eval(), second, epBefore, me, oppLoss, t, k, rating)
                    ? MoveClassification.GREAT : null;
        }
        if (epBefore < t.greatMinEp || epBefore > t.greatMaxEp) {
            return null;
        }
        if (t.greatNoCapture && Tactics.isCapture(b0, uci)
                && (t.greatCaptureRule == 0 || Tactics.isPawnTakesPawn(b0, uci))) {
            return null; // G-E1: a capture is the natural recapture or keeps the material, not a find
        }
        if (t.greatFilters) {
            Move m = Tactics.find(b0, uci);
            if (m != null && b0.getPiece(m.getTo()) != Piece.NONE && !Tactics.isSafe(b0, m.getTo())
                    && oppLoss < t.greatTakesBlunder) {
                return null; // taking a hanging piece is not "great" (unless it punishes a real blunder)
            }
            if (i > 0 && isRecapture(replay, i)) {
                return null;
            }
        }
        // SPEC v1.6: a Great mostly punishes the opponent's error (gap >= 0.05 is enough then), otherwise it must be
        // the only good move by a wide margin
        double gap = epBefore - ep(second.eval(), me, k);
        boolean punishes = oppLoss >= t.greatOpponentLoss && gap >= t.greatPunishGap;
        return punishes || gap >= t.greatGap ? MoveClassification.GREAT : null;
    }

    /**
     * SPEC v2.1 Great: the only move in chess.com's own sense, "critical to the outcome". The move changes the outcome
     * class of the position compared with the second best move (losing below 0.40, equal up to 0.60, winning above)
     * by at least {@link Tuning#greatClassGap}, or every other move loses at least {@link Tuning#greatGap}. A capture
     * is Great only when it punishes the opponent's blunder (not a recapture, opponent's move lost at least
     * {@link Tuning#greatCaptureOppLoss}, second best at least {@link Tuning#greatCaptureGap} worse): taking back or
     * keeping the material is routine.
     */
    private static boolean greatV21(Board b0, String uci, int i, GameReplay replay, Eval best, EngineLine second,
                                    double epBefore, boolean me, double oppLoss, Tuning t, double k, int rating) {
        if (second == null || epBefore < t.greatMinEp) {
            return false;
        }
        double gap = epBefore - ep(second.eval(), me, k);
        // a difference under a pawn does not change the outcome, however steep the curve (SPEC v2.3)
        double cpGap = best.isMate() || second.eval().isMate() ? 10_000 : best.cpFor(me) - second.eval().cpFor(me);
        double r = rating > 0 ? rating : t.defaultRating;
        boolean recapture = i > 0 && isRecapture(replay, i);
        if (b0.isKingAttacked()) {
            // v2.3: an answer to check that does not move the king (interposition, taking the checker) can be Great;
            // king escapes are Great and Best alike for chess.com
            Move m = Tactics.find(b0, uci);
            return t.greatInCheck && m != null && b0.getPiece(m.getFrom()).getPieceType() != PieceType.KING
                    && !recapture && gap >= t.greatInCheckGap && epBefore <= t.greatInCheckMaxEp;
        }
        boolean capture = Tactics.isCapture(b0, uci);
        if (capture) {
            // v2.3: players under 1500 get Great for a capture from a smaller gap
            double capGap = r < t.greatLowRating ? t.greatCaptureGapLow : t.greatCaptureGap;
            if (recapture || gap < capGap) {
                return false;
            }
            if (t.greatCaptureRule == 2) {
                // v2.2: an exchange that has to be made now can be Great; taking free material is routine (Best),
                // except for players under 1000
                Move m = Tactics.find(b0, uci);
                if (m != null && Tactics.see(b0, m.getTo()) > 0 && r >= t.greatFreeMaterialRating) {
                    return false;
                }
            } else if (oppLoss < t.greatCaptureOppLoss) {
                return false;
            }
        }
        boolean changesOutcome = outcomeClass(epBefore, t) > outcomeClass(epBefore - gap, t) && gap >= t.greatClassGap
                && cpGap >= t.greatClassCp;
        // v2.3: a quiet move whose alternative only keeps about equality (second line at most greatQuietCp)
        boolean quiet = t.greatQuietCp > 0 && !capture && !second.eval().isMate()
                && second.eval().cpFor(me) <= t.greatQuietCp && gap >= t.greatQuietGap && cpGap >= t.greatQuietCpFloor;
        return changesOutcome || quiet || gap >= t.greatGap;
    }

    /**
     * SPEC v2.3 R9: a quiet move or a check without capture that starts a forced mate (not mate at once) when the
     * second best move does not win (at most +150 cp, or loses to mate) is Great: chess.com 8 of 8 (Anderssen -
     * Dufresne 22.Bf5+, Wei Yi - Bruzon...). Finding a mate is not "critical" only when the alternative wins anyway.
     */
    private static boolean startsMate(Board b0, String uci, Eval played, EngineLine second, boolean me) {
        if (second == null || !played.isMateFor(me) || played.isCheckmate() || b0.isKingAttacked()
                || Tactics.isCapture(b0, uci) || uci.length() > 4) {
            return false;
        }
        Eval alt = second.eval();
        return alt.isMateAgainst(me) || (!alt.isMate() && alt.cpFor(me) <= 150);
    }

    /**
     * Phase 4: chess.com calls Great a beginner's engine move that punishes the opponent's Blunder (177 games, players
     * under 1000, not a recapture, mover winning, not mate: 8 of 10 Great; the other is an engine tie and the one
     * excluded below). Taking back what was just taken is routine at any level (9 Best of 12 recaptures), and an
     * opponent's Blunder that only fails to punish the mover's own Blunder is a Miss for chess.com: recovering from it
     * is Best (live_180008683178 8.Qxg7 after 7.Qc3?? Be7?).
     */
    private static boolean punishesBlunder(int i, GameReplay replay, List<MoveReview> out, double epBefore,
                                           int rating, Tuning t) {
        return t.greatPunishRating > 0 && rating > 0 && Math.max(rating, t.ratingFloor) < t.greatPunishRating
                && i > 0 && out.get(i - 1).label() == MoveClassification.BLUNDER && !isRecapture(replay, i)
                && (i < 2 || out.get(i - 2).label() != MoveClassification.BLUNDER) && epBefore > t.greatPunishMinEp;
    }

    /** 0 losing, 1 about equal, 2 winning (SPEC v2.1). */
    private static int outcomeClass(double ep, Tuning t) {
        return ep < t.outcomeLow ? 0 : ep > t.outcomeHigh ? 2 : 1;
    }

    /**
     * SPEC v1.9 G+1: a quiet forcing check that keeps a won attack going, when every other move throws much of it
     * away (Torre's windmill 28.Rg7+, Byrne - Fischer 19...Ne2+): chess.com calls these Great (0 false positives on
     * the CV and famous games).
     */
    private static boolean forcingCheck(Board b0, String uci, int i, GameReplay replay, EngineLine second,
                                        double epBefore, double epAfter, boolean me, Tuning t, double k) {
        if (second == null || b0.isKingAttacked() || epBefore <= t.forcingCheckMinEp || epAfter < epBefore - 0.02
                || Tactics.isCapture(b0, uci) || (i > 0 && isRecapture(replay, i))) {
            return false;
        }
        Move m = Tactics.find(b0, uci);
        if (m == null) {
            return false;
        }
        Board b1 = b0.clone();
        b1.doMove(m);
        return b1.isKingAttacked() && !b1.isMated() && epBefore - ep(second.eval(), me, k) >= t.forcingCheckGap;
    }

    /** Shared precondition of Brilliant and Great (WintrChess "critical candidate"). */
    private static boolean candidate(Board b0, String uci, Eval alternative, double epAfter, boolean me,
                                     Tuning t) {
        if ((b0.isKingAttacked() && !t.greatInCheck) || uci.endsWith("q")) {
            return false;
        }
        if (alternative.isMateFor(me) || (!alternative.isMate() && alternative.cpFor(me) >= t.winningAnywayCp)) {
            return false; // winning anyway
        }
        return epAfter >= t.criticalMinEp;
    }

    /** SPEC §5.2: the move leaves a piece en prise that is not simply lost (a sound sacrifice). */
    private static boolean brilliant(Board b0, String uci, boolean me) {
        return sacrificeVerdict(b0, uci, me) == null;
    }

    /**
     * SPEC v1.8 Brilliant: the move leaves at least {@link Tuning#sacMin} pawns of material to the opponent (static
     * exchange), loses almost nothing, does not leave the mover worse, and the alternative was not winning already
     * (unless the move mates). Allowed in check; no king moves or promotions. chess.com gives the same Brilliants with
     * two different engines: the rule is about the board, not the search.
     */
    private static boolean brilliantBySee(Board b0, String uci, boolean me, Eval alternative, Eval played,
                                          double epBefore, double epAfter, Tuning t, double k) {
        Move m = Tactics.find(b0, uci);
        if (m == null || m.getPromotion() != Piece.NONE || b0.getPiece(m.getFrom()).getPieceType() == PieceType.KING) {
            return false;
        }
        if (epBefore - epAfter > t.brilliantMaxLoss || epAfter < t.brilliantMinEpAfter) {
            return false;
        }
        if (alternative != null && ep(alternative, me, k) > t.brilliantMaxAlt && !played.isMateFor(me)) {
            return false; // winning anyway
        }
        return sacrifice(b0, uci, me) >= t.sacMin;
    }

    /**
     * SPEC v1.9 Brilliant: v1.8 with the exclusions of the false positives catalogue ({@code notes/fp/CATALOG.md}):
     * B-E1 only material put en prise by this move counts ({@link Sacrifice}), B-E2 no Brilliant when the alternative
     * was already winning, even by mate, B-E3 a move that is not the engine's choice must lose at most
     * {@link Tuning#brilliantNonTopLoss}, B-E4 no Brilliant when accepting the sacrifice loses more than it wins.
     */
    private static boolean brilliantV19(Board b0, String uci, boolean me, boolean isTop, Eval alternative,
                                        Eval played, double epBefore, double epAfter, Tuning t, double k,
                                        List<String> line) {
        Move m = Tactics.find(b0, uci);
        if (m == null || m.getPromotion() != Piece.NONE || b0.getPiece(m.getFrom()).getPieceType() == PieceType.KING) {
            return false;
        }
        double loss = epBefore - epAfter;
        if (loss > t.brilliantMaxLoss || epAfter < t.brilliantMinEpAfter
                || (!isTop && loss > t.brilliantNonTopLoss)) {
            return false; // B-E3
        }
        Sacrifice sac = Sacrifice.of(b0, uci, me);
        boolean altTest = alternative != null && !(t.brilliantAltNonTopOnly && isTop);
        if (altTest && t.brilliantTopException && isTop && !b0.isKingAttacked() && sac.movedNet() >= 2) {
            // v2.3: the engine's move may be Brilliant although the position was won anyway when it gives up the
            // moved piece for good: the line does not win the material back (not technique in a won position)
            Side side = me ? Side.WHITE : Side.BLACK;
            int gained = Tactics.materialAfter(b0.getFen(), line, side, 6) - Tactics.material(b0, side);
            if (gained <= t.brilliantTopRegain) {
                altTest = false;
            }
        }
        if (altTest && ep(alternative, me, k) > t.brilliantMaxAlt) {
            return false; // B-E2: winning anyway, also when the move mates
        }
        // the same two tests in centipawns, whatever the players' rating (a decided position is decided for anyone):
        // B-E8 the alternative already wins by this much, or mates; B-E7 the mover stands worse after the move
        if (altTest && (alternative.isMateFor(me)
                || (!alternative.isMate() && alternative.cpFor(me) >= t.brilliantWinningCp))) {
            return false;
        }
        if (!played.isMate() && played.cpFor(me) < t.brilliantMinCpAfter) {
            return false;
        }
        if (sac.value() < t.sacMin) {
            return false; // B-E1: nothing new is offered
        }
        return sac.regain() < 0 || sac.regain() < sac.offered() + t.fakeRegain; // B-E4
    }

    /**
     * What a move offers (SPEC v1.9 §5.2): the material the opponent wins by static exchange on the moved piece (minus
     * what it took) or on a piece this move leaves en prise (pieces already en prise before do not count), and, if
     * the opponent accepts with its least valuable capturer, the most the mover then wins back at once (-1 when the
     * piece cannot be taken or taking it mates).
     */
    record Sacrifice(int value, int regain, int offered, int movedNet) {

        static Sacrifice of(Board b0, String uci, boolean me) {
            Move m = Tactics.find(b0, uci);
            if (m == null) {
                return new Sacrifice(0, -1, 0, 0);
            }
            Side side = me ? Side.WHITE : Side.BLACK;
            java.util.Set<Square> before = Tactics.hanging(b0, side).keySet();
            Piece taken = b0.getPiece(m.getTo());
            int captured = Tactics.value(taken);
            Board b1 = b0.clone();
            b1.doMove(m);
            if (b1.isMated()) {
                return new Sacrifice(0, -1, 0, 0);
            }
            int movedNet = Tactics.see(b1, m.getTo()) - captured;
            Square newSq = null;
            int newHang = 0;
            for (Map.Entry<Square, Integer> e : Tactics.hanging(b1, side).entrySet()) {
                if (e.getKey() != m.getTo() && !before.contains(e.getKey()) && e.getValue() > newHang) {
                    newHang = e.getValue();
                    newSq = e.getKey();
                }
            }
            int min = (int) Tuning.DEFAULT.sacMin;
            Square sacSq = movedNet >= min ? m.getTo() : newSq != null && newHang - captured >= min ? newSq : null;
            int offered = sacSq == null ? 0 : sacSq == m.getTo() ? movedNet : newHang - captured;
            int regain = sacSq == null ? -1 : Tactics.regainAfterCapture(b1, sacSq);
            int value = Math.max(movedNet, newHang - captured);
            if (Tuning.DEFAULT.pieceSacrifice) {
                // SPEC v2.1: a piece (or the exchange) given for pawns is a sacrifice even if the pawns make the net
                // loss 1: pieces lost along the exchange minus pieces won, pawns not counted
                int pieceLoss = 0;
                List<Integer> seq = Tactics.seePieceSequence(b1, m.getTo());
                for (int j = 0; j < seq.size(); j++) {
                    pieceLoss += j % 2 == 0 ? seq.get(j) : -seq.get(j);
                }
                pieceLoss -= taken.getPieceType() == PieceType.PAWN ? 0 : captured;
                int moved = movedNet >= 2 || (movedNet >= 1 && pieceLoss >= 2) ? movedNet : 0;
                value = Math.max(moved >= 1 ? moved : 0, newHang - captured);
                if (moved >= 1) {
                    value = Math.max(value, 2);
                }
            }
            return new Sacrifice(value, regain, offered, movedNet);
        }
    }

    /**
     * Material (pawns) the move leaves to the opponent by static exchange: on the moved piece (minus what it took), or
     * on another piece; 0 when the move rather saves pieces that were en prise. (SPEC v1.8 §5.2)
     */
    static int sacrifice(Board b0, String uci, boolean me) {
        Move m = Tactics.find(b0, uci);
        if (m == null) {
            return 0;
        }
        Side side = me ? Side.WHITE : Side.BLACK;
        int hangingBefore = Tactics.hanging(b0, side).size();
        int captured = Tactics.value(b0.getPiece(m.getTo()));
        Board b1 = b0.clone();
        b1.doMove(m);
        if (b1.isMated()) {
            return 0;
        }
        int movedNet = Tactics.see(b1, m.getTo()) - captured;
        Map<Square, Integer> after = Tactics.hanging(b1, side);
        int other = 0;
        for (Map.Entry<Square, Integer> e : after.entrySet()) {
            if (e.getKey() != m.getTo()) {
                other = Math.max(other, e.getValue());
            }
        }
        if (after.size() < hangingBefore && movedNet < Tuning.DEFAULT.sacMin) {
            return 0; // saving pieces, not sacrificing
        }
        return Math.max(movedNet, other - captured);
    }

    /** Null when the move is a real sacrifice (SPEC §5.2), else why it is not (calibration tools). */
    static String sacrificeVerdict(Board b0, String uci, boolean me) {
        Move m = Tactics.find(b0, uci);
        if (m == null || m.getPromotion() != Piece.NONE) {
            return "promotion";
        }
        Side side = me ? Side.WHITE : Side.BLACK;
        int captured = Tactics.value(b0.getPiece(m.getTo()));
        List<Square> unsafeBefore = Tactics.unsafePieces(b0, side, 0);
        boolean movedWasTrapped = Tactics.isTrapped(b0, m.getFrom());
        Board b1 = b0.clone();
        b1.doMove(m);
        List<Square> unsafeAfter = Tactics.unsafePieces(b1, side, captured);
        if (!b1.isKingAttacked() && unsafeAfter.size() < unsafeBefore.size()) {
            return "saves pieces"; // saving pieces, not sacrificing
        }
        if (unsafeAfter.isEmpty()) {
            return "nothing en prise";
        }
        if (movedWasTrapped) {
            return "moved piece was trapped";
        }
        int trapped = 0;
        for (Square sq : unsafeAfter) {
            if (Tactics.isTrapped(b1, sq)) {
                trapped++;
            } else if (!Tactics.isFakeSacrifice(b1, sq)) {
                return null;
            }
        }
        return trapped == unsafeAfter.size() ? "trapped anyway" : "fake sacrifice";
    }

    /** True when move i captures on the square where the opponent just captured (a plain recapture). */
    private static boolean isRecapture(GameReplay replay, int i) {
        String prev = replay.uci().get(i - 1);
        String cur = replay.uci().get(i);
        if (!prev.substring(2, 4).equals(cur.substring(2, 4))) {
            return false;
        }
        Board before = board(replay.fens().get(i - 1));
        Move pm = Tactics.find(before, prev);
        return pm != null && before.getPiece(pm.getTo()) != Piece.NONE;
    }

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }
}
