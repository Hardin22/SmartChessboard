package io.github.hardin22.javachess.Components;

import javafx.geometry.Orientation;
import javafx.scene.layout.Region;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;

/**
 * The board as wide as the screen (720 px on the portrait monitor, no side margins) with the evaluation bar as a
 * thin strip right under it: White's share grows from the left, the score sits on the side that is ahead. When the
 * space is limited by its height instead (landscape, desktop windows) the board is centred and the bar stands
 * vertically on its left, White's share growing from the bottom. The board side is a multiple of 8 px, so squares
 * stay crisp.
 */
public class BoardFrame extends Region {

    private static final double STRIP = 22;
    private static final double SIDE_BAR = 30;
    private static final double GAP = 12;

    private ChessBoardUI board;
    private final EvalBar bar;
    private double maxSide = Double.MAX_VALUE;

    public BoardFrame(EvalBar bar) {
        this.bar = bar;
        getChildren().add(bar);
        bar.visibleProperty().addListener((obs, o, n) -> requestLayout());
        getStyleClass().add("board-frame-region");
    }

    public void setBoard(ChessBoardUI newBoard) {
        if (board != null) {
            getChildren().remove(board);
        }
        board = newBoard;
        if (board != null) {
            board.setFitToParent(true);
            getChildren().add(0, board);
        }
        requestLayout();
    }

    public ChessBoardUI getBoard() {
        return board;
    }

    /** Upper limit for the board side (landscape layouts); by default the board takes the whole width. */
    public void setMaxSide(double side) {
        maxSide = side;
        requestLayout();
    }

    private boolean barShown() {
        return bar.isVisible();
    }

    private static double snap8(double v) {
        return Math.max(64, Math.floor(v / 8) * 8);
    }

    /** Board side when the full width is used (bar under the board). */
    private double wideSide(double width) {
        return snap8(Math.min(maxSide, width));
    }

    private double stripSpace() {
        return barShown() ? STRIP : 0;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computePrefHeight(double width) {
        if (width <= 0) {
            return Math.min(maxSide, 720) + stripSpace();
        }
        return wideSide(width) + stripSpace();
    }

    @Override
    protected double computePrefWidth(double height) {
        return Math.min(maxSide, 720);
    }

    @Override
    protected double computeMinHeight(double width) {
        return 64;
    }

    @Override
    protected double computeMinWidth(double height) {
        return 64;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        double side = wideSide(w);
        if (side + stripSpace() <= h + 0.5) {
            // Full width: board edge to edge, bar as a strip under it.
            double x = Math.round((w - side) / 2);
            if (board != null) {
                board.resizeRelocate(x, 0, side, side);
            }
            if (barShown()) {
                bar.setOrientation(Orientation.HORIZONTAL);
                bar.resizeRelocate(x, side, side, STRIP);
            }
            return;
        }
        // Height-limited: centred board, vertical bar on its left.
        double barSpace = barShown() ? SIDE_BAR + GAP : 0;
        side = snap8(Math.min(Math.min(maxSide, h), w - barSpace));
        double x = Math.round((w - side - barSpace) / 2);
        double y = Math.round((h - side) / 2);
        if (barShown()) {
            bar.setOrientation(Orientation.VERTICAL);
            bar.resizeRelocate(x, y, SIDE_BAR, side);
        }
        if (board != null) {
            board.resizeRelocate(x + barSpace, y, side, side);
        }
    }
}
