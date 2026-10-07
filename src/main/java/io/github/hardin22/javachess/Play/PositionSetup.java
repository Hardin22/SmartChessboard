package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starting a game from a position: checks a FEN (typed, pasted, or built by the position editor) and explains in
 * Italian what is wrong, completes what the editor cannot know (castling rights from the king and rook squares,
 * counters), and gives the normalised FEN the game starts from.
 */
public final class PositionSetup {

    /**
     * Result of a check.
     *
     * @param fen    the normalised FEN (null when the position is not playable)
     * @param errors problems that make the position unplayable (Italian, ready to show)
     * @param notes  harmless corrections made (e.g. castling rights removed), Italian
     */
    public record Result(String fen, List<String> errors, List<String> notes) {
        public Result {
            errors = List.copyOf(errors);
            notes = List.copyOf(notes);
        }

        public boolean ok() {
            return errors.isEmpty() && fen != null;
        }
    }

    private PositionSetup() {
    }

    /** Checks a full or partial FEN ("placement", "placement w", "placement b KQ - 0 1"). */
    public static Result check(String fenText) {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (fenText == null || fenText.isBlank()) {
            return new Result(null, List.of("Posizione vuota"), List.of());
        }
        String[] f = fenText.trim().split("\\s+");
        Piece[] squares = parsePlacement(f[0], errors);
        if (squares == null) {
            return new Result(null, errors, notes);
        }
        Side toMove = Side.WHITE;
        if (f.length > 1) {
            if (f[1].equalsIgnoreCase("b")) {
                toMove = Side.BLACK;
            } else if (!f[1].equalsIgnoreCase("w")) {
                errors.add("Tratto non valido: «" + f[1] + "» (w o b)");
            }
        }
        String castling = f.length > 2 ? f[2] : null;
        String ep = f.length > 3 ? f[3] : "-";
        int halfMoves = f.length > 4 ? parseCounter(f[4], 0) : 0;
        int fullMoves = f.length > 5 ? Math.max(1, parseCounter(f[5], 1)) : 1;
        return build(squares, toMove, castling, ep, halfMoves, fullMoves, errors, notes);
    }

    /**
     * Position from the editor: {@code pieces[i]} is the piece on square i (chesslib order, A1 = 0 … H8 = 63, null
     * or NONE for empty), plus the side to move. Castling rights are given where the king and rook stand on their
     * squares.
     */
    public static Result fromEditor(Piece[] pieces, Side toMove) {
        Piece[] squares = new Piece[64];
        for (int i = 0; i < 64; i++) {
            squares[i] = pieces == null || i >= pieces.length || pieces[i] == null ? Piece.NONE : pieces[i];
        }
        return build(squares, toMove, null, "-", 0, 1, new ArrayList<>(), new ArrayList<>());
    }

    // ------------------------------------------------------------------ internals

    private static Result build(Piece[] sq, Side toMove, String castling, String ep, int halfMoves, int fullMoves,
                                List<String> errors, List<String> notes) {
        int[] kings = new int[2];
        int[] pawns = new int[2];
        int[] pieces = new int[2];
        for (int i = 0; i < 64; i++) {
            Piece p = sq[i];
            if (p == Piece.NONE) {
                continue;
            }
            int s = p.getPieceSide() == Side.WHITE ? 0 : 1;
            pieces[s]++;
            if (p.getPieceType() == PieceType.KING) {
                kings[s]++;
            } else if (p.getPieceType() == PieceType.PAWN) {
                pawns[s]++;
                int rank = i / 8;
                if (rank == 0 || rank == 7) {
                    errors.add("Pedone su " + name(i) + ": i pedoni non possono stare sulla prima o sull'ottava traversa");
                }
            }
        }
        for (int s = 0; s < 2; s++) {
            String side = s == 0 ? "il Bianco" : "il Nero";
            if (kings[s] == 0) {
                errors.add("Manca il re per " + side);
            } else if (kings[s] > 1) {
                errors.add("Troppi re per " + side + ": deve essercene uno solo");
            }
            if (pawns[s] > 8) {
                errors.add("Troppi pedoni per " + side + " (" + pawns[s] + ", massimo 8)");
            }
            if (pieces[s] > 16) {
                errors.add("Troppi pezzi per " + side + " (" + pieces[s] + ", massimo 16)");
            }
        }
        String placement = placement(sq);
        String rights = castlingRights(sq, castling, notes);
        String epSquare = enPassant(sq, toMove, ep, notes);
        String fen = placement + " " + (toMove == Side.WHITE ? "w" : "b") + " " + rights + " " + epSquare + " "
                + halfMoves + " " + fullMoves;
        if (errors.isEmpty()) {
            try {
                Board b = new Board();
                b.loadFromFen(fen);
                // the side that is not to move must not be in check (it would mean its king can be captured)
                Board other = new Board();
                other.loadFromFen(placement + " " + (toMove == Side.WHITE ? "b" : "w") + " - - 0 1");
                if (kingsTouch(sq)) {
                    errors.add("I due re non possono stare su case vicine");
                } else if (other.isKingAttacked()) {
                    errors.add((toMove == Side.WHITE ? "Il re nero" : "Il re bianco")
                            + " è sotto scacco ma non tocca a lui muovere");
                }
            } catch (RuntimeException e) {
                errors.add("Posizione non leggibile");
            }
        }
        if (!errors.isEmpty()) {
            return new Result(null, errors, notes);
        }
        return new Result(fen, errors, notes);
    }

