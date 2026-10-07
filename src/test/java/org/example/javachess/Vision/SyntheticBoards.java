package org.example.javachess.Vision;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Renders 2D board screenshots (like chess.com / lichess) from a FEN, for measuring the vision pipeline. */
final class SyntheticBoards {

    enum Theme {
        LICHESS_BROWN(new Color(240, 217, 181), new Color(181, 136, 99)),
        CHESSCOM_GREEN(new Color(235, 236, 208), new Color(119, 149, 86)),
        GREY(new Color(222, 222, 222), new Color(140, 140, 140)),
        BLUE(new Color(222, 227, 230), new Color(140, 162, 173));

        final Color light;
        final Color dark;

        Theme(Color light, Color dark) {
            this.light = light;
            this.dark = dark;
        }
    }

    enum Condition { CLEAN, DIM_LOW_CONTRAST, BRIGHT_WASHED, NOISY }

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    private SyntheticBoards() {
    }

    static BufferedImage render(String placement, String pieceSet, Theme theme, int size, boolean flipped) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double sq = size / 8.0;
        char[][] grid = BoardReading.parsePlacement(placement);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int file = flipped ? 7 - col : col;
                int rank = flipped ? row : 7 - row;
                boolean light = (file + rank) % 2 == 1;
                g.setColor(light ? theme.light : theme.dark);
                int x0 = (int) Math.round(col * sq);
                int y0 = (int) Math.round(row * sq);
                int x1 = (int) Math.round((col + 1) * sq);
                int y1 = (int) Math.round((row + 1) * sq);
                g.fillRect(x0, y0, x1 - x0, y1 - y0);
                char c = grid[file][rank];
                if (c != BoardReading.EMPTY) {
                    g.drawImage(piece(pieceSet, c), x0, y0, x1 - x0, y1 - y0, null);
                }
            }
        }
        g.dispose();
        return img;
    }

    static BufferedImage degrade(BufferedImage src, Condition condition, long seed) {
        if (condition == Condition.CLEAN) {
            return src;
        }
        Random rnd = new Random(seed);
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int[] ch = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
                for (int i = 0; i < 3; i++) {
                    double v = ch[i];
                    switch (condition) {
                        case DIM_LOW_CONTRAST -> v = 40 + v * 0.35;       // dark, flat screen
                        case BRIGHT_WASHED -> v = 150 + v * 0.40;         // glare / high brightness
                        case NOISY -> v = v + rnd.nextGaussian() * 18;    // sensor-like noise
                        default -> { }
                    }
                    ch[i] = (int) Math.max(0, Math.min(255, Math.round(v)));
                }
                out.setRGB(x, y, (ch[0] << 16) | (ch[1] << 8) | ch[2]);
            }
        }
        return out;
    }

    private static BufferedImage piece(String set, char c) {
        String name = (Character.isUpperCase(c) ? "w" : "b") + Character.toLowerCase(c);
        return CACHE.computeIfAbsent(set + "/" + name, k -> {
            try (InputStream in = SyntheticBoards.class.getResourceAsStream("/vision/pieces/" + k + ".png")) {
                if (in == null) {
                    throw new IllegalStateException("missing piece image " + k);
                }
                return ImageIO.read(in);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
