package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Analysis.AnalysisTree;
import io.github.hardin22.javachess.Analysis.BoardFollower;
import io.github.hardin22.javachess.Analysis.MoveText;
import io.github.hardin22.javachess.Analysis.OpeningNames;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Opening trainer, offline: the player picks an opening and plays its moves (on the screen or with the real
 * pieces); the app plays the other side with the replies most often seen in real games (weighted at random, so
 * every run is a little different) and stops the player when a move leaves the theory. First the moves that
 * define the opening, then the theory that follows it, up to {@link #maxPlies()} plies or the end of the data.
 *
 * <p>A move of the player is <b>right</b> when it is the opening's move (while inside the defining line) or, after
 * it, a move played in at least {@link #GOOD_SHARE} of the games; a move played less often but still in at least
 * {@link #PLAYABLE_SHARE} is accepted with a note; anything else is <b>out of theory</b>: the position stays, the
 * board guides the piece back, and the second wrong try shows the theory moves. View-model on the JavaFX thread.</p>
 */
public final class OpeningTrainer {

    /** Share of the games that makes a move "theory". */
    public static final double GOOD_SHARE = 0.10;
    /** Share below which a move is out of theory. */
    public static final double PLAYABLE_SHARE = 0.02;
    /** The app only replies with moves played in at least this share of the games. */
    public static final double OPPONENT_SHARE = 0.05;
    public static final int DEFAULT_PLIES = 16;

    public enum State {
        /** Waiting for the player's move. */
        YOUR_MOVE,
        /** The last try was out of theory: the position is the same, try again. */
        WRONG,
        /** End of the line (theory or length reached). */
        DONE
    }

    private final OpeningCatalog.Opening opening;
    private final OpeningExplorer explorer;
    private final OpeningExplorer.Rating rating;
    private final OpeningBook book;
    private final int maxPlies;
    private final Random random;
    private final BoardFollower follower;
    private final OpeningProgress progress;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.YOUR_MOVE);
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private final ReadOnlyStringWrapper fen = new ReadOnlyStringWrapper(this, "fen", AnalysisTree.START_FEN);
    private final ReadOnlyStringWrapper lastMove = new ReadOnlyStringWrapper(this, "lastMove");
    private final ReadOnlyStringWrapper shownMove = new ReadOnlyStringWrapper(this, "shownMove");
    private final ReadOnlyStringWrapper movesText = new ReadOnlyStringWrapper(this, "movesText", "");
    private final ReadOnlyStringWrapper openingName = new ReadOnlyStringWrapper(this, "openingName", "");
    private final ReadOnlyObjectWrapper<List<OpeningExplorer.Candidate>> theory =
            new ReadOnlyObjectWrapper<>(this, "theory", List.of());
    private final ReadOnlyIntegerWrapper plies = new ReadOnlyIntegerWrapper(this, "plies");
    private final ReadOnlyIntegerWrapper mistakes = new ReadOnlyIntegerWrapper(this, "mistakes");

    private final List<String> played = new ArrayList<>();
    /** Wrong tries in the current position. */
    private int triesHere;
    private boolean recorded;

    /** Trainer on the bundled data; {@code follower} (may be null) brings in the real pieces. */
    public OpeningTrainer(OpeningCatalog.Opening opening, BoardFollower follower, OpeningProgress progress) {
        this(opening, OpeningExplorer.standard(), OpeningExplorer.Rating.CLUB, OpeningBook.standard(), DEFAULT_PLIES,
                new Random(), follower, progress);
    }

    public OpeningTrainer(OpeningCatalog.Opening opening, OpeningExplorer explorer, OpeningExplorer.Rating rating,
                          OpeningBook book, int maxPlies, Random random, BoardFollower follower,
                          OpeningProgress progress) {
        this.opening = opening;
        this.explorer = explorer;
        this.rating = rating;
        this.book = book;
        this.maxPlies = Math.max(maxPlies, opening.moves().size());
        this.random = random;
        this.follower = follower;
        this.progress = progress;
        restart();
    }

    // ------------------------------------------------------------------ properties

    public OpeningCatalog.Opening opening() {
        return opening;
    }

    /** Plies of a full run (the opening's moves and the theory after them). */
    public int maxPlies() {
        return maxPlies;
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** "Gioca la prima mossa della Partita Italiana", "Bene: 4. c3 (38% delle partite)", "Fuori teoria: riprova"… */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** Position to draw (the player's side at the bottom: {@code opening().white()}). */
    public ReadOnlyStringProperty fenProperty() {
        return fen.getReadOnlyProperty();
    }

    /** Last move played (UCI) for the highlight, null at the start. */
    public ReadOnlyStringProperty lastMoveProperty() {
        return lastMove.getReadOnlyProperty();
    }

    /** Move to show as an arrow (hint or solution, UCI), null otherwise. */
    public ReadOnlyStringProperty shownMoveProperty() {
        return shownMove.getReadOnlyProperty();
    }

    /** The moves so far, numbered with Italian letters ("1. e4 e5 2. Cf3 Cc6 3. Ac4"). */
    public ReadOnlyStringProperty movesTextProperty() {
        return movesText.getReadOnlyProperty();
    }

    /** Name of the last named opening reached ("C50 Partita Italiana: Giuoco Piano"), "" before. */
    public ReadOnlyStringProperty openingNameProperty() {
        return openingName.getReadOnlyProperty();
    }

    /**
     * The theory moves of the current position (most played first), filled only when they may be shown: after a
     * wrong try, after a hint and at the end. Empty while the player is thinking.
     */
    public ReadOnlyObjectProperty<List<OpeningExplorer.Candidate>> theoryProperty() {
        return theory.getReadOnlyProperty();
    }

    /** Plies played so far (both sides). */
    public ReadOnlyIntegerProperty pliesProperty() {
        return plies.getReadOnlyProperty();
    }

    /** Positions where the player left the theory at least once. */
    public ReadOnlyIntegerProperty mistakesProperty() {
        return mistakes.getReadOnlyProperty();
    }

    public List<String> playedMoves() {
        return List.copyOf(played);
    }

    // ------------------------------------------------------------------ actions

    /** Starts the line again from the starting position (the app's replies may differ). */
    public void restart() {
        played.clear();
        triesHere = 0;
        recorded = false;
        fen.set(AnalysisTree.START_FEN);
        lastMove.set(null);
        shownMove.set(null);
        theory.set(List.of());
        openingName.set("");
        plies.set(0);
        mistakes.set(0);
        movesText.set("");
        state.set(State.YOUR_MOVE);
        if (!playersTurn()) {
            reply();
        }
        if (state.get() == State.YOUR_MOVE) {
            message.set(played.size() < opening.moves().size()
                    ? (played.isEmpty() ? "Gioca la prima mossa della " : "Il Bianco ha giocato: rispondi come nella ")
                    + opening.title()
                    : "Continua con una mossa di teoria");
        }
        if (follower != null && follower.isOn()) {
            follower.positionChanged(fen.get(), null, null);
        }
    }

    /** A move of the player on the screen. Returns true when it was accepted. */
    public boolean play(String uci) {
        return tryMove(uci) != null;
    }

    /** Shows the next move: the opening's move, or the most played one. Counts as a mistake in this position. */
    public void hint() {
        if (state.get() == State.DONE) {
            return;
        }
        String best = expected();
        if (best == null) {
            return;
        }
        countMistake();
        shownMove.set(best);
        theory.set(candidates());
        message.set("Si gioca " + MoveText.numbered(fen.get(), best));
    }

    /** Uses the physical board: the LEDs set the position up and show the app's replies; moves made on it count. */
    public void useBoard(boolean on) {
        if (follower == null) {
            return;
        }
        if (on && !follower.isOn()) {
            follower.start(fen.get(), this::tryMove);
        } else if (!on) {
            follower.stop();
        }
    }

    public void close() {
        if (follower != null) {
            follower.stop();
        }
    }

    // ------------------------------------------------------------------ internals

    /** Judges a move of the player; returns the position after it, or null when refused. */
    private String tryMove(String uci) {
        if (state.get() == State.DONE || !playersTurn()) {
            return null;
        }
        String before = fen.get();
        Board board = board(before);
        Move move = MoveText.legal(board, uci);
        if (move == null) {
            return null;
        }
        uci = move.toString();
        int ply = played.size();
        List<OpeningExplorer.Candidate> moves = candidates();
        String text = MoveText.numbered(before, uci);
        String note;
        if (ply < opening.moves().size()) {
            if (!uci.equals(opening.moves().get(ply))) {
                wrong(text + " non è la mossa della " + opening.title(), opening.moves().get(ply), moves);
                return null;
            }
            note = "Giusto: " + text;
        } else {
            OpeningExplorer.Candidate c = moves.stream().filter(m -> m.uci().equals(move.toString())).findFirst()
                    .orElse(null);
            boolean top = c != null && !moves.isEmpty() && moves.get(0).uci().equals(c.uci());
            if (c == null || (c.share() < PLAYABLE_SHARE && !top)) {
                wrong(text + " è fuori teoria", moves.isEmpty() ? null : moves.get(0).uci(), moves);
                return null;
            }
            if (c.share() >= GOOD_SHARE || top) {
                note = "Bene: " + text + " (" + c.percent() + " delle partite)";
            } else {
                note = "Si gioca, ma meno spesso (" + c.percent() + "): la più comune è "
                        + MoveText.numbered(before, moves.get(0).uci());
            }
        }
        apply(uci);
        String afterPlayer = fen.get();
        message.set(note);
        if (!finishedAfterPlayer()) {
            reply();
            if (state.get() == State.YOUR_MOVE) {
                message.set(note + " · ora tocca a te");
            }
        }
        return afterPlayer;
    }

    private void wrong(String text, String solution, List<OpeningExplorer.Candidate> moves) {
        countMistake();
        triesHere++;
        state.set(State.WRONG);
        if (triesHere >= 2 && solution != null) {
            shownMove.set(solution);
            theory.set(moves);
            message.set(text + ". Si gioca " + MoveText.numbered(fen.get(), solution));
        } else {
            message.set(text + ": riprova");
        }
    }

    private void countMistake() {
        if (triesHere == 0 && shownMove.get() == null) {
            mistakes.set(mistakes.get() + 1);
        }
    }

    /** True (and the run is over) when the line has reached its length or the theory ends. */
    private boolean finishedAfterPlayer() {
        if (played.size() >= maxPlies) {
            finish("Linea completata");
            return true;
        }
        return false;
    }

    /** The app's move: the opening's move, or a theory move weighted by how often it is played. */
    private void reply() {
        int ply = played.size();
        String uci;
        if (ply < opening.moves().size()) {
            uci = opening.moves().get(ply);
        } else {
            uci = pickReply(candidates());
            if (uci == null) {
                finish("Fine della teoria");
                return;
            }
        }
        String before = fen.get();
        apply(uci);
        if (follower != null && follower.isOn()) {
            follower.positionChanged(fen.get(), before, uci);
        }
        if (played.size() >= maxPlies) {
            finish("Linea completata");
        } else if (played.size() >= opening.moves().size() && candidates().isEmpty()) {
            finish("Fine della teoria");
        }
    }

    private String pickReply(List<OpeningExplorer.Candidate> moves) {
        if (moves.isEmpty()) {
            return null;
        }
        List<OpeningExplorer.Candidate> usual = moves.stream().filter(m -> m.share() >= OPPONENT_SHARE).toList();
        if (usual.isEmpty()) {
            usual = List.of(moves.get(0));
        }
        long total = usual.stream().mapToLong(OpeningExplorer.Candidate::games).sum();
        long pick = (long) (random.nextDouble() * total);
        for (OpeningExplorer.Candidate c : usual) {
            pick -= c.games();
            if (pick < 0) {
                return c.uci();
            }
        }
        return usual.get(usual.size() - 1).uci();
    }

    private void apply(String uci) {
        Board board = board(fen.get());
        board.doMove(MoveText.legal(board, uci));
        played.add(uci);
        fen.set(board.getFen());
        lastMove.set(uci);
        shownMove.set(null);
        theory.set(List.of());
        triesHere = 0;
        plies.set(played.size());
        movesText.set(MoveText.line(AnalysisTree.START_FEN, played, played.size()));
        book.nameAfter(fen.get()).map(OpeningNames::italian).ifPresent(openingName::set);
        if (state.get() == State.WRONG) {
            state.set(State.YOUR_MOVE);
        }
    }

    private void finish(String why) {
        state.set(State.DONE);
        theory.set(candidates());
        int errors = mistakes.get();
        message.set(why + ": " + fullMoves() + (errors == 0 ? ", nessun errore" : errors == 1 ? ", 1 errore"
                : ", " + errors + " errori"));
        if (progress != null && !recorded) {
            recorded = true;
            progress.record(opening.id(), played.size(), errors);
        }
    }

    /** "8 mosse" (full moves of the line). */
    private String fullMoves() {
        int n = (played.size() + 1) / 2;
        return n == 1 ? "1 mossa" : n + " mosse";
    }

    /** The move the trainer expects now (the opening's move or the most played), null when there is none. */
    private String expected() {
        int ply = played.size();
        if (ply < opening.moves().size()) {
            return opening.moves().get(ply);
        }
        List<OpeningExplorer.Candidate> moves = candidates();
        return moves.isEmpty() ? null : moves.get(0).uci();
    }

    private List<OpeningExplorer.Candidate> candidates() {
        return explorer.moves(fen.get(), rating);
    }

    private boolean playersTurn() {
        return MoveText.whiteToMove(fen.get()) == opening.white();
    }

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    /** "e4 (45%), d4 (30%)": the theory moves as a short text. */
    public static String describe(List<OpeningExplorer.Candidate> moves, int max) {
        return moves.stream().limit(max).map(m -> m.san() + " (" + m.percent() + ")")
                .collect(Collectors.joining(", "));
    }
}
