package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A game replayed from its moves: the legal prefix of the moves, their SAN, every position and the number of legal
 * moves in each. Accepts UCI ("e2e4", "e7e8q") and SAN ("e4", "Nxf7+", "O-O", "e8=Q#") tokens, mixed with move
 * numbers, comments in braces and results, which are skipped. Stops at the first unreadable or illegal move.
 *
 * @param initialFen      starting position
 * @param uci             legal moves in UCI
 * @param sans            the same moves in SAN
 * @param fens            positions: {@code fens.size() == uci.size() + 1}
 * @param legalMoveCounts legal moves available before each played move
 */
public record GameReplay(String initialFen, List<String> uci, List<String> sans, List<String> fens,
                         List<Integer> legalMoveCounts) {

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final Pattern SKIP = Pattern.compile("\\d+\\.+|\\d+\\.\\.\\.|1-0|0-1|1/2-1/2|\\*|\\$\\d+");

    public GameReplay {
        uci = List.copyOf(uci);
        sans = List.copyOf(sans);
        fens = List.copyOf(fens);
        legalMoveCounts = List.copyOf(legalMoveCounts);
    }

    /** Replays whitespace separated move tokens (UCI or SAN, PGN movetext accepted). */
    public static GameReplay of(String initialFen, String movetext) {
        String text = movetext == null ? "" : movetext.replaceAll("\\{[^}]*}", " ").replaceAll("\\([^)]*\\)", " ");
        return of(initialFen, List.of(text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+")));
    }

    /** Replays move tokens (UCI or SAN). */
    public static GameReplay of(String initialFen, List<String> tokens) {
        String start = initialFen == null || initialFen.isBlank() ? START_FEN : initialFen;
        Board board = new Board();
        board.loadFromFen(start);
        List<String> uci = new ArrayList<>();
        List<String> sans = new ArrayList<>();
        List<String> fens = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        fens.add(board.getFen());
        for (String raw : tokens) {
            String token = stripMoveNumber(raw.trim());
            if (token.isEmpty() || SKIP.matcher(token).matches()) {
                continue;
            }
            List<Move> legal;
            try {
                legal = board.legalMoves();
            } catch (RuntimeException e) {
                break;
            }
            Move m = parse(board, token, legal);
            if (m == null) {
                break;
            }
            String san = san(board, m);
            counts.add(legal.size());
            board.doMove(m);
            uci.add(m.toString());
            sans.add(san);
            fens.add(board.getFen());
        }
        return new GameReplay(start, uci, sans, fens, counts);
    }

    /** "12.e4" → "e4", "12...e5" → "e5". */
    private static String stripMoveNumber(String t) {
        int dot = t.lastIndexOf('.');
        if (dot >= 0 && dot < t.length() - 1 && Character.isDigit(t.charAt(0))) {
            return t.substring(dot + 1);
        }
        return t;
    }

    private static Move parse(Board board, String token, List<Move> legal) {
        String t = token.replaceAll("[!?]+$", "");
        if (t.matches("[a-h][1-8][a-h][1-8][qrbnQRBN]?")) {
            for (Move m : legal) {
                if (m.toString().equalsIgnoreCase(t)) {
                    return m;
                }
            }
            // chesslib writes promotions in lower case, castling as king moves: compare loosely
            return null;
        }
        String norm = t.replace("0-0-0", "O-O-O").replace("0-0", "O-O").replaceAll("[+#]", "").replace("e.p.", "");
        for (Move m : legal) {
            String s = san(board, m).replaceAll("[+#]", "");
            if (s.equals(norm)) {
                return m;
            }
        }
        // pawn promotions without '=' ("e8Q") and redundant disambiguation ("Ngf3" when unique)
        for (Move m : legal) {
            String s = san(board, m).replaceAll("[+#]", "");
            if (s.replace("=", "").equals(norm.replace("=", ""))) {
                return m;
            }
        }
        return null;
    }

    /** SAN of a legal move in {@code board} (board unchanged). */
    public static String san(Board board, Move m) {
        try {
            MoveList ml = new MoveList(board.getFen());
            ml.add(m);
            String[] arr = ml.toSanArray();
            return arr.length == 1 ? arr[0] : m.toString();
        } catch (RuntimeException e) {
            return m.toString();
        }
    }

    /** Material value of a piece type in centipawns (P=1, N=B=3, R=5, Q=9), 0 for kings and NONE. */
    static int value(Piece p) {
        if (p == null || p == Piece.NONE) {
            return 0;
        }
        return switch (p.getPieceType()) {
            case PAWN -> 100;
            case KNIGHT, BISHOP -> 300;
            case ROOK -> 500;
            case QUEEN -> 900;
            default -> 0;
        };
    }

    /** Material balance (centipawns) of {@code side} minus the opponent. */
    static int material(Board board, Side side) {
        int sum = 0;
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) {
                continue;
            }
            Piece p = board.getPiece(sq);
            if (p != Piece.NONE) {
                sum += p.getPieceSide() == side ? value(p) : -value(p);
            }
        }
        return sum;
    }
}
