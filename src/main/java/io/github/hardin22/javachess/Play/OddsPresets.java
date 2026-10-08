package io.github.hardin22.javachess.Play;

import java.util.List;
import java.util.Optional;

/**
 * Handicap games ("partite con vantaggio"), the classical way for a stronger player to give a weaker one a chance
 * at the same board (parent and child, teacher and pupil, a strong player against the bot at a low level): the
 * stronger side starts without some material. Positions for White giving the odds; the game is started from the
 * position with {@code AbstractGame.setStartPosition} like any position from the editor, and the guided set-up
 * shows which pieces to leave off.
 *
 * <p>To give odds with Black, the same positions mirrored are in {@link Preset#blackGives()}.</p>
 */
public final class OddsPresets {

    /**
     * @param id    stable key
     * @param title "Senza Donna"
     * @param fen   White gives the odds (White to move)
     * @param note  what it means in practice
     */
    public record Preset(String id, String title, String fen, String note) {
        /** The same odds given by Black: White keeps everything, Black starts without the material. */
        public String blackGives() {
            String[] parts = fen.split(" ");
            String[] ranks = parts[0].split("/");
            StringBuilder mirrored = new StringBuilder();
            for (int i = ranks.length - 1; i >= 0; i--) {
                StringBuilder rank = new StringBuilder();
                for (char c : ranks[i].toCharArray()) {
                    rank.append(Character.isLetter(c)
                            ? (Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c)) : c);
                }
                mirrored.append(rank);
                if (i > 0) {
                    mirrored.append('/');
                }
            }
            String castling = parts[2].equals("-") ? "-" : swapCase(parts[2]);
            castling = sortCastling(castling);
            return mirrored + " w " + castling + " - 0 1";
        }
    }

    private static final List<Preset> ALL = List.of(
            new Preset("pawn", "Senza il pedone f", "rnbqkbnr/pppppppp/8/8/8/8/PPPPP1PP/RNBQKBNR w KQkq - 0 1",
                    "Il vantaggio più piccolo: il pedone f, quello che protegge il Re."),
            new Preset("knight", "Senza un Cavallo", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/R1BQKBNR w KQkq - 0 1",
                    "Senza il Cavallo di b1: circa 3 pedoni di vantaggio."),
            new Preset("rook", "Senza una Torre", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/1NBQKBNR w Kkq - 0 1",
                    "Senza la Torre di a1: un vantaggio grande."),
            new Preset("queen", "Senza Donna", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR w KQkq - 0 1",
                    "Il vantaggio classico tra un giocatore esperto e un principiante."),
            new Preset("queen-rook", "Senza Donna e Torre", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/1NB1KBNR w Kkq - 0 1",
                    "Per chi sta imparando le regole contro un adulto che gioca bene."));

    private OddsPresets() {
    }

    public static List<Preset> all() {
        return ALL;
    }

    public static Optional<Preset> byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    private static String swapCase(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            out.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return out.toString();
    }

    /** "kqKQ" → "KQkq" (FEN order). */
    private static String sortCastling(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : "KQkq".toCharArray()) {
            if (s.indexOf(c) >= 0) {
                out.append(c);
            }
        }
        return out.isEmpty() ? "-" : out.toString();
    }
}
