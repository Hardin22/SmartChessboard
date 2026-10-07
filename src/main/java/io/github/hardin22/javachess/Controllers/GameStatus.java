package io.github.hardin22.javachess.Controllers;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the free-text messages of the game classes and of the board state manager ("Posiziona i pezzi: mancano 4",
 * "Muovi l'avversario: solleva da F8 posiziona su F6", "ERRORE: Controlla E4", "Scaccomatto! Vince il Bianco."...)
 * into a small structure the screens can show in their own words and sizes.
 */
record GameStatus(Kind kind, String text, String from, String to) {

    enum Kind {
        /** No message: the screen shows whose turn it is. */
        NONE,
        /** Pieces to place before the game starts. */
        SETUP,
        /** Board ready, the game starts. */
        READY,
        /** The opponent's (computer, online) move must be made on the board: from -> to. */
        REPLICATE,
        /** The opponent's move has been made on the board. */
        REPLICATED,
        /** A piece is where it should not be, or the engine failed. */
        ERROR,
        /** Connection messages and other information. */
        INFO,
        /** The game is over (text = end message). */
        END
    }

    private static final Pattern SQUARE = Pattern.compile("\\b([A-Ha-h][1-8])\\b");
    private static final Pattern REPLICATE = Pattern.compile("(?i)muovi l'avversario:.*?(?:solleva da ([a-h][1-8]))?\\s*"
            + "posiziona su ([a-h][1-8])");

    static GameStatus none() {
        return new GameStatus(Kind.NONE, "", null, null);
    }

    static GameStatus parse(String message, boolean running) {
        String m = message == null ? "" : message.trim();
        String lower = m.toLowerCase(Locale.ROOT);
        if (m.isEmpty()) {
            return none();
        }
        if (!running) {
            return new GameStatus(Kind.END, m, null, null);
        }
        Matcher replicate = REPLICATE.matcher(m);
        if (replicate.find()) {
            String from = replicate.group(1);
            String to = replicate.group(2);
            return new GameStatus(Kind.REPLICATE, m, from == null ? null : from.toLowerCase(Locale.ROOT),
                    to.toLowerCase(Locale.ROOT));
        }
        if (lower.startsWith("posiziona") || lower.startsWith("configura")) {
            return new GameStatus(Kind.SETUP, m, null, null);
        }
        if (lower.contains("errore") || lower.contains("non disponibile") || lower.startsWith("⚠")) {
            Matcher sq = SQUARE.matcher(m);
            return new GameStatus(Kind.ERROR, m, null, sq.find() ? sq.group(1).toLowerCase(Locale.ROOT) : null);
        }
        if (lower.contains("pronta")) {
            return new GameStatus(Kind.READY, m, null, null);
        }
        if (lower.contains("replicata")) {
            return new GameStatus(Kind.REPLICATED, m, null, null);
        }
        return new GameStatus(Kind.INFO, m, null, null);
    }

    /** Result of an end message from one side's point of view: 1 win, 0 draw, -1 loss, null unknown. */
    static Integer outcomeFor(String endMessage, boolean white) {
        String lower = endMessage == null ? "" : endMessage.toLowerCase(Locale.ROOT);
        if (lower.contains("vince il bianco") || lower.contains("bianco vince") || lower.contains("1-0")) {
            return white ? 1 : -1;
        }
        if (lower.contains("vince il nero") || lower.contains("nero vince") || lower.contains("0-1")) {
            return white ? -1 : 1;
        }
        if (lower.contains("patta") || lower.contains("stallo") || lower.contains("ripetizione")
                || lower.contains("insufficiente") || lower.contains("50 mosse")) {
            return 0;
        }
        return null;
    }

    /** "per scacco matto", "per tempo", "per abbandono", "d'accordo"... (lower case, may be empty). */
    static String reason(String endMessage) {
        String lower = endMessage == null ? "" : endMessage.toLowerCase(Locale.ROOT);
        if (lower.contains("scaccomatto") || lower.contains("scacco matto")) {
            return "per scacco matto";
        }
        if (lower.contains("tempo")) {
            return lower.contains("insufficiente") ? "tempo scaduto, materiale insufficiente" : "per tempo";
        }
        if (lower.contains("abband")) {
            return "per abbandono";
        }
        if (lower.contains("accordo")) {
            return "d'accordo";
        }
        if (lower.contains("stallo")) {
            return "per stallo";
        }
        if (lower.contains("ripetizione")) {
            return "per triplice ripetizione";
        }
        if (lower.contains("insufficiente")) {
            return "materiale insufficiente";
        }
        if (lower.contains("50 mosse")) {
            return "regola delle 50 mosse";
        }
        if (lower.contains("interrott")) {
            return "partita interrotta";
        }
        return "";
    }
}
