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
import java.util.List;

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

    /** Win chance loss thresholds (0..1): a label applies below its bound. */
    public static final double EXCELLENT_MAX = 0.02;
    public static final double GOOD_MAX = 0.05;
    public static final double INACCURACY_MAX = 0.10;
    public static final double MISTAKE_MAX = 0.20;

    /** Second best line this good (cp, mover POV) means the position was winning anyway: no Great/Brilliant. */
    static final int WINNING_ANYWAY_CP = 700;
    /** Brilliant/Great: the mover must not stand worse than this after the move (chess.com allows about equal). */
    static final double CRITICAL_MIN_EP = 0.40;
    /** Great: the second best move loses at least this much win chance. */
    static final double GREAT_GAP = 0.10;
    /** Miss: the opponent's previous move lost at least this much. */
    static final double MISS_OPPONENT_LOSS = 0.10;
    /** Miss: the mover ends no worse than before the opponent's error, within this tolerance. */
    static final double MISS_NO_WORSE = 0.05;
    /** A Blunder must lose at least this much material (pawns) along the line, or allow mate. */
    static final int BLUNDER_MATERIAL = 2;
    /** MultiPV 2 is only worth it in this win chance range (outside, no Great/Brilliant is possible). */
    static final double SECOND_LINE_MIN_EP = 0.20;
    static final double SECOND_LINE_MAX_EP = 0.97;
    /** A terminal draw reached from this win chance or more gives the game away (Blunder, never Miss). */
    static final double GIVE_AWAY_DRAW_EP = 0.6;
    /** Book labels stop after this many plies. */
    static final int BOOK_MAX_PLY = 30;

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
        return new FastVerdict(baseLabel(best, played, whiteMoved, playedIsBest), wb, wa, Math.max(0, wb - wa));
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
        if (played.isCheckmate() && played.isMateFor(me)) {
            return MoveClassification.BEST; // R1: delivering mate
        }
        if (isTop) {
            return MoveClassification.BEST; // R3
        }
        double loss = Math.max(0, best.winChance(me) - played.winChance(me));
        // R4: mate -> mate
        if (best.isMateFor(me) && played.isMateFor(me)) {
            int d = played.mateIn() - (best.mateIn() - 1);
            return d <= 0 ? MoveClassification.BEST : d <= 1 ? MoveClassification.EXCELLENT
                    : d <= 6 ? MoveClassification.GOOD : MoveClassification.INACCURACY;
        }
        if (best.isMateAgainst(me) && played.isMateAgainst(me)) {
            int n = best.mateIn();
            int m = played.mateIn();
            return m >= n ? MoveClassification.BEST
                    : n - m == 1 ? MoveClassification.EXCELLENT : MoveClassification.GOOD;
        }
        if (best.isMateFor(me) && played.isMateAgainst(me)) {
            return MoveClassification.BLUNDER;
        }
        // R5: forced mate lost
        if (best.isMateFor(me) && !played.isMate()) {
            int c = played.cpFor(me);
            return c >= 800 ? MoveClassification.EXCELLENT : c >= 400 ? MoveClassification.GOOD
                    : c >= 200 ? MoveClassification.INACCURACY : c > 0 ? MoveClassification.MISTAKE
                    : MoveClassification.BLUNDER;
        }
        // R6: forced mate conceded
        if (!best.isMateAgainst(me) && played.isMateAgainst(me)) {
            double before = best.winChance(me);
            MoveClassification floor = before >= 0.20 ? MoveClassification.BLUNDER
                    : before >= 0.05 ? MoveClassification.MISTAKE : MoveClassification.INACCURACY;
            return worst(labelForLoss(loss), floor);
        }
        return labelForLoss(loss); // R7
    }

    /** Label for a win chance loss (0..1). */
    public static MoveClassification labelForLoss(double loss) {
        if (loss <= 0.0) {
            return MoveClassification.BEST;
        }
        if (loss < EXCELLENT_MAX) {
            return MoveClassification.EXCELLENT;
        }
        if (loss < GOOD_MAX) {
            return MoveClassification.GOOD;
        }
        if (loss < INACCURACY_MAX) {
            return MoveClassification.INACCURACY;
        }
        if (loss < MISTAKE_MAX) {
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
        GameReplay replay = GameReplay.of(in.initialFen(), in.uciMoves());
        int n = replay.uci().size();
        List<PositionEval> pos = in.positions();
        double[] epBefore = new double[n];
        double[] epAfter = new double[n];
        Eval[] played = new Eval[n];
        for (int i = 0; i < n; i++) {
            PositionEval p0 = pos.get(i);
            boolean me = p0.whiteToMove();
            played[i] = playedEval(p0, pos.get(i + 1), replay.uci().get(i));
            epBefore[i] = p0.eval().winChance(me);
            epAfter[i] = played[i].winChance(me);
        }
        double[][] acc = Accuracy.perMove(in.initialFen(), pos);

        List<MoveReview> out = new ArrayList<>(n);
        boolean inBook = true;
        String opening = null;
        for (int i = 0; i < n; i++) {
            String uci = replay.uci().get(i);
            PositionEval p0 = pos.get(i);
            boolean me = p0.whiteToMove();
            Eval best = p0.eval();
            boolean isTop = uci.equals(p0.bestMove());

            MoveClassification label = null;
            if (inBook && i < BOOK_MAX_PLY) {
                String name = in.book().nameAfter(replay.fens().get(i + 1)).orElse(null);
                if (name != null) {
                    label = MoveClassification.BOOK_MOVE;
                    opening = name;
                }
            }
            if (label == null) {
                inBook = false;
            }
            boolean mates = played[i].isCheckmate() && played[i].isMateFor(me);
            if (label == null && !mates && replay.legalMoveCounts().get(i) == 1) {
                label = MoveClassification.FORCED;
            }
            if (label == null) {
                label = baseLabel(best, played[i], me, isTop);
                boolean drawn = pos.get(i + 1).terminal() && !played[i].isMate();
                if (drawn && epBefore[i] >= GIVE_AWAY_DRAW_EP) {
                    label = MoveClassification.BLUNDER; // stalemate (or dead draw) from a winning position
                }
                if (label == MoveClassification.MISTAKE || label == MoveClassification.BLUNDER) {
                    boolean gives = givesSomethingAway(replay.fens().get(i), uci, p0, pos.get(i + 1), played[i], me,
                            epBefore[i]);
                    if (!gives && missOpportunity(i, pos, played, epBefore, epAfter, me)) {
                        label = MoveClassification.MISS;
                    } else if (label == MoveClassification.BLUNDER && !gives) {
                        label = MoveClassification.MISTAKE;
                    }
                }
                if ((label == MoveClassification.BEST || label == MoveClassification.EXCELLENT) && !mates) {
                    MoveClassification special = special(label, isTop, i, replay, p0, pos.get(i + 1), played[i], epBefore[i],
                            epAfter[i], me);
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
                                           double[] epAfter, boolean me) {
        Eval best = pos.get(i).eval();
        if (i > 0) {
            double oppLoss = Math.max(0, epBefore[i - 1] - epAfter[i - 1]);
            boolean opportunity = oppLoss >= MISS_OPPONENT_LOSS || best.isMateFor(me);
            double epBeforeOpp = pos.get(i - 1).eval().winChance(me);
            if (opportunity && epAfter[i] >= epBeforeOpp - MISS_NO_WORSE) {
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
                                                     Eval played, boolean me, double epBefore) {
        if (played.isMateAgainst(me)) {
            return true;
        }
        if (p1.terminal() && !played.isMate() && epBefore >= GIVE_AWAY_DRAW_EP) {
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
        return mBest - mPlayed >= BLUNDER_MATERIAL;
    }

    /**
     * SPEC §5.2-5.3: Brilliant or Great, null when neither applies. The alternative that must not be winning anyway
     * is the second line when the played move is the engine's choice, the engine's choice otherwise (a sacrifice the
     * engine found only at a deeper search than ours).
     */
    private static MoveClassification special(MoveClassification label, boolean isTop, int i, GameReplay replay,
                                              PositionEval p0, PositionEval p1, Eval played, double epBefore, double epAfter,
                                              boolean me) {
        EngineLine second = p0.secondBest();
        Eval alternative = isTop ? (second == null ? null : second.eval()) : p0.eval();
        if (alternative == null) {
            return null; // no MultiPV here: the reviewer judged it not worth it
        }
        Board b0 = board(replay.fens().get(i));
        String uci = replay.uci().get(i);
        if (!candidate(b0, uci, alternative, epAfter, me)) {
            return null;
        }
        if (brilliant(b0, uci, me)) {
            return MoveClassification.BRILLIANT;
        }
        if (label != MoveClassification.BEST || !isTop || played.isMateFor(me)) {
            return null;
        }
        Move m = Tactics.find(b0, uci);
        if (m != null && b0.getPiece(m.getTo()) != Piece.NONE && !Tactics.isSafe(b0, m.getTo())) {
            return null; // taking a hanging piece is not "great"
        }
        if (i > 0 && isRecapture(replay, i)) {
            return null;
        }
        double gap = epBefore - second.eval().winChance(me);
        return gap >= GREAT_GAP ? MoveClassification.GREAT : null;
    }

    /** Shared precondition of Brilliant and Great (WintrChess "critical candidate"). */
    private static boolean candidate(Board b0, String uci, Eval alternative, double epAfter, boolean me) {
        if (b0.isKingAttacked() || uci.endsWith("q")) {
            return false;
        }
        if (alternative.isMateFor(me) || (!alternative.isMate() && alternative.cpFor(me) >= WINNING_ANYWAY_CP)) {
            return false; // winning anyway
        }
        return epAfter >= CRITICAL_MIN_EP;
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
