package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Utils.PgnCodec;

import java.util.List;

/**
 * Builds the full position (side to move, castling, en passant) behind a piece placement seen on the page,
 * with whatever the page tells besides the pieces, best evidence first:
 * <ol>
 *   <li>the page's move list, replayed from the start, when it ends on the same placement: exact position and the
 *       moves played so far;</li>
 *   <li>the FEN in an analysis board's URL, when it has the same placement;</li>
 *   <li>the running clock; the colour of the piece standing on the last-move highlight (the other side moves);</li>
 *   <li>the start position is white to move; otherwise the given default.</li>
 * </ol>
 * Castling rights are those still possible from the placement; an en passant square follows a highlighted double
 * pawn step.
 */
public final class SetupPosition {

    /**
     * @param board      the position
     * @param initialFen where {@code moves} start from (the position itself when the history is unknown)
     * @param moves      moves from {@code initialFen} to {@code board} (UCI), empty when unknown
     */
    public record Result(Board board, String initialFen, List<String> moves, String turnSource) {
    }

    private SetupPosition() {
    }

    /**
     * Null when the placement is not a legal chess position (two white kings, pawn on the first rank...) or not one a
     * game can reach (more pieces than promotions allow: typically a misreading).
     */
    public static Result build(String placement, BoardSnapshot.BoardView view, PageInfo page, Side defaultTurn) {
        if (placement == null || !plausibleMaterial(placement)) {
            return null;
        }
        List<String> listed = view == null ? null : view.moves();
        if (listed != null && !listed.isEmpty()) {
            PgnCodec.Replay replay = PgnCodec.replay(PgnCodec.START_FEN, listed);
            if (replay.rejectedToken() == null && placement(replay.board()).equals(placement)) {
                return new Result(replay.board(), PgnCodec.START_FEN, replay.uciMoves(), "moves");
            }
        }
        String urlFen = page == null ? null : page.fenInUrl();
        if (urlFen != null) {
            Board b = PgnCodec.boardFromFen(urlFen);
            if (b != null && placement(b).equals(placement)) {
                return new Result(b, b.getFen(), List.of(), "url");
            }
        }
        Side turn = null;
        String source = "default";
        if (view != null && view.turn() != null) {
            turn = view.turn();
            source = "clock";
        }
        Board pieces = piecesOnly(placement);
        if (pieces == null) {
            return null;
        }
        Side moved = view == null ? null : sideThatMoved(pieces, view.lastMove());
        if (turn == null && moved != null) {
            turn = moved.flip();
            source = "last move";
        }
        if (turn == null && placement.equals(START_PLACEMENT)) {
            turn = Side.WHITE;
            source = "start";
        }
        if (turn == null) {
            turn = defaultTurn == null ? Side.WHITE : defaultTurn;
        }
        Board board = withTurn(placement, view, turn);
        if (board == null && (source.equals("default") || source.equals("start"))) {
            // a guessed turn that makes the position illegal (the other king in check): the other side moves
            turn = turn.flip();
            board = withTurn(placement, view, turn);
            source = "check";
        }
        return board == null ? null : new Result(board, board.getFen(), List.of(), source);
    }

    /** The pieces of a placement on a board, without judging whose turn it could be (null when unreadable). */
    private static Board piecesOnly(String placement) {
        if (PgnCodec.boardFromFen(placement + " w - - 0 1") == null
                && PgnCodec.boardFromFen(placement + " b - - 0 1") == null) {
            return null; // not a position with either side to move (two kings of a colour, pawns on the edge...)
        }
        Board b = new Board();
        b.loadFromFen(placement + " w - - 0 1");
        return b;
    }

    private static Board withTurn(String placement, BoardSnapshot.BoardView view, Side turn) {
        Board pieces = piecesOnly(placement);
        String ep = view == null || pieces == null ? "-" : enPassantSquare(pieces, view.lastMove(), turn);
        Board board = PgnCodec.boardFromFen(placement + (turn == Side.WHITE ? " w " : " b ") + "KQkq " + ep + " 0 1");
        if (board == null && !"-".equals(ep)) {
            board = PgnCodec.boardFromFen(placement + (turn == Side.WHITE ? " w " : " b ") + "KQkq - 0 1");
        }
        return board;
    }

    static final String START_PLACEMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";

    /**
     * True when each side has at most 16 pieces and no more extra queens, rooks, bishops and knights than its
     * missing pawns could have become.
     */
    static boolean plausibleMaterial(String placement) {
        for (String side : new String[]{"PNBRQ", "pnbrq"}) {
            int[] n = new int[5];
            for (char c : placement.toCharArray()) {
                int i = side.indexOf(c);
                if (i >= 0) {
                    n[i]++;
                }
            }
            int pawns = n[0];
            int extra = Math.max(0, n[1] - 2) + Math.max(0, n[2] - 2) + Math.max(0, n[3] - 2) + Math.max(0, n[4] - 1);
            if (pawns > 8 || extra > 8 - pawns || pawns + n[1] + n[2] + n[3] + n[4] > 15) {
                return false;
            }
        }
        return true;
    }

    /** FEN placement of a board. */
    public static String placement(Board board) {
        return board.getFen().split(" ")[0];
    }

    /**
     * The side whose piece stands on a last-move square: after a move one of the two highlighted squares is empty
     * and the other holds the piece that moved (castling: both hold the mover's pieces). Null when unclear.
     */
    static Side sideThatMoved(Board board, List<String> lastMove) {
        Side side = null;
        for (String name : lastMove) {
            Square square = square(name);
            if (square == null) {
                return null;
            }
            Piece piece = board.getPiece(square);
            if (piece == null || piece == Piece.NONE) {
                continue;
            }
            if (side != null && side != piece.getPieceSide()) {
                return null;
            }
            side = piece.getPieceSide();
        }
        return side;
    }

    /** The en passant target square after a highlighted double pawn step by the side not to move, else "-". */
    static String enPassantSquare(Board board, List<String> lastMove, Side turn) {
        if (lastMove.size() != 2) {
            return "-";
        }
        Square a = square(lastMove.get(0));
        Square b = square(lastMove.get(1));
        if (a == null || b == null || a.getFile() != b.getFile()) {
            return "-";
        }
        Side mover = turn.flip();
        int fromRank = mover == Side.WHITE ? 1 : 6;
        int toRank = mover == Side.WHITE ? 3 : 4;
        for (Square[] pair : new Square[][]{{a, b}, {b, a}}) {
            if (pair[0].getRank().ordinal() == fromRank && pair[1].getRank().ordinal() == toRank) {
                Piece pawn = board.getPiece(pair[1]);
                if (pawn != null && pawn.getPieceType() == PieceType.PAWN && pawn.getPieceSide() == mover
                        && board.getPiece(pair[0]) == Piece.NONE) {
                    int middle = (fromRank + toRank) / 2;
                    return pair[0].name().substring(0, 1).toLowerCase() + (middle + 1);
                }
            }
        }
        return "-";
    }

    private static Square square(String name) {
        if (name == null || !name.matches("[a-h][1-8]")) {
            return null;
        }
        return Square.valueOf(name.toUpperCase());
    }
}
