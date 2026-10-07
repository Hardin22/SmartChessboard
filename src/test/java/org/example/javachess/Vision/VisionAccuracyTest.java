package org.example.javachess.Vision;

import com.github.bhlangonijr.chesslib.Board;
import org.example.javachess.Utils.PgnCodec;
import org.example.javachess.Utils.RandomFenGenerator;
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
                String fen = RandomFenGenerator.generateRandomFen(10 + rnd.nextInt(40)).split(" ")[0];
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
            Board board = PgnCodec.boardOrStart(RandomFenGenerator.generateRandomFen(12 + rnd.nextInt(20)));
            var moves = board.legalMoves();
            if (moves.isEmpty()) {
                continue;
            }
            var move = moves.get(rnd.nextInt(moves.size()));
            Board after = board.clone();
            after.doMove(move);
            BufferedImage img = SyntheticBoards.degrade(SyntheticBoards.render(after.getFen().split(" ")[0],
                    "Legno", theme, 400, false), SyntheticBoards.Condition.NOISY, trials);
            BoardReading reading = classifier.read(img, false, null);
            PositionResolver.Resolution res = new PositionResolver().resolve(board, reading, false);
            trials++;
            if (res.confident() && res.moves().size() == 1 && res.moves().get(0).equals(move)) {
                resolved++;
            }
        }
        System.out.printf("[vision] moves identified through legality: %d/%d%n", resolved, trials);
        assertEquals(trials, resolved);
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
