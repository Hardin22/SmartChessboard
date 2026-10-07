package io.github.hardin22.javachess.Components;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * The javaChess mark drawn with shapes (crisp at any size, coloured by the theme):
 * a rounded tile holding a 2x2 board whose two light squares sit on the diagonal; the top-right
 * dark square carries a dot, the LED of the smart board. Same geometry as {@code scripts/logo/LogoGenerator.java}.
 */
public class Logo extends Region {

    private final DoubleProperty size = new SimpleDoubleProperty(this, "size", 48);
    private final Rectangle tile = new Rectangle();
    private final Rectangle squareA = new Rectangle();
    private final Rectangle squareB = new Rectangle();
    private final Circle led = new Circle();

    public Logo() {
        getStyleClass().add("logo");
        tile.getStyleClass().add("logo-tile");
        squareA.getStyleClass().add("logo-cut");
        squareB.getStyleClass().add("logo-cut");
        led.getStyleClass().add("logo-led");
        getChildren().addAll(tile, squareA, squareB, led);
        size.addListener((obs, o, n) -> requestLayout());
        setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    }

    public Logo(double size) {
        this();
        setSize(size);
    }

    public DoubleProperty sizeProperty() {
        return size;
    }

    public double getSize() {
        return size.get();
    }

    public void setSize(double value) {
        size.set(value);
    }

    @Override
    protected double computePrefWidth(double height) {
        return getSize();
    }

    @Override
    protected double computePrefHeight(double width) {
        return getSize();
    }

    @Override
    protected void layoutChildren() {
        // Geometry on a 64-unit grid, see LogoGenerator.
        double u = getSize() / 64.0;
        tile.setWidth(64 * u);
        tile.setHeight(64 * u);
        tile.setArcWidth(28 * u);
        tile.setArcHeight(28 * u);
        squareA.setX(14 * u);
        squareA.setY(32 * u);
        squareA.setWidth(18 * u);
        squareA.setHeight(18 * u);
        squareB.setX(32 * u);
        squareB.setY(14 * u);
        squareB.setWidth(18 * u);
        squareB.setHeight(18 * u);
        squareA.setArcWidth(3 * u);
        squareA.setArcHeight(3 * u);
        squareB.setArcWidth(3 * u);
        squareB.setArcHeight(3 * u);
        led.setCenterX(23 * u);
        led.setCenterY(23 * u);
        led.setRadius(4.5 * u);
    }
}
