package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a player wants to know after the review, computed from the (frozen) review result without touching the
 * classification: accuracy by phase (opening / middlegame / endgame), the key moments of the game, and the details
 * of each move (best move and best line in the interface notation, evaluations, win chance lost).
 */
public final class ReviewInsights {

    /** Game phases. */
    public enum Phase {
        OPENING("Apertura"), MIDDLEGAME("Mediogioco"), ENDGAME("Finale");

        private final String italian;

        Phase(String italian) {
            this.italian = italian;
        }

        /** Italian name for the interface. */
        public String italian() {
            return italian;
        }
    }

    /** Qualitative verdict of a phase, for an icon (chess.com style) next to the number. */
    public enum Grade {
        EXCELLENT("Ottima"), GOOD("Buona"), FAIR("Discreta"), POOR("Da rivedere"), NONE("—");

        private final String italian;

        Grade(String italian) {
            this.italian = italian;
        }

        public String italian() {
            return italian;
        }

        static Grade of(double accuracy, int moves) {
            if (moves == 0 || Double.isNaN(accuracy)) {
                return NONE;
            }
            if (accuracy >= 90) {
                return EXCELLENT;
            }
            if (accuracy >= 80) {
                return GOOD;
            }
            if (accuracy >= 65) {
                return FAIR;
            }
            return POOR;
        }
    }

    /**
     * One side's play in one phase.
     *
     * @param phase      the phase
     * @param white      the side
     * @param moves      counted moves of that side in the phase (book moves excluded)
     * @param accuracy   mean accuracy of those moves (0..100), NaN when none
     * @param inaccuracies, mistakes, blunders, misses  counts of the bad labels
     * @param grade      verdict for an icon
     */
    public record PhaseScore(Phase phase, boolean white, int moves, double accuracy, int inaccuracies, int mistakes,
                             int blunders, int misses, Grade grade) {

        /** "86,4" (Italian decimal comma) or "—" when the side made no counted move in the phase. */
        public String accuracyText() {
            return moves == 0 || Double.isNaN(accuracy) ? "—" : String.format(Locale.ITALIAN, "%.1f", accuracy);
        }
    }

    /**
     * Phases of the game.
     *
     * @param middlegameStart ply index (0-based, of the move) where the middlegame starts, or -1 if never reached
     * @param endgameStart    ply index where the endgame starts, or -1 if never reached
     * @param white           White's scores, one per phase reached (in order)
     * @param black           Black's scores
     */
    public record PhaseSummary(int middlegameStart, int endgameStart, List<PhaseScore> white, List<PhaseScore> black) {

        public PhaseSummary {
            white = List.copyOf(white);
            black = List.copyOf(black);
        }

        /** Phase of the move with 0-based index {@code ply}. */
        public Phase phaseOf(int ply) {
            if (endgameStart >= 0 && ply >= endgameStart) {
                return Phase.ENDGAME;
            }
            if (middlegameStart >= 0 && ply >= middlegameStart) {
                return Phase.MIDDLEGAME;
            }
            return Phase.OPENING;
        }
    }

    /** Kind of key moment. */
    public enum MomentKind {
        /** A mistake, blunder or missed win: the mover lost a lot of winning chances. */
        ERROR,
        /** A brilliant or great move. */
        GREAT_MOVE
    }

    /**
     * A moment worth looking at again.
     *
     * @param ply        0-based index of the move in the game (the review screen shows it as ply + 1)
     * @param white      who played it
     * @param kind       error or great move
     * @param label      the review label
     * @param moveText   "18. Dxb7" (Italian)
     * @param bestText   best move "18. Cf5" (Italian), empty when the move was the best
     * @param swing      win chance lost by the mover, 0..1 (0 for great moves)
     * @param swingText  "−32%" for errors, empty for great moves
     */
    public record KeyMoment(int ply, boolean white, MomentKind kind, MoveClassification label, String moveText,
                            String bestText, double swing, String swingText) {
    }

