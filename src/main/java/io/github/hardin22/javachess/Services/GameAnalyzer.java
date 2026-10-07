package io.github.hardin22.javachess.Services;

import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Engine.review.GameReplay;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.GameReviewer;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.ReviewListener;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Full game review for the review screen: adapts {@link GameReviewer} (package {@code Engine.review}) to the
 * {@link MoveAnalysis} list the UI shows. The live analysis pauses while a review runs, so the review processes
 * get all the cores.
 */
public class GameAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GameAnalyzer.class);

    private final Supplier<GameReviewer> reviewers;
    private volatile GameReview lastReview;
    private volatile List<MoveAnalysis> lastAnalysis = List.of();

    public GameAnalyzer() {
        this(GameReviewer::createDefault);
    }

    /** @param reviewers creates the reviewer of one review (closed when the review ends) */
    public GameAnalyzer(Supplier<GameReviewer> reviewers) {
        this.reviewers = reviewers;
    }

    public List<MoveAnalysis> analyzeGame(String pgn, int depth, Consumer<Double> progressCallback) {
        return analyzeGame(pgn, GameReplay.START_FEN, depth, progressCallback);
    }

    /** Analyses a game from {@code initialFen} given as a list of UCI moves. Blocking. */
    public List<MoveAnalysis> analyzeGame(String initialFen, List<String> uciMoves, int depth,
                                          Consumer<Double> progressCallback) {
        return analyzeGame(String.join(" ", uciMoves), initialFen, depth, progressCallback);
    }

    /**
     * Analyses a game given as UCI or SAN moves (move numbers and results are ignored; stops at the first unreadable
     * move). {@code depth} is ignored: the review uses the node budget of the active engine profile. Blocking: call
     * it from a background thread. Returns an empty list when the engine fails.
     */
    public List<MoveAnalysis> analyzeGame(String pgn, String initialFen, int depth, Consumer<Double> progressCallback) {
        GameReview review = review(pgn, initialFen, progressCallback);
        return review == null ? List.of() : lastAnalysis;
    }

    /** Runs the review and returns the full result (null when there is no move or the engine failed). Blocking. */
    public GameReview review(String movetext, String initialFen, Consumer<Double> progressCallback) {
        return review(movetext, initialFen, progressCallback, null);
    }

    /**
     * Like {@link #review(String, String, Consumer)}, also publishing provisional rows (the first moves of the game,
     * without Great/Brilliant) while the review runs. Callbacks run on review threads.
     */
    public GameReview review(String movetext, String initialFen, Consumer<Double> progressCallback,
                             Consumer<List<MoveAnalysis>> partialCallback) {
        return review(movetext, initialFen, 0, 0, progressCallback, partialCallback);
    }

    /** Like {@link #review(String, String, Consumer, Consumer)} with the players' ratings (0 = unknown). */
    public GameReview review(String movetext, String initialFen, int whiteRating, int blackRating,
                             Consumer<Double> progressCallback, Consumer<List<MoveAnalysis>> partialCallback) {
        GameReplay replay = GameReplay.of(initialFen, movetext);
        lastReview = null;
        lastAnalysis = List.of();
        if (replay.uci().isEmpty()) {
            return null;
        }
        ReviewListener listener = new ReviewListener() {
            @Override
            public void onProgress(double fraction) {
                if (progressCallback != null) {
                    progressCallback.accept(fraction);
                }
            }

            @Override
            public void onPartial(GameReview partial) {
                if (partialCallback != null) {
                    partialCallback.accept(toMoveAnalysis(partial));
                }
            }
        };
        AutoCloseable hold = holdLiveAnalysis();
        try (GameReviewer reviewer = reviewers.get()) {
            GameReview r = reviewer.review(replay.initialFen(), replay.uci(), whiteRating, blackRating, listener);
            lastAnalysis = toMoveAnalysis(r);
            lastReview = r;
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("review cancelled");
        } catch (ExecutionException | RuntimeException e) {
            log.error("review engine failed: {}", e.toString());
        } finally {
            try {
                hold.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
        return null;
    }

    /** Rows of the last completed review (empty when it failed). */
    public List<MoveAnalysis> lastAnalysis() {
        return lastAnalysis;
    }

    /** The last completed review of this analyzer, or null. */
    public GameReview lastReview() {
        return lastReview;
    }

    private static AutoCloseable holdLiveAnalysis() {
        try {
            return EngineManager.get().analyzer().holdLive();
        } catch (RuntimeException e) {
            return () -> { };
        }
    }

    /** The review as the rows of the review screen. */
    public static List<MoveAnalysis> toMoveAnalysis(GameReview r) {
        List<MoveAnalysis> out = new ArrayList<>(r.moves().size());
        for (MoveReview m : r.moves()) {
            out.add(new MoveAnalysis(
                    m.ply() + 1,
                    m.uci(),
                    m.fenBefore(),
                    m.after().legacyPawns() * 100, // White POV, as the graph expects
                    m.bestMove() == null ? "" : m.bestMove(),
                    m.label(),
                    m.winLoss() * 100,
                    squareIndex(m.uci()),
                    m.after().isMate(),
                    m.accuracy(),
                    m.winAfter(),
                    m.winBefore()));
        }
        return out;
    }

    private static int squareIndex(String uci) {
        int file = uci.charAt(2) - 'a';
        int rank = uci.charAt(3) - '1';
        return rank * 8 + file; // chesslib Square ordinal: A1=0 .. H8=63
    }

    /**
     * Accuracy (0..100, one decimal) of a side: the review's own figure when {@code analysis} is the result of the
     * last review, otherwise the mean of the per-move accuracies.
     */
    public double calculateAccuracy(List<MoveAnalysis> analysis, boolean isWhite) {
        GameReview r = lastReview;
        double acc;
        if (r != null && analysis == lastAnalysis) {
            acc = isWhite ? r.whiteAccuracy() : r.blackAccuracy();
        } else {
            double sum = 0;
            int n = 0;
            for (MoveAnalysis m : analysis) {
                if (m.isWhiteMove() == isWhite && m.getClassification() != MoveClassification.BOOK_MOVE) {
                    sum += m.getAccuracy();
                    n++;
                }
            }
            acc = n == 0 ? Double.NaN : sum / n;
        }
        if (Double.isNaN(acc)) {
            return 100.0; // only book moves: nothing to blame
        }
        return Math.round(acc * 10.0) / 10.0;
    }
}
