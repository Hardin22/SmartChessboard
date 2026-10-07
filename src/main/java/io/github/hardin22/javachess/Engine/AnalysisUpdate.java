package io.github.hardin22.javachess.Engine;

import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;

import java.util.List;
import java.util.Locale;

/**
 * A snapshot of the live analysis of one position.
 *
 * @param fen           analysed position
 * @param depth         depth of the lines
 * @param lines         exact lines ordered by multipv (empty for checkmate/stalemate)
 * @param finished      true when the search reached its depth (no further update for this position)
 * @param terminalScore for positions without legal moves: mate 0 (checkmated) or cp 0 (stalemate), else null
 * @param nodes         nodes searched
 * @param elapsedMs     search time so far
 */
public record AnalysisUpdate(String fen, int depth, List<InfoLine> lines, boolean finished, Score terminalScore,
                             long nodes, long elapsedMs) {

    public AnalysisUpdate {
        lines = List.copyOf(lines);
    }

    public boolean whiteToMove() {
        String[] f = fen.split(" ");
        return f.length < 2 || f[1].equals("w");
    }

    public InfoLine best() {
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** Best move in UCI or null. */
    public String bestMove() {
        InfoLine b = best();
        return b == null ? null : b.move();
    }

    /** Score of the best line from the side to move's point of view (null if nothing yet). */
    public Score score() {
        InfoLine b = best();
        return b != null ? b.score() : terminalScore;
    }

    /** Score of line {@code i} (0-based) from White's point of view. */
    public Score whiteScore(int i) {
        Score s = i == 0 ? score() : lines.get(i).score();
        return s == null ? null : s.forWhite(whiteToMove());
    }

    /** Eval bar value (White POV pawns, mates as +/-(1000-N)), as the old engine wrapper produced. */
    public double whitePawns() {
        Score s = whiteScore(0);
        return s == null ? 0.0 : s.legacyPawns();
    }

    /** Evaluation text of line {@code i}, White POV: "0.35", "-1.20", "M3", "-M2". */
    public String evalText(int i) {
        Score s = whiteScore(i);
        if (s == null) {
            return "0.00";
        }
        if (s.mate()) {
            return s.value() > 0 ? "M" + s.value() : "-M" + Math.abs(s.value());
        }
        return String.format(Locale.ROOT, "%.2f", s.value() / 100.0);
    }

    /** Line {@code i} as "[0.35] 1)e4 e5 2)Nf3 Nc6" (SAN), the format of the analysis panel. */
    public String formatLine(int i) {
        StringBuilder sb = new StringBuilder("[").append(evalText(i)).append("] ");
        if (i >= lines.size()) {
            return sb.toString().trim();
        }
        List<String> pv = lines.get(i).pv();
        String[] san;
        try {
            MoveList ml = new MoveList(fen);
            com.github.bhlangonijr.chesslib.Board b = new com.github.bhlangonijr.chesslib.Board();
            b.loadFromFen(fen);
            for (String uci : pv) {
                Move m = new Move(uci, b.getSideToMove());
                if (!b.isMoveLegal(m, true) || !b.doMove(m, true)) {
                    break;
                }
                ml.add(m);
            }
            san = ml.toSanArray();
        } catch (Exception e) {
            san = pv.toArray(new String[0]);
        }
        String[] f = fen.split(" ");
        int moveNo = 1;
        try {
            moveNo = Integer.parseInt(f[5]);
        } catch (Exception ignored) {
            // FEN without counters
        }
        boolean white = whiteToMove();
        for (int k = 0; k < san.length; k++) {
            if (white) {
                sb.append(moveNo).append(')');
            } else if (k == 0) {
                sb.append(moveNo).append(")...");
            }
            sb.append(san[k]).append(' ');
            if (!white) {
                moveNo++;
            }
            white = !white;
        }
        return sb.toString().trim();
    }

    static Side sideToMove(String fen) {
        String[] f = fen.split(" ");
        return f.length < 2 || f[1].equals("w") ? Side.WHITE : Side.BLACK;
    }
}