    private static Piece[] parsePlacement(String text, List<String> errors) {
        String[] ranks = text.split("/");
        if (ranks.length != 8) {
            errors.add("La posizione deve avere 8 traverse (separate da /), ne ha " + ranks.length);
            return null;
        }
        Piece[] sq = new Piece[64];
        java.util.Arrays.fill(sq, Piece.NONE);
        for (int r = 0; r < 8; r++) {
            int rank = 7 - r;
            int file = 0;
            for (char c : ranks[r].toCharArray()) {
                if (Character.isDigit(c)) {
                    file += c - '0';
                } else {
                    Piece p;
                    try {
                        p = Piece.fromFenSymbol(String.valueOf(c));
                    } catch (RuntimeException e) {
                        p = null;
                    }
                    if (p == null || p == Piece.NONE) {
                        errors.add("Simbolo non valido nella posizione: «" + c + "»");
                        return null;
                    }
                    if (file < 8) {
                        sq[rank * 8 + file] = p;
                    }
                    file++;
                }
            }
            if (file != 8) {
                errors.add("La traversa " + (rank + 1) + " ha " + file + " case invece di 8");
                return null;
            }
        }
        return sq;
    }

    private static String placement(Piece[] sq) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                Piece p = sq[rank * 8 + file];
                if (p == Piece.NONE) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(p.getFenSymbol());
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    /** Requested rights kept only where king and rook are on their squares; null = every possible right. */
    private static String castlingRights(Piece[] sq, String requested, List<String> notes) {
        StringBuilder out = new StringBuilder();
        String want = requested == null ? "KQkq" : requested.equals("-") ? "" : requested;
        record Right(char letter, int king, int rook, Piece k, Piece r) {
        }
        List<Right> all = List.of(new Right('K', 4, 7, Piece.WHITE_KING, Piece.WHITE_ROOK),
                new Right('Q', 4, 0, Piece.WHITE_KING, Piece.WHITE_ROOK),
                new Right('k', 60, 63, Piece.BLACK_KING, Piece.BLACK_ROOK),
                new Right('q', 60, 56, Piece.BLACK_KING, Piece.BLACK_ROOK));
        List<String> dropped = new ArrayList<>();
        for (Right r : all) {
            if (want.indexOf(r.letter) < 0) {
                continue;
            }
            if (sq[r.king] == r.k && sq[r.rook] == r.r) {
                out.append(r.letter);
            } else if (requested != null) {
                dropped.add(String.valueOf(r.letter));
            }
        }
        if (!dropped.isEmpty()) {
            notes.add("Arrocco tolto (" + String.join("", dropped) + "): re o torre non sono sulla casa iniziale");
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    /** The en passant square only when a pawn just made a double step there; "-" otherwise. */
    private static String enPassant(Piece[] sq, Side toMove, String ep, List<String> notes) {
        if (ep == null || ep.equals("-")) {
            return "-";
        }
        try {
            Square target = Square.valueOf(ep.toUpperCase(Locale.ROOT));
            int i = target.ordinal();
            int rank = i / 8;
            boolean ok;
            if (toMove == Side.WHITE) {
                ok = rank == 5 && sq[i] == Piece.NONE && sq[i - 8] == Piece.BLACK_PAWN && sq[i + 8] == Piece.NONE;
            } else {
                ok = rank == 2 && sq[i] == Piece.NONE && sq[i + 8] == Piece.WHITE_PAWN && sq[i - 8] == Piece.NONE;
            }
            if (ok) {
                return ep.toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException e) {
            // unreadable square: dropped below
        }
        notes.add("Presa en passant ignorata: «" + ep + "» non è possibile");
        return "-";
    }

    private static boolean kingsTouch(Piece[] sq) {
        int wk = -1;
        int bk = -1;
        for (int i = 0; i < 64; i++) {
            if (sq[i] == Piece.WHITE_KING) {
                wk = i;
            } else if (sq[i] == Piece.BLACK_KING) {
                bk = i;
            }
        }
        return wk >= 0 && bk >= 0 && Math.abs(wk / 8 - bk / 8) <= 1 && Math.abs(wk % 8 - bk % 8) <= 1;
    }

    private static int parseCounter(String s, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(s));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String name(int i) {
        return Square.squareAt(i).name().toLowerCase(Locale.ROOT);
    }
}
