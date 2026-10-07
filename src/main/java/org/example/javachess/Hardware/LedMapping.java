package org.example.javachess.Hardware;

import java.util.Locale;

/**
 * Maps a board square (0 = a1, 1 = b1, ..., 63 = h8, the chesslib {@code Square.ordinal()}) to the index of its
 * LED on the WS2812 strip.
 *
 * <p>The default matches the current wiring: LED 0 under a1, strips running along the ranks, serpentine
 * ("snake"): rank 1 goes a1 to h1 (LEDs 0-7), rank 2 comes back h2 to a2 (LEDs 8-15), and so on.
 * Other PCBs can be described with {@code led.layout}, {@code led.origin} and {@code led.direction}
 * in config.properties without touching the firmware.</p>
 */
public final class LedMapping {

    public enum Layout {
        /** Every other row is reversed (single strip folded back and forth). */
        SNAKE,
        /** Every row runs in the same direction. */
        ROWS
    }

    /** Whether consecutive LEDs advance along a rank (a1, b1, c1...) or along a file (a1, a2, a3...). */
    public enum Direction {
        ALONG_RANKS,
        ALONG_FILES
    }

    public static final LedMapping DEFAULT = new LedMapping(Layout.SNAKE, "a1", Direction.ALONG_RANKS);

    private final int[] squareToLed = new int[64];
    private final int[] ledToSquare = new int[64];
    private final String description;

    /**
     * @param layout    snake or plain rows
     * @param origin    corner square under LED 0: a1, h1, a8 or h8
     * @param direction direction of the first row of LEDs
     */
    public LedMapping(Layout layout, String origin, Direction direction) {
        String corner = origin.toLowerCase(Locale.ROOT);
        boolean mirrorFiles = corner.startsWith("h");
        boolean mirrorRanks = corner.endsWith("8");
        if (!corner.matches("[ah][18]")) {
            throw new IllegalArgumentException("LED origin must be a1, h1, a8 or h8: " + origin);
        }
        for (int square = 0; square < 64; square++) {
            int file = square % 8;
            int rank = square / 8;
            if (mirrorFiles) {
                file = 7 - file;
            }
            if (mirrorRanks) {
                rank = 7 - rank;
            }
            int row = direction == Direction.ALONG_RANKS ? rank : file;
            int column = direction == Direction.ALONG_RANKS ? file : rank;
            if (layout == Layout.SNAKE && row % 2 == 1) {
                column = 7 - column;
            }
            int led = row * 8 + column;
            squareToLed[square] = led;
            ledToSquare[led] = square;
        }
        description = layout + " from " + corner + " " + direction;
    }

    /** Builds the mapping from {@code led.layout} (snake|rows), {@code led.origin} and {@code led.direction} (ranks|files). */
    public static LedMapping fromConfig(String layout, String origin, String direction) {
        Layout l = "rows".equalsIgnoreCase(layout) ? Layout.ROWS : Layout.SNAKE;
        Direction d = "files".equalsIgnoreCase(direction) ? Direction.ALONG_FILES : Direction.ALONG_RANKS;
        return new LedMapping(l, origin == null || origin.isBlank() ? "a1" : origin.trim(), d);
    }

    public int ledIndex(int square) {
        return squareToLed[square];
    }

    public int square(int ledIndex) {
        return ledToSquare[ledIndex];
    }

    /** Reorders a square-indexed frame into wire (LED) order. */
    public int[] toWireOrder(int[] bySquare) {
        int[] byLed = new int[64];
        for (int square = 0; square < 64; square++) {
            byLed[squareToLed[square]] = bySquare[square];
        }
        return byLed;
    }

    @Override
    public String toString() {
        return description;
    }
}
