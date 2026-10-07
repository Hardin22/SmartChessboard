package io.github.hardin22.javachess.Components;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.IntFunction;

/**
 * − value + selector for touch screens (replaces sliders, which are fiddly with a finger). Values come from a list
 * (e.g. 1, 2, 3, 5, 10, 15 minutes) or a range with a step. Holding a button does not repeat: one tap, one step.
 */
public class Stepper extends HBox {

    private final int[] values;
    private final IntegerProperty value = new SimpleIntegerProperty();
    private final Label valueLabel = Ui.label("", "stepper-value");
    private final Label unitLabel = Ui.label("", "stepper-unit");
    private final Button minus;
    private final Button plus;
    private IntFunction<String> format = String::valueOf;

    /** Range {@code min..max} with {@code step}. */
    public Stepper(int min, int max, int step, int initial) {
        this(range(min, max, step), initial);
    }

    /** Fixed list of values (ascending). */
    public Stepper(int[] values, int initial) {
        this.values = values.clone();
        getStyleClass().add("stepper");
        setAlignment(Pos.CENTER);
        minus = stepButton("fth-minus", I18n.t("common.less"), -1);
        plus = stepButton("fth-plus", I18n.t("common.more"), 1);
        VBox center = new VBox(0, valueLabel, unitLabel);
        center.setAlignment(Pos.CENTER);
        unitLabel.managedProperty().bind(unitLabel.textProperty().isNotEmpty());
        HBox.setHgrow(center, Priority.ALWAYS);
        center.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(minus, center, plus);
        value.addListener((obs, o, n) -> refresh());
        value.set(nearest(initial));
        refresh();
    }

    private static int[] range(int min, int max, int step) {
        int n = (max - min) / step + 1;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = min + i * step;
        }
        return out;
    }

    private Button stepButton(String icon, String text, int direction) {
        Button b = new Button();
        b.getStyleClass().setAll("stepper-btn");
        b.setGraphic(Icons.of(icon, 34));
        b.setAccessibleText(text);
        b.setOnAction(e -> step(direction));
        return b;
    }

    private void step(int direction) {
        int index = indexOf(value.get());
        int next = Math.max(0, Math.min(values.length - 1, index + direction));
        value.set(values[next]);
    }

    private int indexOf(int v) {
        int best = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) {
                best = i;
            }
        }
        return best;
    }

    private int nearest(int v) {
        return values[indexOf(v)];
    }

    private void refresh() {
        valueLabel.setText(format.apply(value.get()));
        int index = indexOf(value.get());
        minus.setDisable(index == 0);
        plus.setDisable(index == values.length - 1);
    }

    public Stepper format(IntFunction<String> formatter, String unit) {
        this.format = formatter;
        unitLabel.setText(unit == null ? "" : unit);
        refresh();
        return this;
    }

    public IntegerProperty valueProperty() {
        return value;
    }

    public int getValue() {
        return value.get();
    }

    public void setValue(int v) {
        value.set(nearest(v));
    }
}
