package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.Engine.MoveQuality;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Move classification shared by the game review ({@link #classifyGame}, full: Book, Brilliant, Great, Miss) and
 * the board LEDs ({@link #fast}, no MultiPV and no history). Pure functions: no engine, no I/O.
 *
 * <p>Every evaluation is an {@link Eval} (White's point of view, explicit mates), so the side to move, the sign of
 * a score and "mate 0" can no longer be confused. A move that delivers checkmate is always Best (or better); a move
 * that allows a forced mate that did not exist before is always a Blunder.</p>
 */
public final class ReviewClassifier {

    /** Win chance loss thresholds (0..1), upper bounds of each label. */
    public static final double EXCELLENT_MAX = 0.02;
    public static final double GOOD_MAX = 0.05;
    public static final double INACCURACY_MAX = 0.10;
    public static final double MISTAKE_MAX = 0.20;

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
        return new FastVerdict(standardLabel(best, played, whiteMoved, playedIsBest), wb, wa, Math.max(0, wb - wa));
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
     * Best / Excellent / Good / Inaccuracy / Mistake / Blunder from the two evaluations, with the mate rules.
     * Used by both the fast and the full classification.
     */
    static MoveClassification standardLabel(Eval best, Eval played, boolean whiteMoved, boolean playedIsBest) {
        boolean me = whiteMoved;
        if (played.isCheckmate() && played.isMateFor(me)) {
            return MoveClassification.BEST; // delivered mate
        }
        if (playedIsBest) {
            return MoveClassification.BEST;
        }
        // forced mate for the mover before the move
        if (best.isMateFor(me)) {
            if (played.isMateFor(me)) {
                int optimal = Math.max(0, best.mateIn() - 1);
                int extra = played.mateIn() - optimal;
                if (extra <= 0) {
                    return MoveClassification.BEST;
                }
                return extra <= 2 ? MoveClassification.EXCELLENT : MoveClassification.GOOD;
            }
            // the mate is gone: judged on win chance below
        }
        // the mover was already getting mated
        if (best.isMateAgainst(me)) {
            if (played.isMateAgainst(me)) {
                return played.mateIn() >= best.mateIn() ? MoveClassification.BEST : MoveClassification.GOOD;
            }
            return MoveClassification.BEST; // the engine found a better defence than its own best line
        }
        // allowed a forced mate that did not exist
        if (played.isMateAgainst(me)) {
            return MoveClassification.BLUNDER;
        }
        double loss = Math.max(0, best.winChance(me) - played.winChance(me));
        return labelForLoss(loss);
    }

    /** Label for a win chance loss (0..1). */
    public static MoveClassification labelForLoss(double loss) {
        if (loss <= 0.0) {
            return MoveClassification.BEST;
        }
        if (loss <= EXCELLENT_MAX) {
            return MoveClassification.EXCELLENT;
        }
        if (loss <= GOOD_MAX) {
            return MoveClassification.GOOD;
        }
        if (loss <= INACCURACY_MAX) {
            return MoveClassification.INACCURACY;
        }
        if (loss <= MISTAKE_MAX) {
            return MoveClassification.MISTAKE;
        }
        return MoveClassification.BLUNDER;
    }

    // ------------------------------------------------------------------------------------------
    // Full (game review)

    /**
     * Positions (index i = position before move i) whose second best line is needed to decide Great / Brilliant /
     * Miss. The reviewer re-searches only those with MultiPV 2; all the others keep their single line.
     */
    public static BitSet needsSecondLine(ReviewInput in) {
        BitSet need = new BitSet(in.uciMoves().size());
        for (int i = 0; i < in.uciMoves().size(); i++) {
            PositionEval before = in.positions().get(i);
            if (before.terminal() || before.secondBest() != null) {
                continue;
            }
            // only the engine's own choice can be Great/Brilliant
            if (in.uciMoves().get(i).equals(before.bestMove())) {
                need.set(i);
            }
        }
        return need;
    }

    /** Classifies every move of the game and computes the accuracy of both players. */
    public static GameReview classifyGame(ReviewInput in) {
        GameReplay replay = GameReplay.of(in.initialFen(), in.uciMoves());
        List<MoveReview> out = new ArrayList<>(in.uciMoves().size());
        boolean inBook = true;
        String opening = null;
        for (int i = 0; i < in.uciMoves().size(); i++) {
            String uci = in.uciMoves().get(i);
            String fenBefore = replay.fens().get(i);
            PositionEval before = in.positions().get(i);
            PositionEval after = in.positions().get(i + 1);
            boolean white = before.whiteToMove();
            Eval best = before.eval();
            Eval played = playedEval(before, after, uci);
            boolean playedIsBest = uci.equals(before.bestMove());

            MoveClassification label = null;
            if (inBook) {
                String name = in.book().nameAfter(replay.fens().get(i + 1)).orElse(null);
                if (name != null) {
                    label = MoveClassification.BOOK_MOVE;
                    opening = name;
                } else {
                    inBook = false;
                }
            }
            if (label == null && replay.legalMoveCounts().get(i) == 1) {
                label = MoveClassification.FORCED;
            }
            if (label == null) {
                label = standardLabel(best, played, white, playedIsBest);
            }
            double wb = best.winChance(white);
            double wa = played.winChance(white);
            EngineLine bestLine = before.best();
            out.add(new MoveReview(i, uci, replay.sans().get(i), fenBefore, white, label, best, played,
                    before.bestMove(), bestLine == null ? List.of() : bestLine.pv(), wb, wa,
                    Accuracy.moveAccuracy(wb, wa)));
        }
        return new GameReview(in.initialFen(), out, in.positions(), Accuracy.forPlayer(out, true),
                Accuracy.forPlayer(out, false), opening, GameReview.Stats.NONE);
    }

    /**
     * Evaluation after the played move: the evaluation of the next position, except when the played move is one of
     * the lines searched in the position before at least as deep (then its own line is used, so a best move is never
     * penalised by search noise between two positions).
     */
    static Eval playedEval(PositionEval before, PositionEval after, String uci) {
        if (after.terminal()) {
            return after.eval();
        }
        EngineLine own = before.lineFor(uci);
        if (own != null && uci.equals(before.bestMove())) {
            return own.eval();
        }
        return after.eval();
    }
}
