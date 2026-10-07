package io.github.hardin22.javachess.Components;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;

import java.util.ArrayList;
import java.util.List;

/** Standard algebraic notation helpers for the UI: "8. c3 O-O 9. h3 Bb7", "8... Bb7 9. h3". */
public final class Notation {

    private Notation() {
    }

    /** SAN of the UCI moves played from {@code fen}; stops at the first illegal move. Falls back to UCI. */
    public static List<String> toSan(String fen, List<String> uciMoves, int maxPlies) {
        List<String> result = new ArrayList<>();
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            MoveList list = new MoveList(fen);
            for (String uci : uciMoves) {
                if (list.size() >= maxPlies) {
                    break;
                }
                if (!isLegal(board, uci)) {
                    break;
                }
                Move move = new Move(uci, board.getSideToMove());
                board.doMove(move);
                list.add(move);
            }
            for (String san : list.toSanArray()) {
                result.add(castling(san));
            }
        } catch (Exception e) {
            for (int i = 0; i < Math.min(maxPlies, uciMoves.size()); i++) {
                result.add(uciMoves.get(i));
            }
        }
        return result;
    }

    private static boolean isLegal(Board board, String uci) {
        try {
            for (Move legal : board.legalMoves()) {
                if (legal.toString().equalsIgnoreCase(uci)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** Numbered line from a position: white moves get "N.", a line starting with black gets "N...". */
    public static String line(String fen, List<String> uciMoves, int maxPlies) {
        List<String> san = toSan(fen, uciMoves, maxPlies);
        String[] f = fen == null ? new String[0] : fen.split(" ");
        boolean white = f.length < 2 || "w".equals(f[1]);
        int number = 1;
        try {
            number = Integer.parseInt(f[5]);
        } catch (RuntimeException ignored) {
            // FEN without counters
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < san.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (white) {
                sb.append(number).append(". ");
            } else if (i == 0) {
                sb.append(number).append("... ");
            }
            sb.append(san.get(i));
            if (!white) {
                number++;
            }
            white = !white;
        }
        return sb.toString();
    }

    /** Castling with the letter O, as in printed notation (some sources use zeros). */
    public static String castling(String san) {
        return san.replace("0-0-0", "O-O-O").replace("0-0", "O-O");
    }
}
