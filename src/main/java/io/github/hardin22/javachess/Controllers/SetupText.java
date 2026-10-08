package io.github.hardin22.javachess.Controllers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guided set-up message of the board ("Posiziona le Torri nere: a8, f8 · passo 4 di 9, togli i pezzi sulle case
 * rosse (1)") split for the status card: what to place (title), where (the squares, written big like a move), the
 * step ("passo 4 di 9") and whatever follows. Messages in another form give only a title.
 */
record SetupText(String title, String squares, String step, String rest) {

    private static final Pattern GUIDED = Pattern.compile(
            "^(Posiziona [^:·]+?)(?:(?::| in) ([a-h][1-8](?:, [a-h][1-8])*))?\\s*·\\s*(passo \\d+ di \\d+)(?:,\\s*(.*))?$");

    /** Pieces to take away first: "Posiziona i pezzi: togli quelli sulle case rosse (2), poi il Re bianco in g1 · …". */
    private static final Pattern TAKE_AWAY = Pattern.compile(
            "^Posiziona i pezzi: togli quelli sulle case rosse \\((\\d+)\\), poi (.+?)\\s*·\\s*(passo \\d+ di \\d+)$");

    /** Only pieces to take away: "Posiziona i pezzi: togli quelli sulle case rosse (2)". */
    private static final Pattern TAKE_ONLY = Pattern.compile(
            "^Posiziona i pezzi: togli quelli sulle case rosse \\((\\d+)\\)$");
    /** Not guided: "Posiziona i pezzi: mancano 6, da togliere 2 (in rosso)". */
    private static final Pattern MISSING = Pattern.compile(
            "^Posiziona i pezzi: mancano (\\d+)(?:, da togliere (\\d+) \\(in rosso\\))?$");

    static SetupText parse(String message) {
        String text = message == null ? "" : message.trim();
        Matcher only = TAKE_ONLY.matcher(text);
        if (only.matches()) {
            return new SetupText(takeAway(Integer.parseInt(only.group(1))), null, null, null);
        }
        Matcher missing = MISSING.matcher(text);
        if (missing.matches()) {
            int n = Integer.parseInt(missing.group(1));
            String rest = missing.group(2) == null ? null : takeAway(Integer.parseInt(missing.group(2)));
            return new SetupText(n == 1 ? "Manca 1 pezzo" : "Mancano " + n + " pezzi", null, null, rest);
        }
        Matcher away = TAKE_AWAY.matcher(text);
        if (away.matches()) {
            return new SetupText(takeAway(Integer.parseInt(away.group(1))), null, away.group(3),
                    "Poi " + away.group(2));
        }
        Matcher m = GUIDED.matcher(text);
        if (!m.matches()) {
            return new SetupText(text, null, null, null);
        }
        String rest = m.group(4);
        return new SetupText(m.group(1), m.group(2), m.group(3),
                rest == null || rest.isBlank() ? null : Character.toUpperCase(rest.charAt(0)) + rest.substring(1));
    }

    private static String takeAway(int n) {
        return n == 1 ? "Togli il pezzo dalla casa rossa" : "Togli " + n + " pezzi dalle case rosse";
    }

    boolean guided() {
        return step != null;
    }

    /** A form the card knows how to present (guided step, pieces to take away, pieces missing). */
    boolean known(String message) {
        return guided() || !title.equals(message == null ? "" : message.trim());
    }
}
