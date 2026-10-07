package io.github.hardin22.javachess.Vision;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;



import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The vision battery: real pictures of chess.com and lichess boards with the expected position, read by the model.
 * Accuracy is reported by site, theme, piece set and situation (last move highlighted, arrows, dragged piece,
 * promotion menu, popup...), with the kind of each error, and, for positions that follow each other, whether the
 * synchronisation would recognise the move from the vision reading.
 *
 * <ul>
 *   <li>Core set, committed: {@code src/test/resources/vision/battery/} (lichess artwork with free licences and the
 *       local test page). Thresholds are asserted on it.</li>
 *   <li>Extended set, not committed (site artwork): every folder with a {@code manifest.json} under
 *       {@code target/vision-battery/} ({@link BatteryDownload}, browser captures). Reported; asserted only with
 *       {@code -Djavachess.vision.battery.strict=true}.</li>
 * </ul>
 * The report is printed and written to {@code target/vision-battery/report.md}.
 */
class VisionBatteryTest {

    private static PieceClassifier classifier;

    @BeforeAll
    static void loadModel() {
        try {
            classifier = new PieceClassifier(PieceClassifier.DEFAULT_MODEL);
        } catch (Throwable e) {
            classifier = null;
        }
        assumeTrue(classifier != null, "vision model cannot be loaded here");
    }

    @AfterAll
    static void close() {
        if (classifier != null) {
            classifier.close();
        }
    }

    /** One picture and what it must read. */
    record Fixture(String group, String file, JSONObject meta, BufferedImage image) {
        String placement() {
            return meta.getString("placement");
        }

        boolean flipped() {
            return meta.optBoolean("flipped");
        }

        String get(String key) {
            return meta.optString(key, "-");
        }
    }

    /** Result on one picture. */
    record Outcome(Fixture fixture, String read, int wrong, List<String> errors, double minConfidence) {
        boolean exact() {
            return wrong == 0;
        }
    }

    static List<Fixture> loadCore() throws IOException {
        List<Fixture> out = new ArrayList<>();
        try (InputStream in = VisionBatteryTest.class.getResourceAsStream("/vision/battery/manifest.json")) {
            if (in == null) {
                return out;
            }
            JSONArray manifest = new JSONArray(new String(in.readAllBytes()));
            for (int i = 0; i < manifest.length(); i++) {
                JSONObject m = manifest.getJSONObject(i);
                try (InputStream img = VisionBatteryTest.class.getResourceAsStream("/vision/battery/" + m.getString("file"))) {
                    if (img != null) {
                        out.add(new Fixture("core", m.getString("file"), m, ImageIO.read(img)));
                    }
                }
            }
        }
        return out;
    }

