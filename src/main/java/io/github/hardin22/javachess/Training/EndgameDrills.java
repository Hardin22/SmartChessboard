package io.github.hardin22.javachess.Training;

import java.util.List;
import java.util.Optional;

/**
 * Endgames every player should be able to win or hold, played against the engine from a fixed position. Each
 * position was checked with Stockfish (depth 30): the wins are wins, the draws are draws with the right defence.
 */
public final class EndgameDrills {

    /** What the player has to do. */
    public enum Goal {
        /** Give checkmate within the moves allowed. */
        MATE("Dai scacco matto"),
        /** Promote a pawn to a piece that is not taken at once. */
        PROMOTE("Promuovi il pedone"),
        /** Do not lose: reach a draw, or hold the position for the moves given. */
        DRAW("Tieni la patta");

        public final String label;

        Goal(String label) {
            this.label = label;
        }
    }

    /**
     * One drill.
     *
     * @param id       stable key (progress file)
     * @param category "Matti di base", "Finali di pedoni", "Finali di torre"
     * @param title    "Donna contro Re"
     * @param fen      starting position (the engine moves first when it is not the player's turn)
     * @param white    the player has White
     * @param goal     what to achieve
     * @param moves    moves of the player allowed (MATE, PROMOTE) or to hold (DRAW)
     * @param level    1 easy, 2 medium, 3 hard
     * @param tip      the idea, shown on request
     */
    public record Drill(String id, String category, String title, String fen, boolean white, Goal goal, int moves,
                        int level, String tip) {

        /** "Dai scacco matto in 15 mosse", "Tieni la patta per 20 mosse". */
        public String task() {
            return switch (goal) {
                case MATE -> "Dai scacco matto in " + moves + " mosse";
                case PROMOTE -> "Promuovi il pedone in " + moves + " mosse";
                case DRAW -> "Tieni la patta per " + moves + " mosse";
            };
        }
    }

    public static final String MATES = "Matti di base";
    public static final String PAWNS = "Finali di pedoni";
    public static final String ROOKS = "Finali di torre";

    private static final List<Drill> ALL = List.of(
            new Drill("kqk", MATES, "Donna contro Re", "8/8/8/4k3/8/8/8/3QK3 w - - 0 1", true, Goal.MATE, 15, 1,
                    "Con la Donna a salto di Cavallo dal Re nero restringi la sua gabbia, poi avvicina il tuo Re. "
                            + "Attento allo stallo!"),
            new Drill("krk", MATES, "Torre contro Re", "8/8/8/4k3/8/8/8/R3K3 w - - 0 1", true, Goal.MATE, 25, 1,
                    "La Torre taglia il Re nero su una traversa, il tuo Re lo mette in opposizione: ogni scacco lo "
                            + "spinge di una riga verso il bordo."),
            new Drill("krrk", MATES, "Due Torri: la scala", "8/8/8/3k4/8/8/8/R3K2R w - - 0 1", true, Goal.MATE, 12,
                    1, "Le Torri si danno il cambio: una taglia, l'altra dà scacco, fino al bordo. Il Re non serve."),
            new Drill("kbbk", MATES, "Due Alfieri", "8/8/8/3k4/8/8/8/2B1KB2 w - - 0 1", true, Goal.MATE, 30, 2,
                    "Gli Alfieri affiancati fanno un muro diagonale; spingi il Re nero in un angolo con l'aiuto del "
                            + "tuo Re."),
            new Drill("kbnk", MATES, "Alfiere e Cavallo", "8/8/8/3k4/8/8/8/1N2KB2 w - - 0 1", true, Goal.MATE, 50, 3,
                    "Il matto si dà solo nell'angolo del colore dell'Alfiere: porta lì il Re nero (manovra a W del "
                            + "Cavallo)."),
            new Drill("kp-front", PAWNS, "Re davanti al pedone", "4k3/8/4K3/4P3/8/8/8/8 w - - 0 1", true,
                    Goal.PROMOTE, 15, 1,
                    "Con il Re sulla sesta traversa davanti al pedone si vince sempre: prendi l'opposizione e il "
                            + "pedone passa."),
            new Drill("kp-tempo", PAWNS, "La mossa di riserva", "8/8/3k4/8/3K4/8/3P4/8 w - - 0 1", true,
                    Goal.PROMOTE, 20, 2,
                    "Il pedone ancora indietro ti dà una mossa di attesa: usala per conquistare l'opposizione."),
            new Drill("kp-defence", PAWNS, "Difendi contro Re e pedone", "8/8/8/4k3/8/8/4P3/4K3 b - - 0 1", false,
                    Goal.DRAW, 20, 2,
                    "Resta davanti al pedone e, quando il Re bianco avanza, prendi l'opposizione."),
            new Drill("lucena", ROOKS, "Posizione di Lucena", "1K1k4/1P6/8/8/8/8/r7/2R5 w - - 0 1", true,
                    Goal.PROMOTE, 20, 3,
                    "Taglia il Re nero con la Torre, poi \"costruisci il ponte\": Torre in quarta traversa per "
                            + "ripararti dagli scacchi."),
            new Drill("philidor", ROOKS, "Posizione di Philidor", "3k4/8/7r/3PK3/8/8/8/R7 b - - 0 1", false,
                    Goal.DRAW, 25, 3,
                    "Torre sulla sesta traversa finché il pedone non avanza; quando arriva in sesta, scacchi da "
                            + "dietro."));

    private EndgameDrills() {
    }

    public static List<Drill> all() {
        return ALL;
    }

    public static List<String> categories() {
        return List.of(MATES, PAWNS, ROOKS);
    }

    public static List<Drill> inCategory(String category) {
        return ALL.stream().filter(d -> d.category().equals(category)).toList();
    }

    public static Optional<Drill> byId(String id) {
        return ALL.stream().filter(d -> d.id().equals(id)).findFirst();
    }
}
