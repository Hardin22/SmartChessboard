package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Engine.review.GameReview;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import io.github.hardin22.javachess.Hardware.Hardware;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * View-model of the review and analysis screen: a game (or a bare position) with its variations, the position
 * shown, the computer lines of that position, the review data of the move that led there, the opening name, and
 * optionally the physical board following along. Every method and property lives on the JavaFX thread.
 *
 * <p>Typical use by the review screen: create it with the game, {@link #attachReview} when the review is done, bind
 * the board to {@link #fenProperty()} / {@link #lastMoveProperty()}, the move list and graph cursor to
 * {@link #mainPlyProperty()}, the lines panel to {@link #lines()}, and call {@link #next()}, {@link #previous()},
 * {@link #play(String)}, {@link #playLine(int)}, {@link #showBestLine()}, {@link #backToGame()}.</p>
 */
public final class AnalysisSession {

    private static final Logger log = LoggerFactory.getLogger(AnalysisSession.class);

    private final AnalysisTree tree;
    private final EngineLines lines;
    private final BoardFollower follower;
    private volatile OpeningBook book;
    private GameReview review;

    private final ReadOnlyStringWrapper fen = new ReadOnlyStringWrapper(this, "fen");
    private final ReadOnlyStringWrapper lastMove = new ReadOnlyStringWrapper(this, "lastMove");
    private final ReadOnlyIntegerWrapper ply = new ReadOnlyIntegerWrapper(this, "ply");
    private final ReadOnlyIntegerWrapper mainPly = new ReadOnlyIntegerWrapper(this, "mainPly");
    private final ReadOnlyBooleanWrapper inVariation = new ReadOnlyBooleanWrapper(this, "inVariation");
    private final ReadOnlyBooleanWrapper canGoBack = new ReadOnlyBooleanWrapper(this, "canGoBack");
    private final ReadOnlyBooleanWrapper canGoForward = new ReadOnlyBooleanWrapper(this, "canGoForward");
    private final ReadOnlyStringWrapper title = new ReadOnlyStringWrapper(this, "title", "");
    private final ReadOnlyStringWrapper variationText = new ReadOnlyStringWrapper(this, "variationText", "");
    private final ReadOnlyStringWrapper opening = new ReadOnlyStringWrapper(this, "opening", "");
    private final ReadOnlyBooleanWrapper bookMove = new ReadOnlyBooleanWrapper(this, "bookMove");
    private final ReadOnlyObjectWrapper<ReviewInsights.MoveInsight> insight =
            new ReadOnlyObjectWrapper<>(this, "insight");
    private final ReadOnlyIntegerWrapper revision = new ReadOnlyIntegerWrapper(this, "revision");
    private final ReadOnlyBooleanWrapper hasVariations = new ReadOnlyBooleanWrapper(this, "hasVariations");
    private final ReadOnlyObjectWrapper<Position> position = new ReadOnlyObjectWrapper<>(this, "position");

    /**
     * The position shown, as one value: a view that redraws the board listens to {@link #positionProperty()} only
     * and gets exactly one notification per move.
     *
     * @param fen      position
     * @param lastMove move that led there (UCI), null at the start
     * @param ply      half-moves from the start (variations included)
     * @param mainPly  game ply the position belongs to (see {@link #mainPlyProperty()})
     * @param inVariation true in a variation
     */
    public record Position(String fen, String lastMove, int ply, int mainPly, boolean inVariation) {
    }

    /** A game from {@code initialFen} (null = standard start) on the app's engine and board. */
    public AnalysisSession(String initialFen, List<String> gameUci) {
        this(initialFen, gameUci, new EngineLines(), defaultFollower(), null);
        CompletableFuture.supplyAsync(OpeningBook::standard, AppExecutors.io()).thenAccept(b -> AppExecutors.runOnFx(() -> {
            book = b;
            refresh();
        })).exceptionally(t -> {
            log.warn("opening book not available: {}", t.toString());
            return null;
        });
    }

    /**
     * @param lines    computer lines view-model (its engine)
     * @param follower physical board link, or null when there is no board layer
     * @param book     opening book (null: none / loaded later)
     */
    public AnalysisSession(String initialFen, List<String> gameUci, EngineLines lines, BoardFollower follower,
                           OpeningBook book) {
        this.tree = new AnalysisTree(initialFen, gameUci);
        this.lines = lines;
        this.follower = follower;
        this.book = book;
        refresh();
    }

    private static BoardFollower defaultFollower() {
        try {
            return new BoardFollower(Hardware.boardState());
        } catch (RuntimeException | Error e) {
            log.info("no board layer for the analysis: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ properties

    /** Position shown. */
    public ReadOnlyStringProperty fenProperty() {
        return fen.getReadOnlyProperty();
    }

    /**
     * Position shown, last move and plies in a single value, set once per move after every other property: the one
     * to listen to for redrawing the board.
     */
    public ReadOnlyObjectProperty<Position> positionProperty() {
        return position.getReadOnlyProperty();
    }

    /** Move that led to the position (UCI) for the highlight, null at the start. */
    public ReadOnlyStringProperty lastMoveProperty() {
        return lastMove.getReadOnlyProperty();
    }

    /** Half-moves from the start to the position shown (variations included). */
    public ReadOnlyIntegerProperty plyProperty() {
        return ply.getReadOnlyProperty();
    }

    /**
     * Ply of the game the position belongs to: the position itself on the main line, the move where the variation
     * started otherwise. For the move list highlight and the graph cursor (0 = start).
     */
    public ReadOnlyIntegerProperty mainPlyProperty() {
        return mainPly.getReadOnlyProperty();
    }

    /** True when the position is in a variation (show "Torna alla partita"). */
    public ReadOnlyBooleanProperty inVariationProperty() {
        return inVariation.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty canGoBackProperty() {
        return canGoBack.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty canGoForwardProperty() {
        return canGoForward.getReadOnlyProperty();
    }

    /** "12. Cf3", "12… Cc6", or "Posizione iniziale". */
    public ReadOnlyStringProperty titleProperty() {
        return title.getReadOnlyProperty();
    }

    /** Moves of the variation shown, numbered: "12… Ad6 13. Cf3" (empty on the main line). */
    public ReadOnlyStringProperty variationTextProperty() {
        return variationText.getReadOnlyProperty();
    }

    /** Opening of the position from the offline book ("C50 Italian Game"), the last one met on the way; or "". */
    public ReadOnlyStringProperty openingProperty() {
        return opening.getReadOnlyProperty();
    }

    /** True when the position shown is still opening theory (book). */
    public ReadOnlyBooleanProperty bookMoveProperty() {
        return bookMove.getReadOnlyProperty();
    }

    /** Review data of the game move that led to the position (null in variations, at the start or before review). */
    public ReadOnlyObjectProperty<ReviewInsights.MoveInsight> insightProperty() {
        return insight.getReadOnlyProperty();
    }

    /** Incremented whenever the tree changes (a variation added or deleted): refresh variation lists. */
    public ReadOnlyIntegerProperty revisionProperty() {
        return revision.getReadOnlyProperty();
    }

    /** True when at least one variation exists (offer "Esporta con le varianti"). */
    public ReadOnlyBooleanProperty hasVariationsProperty() {
        return hasVariations.getReadOnlyProperty();
    }

    /** The computer lines of the position shown. */
    public EngineLines lines() {
        return lines;
    }

    /** The physical board link, or null when there is no board layer. */
    public BoardFollower boardFollower() {
        return follower;
    }

    /** The tree (read only use: variations list, export). */
    public AnalysisTree tree() {
        return tree;
    }

    /** The review attached with {@link #attachReview}, or null. */
    public GameReview review() {
        return review;
    }

    // ------------------------------------------------------------------ actions

    /** Attaches the finished (or partial) review: the move details become available. */
    public void attachReview(GameReview r) {
        this.review = r;
        refresh();
    }

    /** One move forward along the line shown. */
    public boolean next() {
        String before = tree.current().fen();
        boolean moved = tree.forward();
        if (moved) {
            changed(before, tree.current().uci());
        }
        return moved;
    }

    /** One move back. */
    public boolean previous() {
        boolean moved = tree.back();
        if (moved) {
            changed(null, null);
        }
        return moved;
    }

    /** Start of the game. */
    public void first() {
        tree.first();
        changed(null, null);
    }

    /** End of the line shown. */
    public void last() {
        tree.last();
        changed(null, null);
    }

    /** The game position after {@code ply} half-moves (leaves the variation). */
    public void goToPly(int ply) {
        AnalysisTree.Node before = tree.current();
        tree.goToMainLine(ply);
        if (tree.current().parent() == before) {
            changed(before.fen(), tree.current().uci());
        } else {
            changed(null, null);
        }
    }

    /** A node of the tree (variations list). */
    public void goTo(AnalysisTree.Node node) {
        tree.goTo(node);
        changed(null, null);
    }

    /**
     * Plays {@code uci} from the position shown (screen input or analysis): the game move if it is that one,
     * otherwise a variation. Returns false when the move is illegal.
     */
    public boolean play(String uci) {
        String before = tree.current().fen();
        int size = countNodes();
        AnalysisTree.Node n = tree.play(uci);
        if (n == null) {
            return false;
        }
        if (countNodes() != size) {
            revision.set(revision.get() + 1);
        }
        changed(before, n.uci());
        return true;
    }

    /** Plays computer line {@code index} (0 = best) as a variation; the cursor goes to its first move. */
    public boolean playLine(int index) {
        EngineLines.Line line = lines.line(index);
        if (line == null || !line.pv().isEmpty() && !samePosition(lines.fenProperty().get())) {
            return false;
        }
        return playUciLine(line.pv());
    }

    /**
     * "Mostra la mossa migliore": from the position before the game move shown, plays the review's best line as a
     * variation (cursor on its first move). Works for any game move whose best move was another one; the insight's
     * {@code showBest} says when to offer it prominently (bad moves).
     */
    public boolean showBestLine() {
        ReviewInsights.MoveInsight m = insight.get();
        if (m == null || m.bestLine().isEmpty() || m.best() == null || m.best().equals(m.played())) {
            return false;
        }
        AnalysisTree.Node gameMove = tree.current();
        tree.back();
        if (!playUciLine(m.bestLine())) {
            tree.goTo(gameMove);
            refresh();
            return false;
        }
        return true;
    }

    /** Leaves the variation: the game position where it started. */
    public void backToGame() {
        if (!tree.isInMainLine()) {
            tree.returnToMainLine();
            changed(null, null);
        }
    }

    /** Deletes the variation shown (and goes back to where it started). */
    public boolean deleteVariation() {
        boolean deleted = tree.deleteVariation();
        if (deleted) {
            revision.set(revision.get() + 1);
            changed(null, null);
        }
        return deleted;
    }

    /** Destination squares ("e4", ...) of the piece on {@code square} in the position shown (tap-to-move). */
    public List<String> legalTargets(String square) {
        List<String> out = new ArrayList<>();
        try {
            Board b = new Board();
            b.loadFromFen(tree.current().fen());
            Square from = Square.valueOf(square.toUpperCase(Locale.ROOT));
            for (Move m : b.legalMoves()) {
                if (m.getFrom() == from) {
                    String to = m.getTo().name().toLowerCase(Locale.ROOT);
                    if (!out.contains(to)) {
                        out.add(to);
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    /** The analysis as PGN movetext with the variations in brackets (standard English letters). */
    public String movetext() {
        return tree.movetext();
    }

    /**
     * Physical board following the analysis on/off. On: the LEDs guide the pieces to the position shown, then moves
     * made on the board are played here. Does nothing without a board layer.
     */
    public void setBoardFollowing(boolean on) {
        if (follower == null) {
            return;
        }
        if (on && !follower.isOn()) {
            follower.start(tree.current().fen(), this::playFromBoard);
        } else if (!on) {
            follower.stop();
        }
    }

    /** Leaving the screen: stops the engine and frees the board. */
    public void close() {
        lines.stop();
        if (follower != null) {
            follower.stop();
        }
    }

    // ------------------------------------------------------------------ internals

    private boolean fromBoard;

    private String playFromBoard(String uci) {
        fromBoard = true;
        try {
            return play(uci) ? tree.current().fen() : null;
        } finally {
            fromBoard = false;
        }
    }

    private boolean playUciLine(List<String> pv) {
        int size = countNodes();
        AnalysisTree.Node before = tree.current();
        AnalysisTree.Node first = tree.playLine(pv);
        if (first == null) {
            refresh();
            return false;
        }
        if (countNodes() != size) {
            revision.set(revision.get() + 1);
        }
        changed(before.fen(), first.uci());
        return true;
    }

    private boolean samePosition(String other) {
        return BoardFollower.samePosition(other, tree.current().fen());
    }

    private int countNodes() {
        return count(tree.root());
    }

    private static int count(AnalysisTree.Node n) {
        int c = 1;
        for (AnalysisTree.Node child : n.children()) {
            c += count(child);
        }
        return c;
    }

    /** The cursor moved; {@code fromFen}/{@code uci} describe it when it was one move forward. */
    private void changed(String fromFen, String uci) {
        refresh();
        if (follower != null && !fromBoard) {
            follower.positionChanged(tree.current().fen(), fromFen, uci);
        }
    }

    private void refresh() {
        AnalysisTree.Node n = tree.current();
        fen.set(n.fen());
        lastMove.set(n.uci());
        ply.set(n.ply());
        mainPly.set(tree.branchPoint().ply());
        inVariation.set(!n.isMainLine());
        canGoBack.set(tree.canGoBack());
        canGoForward.set(tree.canGoForward());
        title.set(n.isRoot() ? "Posizione iniziale" : n.numberedMove());
        variationText.set(variationText());
        hasVariations.set(tree.hasVariations());
        OpeningBook b = book;
        opening.set(b == null ? "" : openingName(b));
        bookMove.set(b != null && !n.isRoot() && b.isTheory(n.fen()));
        insight.set(n.isMainLine() && !n.isRoot() ? ReviewInsights.move(review, n.ply() - 1) : null);
        Position p = new Position(n.fen(), n.uci(), n.ply(), tree.branchPoint().ply(), !n.isMainLine());
        if (!p.equals(position.get())) {
            position.set(p);
        }
        lines.show(n.fen());
    }

    private String variationText() {
        List<AnalysisTree.Node> path = tree.variationPath();
        if (path.isEmpty()) {
            return "";
        }
        List<String> uci = path.stream().map(AnalysisTree.Node::uci).toList();
        return MoveText.line(path.get(0).fenBefore(), uci, uci.size());
    }

    private String openingName(OpeningBook b) {
        for (AnalysisTree.Node n = tree.current(); n != null; n = n.parent()) {
            var name = b.nameAfter(n.fen());
            if (name.isPresent()) {
                return name.get();
            }
        }
        return "";
    }
}
