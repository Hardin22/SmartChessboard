package org.example.javachess.Utils;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chess notation helpers built on chesslib: UCI and SAN move conversion, FEN validation and PGN
 * reading/writing. All methods are pure (no I/O) and thread-safe.
 */
public final class PgnCodec {

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final Pattern UCI = Pattern.compile("^[a-h][1-8][a-h][1-8][qrbnQRBN]?$");
    private static final Pattern TAG = Pattern.compile("^\\[(\\w+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\]\\s*$");
    private static final Pattern RESULT = Pattern.compile("^(1-0|0-1|1/2-1/2|\\*)$");
    private static final String[] SEVEN_TAG_ROSTER = {"Event", "Site", "Date", "Round", "White", "Black", "Result"};

    private PgnCodec() {
    }

    // ------------------------------------------------------------------ FEN

    /** Board loaded from a FEN, or null when the FEN is malformed or the position is not playable. */
    public static Board boardFromFen(String fen) {
        if (fen == null || fen.isBlank()) {
            return null;
        }
        String[] parts = fen.trim().split("\\s+");
        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) {
            return null;
        }
        int whiteKings = 0;
        int blackKings = 0;
        for (int r = 0; r < 8; r++) {
            int files = 0;
            for (char c : ranks[r].toCharArray()) {
                if (Character.isDigit(c)) {
                    files += c - '0';
                } else if ("pnbrqkPNBRQK".indexOf(c) >= 0) {
                    files++;
                    if (c == 'K') {
                        whiteKings++;
                    } else if (c == 'k') {
                        blackKings++;
                    } else if ((c == 'p' || c == 'P') && (r == 0 || r == 7)) {
                        return null; // pawn on the first or last rank
                    }
                } else {
                    return null;
                }
            }
            if (files != 8) {
                return null;
            }
        }
        if (whiteKings != 1 || blackKings != 1) {
            return null;
        }
        String side = parts.length > 1 && parts[1].equals("b") ? "b" : "w";
        String castling = sanitizeCastling(ranks, parts.length > 2 ? parts[2] : "-");
        String ep = parts.length > 3 && parts[3].matches("[a-h][36]") ? parts[3] : "-";
        String half = parts.length > 4 && parts[4].matches("\\d+") ? parts[4] : "0";
        String full = parts.length > 5 && parts[5].matches("[1-9]\\d*") ? parts[5] : "1";
        String normalized = parts[0] + " " + side + " " + castling + " " + ep + " " + half + " " + full;
        try {
            Board board = new Board();
            board.loadFromFen(normalized);
            // The side that is not to move must not be in check.
            Board probe = board.clone();
            probe.setSideToMove(board.getSideToMove().flip());
            if (probe.isKingAttacked()) {
                return null;
            }
            return board;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Keeps only the castling rights compatible with the king and rook squares (vision FENs claim KQkq). */
    private static String sanitizeCastling(String[] ranks, String castling) {
        char[][] grid = new char[8][8];
        for (int r = 0; r < 8; r++) {
            int f = 0;
            for (char c : ranks[r].toCharArray()) {
                if (Character.isDigit(c)) {
                    for (int k = 0; k < c - '0'; k++) {
                        grid[r][f++] = '.';
                    }
                } else {
                    grid[r][f++] = c;
                }
            }
        }
        StringBuilder out = new StringBuilder();
        if (castling.indexOf('K') >= 0 && grid[7][4] == 'K' && grid[7][7] == 'R') {
            out.append('K');
        }
        if (castling.indexOf('Q') >= 0 && grid[7][4] == 'K' && grid[7][0] == 'R') {
            out.append('Q');
        }
        if (castling.indexOf('k') >= 0 && grid[0][4] == 'k' && grid[0][7] == 'r') {
            out.append('k');
        }
        if (castling.indexOf('q') >= 0 && grid[0][4] == 'k' && grid[0][0] == 'r') {
            out.append('q');
        }
        return out.isEmpty() ? "-" : out.toString();
    }

    public static boolean isValidFen(String fen) {
        return boardFromFen(fen) != null;
    }

    // ------------------------------------------------------------------ UCI

    public static boolean looksLikeUci(String token) {
        return token != null && UCI.matcher(token).matches();
    }

    /**
     * Legal move matching a UCI string in the given position, or null. A missing promotion letter on a
     * promoting pawn move means queen (some engines and old archives omit it).
     */
    public static Move fromUci(Board board, String uci) {
        if (!looksLikeUci(uci)) {
            return null;
        }
        String u = uci.toLowerCase(Locale.ROOT);
        Square from = Square.valueOf(u.substring(0, 2).toUpperCase(Locale.ROOT));
        Square to = Square.valueOf(u.substring(2, 4).toUpperCase(Locale.ROOT));
        PieceType promo = u.length() == 5 ? promotionType(u.charAt(4)) : null;
        Move fallback = null;
        for (Move m : MoveGenerator.generateLegalMoves(board)) {
            if (m.getFrom() != from || m.getTo() != to) {
                continue;
            }
            Piece p = m.getPromotion();
            if (p == null || p == Piece.NONE) {
                return m;
            }
            if (promo != null && p.getPieceType() == promo) {
                return m;
            }
            if (promo == null && p.getPieceType() == PieceType.QUEEN) {
                fallback = m;
            }
        }
        return fallback;
    }

    public static String toUci(Move move) {
        String s = move.getFrom().value().toLowerCase(Locale.ROOT) + move.getTo().value().toLowerCase(Locale.ROOT);
        Piece promo = move.getPromotion();
        if (promo != null && promo != Piece.NONE) {
            s += Character.toLowerCase(pieceLetter(promo.getPieceType()));
        }
        return s;
    }

    private static PieceType promotionType(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'q' -> PieceType.QUEEN;
            case 'r' -> PieceType.ROOK;
            case 'b' -> PieceType.BISHOP;
            case 'n' -> PieceType.KNIGHT;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ SAN

    /** Standard algebraic notation of a legal move (with +/# suffix). The board is not modified. */
    public static String toSan(Board board, Move move) {
        Piece piece = board.getPiece(move.getFrom());
        PieceType type = piece.getPieceType();
        StringBuilder san = new StringBuilder();
        if (type == PieceType.KING && Math.abs(fileOf(move.getFrom()) - fileOf(move.getTo())) == 2) {
            san.append(fileOf(move.getTo()) > fileOf(move.getFrom()) ? "O-O" : "O-O-O");
        } else {
            boolean capture = board.getPiece(move.getTo()) != Piece.NONE
                    || (type == PieceType.PAWN && fileOf(move.getFrom()) != fileOf(move.getTo()));
            if (type == PieceType.PAWN) {
                if (capture) {
                    san.append(move.getFrom().value().toLowerCase(Locale.ROOT).charAt(0));
                }
            } else {
                san.append(pieceLetter(type));
                san.append(disambiguation(board, move, piece));
            }
            if (capture) {
                san.append('x');
            }
            san.append(move.getTo().value().toLowerCase(Locale.ROOT));
            Piece promo = move.getPromotion();
            if (promo != null && promo != Piece.NONE) {
                san.append('=').append(pieceLetter(promo.getPieceType()));
            }
        }
        Board after = board.clone();
        after.doMove(move);
        if (after.isMated()) {
            san.append('#');
        } else if (after.isKingAttacked()) {
            san.append('+');
        }
        return san.toString();
    }

    private static String disambiguation(Board board, Move move, Piece piece) {
        boolean ambiguous = false;
        boolean sameFile = false;
        boolean sameRank = false;
        for (Move other : MoveGenerator.generateLegalMoves(board)) {
            if (other.getTo() != move.getTo() || other.getFrom() == move.getFrom()
                    || board.getPiece(other.getFrom()) != piece) {
                continue;
            }
            ambiguous = true;
            sameFile |= fileOf(other.getFrom()) == fileOf(move.getFrom());
            sameRank |= rankOf(other.getFrom()) == rankOf(move.getFrom());
        }
        if (!ambiguous) {
            return "";
        }
        String from = move.getFrom().value().toLowerCase(Locale.ROOT);
        if (!sameFile) {
            return from.substring(0, 1);
        }
        if (!sameRank) {
            return from.substring(1, 2);
        }
        return from;
    }

    /** Legal move matching a SAN token (tolerant: ignores +, #, !, ?, accepts 0-0 and e8Q), or null. */
    public static Move fromSan(Board board, String san) {
        String wanted = normalizeSan(san);
        if (wanted.isEmpty()) {
            return null;
        }
        for (Move m : MoveGenerator.generateLegalMoves(board)) {
            if (normalizeSan(toSan(board, m)).equals(wanted)) {
                return m;
            }
        }
        // Over-specified disambiguation (e.g. "Ngf3" when "Nf3" is enough) or missing capture mark.
        String relaxed = wanted.replace("x", "");
        for (Move m : MoveGenerator.generateLegalMoves(board)) {
            Piece p = board.getPiece(m.getFrom());
            String to = m.getTo().value().toLowerCase(Locale.ROOT);
            String promo = m.getPromotion() != null && m.getPromotion() != Piece.NONE
                    ? "=" + pieceLetter(m.getPromotion().getPieceType()) : "";
            if (!relaxed.endsWith(to + promo)) {
                continue;
            }
            String head = relaxed.substring(0, relaxed.length() - to.length() - promo.length());
            char letter = p.getPieceType() == PieceType.PAWN ? 0 : pieceLetter(p.getPieceType());
            String from = m.getFrom().value().toLowerCase(Locale.ROOT);
            if (letter == 0) {
                if (head.isEmpty() && fileOf(m.getFrom()) == fileOf(m.getTo())
                        || head.equals(from.substring(0, 1)) || head.equals(from)) {
                    return m;
                }
            } else if (!head.isEmpty() && head.charAt(0) == letter) {
                String dis = head.substring(1);
                if (dis.isEmpty() || from.startsWith(dis) || from.endsWith(dis) || from.equals(dis)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String normalizeSan(String san) {
        if (san == null) {
            return "";
        }
        String s = san.trim().replaceAll("[+#!?]", "").replace("0-0-0", "O-O-O").replace("0-0", "O-O");
        if (s.startsWith("O-O")) {
            return s;
        }
        // "e8Q" / "e8(Q)" -> "e8=Q"
        Matcher m = Pattern.compile("^(.*[a-h][18])\\(?([QRBN])\\)?$").matcher(s);
        if (m.matches()) {
            s = m.group(1) + "=" + m.group(2);
        }
        return s;
    }

    /** SAN list for a sequence of UCI moves played from {@code initialFen}; stops at the first illegal move. */
    public static List<String> uciToSan(String initialFen, List<String> uciMoves) {
        Board board = boardOrStart(initialFen);
        List<String> out = new ArrayList<>();
        for (String uci : uciMoves) {
            Move m = fromUci(board, uci);
            if (m == null) {
                break;
            }
            out.add(toSan(board, m));
            board.doMove(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ replay

    /** Result of replaying a move list: the legal prefix, the final position and the first rejected token. */
    public record Replay(List<String> uciMoves, String finalFen, String rejectedToken, Board board) {
        public boolean complete() {
            return rejectedToken == null;
        }
    }

    /**
     * Replays tokens (UCI or SAN, move numbers and results are skipped) from {@code initialFen}.
     * Unknown words are ignored; the first token that looks like a move but is illegal stops the replay.
     */
    public static Replay replay(String initialFen, List<String> tokens) {
        Board board = boardOrStart(initialFen);
        List<String> uci = new ArrayList<>();
        String rejected = null;
        for (String raw : tokens) {
            String token = raw.trim();
            if (token.isEmpty() || token.matches("\\d+\\.+") || RESULT.matcher(token).matches()) {
                continue;
            }
            token = token.replaceFirst("^\\d+\\.+", "");
            Move m;
            try {
                if (looksLikeUci(token)) {
                    m = fromUci(board, token);
                } else if (token.matches("^[KQRBNO0a-h][a-h1-8xO0=+#!?QRBN()-]*$")) {
                    m = fromSan(board, token);
                } else {
                    continue; // a word such as "Partita" in old archives
                }
            } catch (RuntimeException e) {
                m = null; // chesslib cannot generate moves for this position
            }
            if (m == null) {
                rejected = token;
                break;
            }
            uci.add(toUci(m));
            board.doMove(m);
        }
        return new Replay(uci, board.getFen(), rejected, board);
    }

    public static Board boardOrStart(String fen) {
        Board b = boardFromFen(fen);
        if (b == null) {
            b = new Board();
            b.loadFromFen(START_FEN);
        }
        return b;
    }

    /** "1-0", "0-1", "1/2-1/2" when the position is over (mate, stalemate, insufficient material...), else null. */
    public static String resultOf(Board board) {
        if (board.isMated()) {
            return board.getSideToMove() == Side.WHITE ? "0-1" : "1-0";
        }
        if (board.isStaleMate() || board.isInsufficientMaterial() || board.isRepetition()
                || board.getHalfMoveCounter() >= 100) {
            return "1/2-1/2";
        }
        return null;
    }

    // ------------------------------------------------------------------ PGN

    /** One game read from PGN text. */
    public record PgnGame(Map<String, String> tags, String initialFen, List<String> uciMoves, String result,
                          String rejectedToken) {
    }

    /** Writes a PGN game (seven tag roster first, SetUp/FEN for non-standard starts, lines up to 80 columns). */
    public static String toPgn(Map<String, String> tags, String initialFen, List<String> uciMoves, String result) {
        String res = result != null && RESULT.matcher(result).matches() ? result : "*";
        Map<String, String> all = new LinkedHashMap<>();
        for (String t : SEVEN_TAG_ROSTER) {
            all.put(t, "?");
        }
        all.put("Round", "-");
        all.put("Date", "????.??.??");
        if (tags != null) {
            tags.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    all.put(k, v);
                }
            });
        }
        all.put("Result", res);
        Board board = boardOrStart(initialFen);
        String startFen = board.getFen();
        if (!startFen.equals(START_FEN)) {
            all.put("SetUp", "1");
            all.put("FEN", startFen);
        } else {
            all.remove("SetUp");
            all.remove("FEN");
        }
        StringBuilder sb = new StringBuilder();
        all.forEach((k, v) -> sb.append('[').append(k).append(" \"").append(escape(v)).append("\"]\n"));
        sb.append('\n');

        StringBuilder line = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (String uci : uciMoves) {
            Move m = fromUci(board, uci);
            if (m == null) {
                break;
            }
            String token;
            if (board.getSideToMove() == Side.WHITE) {
                token = board.getMoveCounter() + ". " + toSan(board, m);
            } else {
                token = (first ? board.getMoveCounter() + "... " : "") + toSan(board, m);
            }
            first = false;
            appendWrapped(body, line, token);
            board.doMove(m);
        }
        appendWrapped(body, line, res);
        body.append(line).append('\n');
        return sb.append(body).toString();
    }

    private static void appendWrapped(StringBuilder body, StringBuilder line, String token) {
        if (line.length() > 0 && line.length() + 1 + token.length() > 80) {
            body.append(line).append('\n');
            line.setLength(0);
        }
        if (line.length() > 0) {
            line.append(' ');
        }
        line.append(token);
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /** Parses every game in a PGN text (comments, variations and NAGs are skipped). */
    public static List<PgnGame> parsePgn(String text) {
        List<PgnGame> games = new ArrayList<>();
        if (text == null) {
            return games;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        Map<String, String> tags = new LinkedHashMap<>();
        StringBuilder movetext = new StringBuilder();
        boolean inMoves = false;
        for (String rawLine : lines) {
            String l = rawLine.strip();
            if (l.startsWith("%")) {
                continue; // escape mechanism
            }
            Matcher tag = TAG.matcher(l);
            if (tag.matches()) {
                if (inMoves) {
                    games.add(buildGame(tags, movetext.toString()));
                    tags = new LinkedHashMap<>();
                    movetext.setLength(0);
                    inMoves = false;
                }
                tags.put(tag.group(1), tag.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
                continue;
            }
            if (!l.isEmpty()) {
                inMoves = true;
                movetext.append(l).append('\n');
            }
        }
        if (inMoves || !tags.isEmpty()) {
            games.add(buildGame(tags, movetext.toString()));
        }
        return games;
    }

    private static PgnGame buildGame(Map<String, String> tags, String movetext) {
        String clean = stripCommentsAndVariations(movetext);
        List<String> tokens = new ArrayList<>();
        String result = null;
        for (String t : clean.split("\\s+")) {
            if (t.isEmpty() || t.startsWith("$")) {
                continue;
            }
            if (RESULT.matcher(t).matches()) {
                result = t;
                continue;
            }
            // "12.e4" or "12...Nf6" -> keep only the move
            String move = t.replaceFirst("^\\d+\\.+", "");
            if (!move.isEmpty()) {
                tokens.add(move);
            }
        }
        String fen = tags.getOrDefault("FEN", START_FEN);
        Replay replay = replay(fen, tokens);
        if (result == null) {
            result = tags.getOrDefault("Result", "*");
        }
        return new PgnGame(tags, boardOrStart(fen).getFen(), replay.uciMoves(), result, replay.rejectedToken());
    }

    private static String stripCommentsAndVariations(String text) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean brace = false;
        boolean semicolon = false;
        for (char c : text.toCharArray()) {
            if (semicolon) {
                if (c == '\n') {
                    semicolon = false;
                    out.append(' ');
                }
                continue;
            }
            if (brace) {
                if (c == '}') {
                    brace = false;
                }
                continue;
            }
            if (c == '{') {
                brace = true;
            } else if (c == ';') {
                semicolon = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ helpers

    public static char pieceLetter(PieceType type) {
        return switch (type) {
            case KNIGHT -> 'N';
            case BISHOP -> 'B';
            case ROOK -> 'R';
            case QUEEN -> 'Q';
            case KING -> 'K';
            default -> 'P';
        };
    }

    private static int fileOf(Square s) {
        return s.getFile().ordinal();
    }

    private static int rankOf(Square s) {
        return s.getRank().ordinal();
    }
}
