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

    static SetupText parse(String message) {
        String text = message == null ? "" : message.trim();
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
