package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Engine.review.Eval;
import io.github.hardin22.javachess.Engine.review.GameReplay;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Engine.review.PositionEval;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.ArrayList;
import java.util.List;

/** Hand-made review results (the real reviewer needs Stockfish). */
final class ReviewFixtures {

    private ReviewFixtures() {
    }

    /** One move's review data: label, best move (null = same as played), win chance before/after, accuracy. */
    record Spec(MoveClassification label, String best, double winBefore, double winAfter, double accuracy) {
        static Spec good() {
            return new Spec(MoveClassification.BEST, null, 0.5, 0.5, 100);
        }

        static Spec book() {
            return new Spec(MoveClassification.BOOK_MOVE, null, 0.5, 0.5, 100);
        }

        static Spec bad(MoveClassification label, String best, double before, double after, double accuracy) {
            return new Spec(label, best, before, after, accuracy);
        }
    }

    static GameReview review(String initialFen, List<String> uci, List<Spec> specs) {
        GameReplay replay = GameReplay.of(initialFen, uci);
        List<MoveReview> moves = new ArrayList<>();
        List<PositionEval> positions = new ArrayList<>();
        for (int i = 0; i < replay.uci().size(); i++) {
            Spec s = i < specs.size() ? specs.get(i) : Spec.good();
            String fen = replay.fens().get(i);
            boolean white = MoveText.whiteToMove(fen);
            String best = s.best() == null ? replay.uci().get(i) : s.best();
            List<String> line = s.best() == null ? List.of(best) : List.of(best);
            moves.add(new MoveReview(i, replay.uci().get(i), replay.sans().get(i), fen, white, s.label(),
                    Eval.cp(white ? 50 : -50), Eval.cp(white ? -100 : 100), best, line, s.winBefore(), s.winAfter(),
                    s.accuracy()));
            positions.add(new PositionEval(fen, Eval.cp(0), List.of(), 10, 0, false));
        }
        positions.add(new PositionEval(replay.fens().get(replay.fens().size() - 1), Eval.cp(0), List.of(), 10, 0,
                false));
        return new GameReview(replay.initialFen(), moves, positions, 90, 80, "C50 Italian Game", GameReview.Stats.NONE);
    }
}
