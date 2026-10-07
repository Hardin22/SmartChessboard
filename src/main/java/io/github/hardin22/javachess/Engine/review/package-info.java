/**
 * Move classification and game review, shared by the review screen, the archive and the board LEDs.
 *
 * <h2>Evaluations</h2>
 * {@link io.github.hardin22.javachess.Engine.review.Eval} is always from WHITE's point of view with explicit mates
 * ({@code CP}, {@code WHITE_MATES n}, {@code BLACK_MATES n}; n = 0 is a checkmate on the board). Convert a UCI score
 * once with {@code Eval.fromUci(score, whiteToMove)} of the position it belongs to and never negate scores by hand;
 * positions without legal moves come from {@code Eval.terminal(board)}. Win chances:
 * {@link io.github.hardin22.javachess.Engine.review.WinModel}.
 *
 * <h2>Fast verdict (LEDs)</h2>
 * {@code ReviewClassifier.fast(best, played, whiteMoved, playedIsBest)}: no MultiPV, no history; labels BEST ..
 * BLUNDER with the review thresholds and the mate rules (delivering mate is Best, allowing a new forced mate is a
 * Blunder).
 *
 * <h2>Full review</h2>
 * Pure: {@code ReviewClassifier.classifyGame(ReviewInput)} labels every move (Book, Forced, Brilliant, Great, Miss
 * and the standard labels) from stored {@link io.github.hardin22.javachess.Engine.review.PositionEval}s and computes
 * the accuracy ({@link io.github.hardin22.javachess.Engine.review.Accuracy}); the players' ratings, when known, make
 * the labels closer to chess.com (its expected points depend on the rating). The calibrated numbers live in
 * {@code ReviewClassifier.Tuning}. With an engine:
 * {@link io.github.hardin22.javachess.Engine.review.GameReviewer} evaluates every position once (node budget, in
 * parallel on a {@link io.github.hardin22.javachess.Engine.review.StockfishPool}), re-searches with MultiPV 2 only
 * the positions returned by {@code ReviewClassifier.needsSecondLine}, and caches evaluations on disk
 * ({@link io.github.hardin22.javachess.Engine.review.EvalCache}).
 */
package io.github.hardin22.javachess.Engine.review;
