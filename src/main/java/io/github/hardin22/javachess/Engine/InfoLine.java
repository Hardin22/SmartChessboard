package io.github.hardin22.javachess.Engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One parsed UCI {@code info} line that carries a principal variation.
 *
 * @param depth     search depth
 * @param selDepth  selective depth (0 when absent)
 * @param multiPv   1-based line index (1 when the engine does not print multipv)
 * @param score     score from the side to move's point of view
 * @param bound     EXACT, LOWER or UPPER (aspiration window results are not final)
 * @param nodes     nodes searched so far (0 when absent)
 * @param nps       nodes per second (0 when absent)
 * @param timeMs    time searched in ms (0 when absent)
 * @param pv        principal variation in UCI notation, never empty
 */
public record InfoLine(int depth, int selDepth, int multiPv, Score score, Bound bound,
                       long nodes, long nps, long timeMs, List<String> pv) {

    public enum Bound { EXACT, LOWER, UPPER }

    public InfoLine {
        pv = List.copyOf(pv);
    }

    /** First move of the principal variation. */
    public String move() {
        return pv.get(0);
    }

    /** Returns a copy with another multipv index (used when a single-line search is stored). */
    public InfoLine withMultiPv(int index) {
        return new InfoLine(depth, selDepth, index, score, bound, nodes, nps, timeMs, pv);
    }

    /**
     * Parses a UCI info line. Returns empty for lines without both a score and a pv
     * (currmove, hashfull-only, "info string", malformed numbers...). Never throws.
     */
    public static Optional<InfoLine> parse(String line) {
        if (line == null || !line.startsWith("info ")) {
            return Optional.empty();
        }
        String[] t = line.trim().split("\\s+");
        int depth = 0;
        int selDepth = 0;
        int multiPv = 1;
        long nodes = 0;
        long nps = 0;
        long time = 0;
        Score score = null;
        Bound bound = Bound.EXACT;
        List<String> pv = Collections.emptyList();
        try {
            for (int i = 1; i < t.length; i++) {
                switch (t[i]) {
                    case "string":
                        return Optional.empty(); // free text until end of line
                    case "depth":
                        depth = Integer.parseInt(t[++i]);
                        break;
                    case "seldepth":
                        selDepth = Integer.parseInt(t[++i]);
                        break;
                    case "multipv":
                        multiPv = Integer.parseInt(t[++i]);
                        break;
                    case "nodes":
                        nodes = Long.parseLong(t[++i]);
                        break;
                    case "nps":
                        nps = Long.parseLong(t[++i]);
                        break;
                    case "time":
                        time = Long.parseLong(t[++i]);
                        break;
                    case "score": {
                        String type = t[++i];
                        int v = Integer.parseInt(t[++i]);
                        if (type.equals("cp")) {
                            score = Score.cp(v);
                        } else if (type.equals("mate")) {
                            score = Score.mate(v);
                        } else {
                            return Optional.empty();
                        }
                        break;
                    }
                    case "lowerbound":
                        bound = Bound.LOWER;
                        break;
                    case "upperbound":
                        bound = Bound.UPPER;
                        break;
                    case "pv": {
                        List<String> moves = new ArrayList<>(t.length - i);
                        for (int j = i + 1; j < t.length; j++) {
                            if (!isUciMove(t[j])) {
                                break;
                            }
                            moves.add(t[j]);
                        }
                        pv = moves;
                        i = t.length; // pv is always the last field
                        break;
                    }
                    case "currmove":
                    case "currmovenumber":
                    case "hashfull":
                    case "tbhits":
                    case "cpuload":
                    case "sbhits":
                        i++; // skip value
                        break;
                    case "wdl":
                        i += 3;
                        break;
                    default:
                        // unknown token: ignore (engines add vendor specific fields)
                        break;
                }
            }
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
        if (score == null || pv.isEmpty() || multiPv < 1) {
            return Optional.empty();
        }
        return Optional.of(new InfoLine(depth, selDepth, multiPv, score, bound, nodes, nps, time, pv));
    }

    /** Syntactic check for a UCI move such as e2e4, e7e8q (also accepts 0000 null move: rejected). */
    public static boolean isUciMove(String s) {
        if (s == null || (s.length() != 4 && s.length() != 5)) {
            return false;
        }
        if (!isFile(s.charAt(0)) || !isRank(s.charAt(1)) || !isFile(s.charAt(2)) || !isRank(s.charAt(3))) {
            return false;
        }
        return s.length() == 4 || "qrbnQRBN".indexOf(s.charAt(4)) >= 0;
    }

    private static boolean isFile(char c) {
        return c >= 'a' && c <= 'h';
    }

    private static boolean isRank(char c) {
        return c >= '1' && c <= '8';
    }
}
