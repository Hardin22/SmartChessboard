package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Engine.EngineStatus;
import io.github.hardin22.javachess.Engine.InfoLine;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Engine.Score;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * View-model of the computer lines of one position: up to three principal variations with their evaluation and the
 * depth reached, as chess.com and lichess show them under the board. The view sets the position with {@link #show}
 * and binds to the read-only properties; everything is updated on the JavaFX thread.
 *
 * <p>Requests are debounced ({@value #DEBOUNCE_MS} ms), so stepping quickly through a game does not restart the
 * engine at every move; the lines of the previous position are cleared at once, so they are never shown for the
 * wrong position. The analysis runs on the shared analysis engine ({@link PositionAnalyzer}): only one view at a time
 * should own it.</p>
 */
public final class EngineLines {

    private static final Logger log = LoggerFactory.getLogger(EngineLines.class);

    /** Setting with the number of lines (1..3). */
    public static final String LINES_KEY = "analysis.lines";
    /** Setting with the requested depth (the engine budget may cap it). */
    public static final String DEPTH_KEY = "analysis.depth";
    public static final int MAX_LINES = 3;
    static final long DEBOUNCE_MS = 150;
    /** Plies shown in the text of a line (the view truncates to one row anyway). */
    static final int TEXT_PLIES = 12;

    /** State of the panel. */
    public enum Status {
        /** No position set, or stopped. */
        IDLE,
        /** Engine working; lines may be empty (first depths) or provisional. */
        SEARCHING,
        /** Final depth reached. */
        DONE,
        /** The side to move is checkmated: no lines. */
        CHECKMATE,
        /** Stalemate: no lines. */
        STALEMATE,
        /** Stockfish is missing or does not start ({@link #messageProperty()} says why). */
        UNAVAILABLE
    }

    /**
     * One computer line.
     *
     * @param rank        1 = best
     * @param move        first move (UCI), e.g. "g1f3"
     * @param pv          the whole variation (UCI)
     * @param moveText    first move, numbered, Italian letters: "12. Cf3", "12… Cc6"
     * @param text        the variation, numbered, Italian letters: "12. Cf3 Cc6 13. d4 exd4"
     * @param eval        evaluation, White's point of view: "+0.35", "−1.20", "M3", "−M2"
     * @param whiteBetter true when the evaluation favours White (light chip), false for Black (dark chip)
     * @param whitePawns  evaluation in pawns, White POV (mate = ±(1000 − N)), for bars and sorting
     * @param depth       depth of this line
     */
    public record Line(int rank, String move, List<String> pv, String moveText, String text, String eval,
                       boolean whiteBetter, double whitePawns, int depth) {
        public Line {
            pv = List.copyOf(pv);
        }
    }

    /** Where the lines come from (the shared analysis engine in the app, a fake in tests). */
    public interface Source {
        void analyze(String fen, int depth, int multiPv, PositionAnalyzer.Listener listener);

        void stop();

        /** Why the engine cannot analyse (Italian, ready to show), or null when it can. */
        String unavailableReason();
    }

    private final Source source;
    private final Executor fx;
    private final ScheduledExecutorService scheduler;
    private final long debounceMs;

    private final ReadOnlyStringWrapper fen = new ReadOnlyStringWrapper(this, "fen");
    private final ReadOnlyObjectWrapper<List<Line>> lines = new ReadOnlyObjectWrapper<>(this, "lines", List.of());
    private final ReadOnlyIntegerWrapper depth = new ReadOnlyIntegerWrapper(this, "depth");
    private final ReadOnlyObjectWrapper<Status> status = new ReadOnlyObjectWrapper<>(this, "status", Status.IDLE);
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(this, "message", "");
    private final ReadOnlyStringWrapper evalText = new ReadOnlyStringWrapper(this, "evalText", "");
    private final ReadOnlyDoubleWrapper whitePawns = new ReadOnlyDoubleWrapper(this, "whitePawns");
    private final ReadOnlyStringWrapper bestMove = new ReadOnlyStringWrapper(this, "bestMove");
    private final ReadOnlyIntegerWrapper lineCount = new ReadOnlyIntegerWrapper(this, "lineCount");
    private final ReadOnlyBooleanWrapper enabled = new ReadOnlyBooleanWrapper(this, "enabled", true);

    private int requestedDepth;
    /** Incremented by every request: updates of older positions are dropped. FX thread only. */
    private int generation;
    private ScheduledFuture<?> pending;

    /** Lines of the application's analysis engine, settings from config.properties. */
    public EngineLines() {
        this(defaultSource(), AppExecutors::runOnFx, AppExecutors.scheduler(), DEBOUNCE_MS);
    }

    /**
     * @param source     analysis engine
     * @param fx         executor of the JavaFX thread (property changes happen there)
     * @param scheduler  timer for the debounce (null or debounceMs = 0: no debounce)
     * @param debounceMs pause before a new position is sent to the engine
     */
    public EngineLines(Source source, Executor fx, ScheduledExecutorService scheduler, long debounceMs) {
        this.source = source;
        this.fx = fx;
        this.scheduler = scheduler;
        this.debounceMs = debounceMs;
        this.lineCount.set(clampLines(ConfigManager.getIntProperty(LINES_KEY, 2)));
        this.requestedDepth = Math.max(1, ConfigManager.getIntProperty(DEPTH_KEY, 22));
    }

    // ------------------------------------------------------------------ properties

    /** Position whose lines are shown (null when idle). */
    public ReadOnlyStringProperty fenProperty() {
        return fen.getReadOnlyProperty();
    }

    /** The lines, best first (0..{@link #lineCountProperty()} entries); never null. */
    public ReadOnlyObjectProperty<List<Line>> linesProperty() {
        return lines.getReadOnlyProperty();
    }

    /** Depth of the lines shown (0 before the first result). */
    public ReadOnlyIntegerProperty depthProperty() {
        return depth.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<Status> statusProperty() {
        return status.getReadOnlyProperty();
    }

    /** Italian text for the states without lines ("Scacco matto", "Stallo", engine problem), else empty. */
    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** Evaluation of the position (best line), White POV: "+0.35"; "1-0"/"0-1"/"½-½" when the game is over. */
    public ReadOnlyStringProperty evalTextProperty() {
        return evalText.getReadOnlyProperty();
    }

    /** Evaluation for the eval bar (pawns, White POV, mate = ±(1000 − N)). */
    public ReadOnlyDoubleProperty whitePawnsProperty() {
        return whitePawns.getReadOnlyProperty();
    }

    /** Best move (UCI) for the arrow, or null. */
    public ReadOnlyStringProperty bestMoveProperty() {
        return bestMove.getReadOnlyProperty();
    }

    /** Number of lines asked for (1..3, saved in the settings). */
    public ReadOnlyIntegerProperty lineCountProperty() {
        return lineCount.getReadOnlyProperty();
    }

    /** False when the panel is switched off (no engine work). */
    public ReadOnlyBooleanProperty enabledProperty() {
        return enabled.getReadOnlyProperty();
    }

    // ------------------------------------------------------------------ actions (FX thread)

    /** Shows the lines of {@code fen} (replaces the previous position). Same position again: nothing happens. */
    public void show(String fen) {
        if (fen == null || fen.isBlank()) {
            stop();
            return;
        }
        if (fen.equals(this.fen.get()) && status.get() != Status.IDLE && status.get() != Status.UNAVAILABLE) {
            return;
        }
        this.fen.set(fen);
        restart();
    }

    /** Number of lines, 1..3 (saved). Restarts the analysis of the current position. */
    public void setLineCount(int count) {
        int k = clampLines(count);
        if (k == lineCount.get()) {
            return;
        }
        lineCount.set(k);
        ConfigManager.setProperty(LINES_KEY, String.valueOf(k));
        if (fen.get() != null) {
            restart();
        }
    }

    /** Switches the lines off (stops the engine) or on again (analyses the current position). */
    public void setEnabled(boolean on) {
        if (on == enabled.get()) {
            return;
        }
        enabled.set(on);
        if (on && fen.get() != null) {
            restart();
        } else if (!on) {
            cancelPending();
            generation++;
            source.stop();
            clearResults(Status.IDLE, "");
        }
    }

    /** Stops the engine and empties the panel (leaving the screen). */
    public void stop() {
        cancelPending();
        generation++;
        source.stop();
        fen.set(null);
        clearResults(Status.IDLE, "");
    }

    /** Line {@code index} (0-based) of the current results, or null. */
    public Line line(int index) {
        List<Line> l = lines.get();
        return index >= 0 && index < l.size() ? l.get(index) : null;
    }

    // ------------------------------------------------------------------ internals

    private void restart() {
        cancelPending();
        int gen = ++generation;
        String position = fen.get();
        if (!enabled.get()) {
            clearResults(Status.IDLE, "");
            return;
        }
        String terminal = terminalState(position);
        if (terminal != null) {
            source.stop();
            boolean mate = terminal.equals("mate");
            clearResults(mate ? Status.CHECKMATE : Status.STALEMATE, mate ? "Scacco matto" : "Stallo");
            evalText.set(mate ? (MoveText.whiteToMove(position) ? "0-1" : "1-0") : "½-½");
            whitePawns.set(mate ? (MoveText.whiteToMove(position) ? -1000 : 1000) : 0);
            return;
        }
        String reason = source.unavailableReason();
        if (reason != null) {
            clearResults(Status.UNAVAILABLE, reason);
            return;
        }
        clearResults(Status.SEARCHING, "");
        Runnable start = () -> fx.execute(() -> {
            if (gen == generation) {
                int k = lineCount.get();
                source.analyze(position, requestedDepth, k, update -> fx.execute(() -> accept(gen, position, update)));
            }
        });
        if (scheduler == null || debounceMs <= 0) {
            start.run();
        } else {
            pending = scheduler.schedule(start, debounceMs, TimeUnit.MILLISECONDS);
        }
    }

    private void accept(int gen, String position, AnalysisUpdate update) {
        if (gen != generation || !position.equals(update.fen())) {
            return; // an older position, or another view took the engine over
        }
        List<Line> out = new ArrayList<>(MAX_LINES);
        int k = Math.min(lineCount.get(), update.lines().size());
        for (int i = 0; i < k; i++) {
            InfoLine info = update.lines().get(i);
            Score white = info.score().forWhite(update.whiteToMove());
            out.add(new Line(i + 1, info.move(), info.pv(), MoveText.numbered(position, info.move()),
                    MoveText.line(position, info.pv(), TEXT_PLIES), MoveText.eval(white), white.centipawns() >= 0,
                    white.legacyPawns(), info.depth()));
        }
        if (out.isEmpty() && update.terminalScore() == null) {
            return; // nothing new to show yet
        }
        lines.set(List.copyOf(out));
        depth.set(update.depth());
        if (!out.isEmpty()) {
            evalText.set(out.get(0).eval());
            whitePawns.set(out.get(0).whitePawns());
            bestMove.set(out.get(0).move());
        }
        status.set(update.finished() ? Status.DONE : Status.SEARCHING);
        message.set("");
    }

    private void clearResults(Status newStatus, String text) {
        lines.set(List.of());
        depth.set(0);
        bestMove.set(null);
        evalText.set("");
        whitePawns.set(0);
        status.set(newStatus);
        message.set(text == null ? "" : text);
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private static int clampLines(int k) {
        return Math.max(1, Math.min(MAX_LINES, k));
    }

    /** "mate", "stalemate" or null for a position with legal moves (or an unreadable FEN). */
    static String terminalState(String fen) {
        try {
            com.github.bhlangonijr.chesslib.Board b = new com.github.bhlangonijr.chesslib.Board();
            b.loadFromFen(fen);
            if (!b.legalMoves().isEmpty()) {
                return null;
            }
            return b.isKingAttacked() ? "mate" : "stalemate";
        } catch (RuntimeException e) {
            log.debug("unreadable position {}: {}", fen, e.toString());
            return null;
        }
    }

    /** The shared analysis engine of {@link EngineManager}. */
    public static Source defaultSource() {
        return new Source() {
            @Override
            public void analyze(String fen, int depth, int multiPv, PositionAnalyzer.Listener listener) {
                PositionAnalyzer.get().analyze(fen, depth, multiPv, listener);
            }

            @Override
            public void stop() {
                PositionAnalyzer.get().stop();
            }

            @Override
            public String unavailableReason() {
                try {
                    EngineManager.get().analysisClient();
                    return null;
                } catch (RuntimeException e) {
                    EngineStatus s = EngineManager.get().statusProperty().get();
                    return s != null && s.state() == EngineStatus.State.ERROR ? s.message()
                            : "Motore di analisi non disponibile";
                }
            }
        };
    }
}
