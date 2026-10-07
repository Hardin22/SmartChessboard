package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Engine.InfoLine;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Engine.Score;
import io.github.hardin22.javachess.Engine.SearchResult;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.MoveReview;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import io.github.hardin22.javachess.Utils.AppExecutors;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * "Rigioca i tuoi errori": the positions where a player went wrong (mistakes, blunders, missed wins) are set up
 * again, one at a time, and the player looks for the right move — on the screen or with the real pieces (the LEDs
 * set the position up and take a wrong try back). The best move is right; another move is accepted when the engine
 * finds it nearly as good. View-model: everything on the JavaFX thread.
 */
public final class MistakeTrainer {

    /** Win chance a try may lose against the best move and still count as right. */
    public static final double TOLERANCE = 0.03;
    static final long NODES = 400_000;

    /**
     * One position to replay.
     *
     * @param ply      0-based index of the move in the game
     * @param fen      position before the mistake
     * @param white    White is to move
     * @param played   the move of the game (UCI)
     * @param best     the best move (UCI)
     * @param bestLine best line from {@code fen}
     * @param label    review label of the game move
     * @param moveText "18. Dxb7" (the game move)
     * @param bestText "18. Cf5"
     */
    public record Exercise(int ply, String fen, boolean white, String played, String best, List<String> bestLine,
                           MoveClassification label, String moveText, String bestText) {
        public Exercise {
            bestLine = List.copyOf(bestLine);
        }

        /** "Trova la mossa migliore per il Bianco" / "… per il Nero". */
        public String task() {
            return "Trova la mossa migliore per il " + (white ? "Bianco" : "Nero");
        }
    }

    /** Where the trainer is. */
    public enum State {
        /** Waiting for a move. */
        YOUR_MOVE,
        /** The engine is judging the try. */
        CHECKING,
        /** The best move. */
        CORRECT,
        /** Another move nearly as good. */
        ALSO_GOOD,
        /** Not good enough: try again or look at the solution. */
        WRONG,
        /** The solution is shown. */
        SOLUTION,
        /** All positions done. */
        FINISHED
    }

    /** Win chance lost by {@code tried} compared with {@code best} in {@code fen} (side to move), 0..1. */
    @FunctionalInterface
    public interface Judge {
        CompletableFuture<Double> loss(String fen, String tried, String best);
    }

    private final List<Exercise> exercises;
    private final Judge judge;
    private final Executor fx;
    private final BoardFollower follower;

