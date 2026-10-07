package io.github.hardin22.javachess.review;

/** Reviewer adapters selectable by name ({@code -Dreview.reviewer}). */
final class Reviewers {

    private Reviewers() {
    }

    static Reviewer create(String name, int depth) {
        return switch (name) {
            case "legacy" -> new LegacyGameAnalyzerReviewer(depth);
            default -> throw new IllegalArgumentException("unknown reviewer " + name + " (legacy)");
        };
    }
}
