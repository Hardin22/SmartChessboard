package io.github.hardin22.javachess.Components;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

import java.util.List;

/**
 * Lays its children out as side-by-side columns when there is room ({@code width >= breakpoint}), otherwise stacks
 * them in one centred column. Every column is at most {@code maxColumnWidth} wide. Used by the page screens so the
 * same nodes work on the 720 px portrait monitor, at 1920x720 and in a desktop window.
 */
public class ResponsiveColumns extends Pane {

    private final DoubleProperty breakpoint = new SimpleDoubleProperty(this, "breakpoint", 1000);
    private final DoubleProperty spacing = new SimpleDoubleProperty(this, "spacing", 24);
    private final DoubleProperty maxColumnWidth = new SimpleDoubleProperty(this, "maxColumnWidth", 640);
    private final javafx.beans.property.BooleanProperty centerVertically =
            new javafx.beans.property.SimpleBooleanProperty(this, "centerVertically", false);

    public ResponsiveColumns() {
        getStyleClass().add("responsive-columns");
        breakpoint.addListener(o -> requestLayout());
        spacing.addListener(o -> requestLayout());
        maxColumnWidth.addListener(o -> requestLayout());
    }

    private List<Node> visibleChildren() {
        return getManagedChildren().stream().filter(Node::isVisible).toList();
    }

    private boolean wide(double width) {
        return width >= breakpoint.get() && visibleChildren().size() > 1;
    }

    private double columnWidth(double width, int columns) {
        double available = (width - spacing.get() * (columns - 1)) / columns;
        return Math.max(0, Math.min(available, maxColumnWidth.get()));
    }

    @Override
    protected double computePrefHeight(double width) {
        if (width < 0) {
            width = getWidth() > 0 ? getWidth() : maxColumnWidth.get();
        }
        double insetsV = snappedTopInset() + snappedBottomInset();
        double inner = width - snappedLeftInset() - snappedRightInset();
        List<Node> nodes = visibleChildren();
        if (nodes.isEmpty()) {
            return insetsV;
        }
        if (wide(inner)) {
            double colW = columnWidth(inner, nodes.size());
            double max = 0;
            for (Node n : nodes) {
                max = Math.max(max, n.prefHeight(colW));
            }
            return max + insetsV;
        }
        double colW = Math.min(inner, maxColumnWidth.get());
        double total = 0;
        for (Node n : nodes) {
            total += n.prefHeight(colW);
        }
        return total + spacing.get() * (nodes.size() - 1) + insetsV;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefWidth(double height) {
        return maxColumnWidth.get() + snappedLeftInset() + snappedRightInset();
    }

    @Override
    protected void layoutChildren() {
        double x0 = snappedLeftInset();
        double y0 = snappedTopInset();
        double inner = getWidth() - x0 - snappedRightInset();
        List<Node> nodes = visibleChildren();
        if (nodes.isEmpty()) {
            return;
        }
        if (wide(inner)) {
            double colW = columnWidth(inner, nodes.size());
            double total = colW * nodes.size() + spacing.get() * (nodes.size() - 1);
            double x = x0 + (inner - total) / 2;
            double tallest = 0;
            for (Node n : nodes) {
                tallest = Math.max(tallest, n.prefHeight(colW));
            }
            // Optionally centre the columns vertically (home); pages keep them top-aligned.
            double areaH = Math.max(tallest, getHeight() - y0 - snappedBottomInset());
            for (Node n : nodes) {
                double h = n.prefHeight(colW);
                double y = centerVertically.get() ? y0 + (areaH - h) / 2 : y0;
                n.resizeRelocate(snap(x), snap(y), colW, h);
                x += colW + spacing.get();
            }
        } else {
            double colW = Math.min(inner, maxColumnWidth.get());
            double x = x0 + (inner - colW) / 2;
            double y = y0;
            for (Node n : nodes) {
                double h = n.prefHeight(colW);
                n.resizeRelocate(snap(x), snap(y), colW, h);
                y += h + spacing.get();
            }
        }
    }

    private double snap(double v) {
        return snapPositionX(v);
    }

    public javafx.beans.property.BooleanProperty centerVerticallyProperty() { return centerVertically; }
    public boolean isCenterVertically() { return centerVertically.get(); }
    public void setCenterVertically(boolean v) { centerVertically.set(v); }

    public DoubleProperty breakpointProperty() { return breakpoint; }
    public double getBreakpoint() { return breakpoint.get(); }
    public void setBreakpoint(double v) { breakpoint.set(v); }

    public DoubleProperty spacingProperty() { return spacing; }
    public double getSpacing() { return spacing.get(); }
    public void setSpacing(double v) { spacing.set(v); }

    public DoubleProperty maxColumnWidthProperty() { return maxColumnWidth; }
    public double getMaxColumnWidth() { return maxColumnWidth.get(); }
    public void setMaxColumnWidth(double v) { maxColumnWidth.set(v); }
}
