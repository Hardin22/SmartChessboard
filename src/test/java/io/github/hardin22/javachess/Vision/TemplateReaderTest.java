package io.github.hardin22.javachess.Vision;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The calibrated reader on a drawn board whose dark squares are strongly textured (like chess.com's "stone") and
 * whose black pieces barely stand out from them: the hard case of the vision battery, without the sites' pictures.
 */
class TemplateReaderTest {

    private static final int SQUARE = 60;
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR";

    /** Square textures never move on the screen: one fixed noise per square, light and dark. */
    private static final BufferedImage BACKGROUND = background();

    @Test
    void lowContrastPiecesOnSquaresNeverSeenEmptyAreRead() {
        TemplateReader reader = new TemplateReader();
        reader.learn(board(START), START, false);
        assertEquals(12, reader.knownSymbols(), "every piece learned, also those that barely stand out");

        // c7, e2 and g1 were never seen empty (occupied at calibration): nothing to compare with but the pieces
        String sicilian = "rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R";
        assertEquals(sicilian, reader.read(board(sicilian), false).withPlacementRules().placement());
    }

    @Test
    void lowContrastPiecesMovedToOtherSquaresAreRead() {
        TemplateReader reader = new TemplateReader();
        reader.learn(board(START), START, false);
        // black pieces on dark squares of the back ranks that were white's at calibration, and the reverse
        String swapped = "RNBQKBNR/PPPPPPPP/8/8/8/8/pppppppp/rnbqkbnr";
        assertEquals(swapped, reader.read(board(swapped), false).withPlacementRules().placement());
    }

    // ------------------------------------------------------------------ drawing

    /** Dark squares: grey with fine grain and short bright veins (they raise the texture threshold, as stone does). */
    private static BufferedImage background() {
        BufferedImage img = new BufferedImage(8 * SQUARE, 8 * SQUARE, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                boolean dark = isDark(x, y);
                int v = dark ? 90 + random.nextInt(17) - 8 : 200 + random.nextInt(11) - 5;
                img.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        int vein = new Color(215, 215, 215).getRGB();
        for (int i = 0; i < 300; i++) {
            int x = random.nextInt(img.getWidth());
            int y = random.nextInt(img.getHeight());
            int length = 6 + random.nextInt(7);
            int dx = random.nextBoolean() ? 1 : -1;
            for (int k = 0; k < length; k++, x += dx, y++) {
                if (x >= 0 && y < img.getHeight() && x < img.getWidth() && isDark(x, y)) {
                    img.setRGB(x, y, vein);
                }
            }
        }
        return img;
    }

    private static boolean isDark(int x, int y) {
        return (x / SQUARE + y / SQUARE) % 2 == 1; // a8 (top left) is light
    }

    /** The board with pieces drawn as plain shapes: white ones bright, black ones close to the dark squares. */
    private static BufferedImage board(String placement) {
        BufferedImage img = new BufferedImage(8 * SQUARE, 8 * SQUARE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(BACKGROUND, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        char[][] grid = BoardReading.parsePlacement(placement);
        for (int file = 0; file < 8; file++) {
            for (int rank = 0; rank < 8; rank++) {
                char symbol = grid[file][rank];
                if (symbol != BoardReading.EMPTY) {
                    int x = file * SQUARE + SQUARE / 2;
                    int y = (7 - rank) * SQUARE + SQUARE / 2;
                    g.setColor(Character.isUpperCase(symbol) ? new Color(235, 235, 235) : new Color(70, 70, 70));
                    drawPiece(g, Character.toLowerCase(symbol), x, y);
                }
            }
        }
        g.dispose();
        return img;
    }

    private static void drawPiece(Graphics2D g, char type, int x, int y) {
        switch (type) {
            case 'p' -> g.fillOval(x - 9, y - 4, 18, 18);
            case 'n' -> g.fillPolygon(new Polygon(new int[] {x - 14, x + 14, x - 6}, new int[] {y + 16, y + 16, y - 18}, 3));
            case 'b' -> g.fillPolygon(new Polygon(new int[] {x, x + 12, x, x - 12}, new int[] {y - 20, y, y + 20, y}, 4));
            case 'r' -> g.fillRect(x - 13, y - 15, 26, 30);
            case 'q' -> g.fillOval(x - 18, y - 18, 36, 36);
            default -> { // king: a cross
                g.fillRect(x - 5, y - 20, 10, 40);
                g.fillRect(x - 17, y - 8, 34, 10);
            }
        }
    }
}
