package io.github.hardin22.javachess.Vision;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Utils.PgnCodec;
import io.github.hardin22.javachess.Utils.RandomFenGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures the vision pipeline on a real lichess screenshot and on synthetic boards rendered in several themes,
 * piece sets and lighting conditions. The numbers are printed so regressions are visible in the build log.
 */
class VisionAccuracyTest {

    private static PieceClassifier classifier;

    @BeforeAll
    static void loadModel() {
        try {
            classifier = new PieceClassifier(PieceClassifier.DEFAULT_MODEL);
            classifier.setGridRefinement(!Boolean.getBoolean("noRefine"));
        } catch (Throwable e) {
            classifier = null; // onnxruntime/OpenCV natives not available on this platform
        }
        assumeTrue(classifier != null, "vision model cannot be loaded here");
    }

    @AfterAll
    static void close() {
        if (classifier != null) {
            classifier.close();
        }
    }

    private static int correct(BoardReading r, String placement) {
        char[][] g = BoardReading.parsePlacement(placement);
        int ok = 0;
        for (int f = 0; f < 8; f++) {
            for (int k = 0; k < 8; k++) {
                if (r.pieceAt(f, k) == g[f][k]) {
                    ok++;
                }
            }
        }
        return ok;
    }

    @Test
    void readsARealLichessScreenshotFromBlacksSide() throws Exception {
        BufferedImage real = ImageIO.read(getClass().getResourceAsStream("/vision/lichess-flipped-endgame.jpg"));
        BoardReading r = classifier.read(real, true, null);
        System.out.printf("[vision] real lichess board: %s, min confidence %.2f, %d ms%n", r.placement(),
                r.minConfidence(), r.inferenceMs());
        assertEquals("8/8/4k3/3n3p/4K3/8/6p1/6N1", r.placement());
        assertTrue(r.hasBoard());
        assertTrue(r.minConfidence() > 0.5f);
    }

    private record Score(int squares, int correct, int exact, int boards) {
        double squareAccuracy() {
            return correct / (double) squares;
        }
    }

    private static Score measure(boolean stretch, SyntheticBoards.Condition condition) throws Exception {
        classifier.setContrastStretch(stretch);
        int squares = 0;
        int correct = 0;
        int exact = 0;
        int boards = 0;
        Random rnd = new Random(42);
        for (SyntheticBoards.Theme theme : SyntheticBoards.Theme.values()) {
            for (String set : new String[]{"Classico", "Legno"}) {
                String fen = RandomFenGenerator.generateRandomFen(10 + rnd.nextInt(40), rnd).split(" ")[0];
                boolean flip = rnd.nextBoolean();
                BufferedImage img = SyntheticBoards.degrade(SyntheticBoards.render(fen, set, theme, 480, flip),
                        condition, boards);
                BoardReading r = classifier.read(img, flip, null).withPlacementRules();
                int c = correct(r, fen);
                squares += 64;
                correct += c;
                exact += c == 64 ? 1 : 0;
                boards++;
            }
        }
        classifier.setContrastStretch(true);
        return new Score(squares, correct, exact, boards);
    }

    @Test
    void syntheticBoardsInEveryThemeAndLighting() throws Exception {
        int boards = 0;
        int exact = 0;
        int squares = 0;
        int correct = 0;
        for (SyntheticBoards.Condition condition : SyntheticBoards.Condition.values()) {
            Score s = measure(true, condition);
            System.out.printf("[vision] %-17s square accuracy %.3f, exact boards %d/%d%n", condition,
                    s.squareAccuracy(), s.exact(), s.boards());
            boards += s.boards();
            exact += s.exact();
            squares += s.squares();
            correct += s.correct();
        }
        double accuracy = correct / (double) squares;
        System.out.printf("[vision] overall square accuracy %.4f, exact boards %d/%d%n", accuracy, exact, boards);
        assertTrue(accuracy >= 0.99, "square accuracy " + accuracy);
        assertTrue(exact >= boards * 0.8, "exact boards " + exact + "/" + boards);
    }

    @Test
    void contrastNormalisationHelpsOnWashedOutScreens() throws Exception {
        Score without = measure(false, SyntheticBoards.Condition.BRIGHT_WASHED);
        Score with = measure(true, SyntheticBoards.Condition.BRIGHT_WASHED);
        System.out.printf("[vision] washed-out screen: square accuracy %.3f -> %.3f with contrast stretch%n",
                without.squareAccuracy(), with.squareAccuracy());
        assertTrue(with.squareAccuracy() > without.squareAccuracy());
        assertTrue(with.squareAccuracy() >= 0.98);
    }

