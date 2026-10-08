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
    /** Third page, mostly for passwords: every other printable ASCII symbol. */
    private static final String[][] MORE = {
            { "!", "$", "%", "&", "*", "(", ")", "=", "?", "€" },
            { "[", "]", "{", "}", "<", ">", ";", ",", "\"" },
            { "\\", "|", "~", "^", "`", "£", "§" } };

    private enum Page { LETTERS, SYMBOLS, MORE }

    private final StringProperty text = new SimpleStringProperty("");
    private final Label display = Ui.label("", "t-h2");
    private final VBox rows = new VBox(10);
    private Page page = Page.LETTERS;
    private boolean upper;
    private final javafx.beans.property.BooleanProperty masked = new javafx.beans.property.SimpleBooleanProperty();
    private String doneText = I18n.t("keyboard.done");
    private Runnable onDone;

    public TouchKeyboard(String prompt) {
        getStyleClass().add("keyboard");
        display.setMinHeight(72);
        display.setMaxWidth(Double.MAX_VALUE);
        display.setPadding(new javafx.geometry.Insets(0, 12, 0, 12));
        display.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> text.get().isEmpty() ? prompt : shown(text.get(), masked.get()) + "▏", text, masked));
        display.getStyleClass().add("keyboard-display");
        javafx.css.PseudoClass empty = javafx.css.PseudoClass.getPseudoClass("empty");
        display.pseudoClassStateChanged(empty, true);
        text.addListener((obs, o, n) -> display.pseudoClassStateChanged(empty, n.isEmpty()));
        getChildren().addAll(display, rows);
        build();
    }

    public StringProperty textProperty() {
        return text;
    }

    public void setOnDone(Runnable action) {
        this.onDone = action;
    }

    /** Hides the keyboard's own line of text (a form shows the text in its fields instead). */
    public void setShowDisplay(boolean show) {
        display.setVisible(show);
        display.setManaged(show);
    }

    /** Passwords: the display shows dots instead of the characters. */
    public void setMasked(boolean masked) {
        this.masked.set(masked);
    }

    /** Label of the confirm key ("Fatto" by default, "Avanti", "Salva"...). */
    public void setDoneText(String doneText) {
        this.doneText = doneText;
        build();
    }

    /** How a text is shown: as typed, or one dot per character. */
    public static String shown(String value, boolean masked) {
        return masked ? "•".repeat(value.length()) : value;
    }

    private void build() {
        rows.getChildren().clear();
        String[][] layout = switch (page) {
            case LETTERS -> LETTERS;
            case SYMBOLS -> SYMBOLS;
            case MORE -> MORE;
        };
        for (int r = 0; r < layout.length; r++) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER);
            if (r == 2) {
                switch (page) {
                    case LETTERS -> row.getChildren().add(special(upper ? "fth-arrow-up-circle" : "fth-arrow-up", null,
                            1.4, () -> {
                                upper = !upper;
                                build();
                            }));
                    case SYMBOLS -> row.getChildren().add(pageKey("#+=", Page.MORE));
                    case MORE -> row.getChildren().add(pageKey("123", Page.SYMBOLS));
                }
            }
            for (String key : layout[r]) {
                String k = upper && page == Page.LETTERS ? key.toUpperCase() : key;
                row.getChildren().add(key(k, 1, () -> type(k)));
            }
            if (r == 2) {
                row.getChildren().add(special("fth-delete", null, 1.4, this::backspace));
            }
            rows.getChildren().add(row);
        }
        HBox bottom = new HBox(8);
        bottom.setAlignment(Pos.CENTER);
        Button mode = key(page == Page.LETTERS ? "123" : "abc", 1.6, () -> {
            page = page == Page.LETTERS ? Page.SYMBOLS : Page.LETTERS;
            build();
        });
        mode.getStyleClass().add("key-wide");
        Button space = key(I18n.t("keyboard.space"), 5, () -> type(" "));
        space.getStyleClass().add("key-wide");
        Button clear = key(I18n.t("keyboard.clear"), 1.8, () -> text.set(""));
        clear.getStyleClass().add("key-wide");
        Button done = special("fth-check", doneText, 2.2, () -> {
            if (onDone != null) {
                onDone.run();
            }
        });
        done.getStyleClass().addAll("key-accent", "key-wide");
        bottom.getChildren().addAll(mode, clear, space, done);
        rows.getChildren().add(bottom);
    }

    private Button pageKey(String label, Page target) {
        Button b = key(label, 1.4, () -> {
            page = target;
            build();
        });
        b.getStyleClass().add("key-wide");
        return b;
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
