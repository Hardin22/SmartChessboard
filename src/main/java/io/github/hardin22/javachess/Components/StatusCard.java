package io.github.hardin22.javachess.Components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The one thing to know right now, readable from the chair: whose turn it is, the computer's move to make on the
 * board, the pieces to place, an error, the result. A tone (style class) gives the state at a glance; the text says
 * it; an optional move is shown in large Geist Mono ("f8 → c5").
 */
public class StatusCard extends VBox {

    public enum Tone {
        PLAIN(""), TURN("turn"), ACTION("action"), ERROR("error"), DONE("done");

        final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    /** What the card shows; {@code move} and {@code detail} may be null. */
    public record Content(Tone tone, String kicker, String title, String move, String detail, List<Node> actions) {
        public Content {
            actions = actions == null ? List.of() : actions;
        }

        public static Content of(Tone tone, String kicker, String title, String detail) {
            return new Content(tone, kicker, title, null, detail, List.of());
        }
    }

    private final Label kicker = Ui.label("", "status-kicker");
    private final Label title = Ui.wrap("", "status-title");
    private final Label move = Ui.label("", "status-move");
    private final Label detail = Ui.wrap("", "status-detail");
    private final HBox actions = new HBox(12);
    private Content shown;

    public StatusCard() {
        getStyleClass().add("status-card");
        setAlignment(Pos.CENTER_LEFT);
        for (Node n : new Node[] { kicker, move, detail, actions }) {
            n.managedProperty().bind(n.visibleProperty());
        }
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));
        getChildren().addAll(kicker, title, move, detail, actions);
        setMinHeight(USE_PREF_SIZE);
    }

    public void show(Content content) {
        if (content.equals(shown)) {
            return;
        }
        shown = content;
        for (Tone t : Tone.values()) {
            if (!t.styleClass.isEmpty()) {
                getStyleClass().remove(t.styleClass);
            }
        }
        if (!content.tone().styleClass.isEmpty()) {
            getStyleClass().add(content.tone().styleClass);
        }
        set(kicker, content.kicker());
        title.setText(content.title() == null ? "" : content.title());
        set(move, content.move());
        set(detail, content.detail());
        actions.getChildren().setAll(content.actions());
        actions.setVisible(!content.actions().isEmpty());
    }

    private static void set(Label label, String text) {
        boolean has = text != null && !text.isBlank();
        label.setText(has ? text : "");
        label.setVisible(has);
    }
}
