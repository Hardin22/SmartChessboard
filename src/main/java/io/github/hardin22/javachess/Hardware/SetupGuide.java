package io.github.hardin22.javachess.Hardware;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Piece-by-piece guide to set up a position on the board. The sensors only see where pieces are, not which ones,
 * so lighting every empty square of a puzzle or an analysis position does not tell the player what goes where.
 * The guide splits the position into groups of identical pieces (the white king, the black rooks, the white
 * pawns...) and asks for one group at a time: only its empty squares light up and the message names the piece
 * ("Posiziona le Torri bianche: a1, f1 · passo 3 di 9"). Pieces of later groups may be placed early: they are
 * simply not asked for any more.
 *
 * <p>The standard starting position and small changes (a few squares) do not need it: everybody knows where the
 * pieces start, and one or two squares are clear from the LEDs.</p>
 */
public final class SetupGuide {

    /** At most this many empty squares are shown all together, without steps. */
    public static final int SMALL_SETUP = 3;

    private static final String START_PLACEMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";
    /** Kings first, then the pieces by value, pawns last; white before black for each kind. */
    private static final PieceType[] ORDER = {PieceType.KING, PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP,
            PieceType.KNIGHT, PieceType.PAWN};

    /** One group of identical pieces: which squares it occupies in the target. */
    public record Group(Piece piece, long squares) {
    }

    /**
     * The step to show now.
     *
     * @param index   1-based number of the current group among the groups of the position
     * @param total   number of groups in the position
     * @param piece   the piece asked for
     * @param missing its squares still empty (the ones to light)
     * @param message Italian instruction, starting with "Posiziona" like the other set-up messages
     */
    public record Step(int index, int total, Piece piece, long missing, String message) {
        /** The instruction without the step count: "Posiziona le Torri bianche: a1, f1". */
        public String instruction() {
            int cut = message.lastIndexOf(" · passo ");
            return cut < 0 ? message : message.substring(0, cut);
        }
    }

    private final List<Group> groups;

    public SetupGuide(Board target) {
        List<Group> list = new ArrayList<>();
        for (PieceType type : ORDER) {
            for (Side side : new Side[]{Side.WHITE, Side.BLACK}) {
                Piece piece = Piece.make(side, type);
                long squares = 0;
                for (int sq = 0; sq < 64; sq++) {
                    if (target.getPiece(Square.squareAt(sq)) == piece) {
                        squares |= Squares.bit(sq);
                    }
                }
                if (squares != 0) {
                    list.add(new Group(piece, squares));
                }
            }
        }
        this.groups = List.copyOf(list);
    }

    public List<Group> groups() {
        return groups;
    }

    /**
     * True when the guide is worth using to go from {@code physical} to {@code target}: not the starting
     * position, and more than {@link #SMALL_SETUP} empty squares to fill.
     */
    public static boolean worthGuiding(Board target, long physical) {
        String placement = target.getFen().split(" ")[0];
        if (placement.equals(START_PLACEMENT)) {
            return false;
        }
        long occupied = occupancy(target);
        return Long.bitCount(occupied & ~physical) > SMALL_SETUP;
    }

    /** The first group with empty squares, or null when every square of the target is occupied. */
    public Step step(long physical) {
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            long missing = group.squares() & ~physical;
            if (missing != 0) {
                return new Step(i + 1, groups.size(), group.piece(), missing,
                        message(group.piece(), missing, i + 1, groups.size()));
            }
        }
        return null;
    }

    /** "Posiziona il Re bianco in g1 · passo 1 di 9", "Posiziona i Pedoni neri: a7, b7, f7 · passo 9 di 9". */
    static String message(Piece piece, long missing, int index, int total) {
        StringBuilder text = new StringBuilder("Posiziona ");
        int count = Long.bitCount(missing);
        text.append(name(piece, count > 1));
        text.append(count == 1 ? " in " : ": ");
        boolean first = true;
        for (long bits = missing; bits != 0; bits &= bits - 1) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(Squares.name(Long.numberOfTrailingZeros(bits)).toLowerCase(Locale.ROOT));
        }
        return text.append(" · passo ").append(index).append(" di ").append(total).toString();
    }

    /** "il Re bianco", "la Donna nera", "le Torri bianche", "gli Alfieri neri", "l'Alfiere bianco". */
    public static String name(Piece piece, boolean plural) {
        boolean white = piece.getPieceSide() == Side.WHITE;
        PieceType type = piece.getPieceType();
        boolean feminine = type == PieceType.QUEEN || type == PieceType.ROOK;
        String noun = switch (type) {
            case KING -> "Re";
            case QUEEN -> plural ? "Donne" : "Donna";
            case ROOK -> plural ? "Torri" : "Torre";
            case BISHOP -> plural ? "Alfieri" : "Alfiere";
            case KNIGHT -> plural ? "Cavalli" : "Cavallo";
            default -> plural ? "Pedoni" : "Pedone";
        };
        String article;
        if (plural) {
            article = feminine ? "le " : (type == PieceType.BISHOP ? "gli " : "i ");
        } else {
            article = feminine ? "la " : (type == PieceType.BISHOP ? "l'" : "il ");
        }
        String colour;
        if (feminine) {
            colour = white ? (plural ? "bianche" : "bianca") : (plural ? "nere" : "nera");
        } else {
            colour = white ? (plural ? "bianchi" : "bianco") : (plural ? "neri" : "nero");
        }
        return article + noun + " " + colour;
    }

    private static long occupancy(Board board) {
        long bits = 0;
        for (int sq = 0; sq < 64; sq++) {
            if (board.getPiece(Square.squareAt(sq)) != Piece.NONE) {
                bits |= Squares.bit(sq);
            }
        }
        return bits;
    }
}
