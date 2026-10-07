package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Utils.PgnCodec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic UCI engine for the end-to-end tests, run as a real child process like Stockfish/lc0.
 *
 * <p>Best move: the first move of the script file (one UCI move per line, args[0]) that is legal in the current
 * position, otherwise the move with the best one-ply material score. Info lines carry that material score
 * (centipawns, side to move) for every MultiPV line, so the review and the coach get plausible numbers.
 * Answers {@code uci}, {@code isready}, {@code position}, {@code go} (any limits), {@code stop}, {@code quit};
 * other commands ({@code setoption}, {@code ucinewgame}) are accepted silently.</p>
 */
public final class ScriptedUciEngine {

    private static final PrintStream OUT = System.out;

    public static void main(String[] args) throws Exception {
        Path scriptFile = args.length > 0 ? Path.of(args[0]) : null;
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        Board board = PgnCodec.boardOrStart(PgnCodec.START_FEN);
        int multiPv = 1;
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.equals("uci")) {
                say("id name ScriptedEngine");
                say("option name Hash type spin default 16 min 1 max 1024");
                say("option name Threads type spin default 1 min 1 max 64");
                say("option name MultiPV type spin default 1 min 1 max 50");
                say("option name Skill Level type spin default 20 min 0 max 20");
                say("uciok");
            } else if (line.equals("isready")) {
                say("readyok");
            } else if (line.startsWith("setoption name MultiPV value ")) {
                multiPv = Math.max(1, Integer.parseInt(line.substring(29).trim()));
            } else if (line.startsWith("position")) {
                board = parsePosition(line);
            } else if (line.startsWith("go")) {
                search(board, multiPv, readScript(scriptFile)); // re-read: tests change it between games
            } else if (line.equals("quit")) {
                return;
            }
            // stop: searches finish immediately, nothing to interrupt
        }
    }

    private static List<String> readScript(Path file) {
        try {
            return file != null && Files.exists(file)
                    ? Files.readAllLines(file).stream().map(String::trim).filter(s -> !s.isEmpty()).toList()
                    : List.of();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private static Board parsePosition(String line) {
        String fen = PgnCodec.START_FEN;
        int movesAt = line.indexOf(" moves ");
        String head = movesAt >= 0 ? line.substring(0, movesAt) : line;
        if (head.contains(" fen ")) {
            fen = head.substring(head.indexOf(" fen ") + 5).trim();
        }
        Board b = PgnCodec.boardOrStart(fen);
        if (movesAt >= 0) {
            for (String uci : line.substring(movesAt + 7).trim().split("\\s+")) {
                Move m = PgnCodec.fromUci(b, uci);
                if (m == null) {
                    break;
                }
                b.doMove(m);
            }
        }
        return b;
    }

    private record Scored(Move move, int cp) {
    }

    private static void search(Board board, int multiPv, List<String> script) {
        List<Move> legal = board.legalMoves();
        if (legal.isEmpty()) {
            say("info depth 1 score " + (board.isKingAttacked() ? "mate 0" : "cp 0"));
            say("bestmove (none)");
            return;
        }
        List<Scored> scored = new ArrayList<>();
        for (Move m : legal) {
            Board after = board.clone();
            after.doMove(m);
            int cp = after.isMated() ? 30000 : -material(after); // side that moved
            scored.add(new Scored(m, cp));
        }
        scored.sort(Comparator.comparingInt(Scored::cp).reversed());
        Move best = scored.get(0).move();
        for (String uci : script) {
            Move m = PgnCodec.fromUci(board, uci);
            if (m != null) {
                best = m;
                break;
            }
        }
        Move chosen = best;
        scored.removeIf(s -> s.move().equals(chosen));
        Board afterBest = board.clone();
        afterBest.doMove(best);
        scored.add(0, new Scored(best, afterBest.isMated() ? 30000 : -material(afterBest)));
        for (int depth = 1; depth <= 3; depth++) {
            for (int i = 0; i < Math.min(multiPv, scored.size()); i++) {
                Scored s = scored.get(i);
                String score = s.cp() >= 30000 ? "mate 1" : "cp " + s.cp();
                say("info depth " + depth + " seldepth " + depth + " multipv " + (i + 1) + " score " + score
                        + " nodes " + (1000 * depth) + " nps 100000 time " + depth + " pv " + PgnCodec.toUci(s.move()));
            }
        }
        say("bestmove " + PgnCodec.toUci(best));
    }

    /** Material balance from the side to move's point of view, in centipawns. */
    private static int material(Board b) {
        int sum = 0;
        for (Piece p : b.boardToArray()) {
            if (p == null || p == Piece.NONE) {
                continue;
            }
            int v = switch (p.getPieceType()) {
                case PAWN -> 100;
                case KNIGHT, BISHOP -> 300;
                case ROOK -> 500;
                case QUEEN -> 900;
                default -> 0;
            };
            sum += p.getPieceSide() == b.getSideToMove() ? v : -v;
        }
        return sum;
    }

    private static synchronized void say(String s) {
        OUT.println(s);
        OUT.flush();
    }

}
