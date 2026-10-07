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
        for (int i = 0; i < n; i++) {
            UciClient c = new UciClient(EngineSpec.of("review-" + i, stockfish, opts));
            c.start();
            clients.add(c);
            idle.add(c);
        }
        this.id = "sf:" + stockfish.getFileName();
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
        UciClient c = idle.take();
        try {
            // generous client-side cap: a node budget normally ends long before (protects against a hung engine)
            long capMs = Math.max(10_000, nodes / 20);
            SearchResult r = c.search(fen, SearchLimits.nodes(nodes).withMultiPv(multiPv).withTimeout(capMs))
                    .result().get(capMs + 10_000, TimeUnit.MILLISECONDS);
            return toPositionEval(fen, board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE, r);
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
