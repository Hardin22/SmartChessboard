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

    static SetupText parse(String message) {
        String text = message == null ? "" : message.trim();
        Matcher away = TAKE_AWAY.matcher(text);
        if (away.matches()) {
            int n = Integer.parseInt(away.group(1));
            return new SetupText(n == 1 ? "Togli il pezzo dalla casa rossa" : "Togli " + n + " pezzi dalle case rosse",
                    null, away.group(3), "Poi " + away.group(2));
        }
        Matcher m = GUIDED.matcher(text);
        if (!m.matches()) {
            return new SetupText(text, null, null, null);
        }
        String rest = m.group(4);
        return new SetupText(m.group(1), m.group(2), m.group(3),
                rest == null || rest.isBlank() ? null : Character.toUpperCase(rest.charAt(0)) + rest.substring(1));
    }

    boolean guided() {
        return step != null;
    }
}
