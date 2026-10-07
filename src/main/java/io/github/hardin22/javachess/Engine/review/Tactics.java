package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static board tactics for Brilliant / Great / Blunder: static exchange evaluation, piece safety, trapped pieces and
 * material along a line ({@code research/SPEC.md} §5.2-5.5). Exchanges are simulated with legal moves, so pins and
 * x-ray attackers are handled. Boards passed in are left unchanged.
 */
final class Tactics {

    private Tactics() {
    }

    /** Material value in pawns (P=1, N=B=3, R=5, Q=9, K=100). */
    static int value(Piece p) {
        if (p == null || p == Piece.NONE) {
            return 0;
        }
        return switch (p.getPieceType()) {
            case PAWN -> 1;
            case KNIGHT, BISHOP -> 3;
            case ROOK -> 5;
            case QUEEN -> 9;
            case KING -> 100;
            default -> 0;
        };
    }

    /**
     * Static exchange evaluation: material (pawns) the side to move wins by starting a capture sequence on
     * {@code sq} with its least valuable attacker, each side free to stop; 0 when it should not capture.
     */
    static int see(Board b, Square sq) {
        Piece target = b.getPiece(sq);
        if (target == Piece.NONE) {
            return 0;
        }
        Move capture = leastValuableCapture(b, sq);
        if (capture == null) {
            return 0;
        }
        int gain = value(target);
        if (target.getPieceType() == PieceType.KING) {
            return gain;
        }
        b.doMove(capture);
        try {
            return Math.max(0, gain - see(b, sq));
        } finally {
            b.undoMove();
        }
    }

    private static Move leastValuableCapture(Board b, Square sq) {
        Move best = null;
        int bestValue = Integer.MAX_VALUE;
        for (Move m : b.legalMoves()) {
            if (m.getTo() == sq) {
                int v = value(b.getPiece(m.getFrom()));
                if (v < bestValue) {
                    bestValue = v;
                    best = m;
                }
            }
        }
        return best;
    }

    /**
     * True when the opponent of the piece on {@code sq} cannot win material by capturing it. Works whoever is to
     * move (a null move gives the opponent the move; a piece of the side in check is judged as is).
     */
    static boolean isSafe(Board b, Square sq) {
        Piece p = b.getPiece(sq);
        if (p == Piece.NONE) {
            return true;
        }
        if (b.getSideToMove() != p.getPieceSide()) {
            return see(b, sq) <= 0;
        }
        if (b.isKingAttacked()) {
            return true;
        }
        Board c = b.clone();
        c.doNullMove();
        return see(c, sq) <= 0;
    }

