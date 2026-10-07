package org.example.javachess.Hardware;

import java.util.Locale;

/** Square helpers for the 0..63 index used across the hardware layer (0 = a1, 7 = h1, 63 = h8). */
public final class Squares {

    private Squares() {
    }

    /** Parses "e4" or "E4"; returns -1 when the text is not a square. */
    public static int parse(String name) {
        if (name == null || name.length() != 2) {
            return -1;
        }
        int file = Character.toLowerCase(name.charAt(0)) - 'a';
        int rank = name.charAt(1) - '1';
        if (file < 0 || file > 7 || rank < 0 || rank > 7) {
            return -1;
        }
        return rank * 8 + file;
    }

    /** Upper-case name ("E4"), matching chesslib {@code Square.name()}. */
    public static String name(int square) {
        return ("" + (char) ('a' + square % 8) + (char) ('1' + square / 8)).toUpperCase(Locale.ROOT);
    }

    public static long bit(int square) {
        return 1L << square;
    }
}
