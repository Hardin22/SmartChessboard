package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.EngineLine;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Engine evaluations of the chess.com-labelled games, stored once ({@link EvalDumpTest}) so the classification can be
 * tuned and cross-validated offline with {@link ReviewClassifier#classifyGame} in seconds.
 *
 * <p>Layout: {@code <dir>/<budget>/<gameId>.jsonl}. Line 1 is the game header
 * {@code {"type":"game","id","initial_fen","uci":[...],"budget","nodes","second_nodes","mpv3_nodes","engine",...}},
 * then one line per position {@code i = 0..plies} (position i is the one before ply i+1):</p>
 * <pre>
 * {"type":"pos","i":0,"fen":"...","legal":20,"terminal":false,
 *  "e":"cp:31","depth":17,"nodes":200412,"pv":["e2e4",...],          main search, MultiPV 1, product budget
 *  "second":{"move":"d2d4","e":"cp:22","depth":15,"nodes":100230,"pv":[...]},   best move excluded (searchmoves)
 *  "mpv3":{"depth":15,"nodes":200150,"lines":[{"move","e","depth","pv"} x3]}}    diagnostic MultiPV 3
 * </pre>
 * Evaluations are White's point of view: {@code "cp:-45"}, {@code "W#3"} (White mates in 3), {@code "B#0"} (Black has
 * mated). Terminal positions (mate, stalemate, insufficient material, repetition / 50 moves as the product treats them)
 * carry only {@code e}; {@code second} is null where the position has a single legal move.
 */
public final class EvalDump {

    /** How the stored searches become the classifier input. */
    public enum Mode {
        /** Like {@link io.github.hardin22.javachess.Engine.review.GameReviewer}: main line, second line only where
         * {@link ReviewClassifier#needsSecondLine} asks for it. */
        PRODUCT,
        /** Main line plus the second line everywhere. */
        SECOND_EVERYWHERE,
        /** The MultiPV 3 search replaces the main one (top line and eval from it). */
        MPV3
    }

    public record Line(String move, Eval eval, List<String> pv, int depth, long nodes) {
        EngineLine engineLine() {
            return new EngineLine(move, eval, pv, depth);
        }
    }

    public record Position(int index, String fen, int legal, boolean terminal, Eval eval, int depth, long nodes,
                           List<String> pv, Line second, List<Line> mpv3, int mpv3Depth, long mpv3Nodes) {

        PositionEval main() {
            if (terminal) {
                return PositionEval.terminal(fen, eval);
            }
            return new PositionEval(fen, eval, List.of(new EngineLine(pv.get(0), eval, pv, depth)), depth, nodes,
                    false);
        }

        PositionEval withSecond() {
            PositionEval p = main();
            if (terminal || second == null) {
                return p;
            }
            return new PositionEval(fen, eval, List.of(p.best(), second.engineLine()), depth, nodes + second.nodes,
                    false);
        }

        PositionEval fromMpv3() {
            if (terminal || mpv3.isEmpty()) {
                return main();
            }
            return new PositionEval(fen, mpv3.get(0).eval(), mpv3.stream().map(Line::engineLine).toList(), mpv3Depth,
                    mpv3Nodes, false);
        }
    }

    public record Game(String id, String initialFen, List<String> uci, String budget, long nodes, long secondNodes,
                       List<Position> positions) {

        public ReviewInput input(Mode mode, OpeningBook book) {
            List<PositionEval> ps = new ArrayList<>();
            for (Position p : positions) {
                ps.add(switch (mode) {
                    case PRODUCT -> p.main();
                    case SECOND_EVERYWHERE -> p.withSecond();
                    case MPV3 -> p.fromMpv3();
                });
            }
            if (mode == Mode.PRODUCT) {
                BitSet need = ReviewClassifier.needsSecondLine(new ReviewInput(initialFen, uci, ps, book));
                for (int i = need.nextSetBit(0); i >= 0; i = need.nextSetBit(i + 1)) {
                    ps.set(i, positions.get(i).withSecond());
                }
            }
            return new ReviewInput(initialFen, uci, ps, book);
        }

        /** Engine nodes the product review spends on this game (main pass + second lines where needed). */
        /**
         * Product input where the Great/Brilliant candidates ({@link ReviewClassifier#needsSecondLine} on the plain
         * main lines) are re-searched deeper, simulated with another dump of the same game: {@code "second"} takes the
         * deep second line when the deep best move is ours, {@code "full"} the deep evaluation and second line.
         *
         * @param extraNodes receives the engine work the re-search adds (index 0)
         */
        public ReviewInput recheckedInput(Game deep, String how, OpeningBook book, long[] extraNodes) {
            ReviewInput base = input(Mode.PRODUCT, book);
            List<PositionEval> ps = new ArrayList<>(base.positions());
            List<PositionEval> plain = positions.stream().map(Position::main).toList();
            BitSet cand = ReviewClassifier.needsSecondLine(new ReviewInput(initialFen, uci, plain, book));
            for (int i = cand.nextSetBit(0); i >= 0; i = cand.nextSetBit(i + 1)) {
                Position dp = deep.positions().get(i);
                if (dp.terminal()) {
                    continue;
                }
                if ("full".equals(how)) {
                    ps.set(i, dp.withSecond());
                    extraNodes[0] += dp.nodes() + (dp.second() == null ? 0 : dp.second().nodes());
                } else if ("second".equals(how) && dp.second() != null
                        && dp.pv().get(0).equals(ps.get(i).bestMove())) {
                    PositionEval p = ps.get(i);
                    ps.set(i, new PositionEval(p.fen(), p.eval(), List.of(p.best(), dp.second().engineLine()),
                            p.depth(), p.nodes(), false));
                    extraNodes[0] += dp.second().nodes();
                }
            }
            return new ReviewInput(initialFen, uci, ps, book);
        }

        public long productNodes(OpeningBook book) {
            long n = 0;
            for (PositionEval p : input(Mode.PRODUCT, book).positions()) {
                n += p.nodes();
            }
            return n;
        }
    }