    private final ReadOnlyIntegerWrapper index = new ReadOnlyIntegerWrapper(this, "index");
    private final ReadOnlyObjectWrapper<Exercise> current = new ReadOnlyObjectWrapper<>(this, "current");
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.YOUR_MOVE);
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private final ReadOnlyStringWrapper fen = new ReadOnlyStringWrapper(this, "fen");
    private final ReadOnlyStringWrapper shownMove = new ReadOnlyStringWrapper(this, "shownMove");
    private final ReadOnlyIntegerWrapper solved = new ReadOnlyIntegerWrapper(this, "solved");
    private final ReadOnlyIntegerWrapper tries = new ReadOnlyIntegerWrapper(this, "tries");
    private int generation;

    /** Trainer on the app's engine; {@code follower} (may be null) brings the real pieces in. */
    public MistakeTrainer(List<Exercise> exercises, BoardFollower follower) {
        this(exercises, MistakeTrainer::engineLoss, AppExecutors::runOnFx, follower);
    }

    public MistakeTrainer(List<Exercise> exercises, Judge judge, Executor fx, BoardFollower follower) {
        this.exercises = List.copyOf(exercises);
        this.judge = judge;
        this.fx = fx;
        this.follower = follower;
        show(0);
    }

    // ------------------------------------------------------------------ building

    /**
     * The positions to replay for one side ({@code white}): mistakes, blunders and missed wins, plus inaccuracies
     * when {@code withInaccuracies}. Moves whose best move is unknown are skipped.
     */
    public static List<Exercise> exercises(GameReview review, boolean white, boolean withInaccuracies) {
        List<Exercise> out = new ArrayList<>();
        if (review == null) {
            return out;
        }
        for (MoveReview m : review.moves()) {
            MoveClassification c = m.label();
            boolean wanted = c == MoveClassification.MISTAKE || c == MoveClassification.BLUNDER
                    || c == MoveClassification.MISS || (withInaccuracies && c == MoveClassification.INACCURACY);
            if (!wanted || m.whiteMoved() != white || m.bestMove() == null || m.bestMove().equals(m.uci())) {
                continue;
            }
            var b = new com.github.bhlangonijr.chesslib.Board();
            b.loadFromFen(m.fenBefore());
            if (MoveText.legal(b, m.bestMove()) == null) {
                continue; // inconsistent review data
            }
            List<String> line = m.bestLine().isEmpty() ? List.of(m.bestMove()) : m.bestLine();
            out.add(new Exercise(m.ply(), m.fenBefore(), white, m.uci(), m.bestMove(), line, c,
                    MoveText.numbered(m.fenBefore(), m.uci()), MoveText.numbered(m.fenBefore(), m.bestMove())));
        }
        return out;
    }

    // ------------------------------------------------------------------ properties

    /** 0-based index of the current position. */
    public ReadOnlyIntegerProperty indexProperty() {
        return index.getReadOnlyProperty();
    }

    public int total() {
        return exercises.size();
    }

    /** The current position (null when there is none or all are done). */
    public ReadOnlyObjectProperty<Exercise> currentProperty() {
        return current.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** What to say: the task, "Giusto! È la mossa migliore", "Non è la mossa giusta: riprova"… */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** Position to draw. */
    public ReadOnlyStringProperty fenProperty() {
        return fen.getReadOnlyProperty();
    }

    /** Move to show (the try, or the solution) for an arrow/highlight; null while waiting. */
    public ReadOnlyStringProperty shownMoveProperty() {
        return shownMove.getReadOnlyProperty();
    }

    /** Positions solved (right at the first try or after retries, without looking at the solution). */
    public ReadOnlyIntegerProperty solvedProperty() {
        return solved.getReadOnlyProperty();
    }

    /** Tries on the current position. */
    public ReadOnlyIntegerProperty triesProperty() {
        return tries.getReadOnlyProperty();
    }

    // ------------------------------------------------------------------ actions

    /** Uses the physical board: the LEDs set the position up and moves made on it are tries. */
    public void useBoard(boolean on) {
        if (follower == null) {
            return;
        }
        if (on && !follower.isOn() && current.get() != null) {
            follower.start(fen.get(), this::boardTry);
        } else if (!on) {
            follower.stop();
        }
    }

    /**
     * A try (screen or board). Ignored unless waiting for a move ({@link State#YOUR_MOVE}); illegal moves are
     * ignored. After a wrong try on the screen call {@link #retry()}; with the board the LEDs take it back at once.
     */
    public void attempt(String uci) {
        Exercise e = current.get();
        if (e == null || state.get() != State.YOUR_MOVE) {
            return;
        }
        var board = new com.github.bhlangonijr.chesslib.Board();
        board.loadFromFen(e.fen());
        var move = MoveText.legal(board, uci);
        if (move == null) {
            return;
        }
        String tried = move.toString();
        tries.set(tries.get() + 1);
        shownMove.set(tried);
        board.doMove(move);
        fen.set(board.getFen());
        if (tried.equals(e.best())) {
            right(State.CORRECT, "Giusto! È la mossa migliore: " + e.bestText());
            return;
        }
        if (tried.equals(e.played())) {
            wrong("È la mossa giocata in partita: cercane una migliore");
            return;
        }
        state.set(State.CHECKING);
        message.set("Controllo la mossa…");
        int gen = ++generation;
        judge.loss(e.fen(), tried, e.best()).whenComplete((loss, err) -> fx.execute(() -> {
            if (gen != generation || current.get() != e) {
                return;
            }
            if (err == null && loss != null && loss <= TOLERANCE) {
                right(State.ALSO_GOOD, "Anche questa va bene! La migliore era " + e.bestText());
            } else {
                wrong(err != null ? "Non riesco a controllare la mossa: riprova" : "Non è la mossa giusta: riprova");
            }
        }));
    }

    /** Back to the position after a wrong try (the board is guided back too). */
    public void retry() {
        Exercise e = current.get();
        if (e == null || state.get() != State.WRONG) {
            return;
        }
        reset(e, e.task());
    }

    /** Shows the solution (the position is not counted as solved). */
    public void showSolution() {
        Exercise e = current.get();
        if (e == null || state.get() == State.FINISHED) {
            return;
        }
        generation++;
        var board = new com.github.bhlangonijr.chesslib.Board();
        board.loadFromFen(e.fen());
        var bestMove = MoveText.legal(board, e.best());
        if (bestMove == null) {
            return; // a review whose best move does not fit the position: nothing sensible to show
        }
        board.doMove(bestMove);
        fen.set(board.getFen());
        shownMove.set(e.best());
        state.set(State.SOLUTION);
        message.set("La mossa migliore era " + e.bestText() + " · linea: "
                + MoveText.line(e.fen(), e.bestLine(), 8));
        boardTo(board.getFen(), e.fen(), e.best());
    }

    /** The next position (or the end). */
    public void next() {
        show(index.get() + 1);
    }

    /** Leaving: frees the board. */
    public void close() {
        generation++;
        if (follower != null) {
            follower.stop();
        }
    }

    // ------------------------------------------------------------------ internals

    /** A move made on the board: the position it reached (a wrong try is then guided back by the LEDs). */
    private String boardTry(String uci) {
        if (state.get() != State.YOUR_MOVE) {
            return null; // not expecting a move: the board is guided back to the position shown
        }
        attempt(uci);
        return fen.get();
    }

    private void right(State s, String text) {
        solved.set(solved.get() + 1);
        state.set(s);
        message.set(text);
    }

    private void wrong(String text) {
        Exercise e = current.get();
        if (follower != null && follower.isOn() && e != null) {
            // with the real pieces: the LEDs take the try back and the player tries again
            fen.set(e.fen());
            shownMove.set(null);
            state.set(State.YOUR_MOVE);
            message.set(text.replace(": riprova", ": rimetti il pezzo e riprova"));
            follower.positionChanged(e.fen(), null, null);
            return;
        }
        state.set(State.WRONG);
        message.set(text);
    }

    private void show(int i) {
        generation++;
        if (i >= exercises.size()) {
            index.set(exercises.size());
            current.set(null);
            state.set(State.FINISHED);
            shownMove.set(null);
            message.set(exercises.isEmpty() ? "Nessun errore da rigiocare: bella partita!"
                    : "Finito: " + solved.get() + " su " + exercises.size() + " risolte");
            return;
        }
        Exercise e = exercises.get(i);
        index.set(i);
        current.set(e);
        tries.set(0);
        reset(e, e.task());
    }

    private void reset(Exercise e, String text) {
        generation++;
        fen.set(e.fen());
        shownMove.set(null);
        state.set(State.YOUR_MOVE);
        message.set(text);
        if (follower != null && follower.isOn()) {
            follower.positionChanged(e.fen(), null, null);
        }
    }

    private void boardTo(String fenAfter, String fenBefore, String uci) {
        if (follower != null && follower.isOn()) {
            follower.positionChanged(fenAfter, fenBefore, uci);
        }
    }

    /** Engine judge: both moves scored in one search, win chance from the side to move. */
    static CompletableFuture<Double> engineLoss(String fen, String tried, String best) {
        try {
            return PositionAnalyzer.get().scoreMoves(fen, List.of(best, tried), NODES, 4_000)
                    .thenApply(r -> lossFrom(r, tried, best));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static double lossFrom(SearchResult r, String tried, String best) {
        Score sb = null;
        Score st = null;
        for (InfoLine l : r.perMove()) {
            if (l.move().equals(best)) {
                sb = l.score();
            } else if (l.move().equals(tried)) {
                st = l.score();
            }
        }
        if (sb == null || st == null) {
            throw new IllegalStateException("move not scored");
        }
        return Math.max(0, sb.winProbability() - st.winProbability());
    }
}
