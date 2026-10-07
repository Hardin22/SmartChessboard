package io.github.hardin22.javachess.Hardware;

import java.util.Locale;

/**
 * Line protocol between the app and the board firmware (version 2). Full description in docs/hardware-protocol.md.
 *
 * <p>Every line is ASCII, ends with {@code \n} and carries a checksum: {@code <payload>*<XX>} where XX is the
 * XOR of all payload bytes in two upper-case hex digits. Lines with a wrong checksum are dropped.</p>
 *
 * <pre>
 * board -> app   H &lt;proto&gt; &lt;firmware&gt;   hello (after reset and on "?")
 *                +E2 / -E2                 square E2 became occupied / empty (debounced)
 *                B &lt;16 hex&gt;                full occupancy, bit i = square i (a1 = bit 0), on "R" and every second
 *                K                         LED command applied (flow control: the app waits for it)
 *                E &lt;reason&gt;                command rejected (bad checksum, bad length...)
 * app -> board   ?                         hello request
 *                R                         occupancy request
 *                F &lt;64 x RRGGBB&gt;           full LED frame in wire order
 *                D &lt;n x IIRRGGBB&gt;          changed LEDs only (II = LED index)
 *                L &lt;XX&gt;                    global brightness 0-255 (on top of the firmware power cap)
 * </pre>
 */
public final class BoardProtocol {

    public static final int VERSION = 2;
    /** Longest line the firmware accepts (its receive buffer). */
    public static final int MAX_LINE = 400;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private BoardProtocol() {
    }

    /** A decoded line from the board. */
    public sealed interface Message permits Hello, SquareChange, Occupancy, Ack, Error, Legacy {
    }

    public record Hello(int protocol, String firmware) implements Message {
    }

    public record SquareChange(int square, boolean occupied) implements Message {
    }

    public record Occupancy(long bits) implements Message {
    }

    public record Ack() implements Message {
    }

    public record Error(String reason) implements Message {
    }

    /** "READY" from the old firmware: sensors still work, LEDs need the new firmware. */
    public record Legacy(String text) implements Message {
    }

    public static String withChecksum(String payload) {
        int xor = checksum(payload, payload.length());
        return payload + '*' + HEX[(xor >> 4) & 0xF] + HEX[xor & 0xF];
    }

    /** Encodes a command line including checksum and newline. */
    public static String command(String payload) {
        return withChecksum(payload) + '\n';
    }

    /**
     * Decodes one line (without the newline). Returns null for empty, corrupted or unknown lines.
     * Lines without checksum are accepted only in the old firmware format ({@code +A1}, {@code -A1}, {@code READY}).
     */
    public static Message parse(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty()) {
            return null;
        }
        int star = line.lastIndexOf('*');
        if (star < 0) {
            return parseLegacy(line);
        }
        if (star != line.length() - 3) {
            return null;
        }
        int expected;
        try {
            expected = Integer.parseInt(line.substring(star + 1), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        if (checksum(line, star) != expected) {
            return null;
        }
        return parsePayload(line.substring(0, star));
    }

    private static Message parsePayload(String payload) {
        char type = payload.charAt(0);
        switch (type) {
            case '+', '-' -> {
                int square = Squares.parse(payload.substring(1));
                return square < 0 ? null : new SquareChange(square, type == '+');
            }
            case 'B' -> {
                String hex = payload.substring(1).trim();
                if (hex.length() != 16) {
                    return null;
                }
                try {
                    return new Occupancy(Long.parseUnsignedLong(hex, 16));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case 'K' -> {
                return new Ack();
            }
            case 'E' -> {
                return new Error(payload.substring(1).trim());
            }
            case 'H' -> {
                String[] parts = payload.substring(1).trim().split("\\s+");
                try {
                    return new Hello(Integer.parseInt(parts[0]), parts.length > 1 ? parts[1] : "?");
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
    }

    private static Message parseLegacy(String line) {
        if (line.equals("READY")) {
            return new Legacy(line);
        }
        if (line.length() == 3 && (line.charAt(0) == '+' || line.charAt(0) == '-')) {
            int square = Squares.parse(line.substring(1));
            if (square >= 0 && Character.isUpperCase(line.charAt(1))) {
                return new SquareChange(square, line.charAt(0) == '+');
            }
        }
        return null;
    }

    private static int checksum(CharSequence text, int end) {
        int xor = 0;
        for (int i = 0; i < end; i++) {
            xor ^= text.charAt(i) & 0xFF;
        }
        return xor;
    }

    /** Full frame command for 64 RGB values in wire order. */
    public static String fullFrame(int[] wireFrame) {
        StringBuilder sb = new StringBuilder(2 + 64 * 6);
        sb.append('F');
        for (int rgb : wireFrame) {
            appendHex(sb, rgb, 6);
        }
        return command(sb.toString());
    }

    /**
     * Smallest command turning {@code previous} into {@code next} (both in wire order): a delta with the changed
     * LEDs, or a full frame when that is shorter. Returns null when nothing changed. A null {@code previous}
     * (state of the LEDs unknown) always gives a full frame.
     */
    public static String frameUpdate(int[] previous, int[] next) {
        if (previous == null) {
            return fullFrame(next);
        }
        int changed = 0;
        for (int i = 0; i < 64; i++) {
            if (previous[i] != next[i]) {
                changed++;
            }
        }
        if (changed == 0) {
            return null;
        }
        if (changed * 8 >= 64 * 6) {
            return fullFrame(next);
        }
        StringBuilder sb = new StringBuilder(2 + changed * 8);
        sb.append('D');
        for (int i = 0; i < 64; i++) {
            if (previous[i] != next[i]) {
                appendHex(sb, i, 2);
                appendHex(sb, next[i], 6);
            }
        }
        return command(sb.toString());
    }

    public static String brightness(int value) {
        StringBuilder sb = new StringBuilder("L");
        appendHex(sb, Math.max(0, Math.min(255, value)), 2);
        return command(sb.toString());
    }

    public static String occupancyLine(long bits) {
        return withChecksum("B" + String.format(Locale.ROOT, "%016X", bits));
    }

    public static String squareLine(int square, boolean occupied) {
        return withChecksum((occupied ? "+" : "-") + Squares.name(square));
    }

    private static void appendHex(StringBuilder sb, int value, int digits) {
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
            sb.append(HEX[(value >> shift) & 0xF]);
        }
    }

    /**
     * Applies an LED command to a frame the way the firmware does; used by the simulator and the tests.
     * Returns false when the command is not a valid frame command.
     */
    public static boolean applyFrameCommand(String commandLine, int[] wireFrame) {
        String line = commandLine.trim();
        int star = line.lastIndexOf('*');
        if (star != line.length() - 3 || checksum(line, star) != Integer.parseInt(line.substring(star + 1), 16)) {
            return false;
        }
        String payload = line.substring(0, star);
        if (payload.charAt(0) == 'F' && payload.length() == 1 + 64 * 6) {
            for (int i = 0; i < 64; i++) {
                wireFrame[i] = Integer.parseInt(payload.substring(1 + i * 6, 7 + i * 6), 16);
            }
            return true;
        }
        if (payload.charAt(0) == 'D' && (payload.length() - 1) % 8 == 0) {
            for (int p = 1; p < payload.length(); p += 8) {
                int index = Integer.parseInt(payload.substring(p, p + 2), 16);
                if (index > 63) {
                    return false;
                }
                wireFrame[index] = Integer.parseInt(payload.substring(p + 2, p + 8), 16);
            }
            return true;
        }
        return false;
    }
}
