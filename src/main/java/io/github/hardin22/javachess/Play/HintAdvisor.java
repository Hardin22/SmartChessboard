package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import io.github.hardin22.javachess.Analysis.MoveText;
import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.Squares;
import io.github.hardin22.javachess.Utils.AppExecutors;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hint on request, in two steps as a coach would give it: first the piece to move (its square lights up on the
 * board), then, if asked again, the move itself (from → to). The hint disappears when the position changes.
 * View-model: properties change on the JavaFX thread; {@link #request(String)} and {@link #clear()} are called there.
 */
public final class HintAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HintAdvisor.class);
    /** Depth of the live analysis good enough to answer at once. */
    static final int LIVE_DEPTH = 12;
    static final long NODES = 1_500_000;

    /** What is shown. */
    public enum Level {
        /** No hint. */
        NONE,
        /** Looking for the move. */
        THINKING,
        /** The piece to move. */
        PIECE,
        /** The whole move. */
        MOVE,
        /** The engine could not answer. */
        FAILED
    }

    private final Function<String, CompletableFuture<String>> bestMoveFinder;
    private final Consumer<Map<Integer, Integer>> leds;
    private final Executor fx;

    private final ReadOnlyObjectWrapper<Level> level = new ReadOnlyObjectWrapper<>(this, "level", Level.NONE);
    private final ReadOnlyStringWrapper fromSquare = new ReadOnlyStringWrapper(this, "fromSquare");
    private final ReadOnlyStringWrapper move = new ReadOnlyStringWrapper(this, "move");
    private final ReadOnlyStringWrapper text = new ReadOnlyStringWrapper(this, "text", "");
    private final ReadOnlyIntegerWrapper used = new ReadOnlyIntegerWrapper(this, "used");
    private final ReadOnlyBooleanWrapper canAskMore = new ReadOnlyBooleanWrapper(this, "canAskMore", true);

    private String fen;
    private String best;
    private int generation;

    /** Hints from the shared analysis engine, LEDs on the board. */
    public HintAdvisor() {
        this(HintAdvisor::findBestMove, pixels -> Hardware.leds().replace(LedRenderer.Layer.HINT, pixels),
                AppExecutors::runOnFx);
    }

    /**
     * @param bestMoveFinder best move (UCI) of a position
     * @param leds           shows the given squares/colours on the board (empty map = off)
     * @param fx             JavaFX thread executor
     */
    public HintAdvisor(Function<String, CompletableFuture<String>> bestMoveFinder,
                       Consumer<Map<Integer, Integer>> leds, Executor fx) {
        this.bestMoveFinder = bestMoveFinder;
        this.leds = leds;
        this.fx = fx;
    }

    // ------------------------------------------------------------------ properties

    public ReadOnlyObjectProperty<Level> levelProperty() {
        return level.getReadOnlyProperty();
    }

    /** Square of the piece to move ("g1") once known; for a highlight on the screen board. */
    public ReadOnlyStringProperty fromSquareProperty() {
        return fromSquare.getReadOnlyProperty();
    }

    /** The move (UCI) at level MOVE, for the arrow; null before. */
    public ReadOnlyStringProperty moveProperty() {
        return move.getReadOnlyProperty();
    }

    /** "Muovi il Cavallo in g1" / "Il suggerimento: 12. Cf3" / "Sto cercando la mossa…". */
    public ReadOnlyStringProperty textProperty() {
        return text.getReadOnlyProperty();
    }

    /** Hints given in this game (each request counts once per position). */
    public ReadOnlyIntegerProperty usedProperty() {
        return used.getReadOnlyProperty();
    }

    /** False when the whole move is already shown (the button can say "Mossa mostrata"). */
    public ReadOnlyBooleanProperty canAskMoreProperty() {
        return canAskMore.getReadOnlyProperty();
    }

    /** Sets the counter (a resumed game). */
    public void setUsed(int count) {
        used.set(Math.max(0, count));
    }

    // ------------------------------------------------------------------ actions (FX thread)

    /** First call: the piece to move. Second call in the same position: the move. */
    public void request(String position) {
        if (position == null) {
            return;
        }
        if (!position.equals(fen)) {
            clear();
            fen = position;
            used.set(used.get() + 1);
            int gen = ++generation;
            level.set(Level.THINKING);
            text.set("Sto cercando la mossa…");
            bestMoveFinder.apply(position).whenComplete((uci, err) -> fx.execute(() -> {
                if (gen != generation) {
                    return; // the position changed meanwhile
                }
                if (err != null || uci == null || MoveText.legal(board(position), uci) == null) {
                    log.info("no hint for {}: {}", position, err == null ? uci : err.toString());
                    level.set(Level.FAILED);
                    text.set("Il motore non ha risposto: riprova");
                    fen = null;
                    return;
                }
                best = uci;
                showPiece();
            }));
            return;
        }
        if (level.get() == Level.PIECE) {
            showMove();
        }
    }

    /** Removes the hint (the position changed, the game ended). */
    public void clear() {
        generation++;
        boolean shown = level.get() == Level.PIECE || level.get() == Level.MOVE;
        fen = null;
        best = null;
        level.set(Level.NONE);
        fromSquare.set(null);
        move.set(null);
        text.set("");
        canAskMore.set(true);
        if (shown) {
            leds.accept(Map.of());
        }
    }

    // ------------------------------------------------------------------ internals

    private void showPiece() {
        String from = best.substring(0, 2);
        Piece piece = board(fen).getPiece(Square.valueOf(from.toUpperCase(Locale.ROOT)));
        fromSquare.set(from);
        move.set(null);
        level.set(Level.PIECE);
        text.set("Muovi " + article(piece) + " in " + from);
        canAskMore.set(true);
        Map<Integer, Integer> px = new HashMap<>();
        px.put(Squares.parse(from), LedColors.BEST);
        leds.accept(px);
    }

    private void showMove() {
        move.set(best);
        level.set(Level.MOVE);
        text.set("Il suggerimento: " + MoveText.numbered(fen, best));
        canAskMore.set(false);
        Map<Integer, Integer> px = new HashMap<>();
        px.put(Squares.parse(best.substring(0, 2)), LedColors.dim(LedColors.BEST, 35));
        px.put(Squares.parse(best.substring(2, 4)), LedColors.BEST);
        leds.accept(px);
    }

    /** "il Cavallo", "l'Alfiere", "la Torre", "la Donna", "il Re", "il pedone". */
    static String article(Piece piece) {
        if (piece == null || piece == Piece.NONE) {
            return "il pezzo";
        }
        return switch (piece.getPieceType()) {
            case KNIGHT -> "il Cavallo";
            case BISHOP -> "l'Alfiere";
            case ROOK -> "la Torre";
            case QUEEN -> "la Donna";
            case KING -> "il Re";
            default -> "il pedone";
        };
    }

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    /** The live analysis when it already knows the position well, otherwise a short search. */
    static CompletableFuture<String> findBestMove(String fen) {
        try {
            PositionAnalyzer analyzer = PositionAnalyzer.get();
            AnalysisUpdate live = analyzer.lastUpdate();
            if (live != null && fen.equals(live.fen()) && live.depth() >= LIVE_DEPTH && live.bestMove() != null) {
                return CompletableFuture.completedFuture(live.bestMove());
            }
            return analyzer.searchBest(fen, NODES, 4_000).thenApply(r -> r.bestMove());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
