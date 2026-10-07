package io.github.hardin22.javachess.review;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.hardin22.javachess.review.ReviewLabel.*;
import static org.junit.jupiter.api.Assertions.*;

/** The harness maths on hand-made reviews (fast, no engine). */
class AgreementReportTest {

    static final List<String> GXH5 = List.of("e4", "d5", "exd5", "Bd7", "d4", "a5", "c4", "f6", "Qh5+", "g6", "Be2",
            "gxh5", "Bxh5#");

    static ChessComDataset.Game game(List<String> san, double w, double b, List<String> labels) {
        JSONObject o = new JSONObject();
        o.put("id", "t" + san.size());
        o.put("url", "https://example.invalid/" + san.size());
        o.put("time_class", "blitz");
        o.put("white_rating", 1000);
        o.put("black_rating", 1100);
        o.put("result", "1-0");
        o.put("moves_san", new JSONArray(san));
        o.put("accuracy", new JSONObject().put("white", w).put("black", b));
        if (labels != null) {
            o.put("labels", new JSONArray(labels));
        }
        return ChessComDataset.parse(o);
    }

    static Reviewer.Result result(List<ReviewLabel> labels, double w, double b) {
        return new Reviewer.Result(labels, w, b, List.of(), 130);
    }

    @Test
    void mateSanityFlagsTheUsersBug() {
        ChessComDataset.Game g = game(GXH5, 80, 30, null);
        List<ReviewLabel> ours = new ArrayList<>(Collections.nCopies(13, BEST));
        AgreementReport r = new AgreementReport("t");
        r.add(g, result(ours, 80, 30));
        assertEquals(1, r.mateViolations().size(), "6...gxh5?? labelled best");
        assertEquals(11, r.mateViolations().get(0).ply());
        assertEquals("6... gxh5", r.mateViolations().get(0).moveText());

        ours.set(11, BLUNDER);
        ours.set(12, BLUNDER);
        AgreementReport r2 = new AgreementReport("t");
        r2.add(g, result(ours, 80, 30));
        assertEquals(1, r2.mateViolations().size(), "7.Bxh5# labelled blunder");
        assertEquals(12, r2.mateViolations().get(0).ply());
        assertEquals(1, r2.summaryJson().getInt("mateInOneAllowedCalledBlunder"));
    }

    @Test
    void unavoidableMateInOneIsNotAViolation() {
        // 1.f3 e5 2.g4?? Qh4#: White had safe moves, so 2.g4 counts
        assertTrue(AgreementReport.couldAvoidMateInOne(
                "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq - 0 2"));
        // Black's only move 1...Kb8 runs into 2.Rh8#: allowing that mate is not a mistake
        assertFalse(AgreementReport.couldAvoidMateInOne("k7/8/1K6/8/8/8/8/7R b - - 0 1"));
    }

    @Test
    void accuracyStatistics() {
        AgreementReport r = new AgreementReport("t");
        List<ReviewLabel> best = Collections.nCopies(13, BEST);
        r.add(game(GXH5, 80, 30, null), result(best, 84, 28)); // errors +4, -2
        r.add(game(GXH5, 60, 50, null), result(best, 60, 56)); // errors 0, +6
        assertEquals(3.0, r.accuracyMae(), 1e-9);
        assertEquals(2.0, r.accuracyBias(), 1e-9);
        assertEquals(Math.sqrt((16 + 4 + 0 + 36) / 4.0), r.accuracyRmse(), 1e-9);
        assertEquals(0.75, r.accuracyWithin(5), 1e-9);
        assertTrue(r.accuracyCorrelation() > 0.9);
        assertFalse(r.hasLabels());
        assertTrue(r.markdown(5).contains("| all | 4 | 3.00 | +2.00 |"), r.markdown(5));
    }

    @Test
    void confusionAndAgreement() {
        List<String> theirs = new ArrayList<>(Collections.nCopies(13, "best"));
        theirs.set(0, "book");
        theirs.set(11, "blunder");
        List<ReviewLabel> ours = new ArrayList<>(Collections.nCopies(13, BEST));
        ours.set(0, BOOK);
        ours.set(1, EXCELLENT); // same bucket as best
        ours.set(2, GOOD);      // off by one bucket
        AgreementReport r = new AgreementReport("t");
        r.add(game(GXH5, 80, 30, theirs), result(ours, 80, 30));
        int[][] m = r.confusion();
        assertEquals(1, m[BLUNDER.ordinal()][BEST.ordinal()]);
        assertEquals(1, m[BOOK.ordinal()][BOOK.ordinal()]);
        assertEquals(10.0 / 13, r.labelAgreement(), 1e-9);
        assertEquals(11.0 / 13, r.bucketAgreement(), 1e-9);
        assertEquals(1, r.labelDisagreements().size(), "only the blunder is 2+ buckets away");
        assertTrue(r.markdown(5).contains("Labels vs chess.com"));
    }
}