    /**
     * Everything about one reviewed move, ready to show.
     *
     * @param ply           0-based index of the move
     * @param white         who played it
     * @param label         review label
     * @param moveText      "12. Cf3"
     * @param played        played move (UCI)
     * @param best          best move (UCI) or null
     * @param bestText      "12. Ce5" or empty when unknown or equal to the played move
     * @param bestLine      best line from the position before the move (UCI), possibly empty
     * @param bestLineText  "12. Ce5 Cxe5 13. dxe5" or empty
     * @param evalBefore    evaluation with the best move, White POV ("+0.80")
     * @param evalAfter     evaluation after the played move, White POV ("−1.20")
     * @param winLoss       win chance lost by the mover (0..1)
     * @param showBest      true when the interface should offer "Mostra la mossa migliore" (a bad move with a
     *                      different best move)
     * @param book          true for a book move
     */
    public record MoveInsight(int ply, boolean white, MoveClassification label, String moveText, String played,
                              String best, String bestText, List<String> bestLine, String bestLineText,
                              String evalBefore, String evalAfter, double winLoss, boolean showBest, boolean book) {
        public MoveInsight {
            bestLine = List.copyOf(bestLine);
        }
    }

    /** Labels of moves the player should look at again. */
    public static boolean isBad(MoveClassification c) {
        return c == MoveClassification.INACCURACY || c == MoveClassification.MISTAKE
                || c == MoveClassification.BLUNDER || c == MoveClassification.MISS;
    }

    private static final int LINE_PLIES = 10;

    private ReviewInsights() {
    }

    // ------------------------------------------------------------------ per move

    /** Details of move {@code ply} (0-based), or null when out of range. */
    public static MoveInsight move(GameReview review, int ply) {
        if (review == null || ply < 0 || ply >= review.moves().size()) {
            return null;
        }
        MoveReview m = review.moves().get(ply);
        String best = m.bestMove();
        List<String> line = !m.bestLine().isEmpty() ? m.bestLine() : best == null ? List.of() : List.of(best);
        boolean different = best != null && !best.equals(m.uci());
        String bestText = different ? MoveText.numbered(m.fenBefore(), best) : "";
        String lineText = different && !line.isEmpty() ? MoveText.line(m.fenBefore(), line, LINE_PLIES) : "";
        return new MoveInsight(ply, m.whiteMoved(), m.label(), MoveText.numbered(m.fenBefore(), m.uci()), m.uci(),
                best, bestText, line, lineText, evalText(m.before()), evalText(m.after()), m.winLoss(),
                different && isBad(m.label()), m.label() == MoveClassification.BOOK_MOVE);
    }

    static String evalText(Eval e) {
        if (e == null) {
            return "";
        }
        return switch (e.kind()) {
            case CP -> MoveText.pawns(e.value());
            case WHITE_MATES -> e.value() == 0 ? "1-0" : "M" + e.value();
            case BLACK_MATES -> e.value() == 0 ? "0-1" : MoveText.MINUS + "M" + e.value();
        };
    }

    // ------------------------------------------------------------------ key moments

    /**
     * Mistakes, blunders and missed wins of both players (or of one side), and brilliant/great moves, in game
     * order. {@code side}: null for both, TRUE for White, FALSE for Black.
     */
    public static List<KeyMoment> keyMoments(GameReview review, Boolean side) {
        List<KeyMoment> out = new ArrayList<>();
        if (review == null) {
            return out;
        }
        for (MoveReview m : review.moves()) {
            if (side != null && m.whiteMoved() != side) {
                continue;
            }
            MoveClassification c = m.label();
            MomentKind kind;
            if (c == MoveClassification.MISTAKE || c == MoveClassification.BLUNDER || c == MoveClassification.MISS) {
                kind = MomentKind.ERROR;
            } else if (c == MoveClassification.BRILLIANT || c == MoveClassification.GREAT) {
                kind = MomentKind.GREAT_MOVE;
            } else {
                continue;
            }
            String best = m.bestMove();
            String bestText = kind == MomentKind.ERROR && best != null && !best.equals(m.uci())
                    ? MoveText.numbered(m.fenBefore(), best) : "";
            double swing = kind == MomentKind.ERROR ? m.winLoss() : 0;
            String swingText = kind == MomentKind.ERROR
                    ? MoveText.MINUS + String.valueOf(Math.round(swing * 100)) + "%" : "";
            out.add(new KeyMoment(m.ply(), m.whiteMoved(), kind, c, MoveText.numbered(m.fenBefore(), m.uci()),
                    bestText, swing, swingText));
        }
        return out;
    }

