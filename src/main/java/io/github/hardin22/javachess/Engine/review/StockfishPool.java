package io.github.hardin22.javachess.Engine.review;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Engine.EngineException;
import io.github.hardin22.javachess.Engine.EngineSpec;
import io.github.hardin22.javachess.Engine.InfoLine;
import io.github.hardin22.javachess.Engine.SearchLimits;
import io.github.hardin22.javachess.Engine.SearchResult;
import io.github.hardin22.javachess.Engine.UciClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A few single-threaded Stockfish processes that evaluate review positions in parallel. One thread per process
 * makes node-limited searches deterministic (same nodes, same result on every machine), and N processes scale
 * better than N threads in one process for many short searches.
 */
public final class StockfishPool implements PositionEvaluator {

    private final List<UciClient> clients = new ArrayList<>();
    private final BlockingQueue<UciClient> idle;
    private final String id;
    private volatile boolean closed;
    /** The process each review thread used last: consecutive positions reuse its warm hash. */
    private final ThreadLocal<UciClient> affinity = new ThreadLocal<>();

    /**
     * @param stockfish executable
     * @param processes number of processes (1 thread each)
     * @param hashMb    Hash of each process
     */
    public StockfishPool(Path stockfish, int processes, int hashMb) {
        int n = Math.max(1, processes);
        idle = new ArrayBlockingQueue<>(n);
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("Threads", "1");
        opts.put("Hash", String.valueOf(Math.max(1, hashMb)));
        List<java.util.concurrent.CompletableFuture<Void>> started = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UciClient c = new UciClient(EngineSpec.of("review-" + i, stockfish, opts));
            started.add(c.start());
            clients.add(c);
            idle.add(c);
        }
        String name = "";
        try {
            started.get(0).get(15, TimeUnit.SECONDS);
            name = clients.get(0).engineName();
        } catch (Exception e) {
            // a failing engine fails its searches later; the id falls back to the file name
        }
        // part of the cache key: another engine version must not reuse cached evaluations
        this.id = name == null || name.isBlank() ? "sf:" + stockfish.getFileName() : name.trim();
    }

    @Override
    public PositionEval evaluate(String fen, int multiPv, long nodes) throws Exception {
        if (closed) {
            throw new EngineException("review engines closed");
        }
        Board board = new Board();
        board.loadFromFen(fen);
        var terminal = Eval.terminal(board);
        if (terminal.isPresent() && board.legalMoves().isEmpty()) {
            return PositionEval.terminal(fen, terminal.get());
        }
        SearchResult r = search(fen, SearchLimits.nodes(nodes).withMultiPv(multiPv), nodes);
        return toPositionEval(fen, board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE, r);
    }

    /** Searches every move but the best one: its top line is the second best move. */
    @Override
    public PositionEval addSecondLine(PositionEval p, long mainNodes, long secondNodes) throws Exception {
        if (p.terminal() || p.best() == null || p.secondBest() != null) {
            return p;
        }
        Board board = new Board();
        board.loadFromFen(p.fen());
        List<String> others = new ArrayList<>();
        for (var m : board.legalMoves()) {
            if (!m.toString().equals(p.bestMove())) {
                others.add(m.toString());
            }
        }
        if (others.isEmpty()) {
            return p;
        }
        boolean white = board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE;
        SearchResult r = search(p.fen(), SearchLimits.nodes(secondNodes).withSearchMoves(others), secondNodes);
        if (r.lines().isEmpty()) {
            return p;
        }
        InfoLine l = r.lines().get(0);
        List<EngineLine> lines = new ArrayList<>(p.lines());
        lines.add(new EngineLine(l.move(), Eval.fromUci(l.score(), white), l.pv(), l.depth()));
        return new PositionEval(p.fen(), p.eval(), lines, p.depth(), p.nodes() + r.nodes(), false);
    }

    private SearchResult search(String fen, SearchLimits limits, long nodes) throws Exception {
        UciClient preferred = affinity.get();
        UciClient c = preferred != null && idle.remove(preferred) ? preferred : idle.take();
        affinity.set(c);
        try {
            // generous client-side cap: a node budget normally ends long before (protects against a hung engine)
            long capMs = Math.max(10_000, nodes / 20);
            return c.search(fen, limits.withTimeout(capMs)).result().get(capMs + 10_000, TimeUnit.MILLISECONDS);
        } finally {
            idle.add(c);
        }
    }

    /** Converts a UCI search result (side to move scores) to a White POV {@link PositionEval}. */
    public static PositionEval toPositionEval(String fen, boolean whiteToMove, SearchResult r) {
        List<EngineLine> lines = new ArrayList<>();
        for (InfoLine l : r.lines()) {
            lines.add(new EngineLine(l.move(), Eval.fromUci(l.score(), whiteToMove), l.pv(), l.depth()));
        }
        if (lines.isEmpty()) {
            var t = Eval.terminal(fen);
            if (t.isPresent()) {
                return PositionEval.terminal(fen, t.get());
            }
            throw new EngineException("no line for " + fen);
        }
        return new PositionEval(fen, lines.get(0).eval(), lines, r.depth(), r.nodes(), false);
    }

    /** Clears the hash of every process (start of a game). */
    @Override
    public void newGame() {
        for (UciClient c : clients) {
            c.newGame();
        }
    }

    @Override
    public int parallelism() {
        return clients.size();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void close() {
        closed = true;
        for (UciClient c : clients) {
            c.closeAsync();
        }
    }
}
