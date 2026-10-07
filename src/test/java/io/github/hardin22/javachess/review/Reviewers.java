package io.github.hardin22.javachess.review;

/** Reviewer adapters selectable by name ({@code -Dreview.reviewer}). */
final class Reviewers {

    private Reviewers() {
    }

    static Reviewer create(String name, int depth) {
        return switch (name) {
            case "core" -> new CoreReviewer(ReviewBenchmarkTest.stockfish());
            case "analyzer", "legacy" -> new LegacyGameAnalyzerReviewer(depth);
            default -> throw new IllegalArgumentException("unknown reviewer " + name + " (core, analyzer)");
        };
    }
}
