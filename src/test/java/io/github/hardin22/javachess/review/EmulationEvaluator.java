package io.github.hardin22.javachess.review;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Engine.EngineException;
import io.github.hardin22.javachess.Engine.EngineSpec;
import io.github.hardin22.javachess.Engine.InfoLine;
import io.github.hardin22.javachess.Engine.SearchLimits;
import io.github.hardin22.javachess.Engine.SearchResult;
import io.github.hardin22.javachess.Engine.UciClient;
import io.github.hardin22.javachess.Engine.review.EngineLine;
import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Engine.review.PositionEvaluator;
import io.github.hardin22.javachess.Engine.review.StockfishPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Review evaluator for the engine emulation study: any UCI command (native Stockfish 19, the chess.com
 * stockfish.js "lite" WASM build under node...), searched with a fixed depth or node limit and optionally MultiPV
 * in the main pass, whatever budget the reviewer asks for. Not a product class: it only feeds
 * {@link EngineEmulationStudyTest}.
 */
final class EmulationEvaluator implements PositionEvaluator {

    /**
     * How every position is searched.
     *
     * @param depth       depth limit (0 = none)
     * @param nodes       node limit (0 = none)
     * @param multiPv     lines of the main pass (1 = the product's single pass)
     * @param secondDepth depth of the second-line search (searchmoves without the best), 0 = none
     * @param secondNodes nodes of the second-line search, 0 = none
     */
    record Limit(int depth, long nodes, int multiPv, int secondDepth, long secondNodes) {

        SearchLimits main() {
            return new SearchLimits(depth, nodes, 0, multiPv, List.of(), 0);
        }

        SearchLimits second(List<String> moves) {
            return new SearchLimits(secondDepth, secondNodes, 0, 1, moves, 0);
        }
    }

    private final List<UciClient> clients = new ArrayList<>();
    private final BlockingQueue<UciClient> idle;
    private final ThreadLocal<UciClient> affinity = new ThreadLocal<>();
    private final Limit limit;
    private final String id;
    final AtomicLong nodes = new AtomicLong();
    final AtomicLong searches = new AtomicLong();

    EmulationEvaluator(String id, List<String> command, int processes, int hashMb, Limit limit) {
        this.id = id;
        this.limit = limit;
        idle = new ArrayBlockingQueue<>(processes);
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("Threads", "1");
        opts.put("Hash", String.valueOf(hashMb));
        List<CompletableFuture<Void>> started = new ArrayList<>();
        for (int i = 0; i < processes; i++) {
            UciClient c = new UciClient(EngineSpec.of("emu-" + i, java.nio.file.Path.of(command.get(0)), opts)
                    .withCommand(command).withReadyTimeout(60_000));
            started.add(c.start());
            clients.add(c);
            idle.add(c);
        }
        for (CompletableFuture<Void> f : started) {
            try {
                f.get(90, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new EngineException("engine " + id + " did not start: " + e);
            }
        }
    }

    @Override
    public PositionEval evaluate(String fen, int multiPv, long ignoredNodes) throws Exception {
        Board board = new Board();
        board.loadFromFen(fen);
        if (board.legalMoves().isEmpty()) {
            return PositionEval.terminal(fen, Eval.terminal(board).orElse(Eval.DRAW));
        }
        SearchLimits l = limit.main();
        l = l.withMultiPv(Math.max(l.multiPv(), multiPv));
        SearchResult r = search(fen, l);
        return StockfishPool.toPositionEval(fen, board.getSideToMove() == Side.WHITE, r);
    }

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
        boolean white = board.getSideToMove() == Side.WHITE;
        SearchResult r = search(p.fen(), limit.second(others));
        if (r.lines().isEmpty()) {
            return p;
        }
        InfoLine l = r.lines().get(0);
        List<EngineLine> lines = new ArrayList<>(p.lines());
        lines.add(new EngineLine(l.move(), Eval.fromUci(l.score(), white), l.pv(), l.depth()));
        return new PositionEval(p.fen(), p.eval(), lines, p.depth(), p.nodes() + r.nodes(), false);
    }

    /** One search on a free process (the thread's last one when idle: consecutive positions share its hash). */
    SearchResult search(String fen, SearchLimits limits) throws Exception {
        UciClient preferred = affinity.get();
        UciClient c = preferred != null && idle.remove(preferred) ? preferred : idle.take();
        affinity.set(c);
        try {
            SearchResult r = c.search(fen, limits.withTimeout(600_000)).result().get(620, TimeUnit.SECONDS);
            nodes.addAndGet(r.nodes());
            searches.incrementAndGet();
            return r;
        } finally {
            idle.add(c);
        }
    }

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
        for (UciClient c : clients) {
            c.closeAsync();
        }
    }
}
