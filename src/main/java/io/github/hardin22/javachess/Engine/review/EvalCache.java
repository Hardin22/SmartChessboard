package io.github.hardin22.javachess.Engine.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disk cache of position evaluations, shared by all reviews (opening positions repeat across games, and an
 * archived game reviewed twice costs nothing the second time). An append-only text file per engine id, loaded in
 * memory on first use; a cached entry answers a request when it searched at least as many nodes and lines.
 *
 * <p>Line format: {@code fenKey TAB multiPv TAB nodesBudget TAB depth TAB nodes TAB line;line...} with
 * {@code line = move,kind,value,pv-moves-separated-by-spaces}. Unreadable lines are skipped.</p>
 */
public final class EvalCache {

    private static final Logger log = LoggerFactory.getLogger(EvalCache.class);

    private record Entry(int multiPv, long budget, PositionEval eval) {
    }

    private final Path file;
    private final Map<String, Entry> map = new HashMap<>();
    private boolean loaded;
    private BufferedWriter out;

    public EvalCache(Path file) {
        this.file = file;
    }

    /** Cache file for an evaluator id in {@code dir}. */
    public static EvalCache in(Path dir, String evaluatorId) {
        return new EvalCache(dir.resolve("evals-" + evaluatorId.replaceAll("[^A-Za-z0-9._-]", "_") + ".tsv"));
    }

    /** A cached evaluation of {@code fen} good enough for the request, or null. */
    public synchronized PositionEval get(String fen, int multiPv, long nodes) {
        load();
        Entry e = map.get(key(fen));
        if (e == null || e.multiPv < multiPv || e.budget < nodes) {
            return null;
        }
        PositionEval p = e.eval;
        // re-attach the requested FEN (move counters may differ from the cached one)
        return new PositionEval(fen, p.eval(), p.lines(), p.depth(), p.nodes(), p.terminal());
    }

    public synchronized void put(PositionEval p, int multiPv, long nodes) {
        if (p.terminal() || p.lines().isEmpty()) {
            return;
        }
        load();
        String k = key(p.fen());
        Entry old = map.get(k);
        if (old != null && old.multiPv >= multiPv && old.budget >= nodes) {
            return;
        }
        map.put(k, new Entry(multiPv, nodes, p));
        try {
            if (out == null) {
                Files.createDirectories(file.getParent());
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
            out.write(encode(k, multiPv, nodes, p));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            log.debug("eval cache not written: {}", e.toString());
        }
    }

    public synchronized int size() {
        load();
        return map.size();
    }

    public synchronized void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // best effort
            }
            out = null;
        }
    }

    /** Piece placement, side to move, castling and en passant (move counters do not change the evaluation). */
    static String key(String fen) {
        String[] p = fen.trim().split("\\s+");
        return String.join(" ", Arrays.copyOf(p, Math.min(4, p.length)));
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    String[] f = line.split("\t");
                    int mpv = Integer.parseInt(f[1]);
                    long budget = Long.parseLong(f[2]);
                    int depth = Integer.parseInt(f[3]);
                    long nodes = Long.parseLong(f[4]);
                    List<EngineLine> lines = new ArrayList<>();
                    for (String l : f[5].split(";")) {
                        String[] g = l.split(",", 4);
                        Eval e = new Eval(Eval.Kind.valueOf(g[1]), Integer.parseInt(g[2]));
                        lines.add(new EngineLine(g[0], e, List.of(g[3].split(" ")), depth));
                    }
                    String fen = f[0] + " 0 1";
                    Entry prev = map.get(f[0]);
                    if (prev == null || (prev.multiPv <= mpv && prev.budget <= budget)) {
                        map.put(f[0], new Entry(mpv, budget,
                                new PositionEval(fen, lines.get(0).eval(), lines, depth, nodes, false)));
                    }
                } catch (RuntimeException skip) {
                    // corrupted or older format line
                }
            }
        } catch (IOException e) {
            log.debug("eval cache not read: {}", e.toString());
        }
    }

    private static String encode(String key, int multiPv, long budget, PositionEval p) {
        StringBuilder sb = new StringBuilder(key).append('\t').append(multiPv).append('\t').append(budget)
                .append('\t').append(p.depth()).append('\t').append(p.nodes()).append('\t');
        for (int i = 0; i < p.lines().size(); i++) {
            EngineLine l = p.lines().get(i);
            if (i > 0) {
                sb.append(';');
            }
            sb.append(l.move()).append(',').append(l.eval().kind()).append(',').append(l.eval().value()).append(',')
                    .append(String.join(" ", l.pv()));
        }
        return sb.toString();
    }
}
