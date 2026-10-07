package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.Locale;

/** chess.com Game Review labels, the common vocabulary of the harness (ours are mapped onto these). */
public enum ReviewLabel {
    BRILLIANT, GREAT, BEST, EXCELLENT, GOOD, BOOK, INACCURACY, MISTAKE, MISS, BLUNDER, FORCED;

    /** Coarse bucket used for the "near agreement" score: equal buckets are a soft match. */
    public int bucket() {
        return switch (this) {
            case BRILLIANT, GREAT, BEST, EXCELLENT, FORCED -> 0;
            case GOOD -> 1;
            case BOOK -> 2;
            case INACCURACY -> 3;
            case MISTAKE, MISS -> 4;
            case BLUNDER -> 5;
        };
    }

    public boolean isError() {
        return this == INACCURACY || this == MISTAKE || this == MISS || this == BLUNDER;
    }

    public boolean isGood() {
        return bucket() <= 2;
    }

    public static ReviewLabel of(MoveClassification c) {
        return c == MoveClassification.BOOK_MOVE ? BOOK : valueOf(c.name());
    }

    /** Parses chess.com / dataset spellings ("brilliant", "Great Move", "bestmove", "book", "??"...); null if unknown. */
    public static ReviewLabel parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("move", "");
        return switch (k) {
            case "brilliant", "!!" -> BRILLIANT;
            case "great", "greatfind", "critical", "!" -> GREAT;
            case "best", "perfect", "*" -> BEST;
            case "excellent" -> EXCELLENT;
            case "good", "okay", "ok" -> GOOD;
            case "book", "theory" -> BOOK;
            case "inaccuracy", "dubious", "?!" -> INACCURACY;
            case "mistake", "?" -> MISTAKE;
            case "miss", "missedwin", "x" -> MISS;
            case "blunder", "??" -> BLUNDER;
            case "forced" -> FORCED;
            default -> null;
        };
    }

    /** Short tag for tables. */
    public String abbrev() {
        return switch (this) {
            case BRILLIANT -> "!!";
            case GREAT -> "!";
            case BEST -> "best";
            case EXCELLENT -> "exc";
            case GOOD -> "good";
            case BOOK -> "book";
            case INACCURACY -> "?!";
            case MISTAKE -> "?";
            case MISS -> "miss";
            case BLUNDER -> "??";
            case FORCED -> "forced";
        };
    }
}
