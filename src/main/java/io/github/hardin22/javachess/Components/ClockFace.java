package io.github.hardin22.javachess.Components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/**
 * A chess clock display: very large Geist Mono Light digits that fit the space they are given. States (as style
 * classes): {@code active} = the running clock, lit; {@code low} = active and under 20 seconds, red;
 * {@code paused}. Text is "5:00", "0:42" and, in the last ten seconds, "9.4".
 *
 * <p>Repaints only when the text or a state changes (the game clock changes the text once a second).</p>
 */
public class ClockFace extends StackPane {

    private static final double LOW_SECONDS = 20;
    private static final String FAMILY = "Geist Light";
    private static double digitRatio = -1;
    private static double separatorRatio;

    /** One label per character, digits in cells of equal width: the time never shifts sideways as it changes. */
    private final javafx.scene.layout.HBox cells = new javafx.scene.layout.HBox();
    private String text = "";
    private double maxFont = 220;
    private double appliedFont = -1;
    private boolean active;
    private boolean paused;
    private double secondsLeft = Double.MAX_VALUE;

    public ClockFace() {
        getStyleClass().add("clock-face");
        cells.setAlignment(Pos.CENTER);
        cells.setMinWidth(0);
        setAlignment(Pos.CENTER);
        getChildren().add(cells);
        setMinHeight(0);
        setMinWidth(0);
        setTime("00:00");
    }

    private static synchronized void measure() {
        if (digitRatio > 0) {
            return;
        }
        javafx.scene.text.Font font = javafx.scene.text.Font.font(FAMILY, 100);
        double max = 0;
        for (char c = '0'; c <= '9'; c++) {
            javafx.scene.text.Text t = new javafx.scene.text.Text(String.valueOf(c));
            t.setFont(font);
            max = Math.max(max, t.getLayoutBounds().getWidth());
        }
        javafx.scene.text.Text colon = new javafx.scene.text.Text(":");
        colon.setFont(font);
        digitRatio = max / 100.0;
        separatorRatio = Math.max(colon.getLayoutBounds().getWidth() / 100.0, 0.22) + 0.04;
    }

    public void setMaxFontSize(double size) {
        maxFont = size;
        appliedFont = -1;
        requestLayout();
    }

    /** Follows the labels written by the game's ChessTimer (text, and the "stopwatch-active" class). */
    public void bind(Label source) {
        source.textProperty().addListener((obs, o, n) -> setTime(n));
        source.getStyleClass().addListener((javafx.collections.ListChangeListener<String>) c ->
                setActive(source.getStyleClass().contains("stopwatch-active")));
        setTime(source.getText());
        setActive(source.getStyleClass().contains("stopwatch-active"));
    }

    /** Raw clock text from {@code ChessClock.display} ("05:00", "00:09.4"). */
    public void setTime(String raw) {
        String formatted = format(raw);
        secondsLeft = seconds(raw);
        if (!formatted.equals(text)) {
            boolean shapeChanged = !shape(formatted).equals(shape(text));
            text = formatted;
            while (cells.getChildren().size() < text.length()) {
                Label cell = new Label();
                cell.getStyleClass().add("clock-digits");
                cell.setAlignment(Pos.CENTER);
                cell.setPadding(javafx.geometry.Insets.EMPTY);
                cells.getChildren().add(cell);
            }
            cells.getChildren().remove(text.length(), cells.getChildren().size());
            for (int i = 0; i < text.length(); i++) {
                ((Label) cells.getChildren().get(i)).setText(String.valueOf(text.charAt(i)));
            }
            if (shapeChanged) {
                appliedFont = -1;
                requestLayout();
            }
        }
        updateStyle();
    }

    /** "d:dd" pattern of a text: cells are resized only when it changes. */
    private static String shape(String s) {
        return s.replaceAll("[0-9]", "d");
    }

    public void setActive(boolean value) {
        active = value;
        updateStyle();
    }

    public boolean isActive() {
        return active;
    }

    public void setPaused(boolean value) {
        paused = value;
        updateStyle();
    }

    private void updateStyle() {
        toggle("active", active && !paused);
        toggle("low", active && !paused && secondsLeft < LOW_SECONDS);
        toggle("paused", paused);
    }

    private void toggle(String styleClass, boolean on) {
        boolean has = getStyleClass().contains(styleClass);
        if (on && !has) {
            getStyleClass().add(styleClass);
        } else if (!on && has) {
            getStyleClass().remove(styleClass);
        }
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth() - snappedLeftInset() - snappedRightInset();
        double h = getHeight() - snappedTopInset() - snappedBottomInset();
        if (w > 0 && h > 0 && !text.isEmpty()) {
            measure();
            int digits = 0;
            int separators = 0;
            for (char c : text.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits++;
                } else {
                    separators++;
                }
            }
            double units = digits * digitRatio + separators * separatorRatio;
            double size = Math.floor(Math.min(maxFont, Math.min(w * 0.96 / units, h / 1.08)));
            if (Math.abs(size - appliedFont) >= 1) {
                appliedFont = size;
                String style = "-fx-font-size: " + size + "px;";
                for (int i = 0; i < cells.getChildren().size(); i++) {
                    Label cell = (Label) cells.getChildren().get(i);
                    double cw = Math.ceil((Character.isDigit(text.charAt(i)) ? digitRatio : separatorRatio) * size);
                    cell.setStyle(style);
                    cell.setMinWidth(cw);
                    cell.setPrefWidth(cw);
                    cell.setMaxWidth(cw);
                }
            }
        }
        super.layoutChildren();
    }

    @Override
    protected double computePrefHeight(double width) {
        return Math.min(maxFont * 1.15, 280) + snappedTopInset() + snappedBottomInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        return 200;
    }

    /** "05:00" -> "5:00", "00:42" -> "0:42", "00:09.4" -> "9.4", "75:00" -> "75:00". */
    public static String format(String raw) {
        if (raw == null || raw.isBlank()) {
            return "–:––";
        }
        String s = raw.trim();
        int colon = s.indexOf(':');
        if (colon < 0) {
            return s;
        }
        String minutes = s.substring(0, colon);
        String rest = s.substring(colon + 1);
        try {
            int m = Integer.parseInt(minutes);
            if (m == 0 && rest.contains(".")) {
                String secs = rest.startsWith("0") ? rest.substring(1) : rest;
                return secs;
            }
            return m + ":" + rest;
        } catch (NumberFormatException e) {
            return s;
        }
    }

    static double seconds(String raw) {
        if (raw == null) {
            return Double.MAX_VALUE;
        }
        try {
            String[] parts = raw.trim().split(":");
            return Integer.parseInt(parts[0]) * 60 + Double.parseDouble(parts[1]);
        } catch (RuntimeException e) {
            return Double.MAX_VALUE;
        }
    }
}
