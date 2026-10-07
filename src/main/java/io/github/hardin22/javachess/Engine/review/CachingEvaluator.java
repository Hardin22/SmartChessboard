package io.github.hardin22.javachess.Engine.review;

import java.util.concurrent.atomic.AtomicInteger;

/** A {@link PositionEvaluator} that answers from an {@link EvalCache} first and stores what it searches. */
public final class CachingEvaluator implements PositionEvaluator {

    private final PositionEvaluator engine;
    private final EvalCache cache;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    public CachingEvaluator(PositionEvaluator engine, EvalCache cache) {
        this.engine = engine;
        this.cache = cache;
    }

    @Override
    public PositionEval evaluate(String fen, int multiPv, long nodes) throws Exception {
        PositionEval hit = cache.get(fen, multiPv, nodes);
        if (hit != null) {
            hits.incrementAndGet();
            return hit;
        }
        misses.incrementAndGet();
        PositionEval p = engine.evaluate(fen, multiPv, nodes);
        cache.put(p, multiPv, nodes);
        return p;
    }

    @Override
    public PositionEval addSecondLine(PositionEval p, long mainNodes, long secondNodes) throws Exception {
        PositionEval hit = cache.get(p.fen(), 2, mainNodes);
        if (hit != null) {
            hits.incrementAndGet();
            return hit;
        }
        misses.incrementAndGet();
        PositionEval r = engine.addSecondLine(p, mainNodes, secondNodes);
        if (r.secondBest() != null) {
            cache.put(r, 2, mainNodes);
        }
        return r;
    }

    @Override
    public void newGame() {
        engine.newGame();
    }

    public int hits() {
        return hits.get();
    }

    public int misses() {
        return misses.get();
    }

    @Override
    public int parallelism() {
        return engine.parallelism();
    }

    @Override
    public String id() {
        return engine.id();
    }

    @Override
    public void close() {
        cache.close();
        try {
            engine.close();
        } catch (Exception ignored) {
            // engines close best effort
        }
    }
}
