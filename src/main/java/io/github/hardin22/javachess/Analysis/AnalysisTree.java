package io.github.hardin22.javachess.Analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A game with its variations: the main line (the moves actually played) and any line tried from any position.
 * A cursor marks the position shown. Playing a move that already exists from the current position just moves the
 * cursor there; a new move starts (or extends) a variation. The first child of a node is always the continuation
 * of its line: the main line for main-line nodes, the variation itself for variation nodes.
 *
 * <p>Not thread-safe: owned by one thread (the JavaFX thread in the app).</p>
 */
public final class AnalysisTree {

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** One position of the tree, reached by {@link #uci()} from its parent. */
    public static final class Node {
        private final Node parent;
        private final String uci;
        private final String san;
        private final String fen;
        private final int ply;
        private final boolean mainLine;
        private final List<Node> children = new ArrayList<>(2);

        private Node(Node parent, String uci, String san, String fen, boolean mainLine) {
            this.parent = parent;
            this.uci = uci;
            this.san = san;
            this.fen = fen;
            this.ply = parent == null ? 0 : parent.ply + 1;
            this.mainLine = mainLine;
        }

        /** Null for the root. */
        public Node parent() {
            return parent;
        }

        /** Move that led here (UCI), null for the root. */
        public String uci() {
            return uci;
        }

        /** Same move in SAN (English letters), null for the root. */
        public String san() {
            return san;
        }

        /** Position after the move. */
        public String fen() {
            return fen;
        }

        /** Half-moves from the root (0 = root). */
        public int ply() {
            return ply;
        }

        /** True for the root and the moves of the game itself. */
        public boolean isMainLine() {
            return mainLine;
        }

        public boolean isRoot() {
            return parent == null;
        }

        /** Continuations, the first being the line's own continuation. Unmodifiable view. */
        public List<Node> children() {
            return Collections.unmodifiableList(children);
        }

        /** Position before the move (the parent's), null for the root. */
        public String fenBefore() {
            return parent == null ? null : parent.fen;
        }

        /** "12. Cf3" / "12… Cc6" (Italian letters), empty for the root. */
        public String numberedMove() {
            return parent == null ? "" : MoveText.number(parent.fen) + MoveText.italian(san);
        }

        @Override
        public String toString() {
            return isRoot() ? "root" : ply + ":" + uci + (mainLine ? "" : "*");
        }
    }

    private final Node root;
    private final List<Node> mainLine = new ArrayList<>();
    private Node current;

    /** The game from {@code initialFen} (null = standard start); stops at the first illegal move. */
    public AnalysisTree(String initialFen, List<String> mainLineUci) {
        String start = initialFen == null || initialFen.isBlank() ? START_FEN : initialFen;
        Board board = new Board();
        board.loadFromFen(start);
        root = new Node(null, null, null, board.getFen(), true);
        Node at = root;
        for (String uci : mainLineUci == null ? List.<String>of() : mainLineUci) {
            Node next = child(at, uci, true);
            if (next == null) {
                break;
            }
            mainLine.add(next);
            at = next;
        }
        current = root;
    }

    // ------------------------------------------------------------------ reading

    public Node root() {
        return root;
    }

    public Node current() {
        return current;
    }

    /** The moves of the game (without the root). */
    public List<Node> mainLine() {
        return Collections.unmodifiableList(mainLine);
    }

    /** Number of moves of the game. */
    public int mainLineLength() {
        return mainLine.size();
    }

    /** Main-line node after {@code ply} half-moves (0 = start). */
    public Node mainLineNode(int ply) {
        return ply <= 0 || mainLine.isEmpty() ? root : mainLine.get(Math.min(ply, mainLine.size()) - 1);
    }

    public boolean isInMainLine() {
        return current.mainLine;
    }

    /** The last main-line position on the way to the current one (the current node itself when on the main line). */
    public Node branchPoint() {
        Node n = current;
        while (!n.mainLine) {
            n = n.parent;
        }
        return n;
    }

    /** Moves from the branch point to the current position (empty on the main line). */
    public List<Node> variationPath() {
        List<Node> path = new ArrayList<>();
        for (Node n = current; !n.mainLine; n = n.parent) {
            path.add(n);
        }
        Collections.reverse(path);
        return path;
    }

    /** Moves from the root to the current position. */
    public List<Node> path() {
        List<Node> path = new ArrayList<>();
        for (Node n = current; n.parent != null; n = n.parent) {
            path.add(n);
        }
        Collections.reverse(path);
        return path;
    }

    /** UCI moves from the root to the current position. */
    public List<String> pathUci() {
        return path().stream().map(Node::uci).toList();
    }

    public boolean canGoBack() {
        return current.parent != null;
    }

    public boolean canGoForward() {
        return !current.children.isEmpty();
    }

    // ------------------------------------------------------------------ moving the cursor

    /** Moves the cursor to {@code node} (must belong to this tree). */
    public Node goTo(Node node) {
        Objects.requireNonNull(node, "node");
        Node r = node;
        while (r.parent != null) {
            r = r.parent;
        }
        if (r != root) {
            throw new IllegalArgumentException("node of another tree");
        }
        current = node;
        return current;
    }

    /** One move forward along the current line; false at its end. */
    public boolean forward() {
        if (current.children.isEmpty()) {
            return false;
        }
        current = current.children.get(0);
        return true;
    }

    /** One move back; false at the start. */
    public boolean back() {
        if (current.parent == null) {
            return false;
        }
        current = current.parent;
        return true;
    }

    /** To the starting position. */
    public void first() {
        current = root;
    }

    /** To the end of the current line (the end of the game on the main line). */
    public void last() {
        while (!current.children.isEmpty()) {
            current = current.children.get(0);
        }
    }

    /** To the main-line position after {@code ply} half-moves (leaves any variation). */
    public Node goToMainLine(int ply) {
        current = mainLineNode(Math.max(0, ply));
        return current;
    }

    /** Leaves the variation: back to the main-line position where it started. Returns the node. */
    public Node returnToMainLine() {
        current = branchPoint();
        return current;
    }

    // ------------------------------------------------------------------ adding moves

    /**
     * Plays {@code uci} from the current position: moves to the existing continuation, or adds a variation.
     * A four-letter pawn move to the last rank promotes to a queen. Returns the new current node, or null (and the
     * cursor stays) when the move is not legal.
     */
    public Node play(String uci) {
        Node next = child(current, uci, false);
        if (next != null) {
            current = next;
        }
        return next;
    }

    /**
     * Plays a whole line from the current position (e.g. a computer line) and puts the cursor on its <b>first</b>
     * move, so the view can step through it. Stops at the first illegal move. Returns the first node, or null when
     * the first move is illegal.
     */
    public Node playLine(List<String> uciMoves) {
        if (uciMoves == null || uciMoves.isEmpty()) {
            return null;
        }
        Node start = current;
        Node first = null;
        Node at = current;
        for (String uci : uciMoves) {
            Node next = child(at, uci, false);
            if (next == null) {
                break;
            }
            if (first == null) {
                first = next;
            }
            at = next;
        }
        current = first == null ? start : first;
        return first;
    }

    /**
     * Deletes the variation the cursor is in (from the move where it leaves its parent line) and puts the cursor on
     * the position before it. Returns false on the main line (the game itself cannot be deleted).
     */
    public boolean deleteVariation() {
        if (current.mainLine) {
            return false;
        }
        Node n = current;
        // climb while n continues its parent's line (first child): the variation starts where it is not
        while (!n.parent.mainLine && n.parent.children.get(0) == n) {
            n = n.parent;
        }
        n.parent.children.remove(n);
        current = n.parent;
        return true;
    }

    /** True when the tree has at least one move outside the main line. */
    public boolean hasVariations() {
        List<Node> mainNodes = new ArrayList<>(mainLine);
        mainNodes.add(root);
        for (Node m : mainNodes) {
            for (Node c : m.children) {
                if (!c.mainLine) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ export

    /**
     * Movetext with the variations in brackets, SAN with English letters (standard PGN):
     * "1. e4 e5 (1... c5 2. Nf3) 2. Nf3".
     */
    public String movetext() {
        StringBuilder sb = new StringBuilder();
        writeLine(sb, root, true);
        return sb.toString().trim();
    }

    private void writeLine(StringBuilder sb, Node from, boolean forceNumber) {
        Node at = from;
        boolean needNumber = forceNumber;
        while (!at.children.isEmpty()) {
            Node main = at.children.get(0);
            if (at.mainLine && !main.mainLine) {
                // after the last move of the game every continuation is analysis: all in brackets
                for (Node alt : at.children) {
                    sb.append("(");
                    appendMove(sb, alt, true);
                    writeLine(sb, alt, false);
                    trimEnd(sb);
                    sb.append(") ");
                }
                return;
            }
            appendMove(sb, main, needNumber);
            needNumber = false;
            for (int i = 1; i < at.children.size(); i++) {
                Node alt = at.children.get(i);
                sb.append("(");
                appendMove(sb, alt, true);
                writeLine(sb, alt, false);
                trimEnd(sb);
                sb.append(") ");
                needNumber = true;
            }
            at = main;
        }
    }

    private static void appendMove(StringBuilder sb, Node n, boolean forceNumber) {
        String before = n.parent.fen;
        boolean white = MoveText.whiteToMove(before);
        int number = MoveText.moveNumber(before);
        if (white) {
            sb.append(number).append(". ");
        } else if (forceNumber) {
            sb.append(number).append("... ");
        }
        sb.append(n.san).append(' ');
    }

    private static void trimEnd(StringBuilder sb) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
    }

    // ------------------------------------------------------------------ internals

    /** Existing child for {@code uci}, or a new one; null when the move is illegal. */
    private Node child(Node parent, String uci, boolean mainLineNode) {
        Board board = new Board();
        try {
            board.loadFromFen(parent.fen);
        } catch (RuntimeException e) {
            return null;
        }
        Move move = MoveText.legal(board, uci);
        if (move == null) {
            return null;
        }
        String key = move.toString();
        for (Node c : parent.children) {
            if (c.uci.equals(key)) {
                return c;
            }
        }
        String san = MoveText.san(parent.fen, List.of(key), 1).stream().findFirst().orElse(key);
        board.doMove(move);
        Node node = new Node(parent, key, san, board.getFen(), mainLineNode && parent.mainLine);
        parent.children.add(node);
        return node;
    }
}