    static List<Fixture> loadExtended() throws IOException {
        List<Fixture> out = new ArrayList<>();
        Path root = Path.of(System.getProperty("javachess.vision.battery.dir", "target/vision-battery"));
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var dirs = Files.walk(root, 3)) {
            for (Path manifestFile : dirs.filter(p -> p.getFileName().toString().equals("manifest.json")).toList()) {
                Path dir = manifestFile.getParent();
                JSONArray manifest = new JSONArray(Files.readString(manifestFile));
                for (int i = 0; i < manifest.length(); i++) {
                    JSONObject m = manifest.getJSONObject(i);
                    Path img = dir.resolve(m.getString("file"));
                    if (Files.isRegularFile(img)) {
                        BufferedImage bi = ImageIO.read(img.toFile());
                        if (bi != null) {
                            out.add(new Fixture(dir.getFileName().toString(), m.getString("file"), m, bi));
                        }
                    }
                }
            }
        }
        return out;
    }

    static Outcome read(Fixture f) throws Exception {
        return compare(f, classifier.read(f.image(), f.flipped(), null).withPlacementRules());
    }

    /** colour (right piece, wrong side), type, missing (piece read as empty), phantom (empty read as a piece). */
    static String kind(char want, char got) {
        if (want == BoardReading.EMPTY) {
            return "phantom";
        }
        if (got == BoardReading.EMPTY) {
            return "missing";
        }
        if (Character.toLowerCase(want) == Character.toLowerCase(got)) {
            return "colour";
        }
        return "type";
    }

    @Test
    void coreBatteryReadsRealBoards() throws Exception {
        List<Fixture> core = loadCore();
        assumeTrue(!core.isEmpty(), "no core battery");
        List<Outcome> outcomes = new ArrayList<>();
        for (Fixture f : core) {
            outcomes.add(read(f));
        }
        String report = report("Core battery (committed)", outcomes);
        System.out.println(report);
        write("report-core.md", report);
        double squares = squareAccuracy(outcomes);
        long exact = outcomes.stream().filter(Outcome::exact).count();
        assertTrue(squares >= Double.parseDouble(System.getProperty("javachess.vision.battery.minSquares", "0.99")),
                "square accuracy " + squares);
        assertTrue(exact >= Math.ceil(outcomes.size() * Double.parseDouble(
                System.getProperty("javachess.vision.battery.minExact", "0.85"))), "exact " + exact + "/" + outcomes.size());
    }

    @Test
    void extendedBatteryReport() throws Exception {
        List<Fixture> extended = loadExtended();
        assumeTrue(!extended.isEmpty(), "no extended battery in target/vision-battery");
        List<Outcome> outcomes = new ArrayList<>();
        for (Fixture f : extended) {
            outcomes.add(read(f));
        }
        String report = report("Extended battery (" + extended.size() + " pictures)", outcomes);
        System.out.println(report);
        write("report.md", report);
        if (Boolean.getBoolean("javachess.vision.battery.strict")) {
            assertTrue(squareAccuracy(outcomes) >= 0.99, "square accuracy " + squareAccuracy(outcomes));
        }
    }

    /**
     * For consecutive positions of real games (browser captures with a "ply" and "game"), the move between them is
     * recovered from the vision reading of the second picture: the synchronisation's view of vision.
     */
    @Test
    void movesAreRecognisedFromVisionReadings() throws Exception {
        List<Fixture> all = new ArrayList<>(loadCore());
        all.addAll(loadExtended());
        Map<String, List<Fixture>> games = new TreeMap<>();
        for (Fixture f : all) {
            if (f.meta().has("game") && f.meta().has("ply")) {
                games.computeIfAbsent(f.group() + "/" + f.meta().getString("game"), k -> new ArrayList<>()).add(f);
            }
        }
        assumeTrue(!games.isEmpty(), "no game sequences");
        int moves = 0;
        int right = 0;
        int ghosts = 0;
        int undecided = 0;
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<Fixture>> g : games.entrySet()) {
            List<Fixture> seq = g.getValue();
            seq.sort((a, b) -> Integer.compare(a.meta().getInt("ply"), b.meta().getInt("ply")));
            for (int i = 1; i < seq.size(); i++) {
                Fixture before = seq.get(i - 1);
                Fixture after = seq.get(i);
                if (!before.meta().has("fen")) {
                    continue;
                }
                Board b = new Board();
                b.loadFromFen(before.meta().getString("fen"));
                Move truth = null;
                for (Move m : MoveGenerator.generateLegalMoves(b)) {
                    Board c = b.clone();
                    c.doMove(m);
                    if (c.getFen().split(" ")[0].equals(after.placement())) {
                        truth = m;
                    }
                }
                if (truth == null) {
                    continue; // not consecutive plies
                }
                moves++;
                BoardReading r = classifier.read(after.image(), after.flipped(), null).withPlacementRules();
                PositionResolver.Resolution res = new PositionResolver().resolve(b, r, true);
                if (res.confident() && res.moves().equals(List.of(truth))) {
                    right++;
                } else if (res.confident()) {
                    ghosts++;
                    failures.add(g.getKey() + " ply " + after.meta().getInt("ply") + ": " + truth + " read as "
                            + res.moves());
                } else {
                    undecided++;
                    failures.add(g.getKey() + " ply " + after.meta().getInt("ply") + ": " + truth + " undecided");
                }
            }
        }
        String report = String.format("Moves from vision readings: %d moves, %d right, %d wrong (ghost/other move),"
                + " %d undecided%n%s", moves, right, ghosts, undecided, String.join("\n", failures));
        System.out.println(report);
        write("report-moves.md", report);
        assertTrue(ghosts == 0, "vision must never produce a wrong move: " + failures);
    }

    /**
     * The calibrated reader ({@link TemplateReader}) against the model on the same pictures: per group (one theme
     * and piece set), it learns from the picture marked "calibration" (the start position) and reads the others.
     */
    @Test
    void calibratedReaderAgainstTheModel() throws Exception {
        List<Fixture> all = new ArrayList<>(loadCore());
        all.addAll(loadExtended());
        Map<String, List<Fixture>> groups = new TreeMap<>();
        for (Fixture f : all) {
            if (f.meta().has("group")) {
                groups.computeIfAbsent(f.group() + "/" + f.meta().getString("group"), k -> new ArrayList<>()).add(f);
            }
        }
        assumeTrue(!groups.isEmpty(), "no calibration groups");
        List<Outcome> model = new ArrayList<>();
        List<Outcome> calibrated = new ArrayList<>();
        List<Outcome> fused = new ArrayList<>();
        List<Outcome> progressive = new ArrayList<>();
        int skipped = 0;
        for (List<Fixture> g : groups.values()) {
            Fixture cal = g.stream().filter(f -> f.meta().optBoolean("calibration")).findFirst().orElse(null);
            if (cal == null) {
                continue;
            }
            TemplateReader reader = new TemplateReader();
            reader.learn(cal.image(), cal.placement(), cal.flipped());
            // as in the app: vision keeps learning from positions known for sure (the page), one after the other
            TemplateReader learning = new TemplateReader();
            learning.learn(cal.image(), cal.placement(), cal.flipped());
            for (Fixture f : g) {
                if (f == cal || !learning.fits(f.image())) {
                    continue;
                }
                progressive.add(compare(f, learning.read(f.image(), f.flipped()).withPlacementRules()));
                learning.learn(f.image(), f.placement(), f.flipped());
            }
            for (Fixture f : g) {
                if (f == cal) {
                    continue;
                }
                if (!reader.fits(f.image())) {
                    skipped++; // another board size: the app learns again in that case
                    continue;
                }
                BoardReading m = classifier.read(f.image(), f.flipped(), null);
                BoardReading t = reader.read(f.image(), f.flipped());
                model.add(compare(f, m.withPlacementRules()));
                calibrated.add(compare(f, t.withPlacementRules()));
                fused.add(compare(f, TemplateReader.fuse(t, m).withPlacementRules()));
            }
        }
        String report = report("Model on calibration groups", model) + "\n" + report("Calibrated reader", calibrated)
                + "\n" + report("Calibrated reader fused with the model", fused)
                + "\n" + report("Calibrated reader that keeps learning (as with the page)", progressive)
                + "\nPictures of another size than their calibration (skipped): " + skipped + "\n";
        System.out.println(report);
        write("report-calibrated.md", report);
        assertTrue(squareAccuracy(calibrated) >= squareAccuracy(model),
                "calibrated " + squareAccuracy(calibrated) + " vs model " + squareAccuracy(model));
    }

    static Outcome compare(Fixture f, BoardReading r) {
        char[][] truth = BoardReading.parsePlacement(f.placement());
        List<String> errors = new ArrayList<>();
        for (int file = 0; file < 8; file++) {
            for (int rank = 0; rank < 8; rank++) {
                char want = truth[file][rank];
                char got = r.pieceAt(file, rank);
                if (want != got) {
                    errors.add(kind(want, got) + " " + BoardReading.squareName(file, rank) + " " + want + "->" + got);
                }
            }
        }
        return new Outcome(f, r.placement(), errors.size(), errors, r.minConfidence());
    }

    // ------------------------------------------------------------------ report

    static double squareAccuracy(List<Outcome> outcomes) {
        long wrong = outcomes.stream().mapToLong(Outcome::wrong).sum();
        return 1 - wrong / (64.0 * Math.max(1, outcomes.size()));
    }

    static String report(String title, List<Outcome> outcomes) {
        StringBuilder sb = new StringBuilder("## ").append(title).append("\n\n");
        sb.append(String.format("All: %d pictures, exact %d (%.1f%%), square accuracy %.4f%n%n", outcomes.size(),
                outcomes.stream().filter(Outcome::exact).count(),
                100.0 * outcomes.stream().filter(Outcome::exact).count() / Math.max(1, outcomes.size()),
                squareAccuracy(outcomes)));
        table(sb, "site", outcomes, o -> o.fixture().get("site"));
        table(sb, "category", outcomes, o -> o.fixture().get("category"));
        table(sb, "orientation", outcomes, o -> o.fixture().flipped() ? "black at the bottom" : "white at the bottom");
        table(sb, "theme", outcomes, o -> o.fixture().get("site") + " " + o.fixture().get("theme"));
        table(sb, "pieces", outcomes, o -> o.fixture().get("site") + " " + o.fixture().get("pieces"));
        Map<String, Integer> kinds = new TreeMap<>();
        for (Outcome o : outcomes) {
            for (String e : o.errors()) {
                kinds.merge(e.split(" ")[0], 1, Integer::sum);
            }
        }
        sb.append("Errors by kind: ").append(kinds).append("\n\n### Failures\n\n");
        for (Outcome o : outcomes) {
            if (!o.exact()) {
                sb.append("- ").append(o.fixture().group()).append('/').append(o.fixture().file()).append(": ")
                        .append(o.errors()).append('\n');
            }
        }
        return sb.toString();
    }

    private static void table(StringBuilder sb, String by, List<Outcome> outcomes, Function<Outcome, String> key) {
        Map<String, List<Outcome>> groups = new TreeMap<>();
        for (Outcome o : outcomes) {
            groups.computeIfAbsent(key.apply(o), k -> new ArrayList<>()).add(o);
        }
        if (groups.size() <= 1 && !by.equals("site")) {
            return;
        }
        sb.append("| ").append(by).append(" | pictures | exact | squares |\n|---|---|---|---|\n");
        for (Map.Entry<String, List<Outcome>> g : groups.entrySet()) {
            List<Outcome> l = g.getValue();
            sb.append(String.format("| %s | %d | %d | %.3f |%n", g.getKey(), l.size(),
                    l.stream().filter(Outcome::exact).count(), squareAccuracy(l)));
        }
        sb.append('\n');
    }

    private static void write(String name, String text) {
        try {
            Path dir = Path.of("target/vision-battery");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), text);
        } catch (IOException e) {
            System.out.println("report not written: " + e);
        }
    }

    static List<Path> files(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(out::add);
        }
        return out;
    }
}
