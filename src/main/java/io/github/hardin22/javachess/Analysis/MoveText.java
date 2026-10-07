package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import io.github.hardin22.javachess.Engine.Score;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Move and evaluation texts for the analysis features, in the notation the interface uses: SAN with Italian piece
 * letters (C cavallo, A alfiere, T torre, D donna, R re), "12. Cf3 Cc6", "12… Ad6", evaluations "+0.35",
 * "−1.20" (true minus sign), "M3", "−M2".
 */
public final class MoveText {

    /** Typographic minus used in evaluations (a hyphen looks too short next to digits). */
    public static final char MINUS = '−';
    /** Ellipsis after the move number of a line that starts with Black ("12… Ad6"). */
    public static final String BLACK_DOTS = "…";

    private MoveText() {
    }

    /** SAN (English letters) of {@code uciMoves} played from {@code fen}; stops at the first illegal move. */
    public static List<String> san(String fen, List<String> uciMoves, int maxPlies) {
        List<String> out = new ArrayList<>();
        if (fen == null || uciMoves == null) {
            return out;
        }
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            MoveList list = new MoveList(fen);
            for (String uci : uciMoves) {
                if (list.size() >= maxPlies) {
                    break;
                }
                Move move = legal(board, uci);
                if (move == null) {
                    break;
                }
                board.doMove(move);
                list.add(move);
            }
            for (String s : list.toSanArray()) {
                out.add(s.replace("0-0-0", "O-O-O").replace("0-0", "O-O"));
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /** The legal move of {@code board} written {@code uci} (promotion letter optional = queen), or null. */
    public static Move legal(Board board, String uci) {
        if (uci == null || uci.length() < 4) {
            return null;
        }
        String u = uci.trim().toLowerCase(Locale.ROOT);
        Move queenFallback = null;
        try {
            for (Move m : board.legalMoves()) {
                String s = m.toString();
                if (s.equals(u)) {
                    return m;
                }
                if (u.length() == 4 && s.length() == 5 && s.startsWith(u) && s.endsWith("q")) {
                    queenFallback = m;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return queenFallback;
    }

    /** SAN of one move from {@code fen} (English letters), or the UCI text when it is not legal there. */
    public static String sanOf(String fen, String uci) {
        List<String> s = san(fen, List.of(uci), 1);
        return s.isEmpty() ? uci : s.get(0);
    }

    /**
     * Italian piece letters: N→C, B→A, R→T, Q→D, K→R, also after "=" in promotions; files, captures, checks and
     * castling are unchanged ("Nxe5+" → "Cxe5+", "e8=Q" → "e8=D").
     */
    public static String italian(String san) {
        if (san == null || san.isEmpty()) {
            return san;
        }
        StringBuilder out = new StringBuilder(san.length());
        for (int i = 0; i < san.length(); i++) {
            char c = san.charAt(i);
            boolean pieceLetter = (i == 0 || san.charAt(i - 1) == '=' || san.charAt(i - 1) == ' ')
                    && "NBRQK".indexOf(c) >= 0;
            out.append(pieceLetter ? switch (c) {
                case 'N' -> 'C';
                case 'B' -> 'A';
                case 'R' -> 'T';
                case 'Q' -> 'D';
                default -> 'R';
            } : c);
        }
        return out.toString();
    }

    /** Full move number of the position (6th FEN field, 1 when missing). */
    public static int moveNumber(String fen) {
        try {
            return Integer.parseInt(fen.trim().split("\\s+")[5]);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** True when White is to move in {@code fen}. */
    public static boolean whiteToMove(String fen) {
        String[] f = fen == null ? new String[0] : fen.trim().split("\\s+");
        return f.length < 2 || !"b".equals(f[1]);
    }

    /** "12. Cf3" or "12… Cf6": the move {@code uci} played in {@code fen}, numbered, Italian letters. */
    public static String numbered(String fen, String uci) {
        return number(fen) + italian(sanOf(fen, uci));
    }

    /** "12. " when White is to move in {@code fen}, "12… " when Black is. */
    public static String number(String fen) {
        return moveNumber(fen) + (whiteToMove(fen) ? ". " : BLACK_DOTS + " ");
    }

    /**
     * Numbered line from {@code fen} with Italian letters: "12. Cf3 Cc6 13. d4", "12… Cc6 13. d4". At most
     * {@code maxPlies} moves; stops at the first illegal move.
     */
    public static String line(String fen, List<String> uciMoves, int maxPlies) {
        List<String> san = san(fen, uciMoves, maxPlies);
        StringBuilder sb = new StringBuilder();
        boolean white = whiteToMove(fen);
        int number = moveNumber(fen);
        for (int i = 0; i < san.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (white) {
                sb.append(number).append(". ");
            } else if (i == 0) {
                sb.append(number).append(BLACK_DOTS).append(' ');
            }
            sb.append(italian(san.get(i)));
            if (!white) {
                number++;
            }
            white = !white;
        }
        return sb.toString();
    }

    /**
     * Evaluation from White's point of view as shown in the interface: "+0.35", "−1.20", "0.00", "M3" (White
     * mates in 3), "−M2" (Black mates in 2), "1-0" / "0-1" for a position already checkmated.
     */
    public static String eval(Score whiteScore) {
        if (whiteScore == null) {
            return "";
        }
        if (whiteScore.mate()) {
            if (whiteScore.value() == 0) {
                return whiteScore.isWinningMate() ? "1-0" : "0-1";
            }
            return whiteScore.value() > 0 ? "M" + whiteScore.value() : MINUS + "M" + (-whiteScore.value());
        }
        return pawns(whiteScore.value());
    }

    /** Centipawns (White POV) as "+0.35", "−1.20", "0.00". */
    public static String pawns(int whiteCentipawns) {
        if (Math.abs(whiteCentipawns) < 1) {
            return "0.00";
        }
        String abs = String.format(Locale.ROOT, "%.2f", Math.abs(whiteCentipawns) / 100.0);
        return (whiteCentipawns > 0 ? "+" : String.valueOf(MINUS)) + abs;
    }
}