    private EvalDump() {
    }

    // ---------------------------------------------------------------------------------- eval strings

    public static String format(Eval e) {
        return switch (e.kind()) {
            case CP -> "cp:" + e.value();
            case WHITE_MATES -> "W#" + e.value();
            case BLACK_MATES -> "B#" + e.value();
        };
    }

    public static Eval parse(String s) {
        if (s.startsWith("cp:")) {
            return Eval.cp(Integer.parseInt(s.substring(3)));
        }
        int n = Integer.parseInt(s.substring(2));
        return switch (s.charAt(0)) {
            case 'W' -> Eval.whiteMates(n);
            case 'B' -> Eval.blackMates(n);
            default -> throw new IllegalArgumentException("eval: " + s);
        };
    }

    static JSONObject line(EngineLine l, long nodes) {
        JSONObject o = new JSONObject();
        o.put("move", l.move());
        o.put("e", format(l.eval()));
        o.put("depth", l.depth());
        if (nodes >= 0) {
            o.put("nodes", nodes);
        }
        o.put("pv", new JSONArray(l.pv().subList(0, Math.min(l.pv().size(), PV_MAX))));
        return o;
    }

    /** Stored PV length (the classifier looks 6 plies ahead). */
    static final int PV_MAX = 16;

    // ---------------------------------------------------------------------------------- reading

    /** Every game of {@code <dir>/<budget>}, sorted by id; empty when the directory does not exist. */
    public static List<Game> load(Path dir, String budget) {
        Path d = dir.resolve(budget);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(d)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).sorted()
                    .map(EvalDump::read).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Game read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JSONObject h = new JSONObject(lines.get(0));
        List<Position> ps = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            if (!l.isBlank()) {
                ps.add(position(new JSONObject(l)));
            }
        }
        List<String> uci = ChessComDataset.strings(h.getJSONArray("uci"));
        if (ps.size() != uci.size() + 1) {
            throw new IllegalStateException(file + ": " + ps.size() + " positions for " + uci.size() + " moves");
        }
        String fen = h.optString("initial_fen", "");
        return new Game(h.getString("id"), fen.isEmpty() ? null : fen, uci, h.optString("budget"),
                h.optLong("nodes"), h.optLong("second_nodes"), ps);
    }

    private static Position position(JSONObject o) {
        boolean terminal = o.optBoolean("terminal");
        Eval e = parse(o.getString("e"));
        Line second = o.optJSONObject("second") == null ? null : lineOf(o.getJSONObject("second"));
        List<Line> mpv3 = new ArrayList<>();
        int mDepth = 0;
        long mNodes = 0;
        JSONObject m = o.optJSONObject("mpv3");
        if (m != null) {
            mDepth = m.optInt("depth");
            mNodes = m.optLong("nodes");
            JSONArray a = m.getJSONArray("lines");
            for (int i = 0; i < a.length(); i++) {
                mpv3.add(lineOf(a.getJSONObject(i)));
            }
        }
        List<String> pv = o.has("pv") ? ChessComDataset.strings(o.getJSONArray("pv")) : List.of();
        return new Position(o.getInt("i"), o.getString("fen"), o.optInt("legal"), terminal, e, o.optInt("depth"),
                o.optLong("nodes"), pv, second, List.copyOf(mpv3), mDepth, mNodes);
    }

    private static Line lineOf(JSONObject o) {
        return new Line(o.getString("move"), parse(o.getString("e")), ChessComDataset.strings(o.getJSONArray("pv")),
                o.optInt("depth"), o.optLong("nodes"));
    }
}
