package io.github.hardin22.javachess.Engine;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Move feedback for the board LEDs, built on the live analysis ({@link PositionAnalyzer}).
 *
 * <p>Strategy (all on one engine, reusing its hash):</p>
 * <ol>
 *   <li>While the player thinks, the live analysis of the current position runs anyway (eval bar): best move
 *       and best score are ready when they move.</li>
 *   <li>Piece lifted: one {@code go searchmoves <moves of that piece>} MultiPV search scores every
 *       destination ({@link #onPieceLifted}); the same numbers give an instant verdict if that piece moves.</li>
 *   <li>Move played ({@link #onMovePlayed}): the live analysis follows the new position; when it reaches the
 *       minimum depth the verdict is emitted (preliminary), and again only if the class changes by the
 *       confirmation depth / time cap.</li>
 * </ol>
 * Classification maths: {@link MoveClassifier}. Events: {@link MoveFeedbackListener}, engine event thread.
 */
public final class MoveCoach {

    private static final Logger log = LoggerFactory.getLogger(MoveCoach.class);
    /** Give up waiting for the minimum depth after this long (engine missing / analysis stopped). */
    static final long HARD_DEADLINE_MS = 8_000;
    private static final int KNOWLEDGE_SIZE = 8;

    private static volatile MoveCoach instance;

    private final PositionAnalyzer analyzer;
    private final Supplier<EngineManager.Budget> budget;
    private volatile MoveFeedbackListener listener;
    private volatile MoveFeedbackListener fallbackListener;
    private volatile boolean enabled = true;
    private final AtomicInteger liftSeq = new AtomicInteger();

    /** Per position: latest live analysis and latest candidate scores. Key = FEN without move counters. */
    private final Map<String, Knowledge> knowledge = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Knowledge> eldest) {
            return size() > KNOWLEDGE_SIZE;
        }
    };

    private volatile Pending pending;

    private static final class Knowledge {
        AnalysisUpdate main;
        SearchResult candidates;
    }

    private static final class Pending {
        final String fenBefore;
        final String afterKey;
        final String uci;
        final long t0 = System.nanoTime();
        volatile Score bestBefore;
        volatile String bestMove;
        volatile boolean playedIsBest;
        MoveQuality emitted;
        boolean confirmed;
        boolean fallbackStarted;
        ScheduledFuture<?> capTask;
        ScheduledFuture<?> deadlineTask;

        Pending(String fenBefore, String afterKey, String uci, Score bestBefore, String bestMove, boolean playedIsBest) {
            this.fenBefore = fenBefore;
            this.afterKey = afterKey;
            this.uci = uci;
            this.bestBefore = bestBefore;
            this.bestMove = bestMove;
            this.playedIsBest = playedIsBest;
        }
    }

    public static MoveCoach get() {
        MoveCoach c = instance;
        if (c == null) {
            synchronized (MoveCoach.class) {
                c = instance;
                if (c == null) {
                    c = new MoveCoach(PositionAnalyzer.get(), () -> EngineManager.get().budget());
                    c.setFallbackListener(new HardwareMoveFeedback()); // board LEDs by default
                    instance = c;
                }
            }
        }
        return c;
    }

    MoveCoach(PositionAnalyzer analyzer, Supplier<EngineManager.Budget> budget) {
        this.analyzer = analyzer;
        this.budget = budget;
        analyzer.addObserver(this::onAnalysis);
    }

    /** Registers the LED renderer (replaces the previous one; null removes it). */
    public void setFeedbackListener(MoveFeedbackListener l) {
        this.listener = l;
    }

    /** True when a renderer is registered. */
    public boolean hasFeedbackListener() {
        return listener != null;
    }

    /** Renderer used while no listener is registered (default: the board LEDs via the hardware layer). */
    public void setFallbackListener(MoveFeedbackListener l) {
        this.fallbackListener = l;
    }

    /** Enables/disables move verdicts and hints (e.g. online games, suggestions off). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            pending = null;
            liftSeq.incrementAndGet();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when no verdict is pending (for tests and diagnostics). */
    boolean isIdle() {
        return pending == null;
    }

    // ------------------------------------------------------------------------------------------
    // Lift hints

    /**
     * A piece was lifted on {@code square} in {@code fen}: scores its legal moves and emits
     * {@link MoveFeedbackListener#onCandidates}. The future completes with the same feedback, or null if stale.
     */
    public CompletableFuture<CandidateFeedback> onPieceLifted(String fen, String square) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }
        int token = liftSeq.incrementAndGet();
        return scorePiece(fen, square).thenApply(fb -> {
            if (fb == null || token != liftSeq.get()) {
                return null;
            }
            dispatch(l -> l.onCandidates(fb));
            return fb;
        });
    }

    /** The lifted piece was put down (back or as a move): cancels hints. */
    public void onPieceReleased() {
        liftSeq.incrementAndGet();
        analyzer.cancelScoring();
        dispatch(MoveFeedbackListener::onClear);
    }

    /** Evaluation off: emits the legal destinations of the lifted piece only (no engine). */
    public void showLegalTargets(String fen, String square) {
        List<String> targets = new ArrayList<>();
        for (Move m : legalMovesFrom(fen, square)) {
            String to = m.getTo().name();
            if (!targets.contains(to)) {
                targets.add(to);
            }
        }
        dispatch(l -> l.onLegalTargets(square.toUpperCase(), List.copyOf(targets)));
    }

    /**
     * Scores every legal move of the piece on {@code square} without emitting LED events
     * (also used by the on-screen hints). Completes with null if there is nothing to score.
     */
    public CompletableFuture<CandidateFeedback> scorePiece(String fen, String square) {
        long t0 = System.nanoTime();
        List<Move> moves = legalMovesFrom(fen, square);
        if (moves.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new CandidateFeedback(fen, square.toUpperCase(), Map.of(), null, 0, 0));
        }
        List<String> uci = moves.stream().map(Move::toString).toList();
        EngineManager.Budget b = budget.get();
        return analyzer.scoreMoves(fen, uci, b.candidateNodes(), b.candidateCapMs()).handle((r, err) -> {
            if (err != null || r == null) {
                log.debug("candidate search failed: {}", err == null ? "no result" : EngineManager.rootMessage(err));
                return null;
            }
            Knowledge k = knowledgeFor(fen);
            AnalysisUpdate main;
            synchronized (knowledge) {
                k.candidates = r;
                main = k.main;
            }
            Best best = bestOf(main, r);
            if (best == null) {
                return null;
            }
            Map<String, MoveQuality> dest = new LinkedHashMap<>();
            List<InfoLine> lines = new ArrayList<>(r.perMove());
            lines.sort(Comparator.comparingInt((InfoLine l) -> l.score().centipawns()).reversed());
            for (InfoLine l : lines) {
                MoveQuality q = MoveClassifier.classify(best.score, l.score(), l.move().equals(best.move)).quality();
                String to = l.move().substring(2, 4).toUpperCase();
                MoveQuality prev = dest.get(to);
                if (prev == null || q.ordinal() < prev.ordinal()) {
                    dest.put(to, q);
                }
            }
            long latency = (System.nanoTime() - t0) / 1_000_000;
            log.debug("candidates {} {}: {} (depth {}, {} ms)", square, fen, dest, r.depth(), latency);
            return new CandidateFeedback(fen, square.toUpperCase(), dest, best.move, r.depth(), latency);
        });
    }

    // ------------------------------------------------------------------------------------------
    // Verdict

    /**
     * A human move was played. Emits {@link MoveFeedbackListener#onMoveClassified} (preliminary, then final
     * only if the class changes) and makes the live analysis follow the new position.
     */
    public void onMovePlayed(String fenBefore, String uci) {
        if (!enabled || fenBefore == null || uci == null) {
            return;
        }
        liftSeq.incrementAndGet();
        String fenAfter = applyMove(fenBefore, uci);
        if (fenAfter == null) {
            log.warn("illegal move {} for coach in {}", uci, fenBefore);
            return;
        }
        Knowledge k = knowledgeFor(fenBefore);
        AnalysisUpdate main;
        SearchResult cand;
        synchronized (knowledge) {
            main = k.main;
            cand = k.candidates;
        }
        Best best = bestOf(main, cand);
        EngineManager.Budget b = budget.get();
        Pending p = new Pending(fenBefore, key(fenAfter), uci, best == null ? null : best.score,
                best == null ? null : best.move, best != null && uci.equals(best.move));
        Pending old = pending;
        if (old != null) {
            cancelTimers(old);
        }
        pending = p;

        // Instant preliminary verdict from the lift search, if it scored this move deep enough.
        InfoLine scored = cand == null ? null : cand.lineFor(uci);
        if (best != null && scored != null && cand.depth() >= b.coachMinDepth() - 2) {
            emit(p, MoveClassifier.classify(best.score, scored.score(), p.playedIsBest), scored.depth(), true);
        }

        if (best == null || best.depth < b.coachMinDepth() - 2) {
            // Moved before the analysis of the previous position got anywhere (game start, very fast move):
            // first get a real "best" for the position before, then follow the new position.
            analyzer.searchBest(fenBefore, b.candidateNodes(), b.coachCapMs()).whenComplete((r, err) ->
                    EngineEvents.EXECUTOR.execute(() -> {
                        if (pending != p) {
                            return;
                        }
                        if (r != null && r.best() != null && (best == null || r.depth() >= best.depth)) {
                            p.bestBefore = r.best().score();
                            p.bestMove = r.bestMove();
                            p.playedIsBest = uci.equals(r.bestMove());
                        }
                        startFollowing(p, fenAfter, b);
                    }));
        } else {
            startFollowing(p, fenAfter, b);
        }
    }

    /**
     * Guaranteed minimum: a {@link PositionAnalyzer.Priority#VERDICT} search of the new position to the verdict depth
     * (queued ahead of a bot move that shares the engine), then the live analysis follows the new position and
     * provides the deeper confirmation.
     */
    private void startFollowing(Pending p, String fenAfter, EngineManager.Budget b) {
        p.capTask = EngineEvents.EXECUTOR.schedule(() -> onCap(p), b.coachCapMs(), TimeUnit.MILLISECONDS);
        p.deadlineTask = EngineEvents.EXECUTOR.schedule(() -> onDeadline(p), HARD_DEADLINE_MS, TimeUnit.MILLISECONDS);
        if (p.emitted == null && legalMoveCount(fenAfter) > 0) {
            analyzer.submit(PositionAnalyzer.Priority.VERDICT, fenAfter,
                    SearchLimits.depth(b.coachMinDepth()).withTimeout(HARD_DEADLINE_MS), Map.of())
                    .whenComplete((r, err) -> EngineEvents.EXECUTOR.execute(() -> {
                        if (err != null || r == null || r.best() == null || pending != p || p.emitted != null) {
                            return;
                        }
                        Score played = r.best().score().negate();
                        Score best = p.bestBefore != null ? p.bestBefore : played;
                        emit(p, MoveClassifier.classify(best, played, p.playedIsBest), r.depth(),
                                r.depth() < b.coachConfirmDepth());
                    }));
        }
        analyzer.follow(fenAfter);
    }

    private static int legalMoveCount(String fen) {
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            return MoveGenerator.generateLegalMoves(board).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private void onAnalysis(AnalysisUpdate u) {
        Knowledge k = knowledgeFor(u.fen());
        synchronized (knowledge) {
            if (k.main == null || u.finished() || u.depth() >= k.main.depth()) {
                k.main = u;
            }
        }
        Pending p = pending;
        if (p == null || p.confirmed) {
            return;
        }
        if (!p.afterKey.equals(key(u.fen()))) {
            if (p.emitted == null && !p.fallbackStarted && !key(p.fenBefore).equals(key(u.fen()))) {
                // The game moved on (e.g. fast bot reply) before the minimum depth: guarantee a verdict with a
                // short dedicated search of the played move from the position before it.
                p.fallbackStarted = true;
                guaranteedVerdict(p);
            }
            return;
        }
        EngineManager.Budget b = budget.get();
        Score playedForMover;
        if (u.terminalScore() != null) {
            playedForMover = u.terminalScore().mate() ? Score.mate(1) : Score.cp(0); // we mated / stalemated
        } else if (u.depth() >= b.coachMinDepth() || u.finished()) {
            playedForMover = u.score().negate();
        } else {
            return;
        }
        Score bestBefore = p.bestBefore != null ? p.bestBefore : playedForMover;
        MoveClassifier.Classification c = MoveClassifier.classify(bestBefore, playedForMover, p.playedIsBest);
        boolean confirmation = u.finished() || u.terminalScore() != null || u.depth() >= b.coachConfirmDepth();
        if (p.emitted == null) {
            emit(p, c, u.depth(), !confirmation);
        } else if (confirmation && c.quality() != p.emitted) {
            emitFinalChange(p, c, u.depth());
        }
        if (confirmation) {
            finish(p);
        }
    }

    private void guaranteedVerdict(Pending p) {
        EngineManager.Budget b = budget.get();
        analyzer.scoreMoves(p.fenBefore, List.of(p.uci), b.candidateNodes(), b.coachCapMs(),
                PositionAnalyzer.Priority.VERDICT).thenAccept(r -> {
            EngineEvents.EXECUTOR.execute(() -> {
                InfoLine line = r == null ? null : r.lineFor(p.uci);
                if (pending != p || p.emitted != null || line == null) {
                    return;
                }
                Score best = p.bestBefore != null ? p.bestBefore : line.score();
                emit(p, MoveClassifier.classify(best, line.score(), p.playedIsBest), line.depth(), false);
                finish(p);
            });
        });
    }

    private void onCap(Pending p) {
        if (pending != p || p.emitted == null) {
            return; // still waiting for the minimum depth (until the hard deadline)
        }
        finish(p);
    }

    private void onDeadline(Pending p) {
        if (pending == p) {
            if (p.emitted == null) {
                log.info("no verdict for {}: analysis did not reach depth {} in {} ms", p.uci,
                        budget.get().coachMinDepth(), HARD_DEADLINE_MS);
            }
            finish(p);
        }
    }

    private void finish(Pending p) {
        p.confirmed = true;
        cancelTimers(p);
        if (pending == p) {
            pending = null;
        }
    }

    private static void cancelTimers(Pending p) {
        if (p.capTask != null) {
            p.capTask.cancel(false);
        }
        if (p.deadlineTask != null) {
            p.deadlineTask.cancel(false);
        }
    }

    private void emit(Pending p, MoveClassifier.Classification c, int depth, boolean preliminary) {
        p.emitted = c.quality();
        MoveFeedback fb = feedback(p, c, depth, preliminary, false);
        log.info("move {} -> {} (loss {}%, depth {}, {} ms{})", p.uci, c.quality(),
                String.format(java.util.Locale.ROOT, "%.1f", c.winLoss() * 100), depth, fb.latencyMs(),
                preliminary ? ", preliminary" : "");
        dispatch(l -> l.onMoveClassified(fb));
    }

    private void emitFinalChange(Pending p, MoveClassifier.Classification c, int depth) {
        MoveQuality before = p.emitted;
        p.emitted = c.quality();
        MoveFeedback fb = feedback(p, c, depth, false, true);
        log.info("move {} re-classified {} -> {} at depth {} ({} ms)", p.uci, before, c.quality(), depth,
                fb.latencyMs());
        dispatch(l -> l.onMoveClassified(fb));
    }

    private static MoveFeedback feedback(Pending p, MoveClassifier.Classification c, int depth, boolean preliminary,
                                         boolean changed) {
        String best = p.playedIsBest || p.bestMove == null ? null : p.bestMove;
        return new MoveFeedback(p.fenBefore, p.uci, p.uci.substring(0, 2).toUpperCase(),
                p.uci.substring(2, 4).toUpperCase(), c.quality(), preliminary, changed, best,
                best == null ? null : best.substring(0, 2).toUpperCase(),
                best == null ? null : best.substring(2, 4).toUpperCase(), c.winLoss() * 100, depth,
                (System.nanoTime() - p.t0) / 1_000_000);
    }

    private void dispatch(java.util.function.Consumer<MoveFeedbackListener> event) {
        MoveFeedbackListener l = listener != null ? listener : fallbackListener;
        if (l == null) {
            return;
        }
        EngineEvents.EXECUTOR.execute(() -> {
            try {
                event.accept(l);
            } catch (RuntimeException e) {
                log.warn("move feedback listener failed: {}", e.toString());
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    private record Best(Score score, String move, int depth) {
    }

    /**
     * Best score/move of a position (mover POV) from the live analysis and/or a candidate search.
     * The same move scored by both keeps the deeper score; otherwise the highest score wins.
     */
    static Best bestOf(AnalysisUpdate main, SearchResult cand) {
        Best b = null;
        if (main != null && main.best() != null) {
            b = new Best(main.best().score(), main.bestMove(), main.depth());
        }
        if (cand != null) {
            for (InfoLine l : cand.perMove()) {
                if (b != null && l.move().equals(b.move)) {
                    if (l.depth() >= b.depth) {
                        b = new Best(l.score(), l.move(), l.depth());
                    }
                } else if (b == null || l.score().centipawns() > b.score.centipawns()) {
                    b = new Best(l.score(), l.move(), l.depth());
                }
            }
        }
        return b;
    }

    private Knowledge knowledgeFor(String fen) {
        synchronized (knowledge) {
            return knowledge.computeIfAbsent(key(fen), x -> new Knowledge());
        }
    }

    /** FEN without the move counters (positions repeat with different counters). */
    static String key(String fen) {
        String[] f = fen.trim().split("\\s+");
        return f.length >= 4 ? f[0] + " " + f[1] + " " + f[2] + " " + f[3] : fen.trim();
    }

    static List<Move> legalMovesFrom(String fen, String square) {
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            Square from = Square.valueOf(square.toUpperCase());
            return MoveGenerator.generateLegalMoves(board).stream().filter(m -> m.getFrom() == from).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    static String applyMove(String fen, String uci) {
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            Move m = new Move(uci, board.getSideToMove());
            if (!board.legalMoves().contains(m)) {
                return null;
            }
            board.doMove(m);
            return board.getFen();
        } catch (Exception e) {
            return null;
        }
    }
}
