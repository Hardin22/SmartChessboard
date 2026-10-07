package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis;
import io.github.hardin22.javachess.Services.GameAnalyzer;

import java.util.List;

/** The review as the app screens call it ({@link GameAnalyzer} facade); before the redesign this was the legacy review. */
public final class LegacyGameAnalyzerReviewer implements Reviewer {

    private final int depth;

    public LegacyGameAnalyzerReviewer(int depth) {
        this.depth = depth;
    }

    @Override
    public String name() {
        return "GameAnalyzer facade, depth " + depth;
    }

    @Override
    public Result review(ChessComDataset.Game game) {
        GameAnalyzer analyzer = new GameAnalyzer();
        long t0 = System.nanoTime();
        List<MoveAnalysis> a = analyzer.analyzeGame(String.join(" ", game.uci()), depth, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (a.size() != game.plies()) {
            throw new IllegalStateException(game.id() + ": reviewed " + a.size() + " of " + game.plies() + " plies");
        }
        return new Result(a.stream().map(m -> ReviewLabel.of(m.getClassification())).toList(),
                analyzer.calculateAccuracy(a, true), analyzer.calculateAccuracy(a, false),
                a.stream().map(MoveAnalysis::getScore).toList(), ms);
    }
}
