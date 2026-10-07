package io.github.hardin22.javachess.Components;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * On-screen keyboard for the touch screen (no physical keyboard on the board): Italian QWERTY letters, digits and
 * the symbols needed for user names and e-mail addresses, keys 88 px tall. Edits {@link #textProperty()}; the
 * screen decides what to do with the text (live search, a settings field...).
 */
public class TouchKeyboard extends VBox {

    private static final String[][] LETTERS = {
            { "q", "w", "e", "r", "t", "y", "u", "i", "o", "p" },
            { "a", "s", "d", "f", "g", "h", "j", "k", "l" },
            { "z", "x", "c", "v", "b", "n", "m" } };
    private static final String[][] SYMBOLS = {
            { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0" },
            { "@", ".", "-", "_", "+", "'", "/", ":", "#" },
            { "à", "è", "é", "ì", "ò", "ù", "?" } };

    private final StringProperty text = new SimpleStringProperty("");
    private final Label display = Ui.label("", "t-h2");
    private final VBox rows = new VBox(10);
    private boolean symbols;
    private boolean upper;
    private Runnable onDone;

    public TouchKeyboard(String prompt) {
        getStyleClass().add("keyboard");
        display.setMinHeight(72);
        display.setMaxWidth(Double.MAX_VALUE);
        display.setPadding(new javafx.geometry.Insets(0, 12, 0, 12));
        display.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> text.get().isEmpty() ? prompt : text.get() + "▏", text));
        display.styleProperty().bind(javafx.beans.binding.Bindings.when(text.isEmpty())
                .then("-fx-text-fill: -c-fg3;").otherwise(""));
        getChildren().addAll(display, rows);
        build();
    }

    public StringProperty textProperty() {
        return text;
    }

    public void setOnDone(Runnable action) {
        this.onDone = action;
    }

    private void build() {
        rows.getChildren().clear();
        String[][] layout = symbols ? SYMBOLS : LETTERS;
        for (int r = 0; r < layout.length; r++) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER);
            if (r == 2 && !symbols) {
                row.getChildren().add(special(upper ? "fth-arrow-up-circle" : "fth-arrow-up", null, 1.4, () -> {
                    upper = !upper;
                    build();
                }));
            }
            for (String key : layout[r]) {
                String k = upper && !symbols ? key.toUpperCase() : key;
                row.getChildren().add(key(k, 1, () -> type(k)));
            }
            if (r == 2) {
                row.getChildren().add(special("fth-delete", null, 1.4, this::backspace));
            }
            rows.getChildren().add(row);
        }
        HBox bottom = new HBox(8);
        bottom.setAlignment(Pos.CENTER);
        Button mode = key(symbols ? "abc" : "123", 1.6, () -> {
            symbols = !symbols;
            build();
        });
        mode.getStyleClass().add("key-wide");
        Button space = key(I18n.t("keyboard.space"), 5, () -> type(" "));
        space.getStyleClass().add("key-wide");
        Button clear = key(I18n.t("keyboard.clear"), 1.8, () -> text.set(""));
        clear.getStyleClass().add("key-wide");
        Button done = special("fth-check", I18n.t("keyboard.done"), 2.2, () -> {
            if (onDone != null) {
                onDone.run();
            }
        });
        done.getStyleClass().addAll("key-accent", "key-wide");
        bottom.getChildren().addAll(mode, clear, space, done);
        rows.getChildren().add(bottom);
    }

    private void type(String s) {
        text.set(text.get() + s);
        if (upper) {
            upper = false;
            build();
        }
    }

    private void backspace() {
        String t = text.get();
        if (!t.isEmpty()) {
            text.set(t.substring(0, t.length() - 1));
        }
    }

    private Button key(String label, double weight, Runnable action) {
        Button b = new Button(label);
        b.getStyleClass().setAll("key");
        b.setMnemonicParsing(false);
        b.setFocusTraversable(false);
        size(b, weight);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button special(String icon, String label, double weight, Runnable action) {
        Button b = key(label == null ? "" : label, weight, action);
        b.setGraphic(Icons.of(icon, 28));
        return b;
    }

    private static void size(Button b, double weight) {
        b.setMinWidth(0);
        b.setPrefWidth(62 * weight);
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
    }
}
