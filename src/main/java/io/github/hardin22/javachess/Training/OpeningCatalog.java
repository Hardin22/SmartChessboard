package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import io.github.hardin22.javachess.Analysis.OpeningNames;
import io.github.hardin22.javachess.Engine.review.OpeningBook;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The openings the trainer offers: the most played ones, each with the side that trains it and the moves that
 * define it. Chosen for players who sit at a board: the classical open games and the main defences for both
 * colours.
 */
public final class OpeningCatalog {

    /**
     * An opening to train.
     *
     * @param id    stable key (progress file)
     * @param title Italian name shown in the list ("Partita Italiana")
     * @param white the player trains it with White
     * @param moves the moves that define it (UCI, both sides, from the starting position)
     * @param san   the same moves as written ("1. e4 e5 2. Cf3 Cc6 3. Ac4")
     * @param idea  one line on what it is about
     */
    public record Opening(String id, String title, boolean white, List<String> moves, String san, String idea) {
        public Opening {
            moves = List.copyOf(moves);
        }

        /** "C50 Partita Italiana: Giuoco Piano": the name of the position the line reaches, when the book knows it. */
        public Optional<String> bookName() {
            Board b = new Board();
            for (String uci : moves) {
                b.doMove(new Move(uci, b.getSideToMove()));
            }
            return OpeningBook.standard().nameAfter(b.getFen()).map(OpeningNames::italian);
        }
    }

    private static final List<Opening> ALL = List.of(
            opening("italiana", "Partita Italiana", true, "e4 e5 Nf3 Nc6 Bc4",
                    "Sviluppo rapido e Alfiere puntato su f7: la prima apertura da imparare."),
            opening("spagnola", "Partita Spagnola", true, "e4 e5 Nf3 Nc6 Bb5",
                    "Pressione sul Cavallo che difende e5: la più classica delle aperture aperte."),
            opening("scozzese", "Partita Scozzese", true, "e4 e5 Nf3 Nc6 d4",
                    "Il Bianco apre subito il centro: gioco libero e diretto."),
            opening("viennese", "Partita Viennese", true, "e4 e5 Nc3",
                    "Cavallo prima del pedone f: si prepara f4 con calma."),
            opening("gambetto-re", "Gambetto di Re", true, "e4 e5 f4",
                    "Un pedone in cambio dell'attacco: romantico e tagliente."),
            opening("gambetto-donna", "Gambetto di Donna", true, "d4 d5 c4",
                    "Il pedone c contro il centro nero: la base del gioco di Donna."),
            opening("londra", "Sistema Londra", true, "d4 d5 Bf4",
                    "Lo stesso schema contro quasi tutto: Alfiere in f4, pedoni in d4-e3-c3."),
            opening("inglese", "Apertura Inglese", true, "c4",
                    "Controllo di d5 dal lato: gioco di posizione e strutture flessibili."),
            opening("catalana", "Apertura Catalana", true, "d4 Nf6 c4 e6 g3",
                    "Alfiere in fianchetto sulla grande diagonale: pressione lunga sul lato di Donna."),
            opening("siciliana", "Difesa Siciliana", false, "e4 c5",
                    "La risposta più combattiva a 1. e4: posizioni sbilanciate."),
            opening("francese", "Difesa Francese", false, "e4 e6",
                    "Catena di pedoni solida, contrattacco sul centro con ...d5 e ...c5."),
            opening("caro-kann", "Difesa Caro-Kann", false, "e4 c6",
                    "Solida come la Francese, ma l'Alfiere chiaro esce prima della catena."),
            opening("scandinava", "Difesa Scandinava", false, "e4 d5",
                    "Il Nero attacca subito e4: semplice da imparare, poche linee."),
            opening("due-cavalli", "Difesa dei due cavalli", false, "e4 e5 Nf3 Nc6 Bc4 Nf6",
                    "Contro la Partita Italiana il Nero attacca e4 invece di difendersi."),
            opening("russa", "Difesa Russa", false, "e4 e5 Nf3 Nf6",
                    "Il Nero risponde con un contrattacco simmetrico: molto solida."),
            opening("gambetto-donna-rifiutato", "Gambetto di Donna rifiutato", false, "d4 d5 c4 e6",
                    "Il Nero tiene il centro con ...e6: la difesa classica."),
            opening("slava", "Difesa Slava", false, "d4 d5 c4 c6",
                    "Il centro sostenuto con ...c6 senza chiudere l'Alfiere chiaro."),
            opening("est-indiana", "Difesa Est-Indiana", false, "d4 Nf6 c4 g6",
                    "Il Nero lascia il centro al Bianco e lo attacca più tardi."),
            opening("nimzo-indiana", "Difesa Nimzo-Indiana", false, "d4 Nf6 c4 e6 Nc3 Bb4",
                    "Inchiodatura del Cavallo e lotta per e4."),
            opening("olandese", "Difesa Olandese", false, "d4 f5",
                    "Il Nero prende e4 con il pedone f: gioco sul lato di Re."));

    private OpeningCatalog() {
    }

    public static List<Opening> all() {
        return ALL;
    }

    /** The openings for one side. */
    public static List<Opening> forSide(boolean white) {
        return ALL.stream().filter(o -> o.white() == white).toList();
    }

    public static Optional<Opening> byId(String id) {
        return ALL.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    private static Opening opening(String id, String title, boolean white, String san, String idea) {
        MoveList list = new MoveList();
        list.loadFromSan(san);
        List<String> uci = new ArrayList<>();
        for (Move m : list) {
            uci.add(m.toString());
        }
        return new Opening(id, title, white, uci, io.github.hardin22.javachess.Analysis.MoveText.line(
                io.github.hardin22.javachess.Analysis.AnalysisTree.START_FEN, uci, uci.size()), idea);
    }
}
