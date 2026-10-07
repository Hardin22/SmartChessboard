package io.github.hardin22.javachess.Engine.review;

import io.github.hardin22.javachess.review.EvalDump;
import io.github.hardin22.javachess.review.LabelledGame;
import io.github.hardin22.javachess.review.ReviewLabel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 4 "different engine" check (category 1): reclassifies a ply with the engine oracle's Stockfish 16 depth 22
 * MultiPV 5 lines ({@code notes/special/oracle/<id>_<ply>.json}) in place of the product's lines at the position
 * before the move, everything else as in {@code SpecialLabelsGateTest}. A Brilliant/Great disagreement is an engine
 * difference when the label on the SF16 lines is chess.com's. Diagnostic only: skipped unless asked for.
 * <pre>
 * ./mvnw test -DskipE2E=true -Dtest=SpecialLabelsGateTest                       # writes target/special/errors.tsv
 * ./mvnw test -DskipE2E=true -Dtest=Sf16SwapProbe -Dreview.swap=true [-Dreview.swap.shard=5]
 *     [-Dreview.swap.keys=id:ply,id:ply]     # given plies instead of the gate errors
 *     # -> target/special/sf16swap.tsv: id ply san kind ours chesscom label_on_sf16 rank top second
 * </pre>
 */
class Sf16SwapProbe {

    @Test
    void swap() throws Exception {
        assumeTrue(Boolean.getBoolean("review.swap"), "SF16 swap probe not requested (-Dreview.swap=true)");
        Path data = Path.of(System.getProperty("user.home"), ".javachess-orchestrator", "review-team", "data");
        Path oracle = data.getParent().resolve("notes/special/oracle");
        Path evals = data.resolve("evals_labeled");
        assumeTrue(Files.isDirectory(oracle) && Files.isDirectory(evals), "review team data not found");
        Map<String, EvalDump.Game> dumps = new HashMap<>();
        for (EvalDump.Game d : EvalDump.load(evals, "lite-block")) {
            dumps.put(d.id(), d);
        }
        for (EvalDump.Game d : EvalDump.load(evals.resolve("holdout"), "lite-block")) {
            dumps.put(d.id(), d);
        }
        for (EvalDump.Game d : EvalDump.load(data.resolve("evals_famous"), "lite")) {
            dumps.put(d.id(), d);
        }
        Map<String, LabelledGame> games = new HashMap<>();
        for (LabelledGame g : LabelledGame.loadAll(data.resolve("labels_chesscom_sf22"), data.resolve("games"))) {
            games.put(g.id(), g);
        }
        for (LabelledGame g : LabelledGame.loadAll(data.resolve("famous_chesscom"), data.resolve("famous"))) {
            games.put(g.id(), g);
        }

        // id:ply -> kind ("" for plies given by hand)
        Map<String, String> wanted = new LinkedHashMap<>();
        String keys = System.getProperty("review.swap.keys", "");
        if (!keys.isBlank()) {
            for (String k : keys.split(",")) {
                wanted.put(k.trim(), "");
            }
        } else {
            String shard = System.getProperty("review.swap.shard", "");
            for (String line : Files.readAllLines(Path.of("target/special/errors.tsv"))) {
                String[] c = line.split("\t");
                if (!c[0].equals("shard") && (shard.isEmpty() || c[0].equals(shard))) {
                    wanted.merge(c[2] + ":" + c[3], c[5], (a, b) -> a + "," + b);
                }
            }
        }

        OpeningBook book = OpeningBook.standard();
        StringBuilder sb = new StringBuilder("id\tply\tsan\tkind\tours\tchesscom\tlabel_on_sf16\trank\ttop\tsecond\n");
        for (Map.Entry<String, String> w : wanted.entrySet()) {
            String id = w.getKey().substring(0, w.getKey().lastIndexOf(':'));
            int ply = Integer.parseInt(w.getKey().substring(w.getKey().lastIndexOf(':') + 1));
            LabelledGame g = games.get(id);
            EvalDump.Game d = dumps.get(id);
            if (g == null || d == null) {
                sb.append(id).append('\t').append(ply).append("\t\t").append(w.getValue()).append("\tNO-GAME\n");
                continue;
            }
            ReviewInput base = d.input(EvalDump.Mode.PRODUCT, book);
            ReviewInput in = new ReviewInput(base.initialFen(), base.uciMoves(), base.positions(), base.book(),
                    g.whiteRating(), g.blackRating());
            MoveReview ours = ReviewClassifier.classifyGame(in).moves().get(ply - 1);
            String head = String.join("\t", id, String.valueOf(ply), ours.san(), w.getValue(), lower(ours),
                    g.labels().get(ply - 1).name().toLowerCase());
            Path f = oracle.resolve(id + "_" + ply + ".json");
            if (!Files.exists(f)) {
                sb.append(head).append("\tNO-ORACLE\n");
                continue;
            }
            JSONObject root = new JSONObject(Files.readString(f));
            JSONObject o = root.getJSONObject("sf16_d22");
            List<EngineLine> lines = new ArrayList<>();
            JSONArray ls = o.getJSONArray("lines");
            for (int j = 0; j < ls.length(); j++) {
                lines.add(line(ls.getJSONObject(j)));
            }
            String played = root.getString("move");
            if (lines.stream().noneMatch(l -> l.move().equals(played)) && o.has("played") && !o.isNull("played")) {
                lines.add(line(o.getJSONObject("played"))); // the played move's own line, after the top 5
            }
            List<PositionEval> ps = new ArrayList<>(in.positions());
            PositionEval p0 = ps.get(ply - 1);
            ps.set(ply - 1, new PositionEval(p0.fen(), lines.get(0).eval(), lines, 22, 0, false));
            ReviewInput swapped = new ReviewInput(in.initialFen(), in.uciMoves(), ps, in.book(), in.whiteRating(),
                    in.blackRating());
            MoveReview m = ReviewClassifier.classifyGame(swapped).moves().get(ply - 1);
            sb.append(head).append('\t').append(String.join("\t", lower(m),
                    o.isNull("rank") ? "-" : String.valueOf(o.getInt("rank")),
                    lines.get(0).move() + " " + lines.get(0).eval().format(),
                    lines.size() > 1 ? lines.get(1).move() + " " + lines.get(1).eval().format() : "")).append('\n');
        }
        Path out = Path.of("target/special/sf16swap.tsv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
    }

    private static String lower(MoveReview m) {
        return ReviewLabel.of(m.label()).name().toLowerCase();
    }

    /** One oracle line: scores are White's point of view, {@code cp:-45}, {@code W#3}, {@code B#2}. */
    private static EngineLine line(JSONObject l) {
        String e = l.getString("e");
        Eval ev = e.startsWith("cp:") ? Eval.cp(Integer.parseInt(e.substring(3)))
                : e.startsWith("W#") ? Eval.whiteMates(Math.abs(Integer.parseInt(e.substring(2))))
                : Eval.blackMates(Math.abs(Integer.parseInt(e.substring(2))));
        List<String> pv = new ArrayList<>();
        JSONArray a = l.getJSONArray("pv");
        for (int j = 0; j < a.length(); j++) {
            pv.add(a.getString(j));
        }
        return new EngineLine(l.getString("move"), ev, pv, 22);
    }
}
