package io.github.hardin22.javachess.Components;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import io.github.hardin22.javachess.Oggetti.EvalBar;

/**
 * Responsive layout shared by the game, review and puzzle screens.
 *
 * <p>Portrait (the 720x1920 board monitor): header, top player bar, a square board as wide as the screen, a thin
 * horizontal evaluation bar, bottom player bar and the side panel filling the rest.<br>
 * Landscape (1920x720 or a desktop window): header on top, the board as tall as possible on the left with a
 * vertical evaluation bar, and a column on the right with the player bars around the side panel.</p>
 *
 * <p>The board slot should hold a node that accepts any square size (e.g. a StackPane containing a
 * {@code ChessBoardUI} with {@code setFitToParent(true)}).</p>
 */
public class GameLayout extends Pane {

    private static final double GAP = 12;
    private static final double EVAL_THICKNESS = 8;
    private static final double MIN_SIDE_PORTRAIT = 220;

    private final ObjectProperty<Node> header = slot("header");
    private final ObjectProperty<Node> topBar = slot("topBar");
    private final ObjectProperty<Node> board = slot("board");
    private final ObjectProperty<Node> evalBar = slot("evalBar");
    private final ObjectProperty<Node> bottomBar = slot("bottomBar");
    private final ObjectProperty<Node> side = slot("side");
    private final ReadOnlyBooleanWrapper landscape = new ReadOnlyBooleanWrapper(this, "landscape", false);

    public GameLayout() {
        getStyleClass().add("game-layout");
    }

    private ObjectProperty<Node> slot(String name) {
        ObjectProperty<Node> p = new SimpleObjectProperty<>(this, name);
        p.addListener((obs, oldNode, newNode) -> {
            if (oldNode != null) {
                getChildren().remove(oldNode);
            }
            if (newNode != null && !getChildren().contains(newNode)) {
                getChildren().add(newNode);
            }
            requestLayout();
        });
        return p;
    }

    public ReadOnlyBooleanProperty landscapeProperty() {
        return landscape.getReadOnlyProperty();
    }

    public boolean isLandscape() {
        return landscape.get();
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean wide = w > h * 1.05;
        landscape.set(wide);
        double pad = w >= 1000 ? 24 : 16;
        double contentW = w - 2 * pad;
        double y = 12;

        Node headerNode = header.get();
        if (isShown(headerNode)) {
            double hh = headerNode.prefHeight(contentW);
            headerNode.resizeRelocate(pad, y, contentW, hh);
            y += hh + GAP;
        }

        if (wide) {
            layoutLandscape(w, h, pad, y);
        } else {
            layoutPortrait(w, h, pad, contentW, y);
        }
    }

    private void layoutPortrait(double w, double h, double pad, double contentW, double y) {
        double top = pref(topBar.get(), contentW);
        double bottom = pref(bottomBar.get(), contentW);
        boolean hasEval = isShown(evalBar.get());
        double eval = hasEval ? EVAL_THICKNESS + GAP / 2 : 0;
        double fixed = y + gapIf(top) + top + gapIf(bottom) + bottom + eval + GAP + pad;
        double sideMin = isShown(side.get()) ? Math.max(MIN_SIDE_PORTRAIT, side.get().minHeight(contentW)) : 0;
        double size = floor8(Math.min(contentW, h - fixed - sideMin));
        double x = (w - size) / 2;

        if (isShown(topBar.get())) {
            topBar.get().resizeRelocate(x, y, size, top);
            y += top + GAP;
        }
        place(board.get(), x, y, size, size);
        y += size;
        if (hasEval) {
            setEvalOrientation(Orientation.HORIZONTAL);
            evalBar.get().resizeRelocate(x, y + GAP / 2, size, EVAL_THICKNESS);
            y += eval;
        }
        if (isShown(bottomBar.get())) {
            y += GAP;
            bottomBar.get().resizeRelocate(x, y, size, bottom);
            y += bottom;
        }
        if (isShown(side.get())) {
            y += GAP;
            side.get().resizeRelocate(x, y, size, Math.max(0, h - y - pad));
        }
    }

    private void layoutLandscape(double w, double h, double pad, double y) {
        double availH = h - y - pad;
        boolean hasEval = isShown(evalBar.get());
        double evalW = hasEval ? EVAL_THICKNESS + GAP : 0;
        double size = floor8(Math.min(availH, w * 0.58));
        double colGap = 24;
        double rightW = Math.min(w - 2 * pad - evalW - size - colGap, 720);
        double groupW = evalW + size + colGap + rightW;
        double x = Math.max(pad, (w - groupW) / 2);

        if (hasEval) {
            setEvalOrientation(Orientation.VERTICAL);
            evalBar.get().resizeRelocate(x, y, EVAL_THICKNESS, size);
            x += evalW;
        }
        place(board.get(), x, y, size, size);
        x += size + colGap;

        double top = pref(topBar.get(), rightW);
        double bottom = pref(bottomBar.get(), rightW);
        double cy = y;
        if (isShown(topBar.get())) {
            topBar.get().resizeRelocate(x, cy, rightW, top);
            cy += top + GAP;
        }
        double bottomY = y + size - bottom;
        if (isShown(bottomBar.get())) {
            bottomBar.get().resizeRelocate(x, bottomY, rightW, bottom);
        } else {
            bottomY = y + size + GAP;
        }
        if (isShown(side.get())) {
            side.get().resizeRelocate(x, cy, rightW, Math.max(0, bottomY - GAP - cy));
        }
    }

    private void setEvalOrientation(Orientation o) {
        if (evalBar.get() instanceof EvalBar bar) {
            bar.setOrientation(o);
        }
    }

    private static void place(Node node, double x, double y, double w, double h) {
        if (isShown(node)) {
            node.resizeRelocate(x, y, w, h);
        }
    }

    private static boolean isShown(Node node) {
        return node != null && node.isManaged() && node.isVisible();
    }

    private static double pref(Node node, double width) {
        return isShown(node) ? node.prefHeight(width) : 0;
    }

    private static double gapIf(double size) {
        return size > 0 ? GAP : 0;
    }

    private static double floor8(double v) {
        return Math.max(64, Math.floor(v / 8) * 8);
    }

    public ObjectProperty<Node> headerProperty() { return header; }
    public Node getHeader() { return header.get(); }
    public void setHeader(Node n) { header.set(n); }

    public ObjectProperty<Node> topBarProperty() { return topBar; }
    public Node getTopBar() { return topBar.get(); }
    public void setTopBar(Node n) { topBar.set(n); }

    public ObjectProperty<Node> boardProperty() { return board; }
    public Node getBoard() { return board.get(); }
    public void setBoard(Node n) { board.set(n); }

    public ObjectProperty<Node> evalBarProperty() { return evalBar; }
    public Node getEvalBar() { return evalBar.get(); }
    public void setEvalBar(Node n) { evalBar.set(n); }

    public ObjectProperty<Node> bottomBarProperty() { return bottomBar; }
    public Node getBottomBar() { return bottomBar.get(); }
    public void setBottomBar(Node n) { bottomBar.set(n); }

    public ObjectProperty<Node> sideProperty() { return side; }
    public Node getSide() { return side.get(); }
    public void setSide(Node n) { side.set(n); }
}
