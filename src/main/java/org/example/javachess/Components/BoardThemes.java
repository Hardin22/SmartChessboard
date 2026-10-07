package org.example.javachess.Components;

import javafx.scene.paint.Color;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Board styles. Flat styles are painted programmatically (crisp, cheap, with coordinates); the image styles
 * are the PNGs in {@code /images/Scacchiere}. The value stored in {@code theme.board} is the id.
 */
public final class BoardThemes {

    public static final String DEFAULT = "Grafite";

    public record Colors(Color light, Color dark) {
    }

    private static final Map<String, Colors> FLAT = new LinkedHashMap<>();
    static {
        FLAT.put("Grafite", new Colors(Color.web("#E4E4E7"), Color.web("#A1A1AA")));
        FLAT.put("Ardesia", new Colors(Color.web("#DEE3EA"), Color.web("#7D8A9C")));
        FLAT.put("Sabbia", new Colors(Color.web("#EEDCC0"), Color.web("#B88B62")));
        FLAT.put("Salvia", new Colors(Color.web("#E9EDDF"), Color.web("#7D9468")));
        FLAT.put("Notte", new Colors(Color.web("#5B5B60"), Color.web("#2F2F33")));
    }

    /** Image-based boards shipped in /images/Scacchiere. */
    public static final List<String> IMAGE_BOARDS = List.of("Marghiacciato.png", "Legno.png", "Marrone.png",
            "Checkers.png", "Bubblegum.png", "Neon.png");

    public static final List<String> PIECE_SETS = List.of("Classico", "Legno", "Vetro", "Neon", "Spray", "Bubblegum");

    private BoardThemes() {
    }

    /** Board style chosen by the user ({@code theme.board}). */
    public static String currentBoard() {
        String override = System.getProperty("javachess.theme.board");
        return override != null ? override : org.example.javachess.Utils.ConfigManager.getProperty("theme.board", DEFAULT);
    }

    /** Piece set chosen by the user ({@code theme.piece}). */
    public static String currentPieces() {
        String override = System.getProperty("javachess.theme.pieces");
        return override != null ? override : org.example.javachess.Utils.ConfigManager.getProperty("theme.piece", "Classico");
    }

    /** Colours of a flat style, or null for image styles. */
    public static Colors colors(String id) {
        return id == null ? null : FLAT.get(id);
    }

    public static List<String> flatBoards() {
        return List.copyOf(FLAT.keySet());
    }

    /** Display name without the file extension. */
    public static String label(String id) {
        if (id == null) {
            return "";
        }
        String name = id.replace(".png", "");
        return "Marghiacciato".equals(name) ? "Mare ghiacciato" : name;
    }
}
