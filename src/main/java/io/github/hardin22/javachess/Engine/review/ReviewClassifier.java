package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
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
        /** Brilliant may also come from a Good move (a sacrifice our shallower search undervalues), SPEC v1.5. */
        final boolean brilliantFromGood;
        /** Great: the second best move loses at least this much win chance. */
        final double greatGap;
        /** Great after an opponent's error (oppLoss >= the Miss threshold): smaller gap to the second best move. */
        final double greatPunishGap;
        /** Great only from about equal to clearly better positions (chess.com: 10th-90th percentile 0.49-0.94). */
        final double greatMinEp;
        final double greatMaxEp;
        /** Not Great: taking a hanging piece, a plain recapture. */
        final boolean greatFilters;
        /** Great after an opponent's error: the opponent's previous move lost at least this much. */
        final double greatOpponentLoss;
        /** Miss: the opponent's previous move lost at least this much. */
        final double missOpponentLoss;
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
        /** Book also covers this many plies after the last named position when each loses less than ... */
        final int bookExtend;
        /** ... this win chance. */
        final double bookExtendLoss;
        /**
         * Win chance curve: logistic slope per centipawn for an unknown or 1500 rating ... (chess.com's expected
         * points depend on the rating: on its labels the best slope is ~0.0015 under 1000 and ~0.007 over 2000)
         */
        final double slope;
        /** ... multiplied by exp(slopeRating * (rating - 1500) / 1000) when the mover's rating is known. */
        final double slopeRating;

        private Tuning(Map<String, Double> overrides) {
            this.overrides = Map.copyOf(overrides);
            excellentMax = get("excellent", 0.02);
            goodMax = get("good", 0.05);
            inaccuracyMax = get("inaccuracy", 0.10);
            mistakeMax = get("mistake", 0.20);
            winningAnywayCp = get("winningAnywayCp", 700);
            criticalMinEp = get("criticalMinEp", 0.40);
            brilliantFromGood = get("brilliantFromGood", 1) != 0;
            greatGap = get("greatGap", 0.20);
            greatPunishGap = get("greatPunishGap", 0.10);
            greatMinEp = get("greatMinEp", 0.45);
            greatMaxEp = get("greatMaxEp", 0.95);
            greatFilters = get("greatFilters", 1) != 0;
            missOpponentLoss = get("missOpponentLoss", 0.08);
            greatOpponentLoss = get("greatOpponentLoss", 0.05);
            missNoWorse = get("missNoWorse", 0.10);
            blunderAnywayLoss = get("blunderAnyway", 0.30);
            blunderMaterial = get("blunderMaterial", 2);
            giveAwayDrawEp = get("giveAwayDrawEp", 0.6);
            bookMaxGap = (int) get("bookMaxGap", 4);
            bookMaxPly = (int) get("bookMaxPly", 20);
            lostDrop = get("lostDrop", 150);
            lostEval = get("lostEval", 400);
            bookExtend = (int) get("bookExtend", 2);
            bookExtendLoss = get("bookExtendLoss", 0.02);
            slope = get("slope", 0.0035);
            slopeRating = get("slopeRating", 0.5);
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
            return rating <= 0 ? slope : slope * Math.exp(slopeRating * (rating - 1500) / 1000.0);
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
        double wb = best.winChance(whiteMoved);
        double wa = played.winChance(whiteMoved);
        return new FastVerdict(baseLabel(best, played, whiteMoved, playedIsBest, false, Tuning.DEFAULT,
                WinModel.SLOPE), wb, wa,
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
        return baseLabel(best, played, me, isTop, false, Tuning.DEFAULT, WinModel.SLOPE);
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
            return m == 1 && n >= 3 ? MoveClassification.INACCURACY : MoveClassification.EXCELLENT;
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
            if (b.isKingAttacked() || uci.endsWith("q")) {
                continue;
            }
            double ep = before.eval().winChance(me);
            boolean greatRange = ep >= SECOND_LINE_MIN_EP && ep <= SECOND_LINE_MAX_EP && !before.eval().isMate();
            // a sacrifice can be Brilliant even when it mates: only the second line tells if it was needed
            if (greatRange || (!before.eval().isMateAgainst(me) && brilliant(b, uci, me))) {
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
            String name = in.book().nameAfter(replay.fens().get(i + 1)).orElse(null);
            if (name != null) {
                bookEnd = i;
                opening = name;
            } else if (i - bookEnd > t.bookMaxGap) {
                break;
            }
        }
        // chess.com's book is larger than the named openings: the few accurate moves right after the last named
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
                if (t.lostDrop > 0 && severity(label) < severity(MoveClassification.MISTAKE) && !best.isMate()
                        && !played[i].isMate() && best.cpFor(me) <= -t.lostEval
                        && best.cpFor(me) - played[i].cpFor(me) >= t.lostDrop) {
                    // chess.com calls a Mistake what makes a lost position clearly worse, although the win chance
                    // hardly changes (43 labelled games: 21 of 29 moves losing 2+ pawns from -5 or worse)
                    label = MoveClassification.MISTAKE;
                }
                if (label == MoveClassification.MISTAKE || label == MoveClassification.BLUNDER) {
                    boolean gives = givesSomethingAway(replay.fens().get(i), uci, p0, pos.get(i + 1), played[i], me,
                            epBefore[i], t);
                    if (!gives && missOpportunity(i, pos, played, epBefore, epAfter, me, t, me ? kWhite : kBlack)) {
                        label = MoveClassification.MISS;
                    } else if (label == MoveClassification.BLUNDER && !gives
                            && epBefore[i] - epAfter[i] < t.blunderAnywayLoss) {
                        label = MoveClassification.MISTAKE;
                    }
                }
                boolean nearBest = label == MoveClassification.BEST || label == MoveClassification.EXCELLENT
                        || (t.brilliantFromGood && label == MoveClassification.GOOD);
                if (nearBest && !mates) {
                    double oppLoss = i > 0 ? Math.max(0, epBefore[i - 1] - epAfter[i - 1]) : 0;
                    MoveClassification special = special(label, isTop, i, replay, p0, pos.get(i + 1), played[i],
                            epBefore[i], epAfter[i], me, oppLoss, t, me ? kWhite : kBlack);
                    if (special != null) {
                        label = special;
                    }
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
                                              Tuning t, double k) {
        EngineLine second = p0.secondBest();
        Eval alternative = isTop ? (second == null ? null : second.eval()) : p0.eval();
        if (alternative == null) {
            return null; // no MultiPV here: the reviewer judged it not worth it
        }
        Board b0 = board(replay.fens().get(i));
        String uci = replay.uci().get(i);
        if (!candidate(b0, uci, alternative, epAfter, me, t)) {
            return null;
        }
        if (brilliant(b0, uci, me)) {
            return MoveClassification.BRILLIANT;
        }
        if (label != MoveClassification.BEST || !isTop || played.isMateFor(me)) {
            return null;
        }
        if (epBefore < t.greatMinEp || epBefore > t.greatMaxEp) {
            return null;
        }
        if (t.greatFilters) {
            Move m = Tactics.find(b0, uci);
            if (m != null && b0.getPiece(m.getTo()) != Piece.NONE && !Tactics.isSafe(b0, m.getTo())) {
                return null; // taking a hanging piece is not "great"
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

    /** Shared precondition of Brilliant and Great (WintrChess "critical candidate"). */
    private static boolean candidate(Board b0, String uci, Eval alternative, double epAfter, boolean me,
                                     Tuning t) {
        if (b0.isKingAttacked() || uci.endsWith("q")) {
            return false;
        }
        if (alternative.isMateFor(me) || (!alternative.isMate() && alternative.cpFor(me) >= t.winningAnywayCp)) {
            return false; // winning anyway
        }
        return epAfter >= t.criticalMinEp;
    }

    /** SPEC §5.2: the move leaves a piece en prise that is not simply lost (a sound sacrifice). */
    private static boolean brilliant(Board b0, String uci, boolean me) {
        Move m = Tactics.find(b0, uci);
        if (m == null || m.getPromotion() != Piece.NONE) {
            return false;
        }
        Side side = me ? Side.WHITE : Side.BLACK;
        int captured = Tactics.value(b0.getPiece(m.getTo()));
        List<Square> unsafeBefore = Tactics.unsafePieces(b0, side, 0);
        boolean movedWasTrapped = Tactics.isTrapped(b0, m.getFrom());
        Board b1 = b0.clone();
        b1.doMove(m);
        List<Square> unsafeAfter = Tactics.unsafePieces(b1, side, captured);
        if (!b1.isKingAttacked() && unsafeAfter.size() < unsafeBefore.size()) {
            return false; // saving pieces, not sacrificing
        }
        if (unsafeAfter.isEmpty() || movedWasTrapped) {
            return false;
        }
        int real = 0;
        for (Square sq : unsafeAfter) {
            if (!Tactics.isTrapped(b1, sq) && !Tactics.isFakeSacrifice(b1, sq)) {
                real++;
            }
        }
        return real > 0;
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
