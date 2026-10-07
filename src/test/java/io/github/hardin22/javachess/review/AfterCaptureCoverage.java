package io.github.hardin22.javachess.review;

import io.github.hardin22.javachess.Engine.review.EngineLine;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Engine.review.ReviewClassifier;
import io.github.hardin22.javachess.Engine.review.ReviewInput;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Developer tool: the product's after-capture requests ({@link ReviewClassifier#afterCaptureRequests}, run with
 * {@code -Djavachess.review.afterCaptureSearch=1}) on the 177 gate games against the stored searches of
 * {@link CaptureEvals}: same move and capture, requested but not stored, stored but not requested.
 */
public final class AfterCaptureCoverage {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(System.getProperty("user.home"), ".javachess-orchestrator/review-team/data");
        Path evals = data.resolve("evals_labeled");
        Map<String, EvalDump.Game> dumps = new HashMap<>();
        for (EvalDump.Game d : EvalDump.load(evals, "lite-block")) dumps.put(d.id(), d);
        for (EvalDump.Game d : EvalDump.load(evals.resolve("holdout"), "lite-block")) dumps.put(d.id(), d);
        for (EvalDump.Game d : EvalDump.load(data.resolve("evals_famous"), "lite")) dumps.put(d.id(), d);
        Map<String, Map<Integer, EngineLine>> stored = CaptureEvals.load(CaptureEvals.FILE);
        OpeningBook book = OpeningBook.standard();
        int same = 0, otherCapture = 0, extra = 0, missing = 0;
        for (EvalDump.Game d : dumps.values()) {
            ReviewInput in = d.input(EvalDump.Mode.PRODUCT, book);
            Map<Integer, String> req = ReviewClassifier.afterCaptureRequests(in);
            Map<Integer, EngineLine> st = stored.getOrDefault(d.id(), Map.of());
            for (Map.Entry<Integer, String> e : req.entrySet()) {
                EngineLine s = st.get(e.getKey());
                if (s == null) {
                    extra++;
                } else if (s.move().equals(e.getValue())) {
                    same++;
                } else {
                    otherCapture++;
                    System.out.println("other capture " + d.id() + " ply " + (e.getKey() + 1) + ": requested "
                            + e.getValue() + ", stored " + s.move());
                }
            }
            for (Integer i : st.keySet()) {
                if (!req.containsKey(i)) {
                    missing++;
                    System.out.println("not requested " + d.id() + " ply " + (i + 1) + " " + st.get(i).move());
                }
            }
        }
        System.out.printf("requests: %d same as stored, %d other capture, %d not stored (extra), %d stored but not "
                + "requested%n", same, otherCapture, extra, missing);
    }
}