    @Test
    void legalityResolvesTheMoveEvenWhenSquaresAreMisread() throws Exception {
        // Known position, then the screen shows the position after a move: detect which move it was.
        Random rnd = new Random(3);
        int resolved = 0;
        int trials = 0;
        for (SyntheticBoards.Theme theme : SyntheticBoards.Theme.values()) {
            Board board = PgnCodec.boardOrStart(RandomFenGenerator.generateRandomFen(12 + rnd.nextInt(20), rnd));
            var moves = board.legalMoves();
            if (moves.isEmpty()) {
                continue;
            }
            var move = moves.get(rnd.nextInt(moves.size()));
            Board after = board.clone();
            after.doMove(move);
            BufferedImage img = SyntheticBoards.degrade(SyntheticBoards.render(after.getFen().split(" ")[0],
                    "Legno", theme, 400, false), SyntheticBoards.Condition.NOISY, trials);
            BoardReading reading = classifier.read(img, false, null).withPlacementRules();
            PositionResolver.Resolution res = new PositionResolver().resolve(board, reading, false);
            trials++;
            if (res.confident() && res.moves().size() == 1 && res.moves().get(0).equals(move)) {
                resolved++;
            } else {
                System.out.printf("[vision] not resolved: played %s, got %s margin %.1f mismatches %d, read %s vs %s%n",
                        move, res.moves(), res.margin(), res.mismatches(), reading.placement(),
                        after.getFen().split(" ")[0]);
            }
        }
        System.out.printf("[vision] moves identified through legality: %d/%d%n", resolved, trials);
        assertEquals(trials, resolved);
    }

    @Test
    void findsTheBoardOnAClutteredScreenAndReadsIt() throws Exception {
        int found = 0;
        int read = 0;
        int trials = 0;
        Random rnd = new Random(11);
        for (SyntheticBoards.Theme theme : SyntheticBoards.Theme.values()) {
            String fen = RandomFenGenerator.generateRandomFen(15 + rnd.nextInt(30), rnd).split(" ")[0];
            int size = 420 + rnd.nextInt(200);
            int bx = 150 + rnd.nextInt(500);
            int by = 120 + rnd.nextInt(200);
            BufferedImage screen = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = screen.createGraphics();
            g.setColor(new java.awt.Color(38, 36, 33));
            g.fillRect(0, 0, 1600, 1000);
            g.setColor(new java.awt.Color(200, 200, 200));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 18));
            for (int i = 0; i < 30; i++) {
                g.drawString("Player " + i + "  (" + (1500 + 17 * i) + ")  1. e4 e5 2. Nf3", 1200, 40 + i * 30);
            }
            g.fillRect(20, 20, 100, 900); // side bar
            g.drawImage(SyntheticBoards.render(fen, "Classico", theme, size, false), bx, by, null);
            g.dispose();

            trials++;
            java.awt.Rectangle r = classifier.findBoard(screen);
            if (r == null) {
                System.out.printf("[vision] %s: board not found (truth %d,%d %dpx)%n", theme, bx, by, size);
                continue;
            }
            java.awt.Rectangle truth = new java.awt.Rectangle(bx, by, size, size);
            java.awt.Rectangle inter = r.intersection(truth);
            double iou = inter.isEmpty() ? 0 : (double) inter.width * inter.height
                    / ((double) r.width * r.height + (double) size * size - (double) inter.width * inter.height);
            if (iou > 0.85) {
                found++;
            }
            BoardReading reading = classifier.read(screen.getSubimage(r.x, r.y, r.width, r.height), false, null)
                    .withPlacementRules();
            if (reading.placement().equals(fen)) {
                read++;
            } else {
                System.out.printf("[vision] %s: found %s (truth %s), read %s vs %s%n", theme, r, truth,
                        reading.placement(), fen);
            }
        }
        System.out.printf("[vision] full screen: board found %d/%d, position read exactly %d/%d%n", found, trials,
                read, trials);
        assertEquals(trials, found);
        assertTrue(read >= trials - 1, "read " + read + "/" + trials);
    }

    @Test
    void inferenceIsFastEnough() throws Exception {
        BufferedImage img = SyntheticBoards.render(PgnCodec.START_FEN.split(" ")[0], "Classico",
                SyntheticBoards.Theme.CHESSCOM_GREEN, 600, false);
        classifier.read(img, false, null); // warm-up
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            BoardReading r = classifier.read(img, false, null).withPlacementRules();
            best = Math.min(best, (System.nanoTime() - t) / 1_000_000);
            assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", r.placement());
        }
        System.out.printf("[vision] one board read (pre-processing + inference): %d ms%n", best);
        assertTrue(best < 3000, "too slow: " + best + " ms");
    }
}