    /**
     * Pieces of {@code color} (no pawns or king) the opponent wins material on by static exchange if it were its move,
     * with that gain (pawns). (SPEC v1.8 §5.2: "hanging")
     */
    static Map<Square, Integer> hanging(Board b, Side color) {
        Board o = b;
        if (b.getSideToMove() == color) {
            String[] f = b.getFen().split(" ");
            f[1] = color == Side.WHITE ? "b" : "w";
            f[3] = "-";
            o = new Board();
            o.loadFromFen(String.join(" ", f));
        }
        Map<Square, Integer> out = new LinkedHashMap<>();
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) {
                continue;
            }
            Piece p = o.getPiece(sq);
            if (p == Piece.NONE || p.getPieceSide() != color || p.getPieceType() == PieceType.PAWN
                    || p.getPieceType() == PieceType.KING) {
                continue;
            }
            int g = see(o, sq);
            if (g > 0) {
                out.put(sq, g);
            }
        }
        return out;
    }

    /** Pieces of {@code color} (no pawns or king) worth more than {@code minValue} that are not safe. */
    static List<Square> unsafePieces(Board b, Side color, int minValue) {
        List<Square> out = new ArrayList<>();
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) {
                continue;
            }
            Piece p = b.getPiece(sq);
            if (p == Piece.NONE || p.getPieceSide() != color) {
                continue;
            }
            PieceType t = p.getPieceType();
            if (t == PieceType.PAWN || t == PieceType.KING || value(p) <= minValue) {
                continue;
            }
            if (!isSafe(b, sq)) {
                out.add(sq);
            }
        }
        return out;
    }

    /** True when the piece on {@code sq} is unsafe and no move of it reaches a safe square (or wins as much). */
    static boolean isTrapped(Board b, Square sq) {
        Piece p = b.getPiece(sq);
        if (p == Piece.NONE || isSafe(b, sq)) {
            return false;
        }
        Board c = b.clone();
        if (c.getSideToMove() != p.getPieceSide()) {
            if (c.isKingAttacked()) {
                return false;
            }
            c.doNullMove();
        }
        for (Move m : c.legalMoves()) {
            if (m.getFrom() != sq) {
                continue;
            }
            int captured = value(c.getPiece(m.getTo()));
            c.doMove(m);
            boolean safe = see(c, m.getTo()) <= captured;
            c.undoMove();
            if (safe) {
                return false;
            }
        }
        return true;
    }

    /** Material of {@code side} minus the opponent, in pawns. */
    static int material(Board b, Side side) {
        int sum = 0;
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) {
                continue;
            }
            Piece p = b.getPiece(sq);
            if (p != Piece.NONE && p.getPieceType() != PieceType.KING) {
                sum += p.getPieceSide() == side ? value(p) : -value(p);
            }
        }
        return sum;
    }

    /**
     * Material balance of {@code side} after following {@code line} (UCI) from {@code fen}: at most {@code maxPlies}
     * plies, stopping early at a quiet point (two plies without a capture). Illegal tails are ignored.
     */
    static int materialAfter(String fen, List<String> line, Side side, int maxPlies) {
        Board b = new Board();
        b.loadFromFen(fen);
        int quiet = 0;
        for (int i = 0; i < Math.min(maxPlies, line.size()); i++) {
            Move m = find(b, line.get(i));
            if (m == null) {
                break;
            }
            boolean capture = b.getPiece(m.getTo()) != Piece.NONE || isEnPassant(b, m);
            b.doMove(m);
            quiet = capture ? 0 : quiet + 1;
            if (quiet >= 2 && i >= 1) {
                break;
            }
        }
        return material(b, side);
    }

    /**
     * True when taking the piece on {@code sq} (opponent to move) lets its owner immediately win back as much (a
     * capture worth at least the piece by static exchange): a trade, not a sacrifice. (WintrChess also treats "taking
     * allows mate in one" as fake; measured on the Chessigma brilliant benchmark it only lost true Brilliants.)
     */
    static boolean isFakeSacrifice(Board b1, Square sq) {
        return isFakeSacrifice(b1, sq, FAKE_MODE);
    }

    /**
     * Calibration: 0 = any immediate win-back worth the piece makes a sacrifice fake, 1 = only taking back on the same
     * square, 2 = never (default). On the chess.com Brilliants (Chessigma benchmark + labelled games, 108 moves) the
     * win-back test rejected 24 real Brilliants for 9 good non-Brilliant moves: chess.com counts a piece left en prise
     * as a sacrifice even when the material comes back at once.
     */
    static final int FAKE_MODE = Integer.getInteger("javachess.review.fakeMode", 2);

    static boolean isFakeSacrifice(Board b1, Square sq, int mode) {
        if (mode == 2) {
            return false;
        }
        Piece p = b1.getPiece(sq);
        if (p == Piece.NONE || b1.getSideToMove() == p.getPieceSide()) {
            return false;
        }
        Move take = leastValuableCapture(b1, sq);
        if (take == null) {
            return true;
        }
        Board b = b1.clone();
        b.doMove(take);
        int value = value(p);
        for (Move m : b.legalMoves()) {
            if (mode == 1 && m.getTo() != sq) {
                continue;
            }
            if (b.getPiece(m.getTo()) != Piece.NONE && see(b, m.getTo()) >= value) {
                return true;
            }
        }
        return false;
    }

    static Move find(Board b, String uci) {
        try {
            for (Move m : b.legalMoves()) {
                if (m.toString().equals(uci)) {
                    return m;
                }
            }
        } catch (RuntimeException ignored) {
            // invalid position
        }
        return null;
    }

    private static boolean isEnPassant(Board b, Move m) {
        Piece p = b.getPiece(m.getFrom());
        return p.getPieceType() == PieceType.PAWN && m.getFrom().getFile() != m.getTo().getFile();
    }
}
