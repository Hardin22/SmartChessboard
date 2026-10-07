package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Engine.Score;

import java.util.Locale;
import java.util.Optional;

/**
 * A position evaluation from WHITE's point of view, with mates that can never be misread.
 *
 * <p>UCI scores are relative to the side to move and {@code score mate 0} ("the side to move is checkmated") has no
 * sign, so negating it to change point of view silently turns a delivered mate into a suffered one. This type stores
 * who mates instead of a signed number: convert a UCI score once with {@link #fromUci(Score, boolean)} and never
 * negate scores by hand again.</p>
 *
 * @param kind  {@link Kind#CP}, {@link Kind#WHITE_MATES} or {@link Kind#BLACK_MATES}
 * @param value centipawns from White's point of view for CP; moves to mate (&ge; 0) otherwise, where 0 means the
 *              position is already checkmate
 */
public record Eval(Kind kind, int value) {

    public enum Kind { CP, WHITE_MATES, BLACK_MATES }

    /** Draw by stalemate, insufficient material or repetition. */
    public static final Eval DRAW = new Eval(Kind.CP, 0);

    public Eval {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        if (kind != Kind.CP && value < 0) {
            throw new IllegalArgumentException("moves to mate must be >= 0: " + value);
        }
    }

    public static Eval cp(int whiteCentipawns) {
        return new Eval(Kind.CP, whiteCentipawns);
    }

    public static Eval whiteMates(int moves) {
        return new Eval(Kind.WHITE_MATES, moves);
    }

    public static Eval blackMates(int moves) {
        return new Eval(Kind.BLACK_MATES, moves);
    }

    /** Mate in {@code moves} for the given side. */
    public static Eval mateFor(boolean white, int moves) {
        return white ? whiteMates(moves) : blackMates(moves);
    }

    /**
     * Converts a UCI score of a position to this White point of view.
     *
     * @param s           UCI score, side to move point of view ({@code mate 0} or {@code mate -N}: side to move is mated)
     * @param whiteToMove side to move of the position the score belongs to
     */
    public static Eval fromUci(Score s, boolean whiteToMove) {
        if (!s.mate()) {
            return cp(whiteToMove ? s.value() : -s.value());
        }
        boolean sideToMoveWins = s.value() > 0;
        return mateFor(whiteToMove == sideToMoveWins, Math.abs(s.value()));
    }

    /**
     * Evaluation of a position with no legal move (checkmate or stalemate) or a dead draw by insufficient material;
     * empty when the game goes on and an engine is needed.
     */
    public static Optional<Eval> terminal(Board board) {
        boolean noMoves;
        try {
            noMoves = board.legalMoves().isEmpty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (noMoves) {
            if (board.isKingAttacked()) {
                // the side to move is checkmated: the other side won
                return Optional.of(mateFor(board.getSideToMove() != com.github.bhlangonijr.chesslib.Side.WHITE, 0));
            }
            return Optional.of(DRAW);
        }
        if (board.isInsufficientMaterial()) {
            return Optional.of(DRAW);
        }
        return Optional.empty();
    }

    /** {@link #terminal(Board)} for a FEN. */
    public static Optional<Eval> terminal(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return terminal(b);
    }

    public boolean isMate() {
        return kind != Kind.CP;
    }

    /** True when the position is checkmate (someone already won). */
    public boolean isCheckmate() {
        return isMate() && value == 0;
    }

    /** True when {@code white} (or Black) mates by force (or already mated the opponent). */
    public boolean isMateFor(boolean white) {
        return kind == (white ? Kind.WHITE_MATES : Kind.BLACK_MATES);
    }

    /** True when the other side of {@code white} mates by force. */
    public boolean isMateAgainst(boolean white) {
        return isMateFor(!white);
    }

    /** Moves to mate for mate scores, 0 for CP. */
    public int mateIn() {
        return isMate() ? value : 0;
    }

    /**
     * Centipawns from the point of view of {@code white} (or Black). Mates map to a large value that still prefers
     * the shorter mate: {@code +/-(100_000 - 100 * moves)}.
     */
    public int cpFor(boolean white) {
        int w;
        if (kind == Kind.CP) {
            w = value;
        } else {
            int m = 100_000 - 100 * Math.min(value, 900);
            w = kind == Kind.WHITE_MATES ? m : -m;
        }
        return white ? w : -w;
    }

    /** Win chance (0..1) of {@code white} (or Black), see {@link WinModel}. */
    public double winChance(boolean white) {
        return WinModel.winChance(this, white);
    }

    /** Back to a UCI score for the given side to move (mate 0 only for the side that is checkmated). */
    public Score toUci(boolean whiteToMove) {
        if (kind == Kind.CP) {
            return Score.cp(whiteToMove ? value : -value);
        }
        boolean sideToMoveWins = isMateFor(whiteToMove);
        if (value == 0) {
            // UCI cannot express "the side to move has delivered mate"; only the mated side gets mate 0.
            return sideToMoveWins ? Score.mate(1) : Score.mate(0);
        }
        return Score.mate(sideToMoveWins ? value : -value);
    }

    /**
     * Legacy value used by the eval bar and graph: pawns from White's point of view, mate encoded as
     * {@code +/-(1000 - N)} (checkmate = +/-1000).
     */
    public double legacyPawns() {
        if (kind == Kind.CP) {
            return value / 100.0;
        }
        double m = 1000.0 - value;
        return kind == Kind.WHITE_MATES ? m : -m;
    }

    /** "+0.35", "-1.20", "M3" (White mates), "-M2" (Black mates), "1-0" / "0-1" for a checkmate. */
    public String format() {
        return switch (kind) {
            case CP -> String.format(Locale.ROOT, "%+.2f", value / 100.0);
            case WHITE_MATES -> value == 0 ? "1-0" : "M" + value;
            case BLACK_MATES -> value == 0 ? "0-1" : "-M" + value;
        };
    }

    @Override
    public String toString() {
        return format();
    }
}