    // ------------------------------------------------------------------ phases

    /** Accuracy by phase for both players. */
    public static PhaseSummary phases(GameReview review) {
        if (review == null || review.moves().isEmpty()) {
            return new PhaseSummary(-1, -1, List.of(), List.of());
        }
        int mid = -1;
        int end = -1;
        List<MoveReview> moves = review.moves();
        for (int i = 0; i < moves.size(); i++) {
            Board b = new Board();
            try {
                b.loadFromFen(moves.get(i).fenBefore());
            } catch (RuntimeException e) {
                continue;
            }
            if (end < 0 && isEndgame(b)) {
                end = i;
                if (mid < 0) {
                    mid = i;
                }
                break;
            }
            if (mid < 0 && isMiddlegame(b, i)) {
                mid = i;
            }
        }
        PhaseSummary frame = new PhaseSummary(mid, end, List.of(), List.of());
        return new PhaseSummary(mid, end, scores(moves, frame, true), scores(moves, frame, false));
    }

    private static List<PhaseScore> scores(List<MoveReview> moves, PhaseSummary frame, boolean white) {
        Map<Phase, double[]> acc = new EnumMap<>(Phase.class); // sum, n, inacc, mistakes, blunders, misses
        Map<Phase, Boolean> reached = new EnumMap<>(Phase.class);
        for (int i = 0; i < moves.size(); i++) {
            Phase p = frame.phaseOf(i);
            reached.put(p, true);
            MoveReview m = moves.get(i);
            if (m.whiteMoved() != white) {
                continue;
            }
            double[] a = acc.computeIfAbsent(p, k -> new double[6]);
            MoveClassification c = m.label();
            if (c != MoveClassification.BOOK_MOVE) {
                a[0] += m.accuracy();
                a[1]++;
            }
            if (c == MoveClassification.INACCURACY) {
                a[2]++;
            } else if (c == MoveClassification.MISTAKE) {
                a[3]++;
            } else if (c == MoveClassification.BLUNDER) {
                a[4]++;
            } else if (c == MoveClassification.MISS) {
                a[5]++;
            }
        }
        List<PhaseScore> out = new ArrayList<>();
        for (Phase p : Phase.values()) {
            if (!reached.containsKey(p)) {
                continue;
            }
            double[] a = acc.getOrDefault(p, new double[6]);
            int n = (int) a[1];
            double mean = n == 0 ? Double.NaN : Math.round(a[0] / n * 10) / 10.0;
            out.add(new PhaseScore(p, white, n, mean, (int) a[2], (int) a[3], (int) a[4], (int) a[5],
                    Grade.of(mean, n)));
        }
        return out;
    }

    /** Queens, rooks, bishops and knights of both sides. */
    static int majorsAndMinors(Board b) {
        int n = 0;
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) {
                continue;
            }
            Piece p = b.getPiece(sq);
            if (p == Piece.NONE) {
                continue;
            }
            PieceType t = p.getPieceType();
            if (t != PieceType.PAWN && t != PieceType.KING) {
                n++;
            }
        }
        return n;
    }

    /** Endgame (lichess "Divider" rule): six or fewer queens, rooks and minor pieces left. */
    static boolean isEndgame(Board b) {
        return majorsAndMinors(b) <= 6;
    }

    /**
     * Middlegame (simplified lichess "Divider"): ten or fewer major and minor pieces, or one side's back rank thinned
     * out (fewer than four pieces on it), or past move 20.
     */
    static boolean isMiddlegame(Board b, int ply) {
        return majorsAndMinors(b) <= 10 || backRankSparse(b, Side.WHITE) || backRankSparse(b, Side.BLACK)
                || ply >= 40;
    }

    private static boolean backRankSparse(Board b, Side side) {
        int rank = side == Side.WHITE ? 0 : 7;
        int pieces = 0;
        for (int file = 0; file < 8; file++) {
            Piece p = b.getPiece(Square.squareAt(rank * 8 + file));
            if (p != Piece.NONE && p.getPieceSide() == side) {
                pieces++;
            }
        }
        return pieces < 4;
    }
}
