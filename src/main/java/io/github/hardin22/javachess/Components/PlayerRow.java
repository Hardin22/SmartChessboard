package io.github.hardin22.javachess.Components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** A player above or below the board: colour tile, name, state line ("Sta pensando…") and captured material. */
public class PlayerRow extends HBox {

    private final Region avatar = new Region();
    private final StackPane avatarBox = new StackPane(avatar);
    private final Label name = Ui.label("", "player-name");
    private final Label meta = Ui.label("", "player-meta");
    private final MaterialView material;
    private final boolean white;
    private final Label clock = Ui.label("", "row-clock");

    public PlayerRow(boolean white) {
        this.white = white;
        getStyleClass().add("player-row");
        avatar.getStyleClass().addAll("avatar", white ? "white" : "black");
        material = new MaterialView(white, 34);
        meta.managedProperty().bind(meta.textProperty().isNotEmpty());
        VBox texts = new VBox(2, name, meta);
        texts.setMinWidth(0);
        texts.setAlignment(Pos.CENTER_LEFT);
        clock.managedProperty().bind(clock.visibleProperty());
        clock.setVisible(false);
        clock.setMinWidth(USE_PREF_SIZE);
        getChildren().addAll(avatarBox, texts, Ui.hgrow(), material, clock);
        setAlignment(Pos.CENTER_LEFT);
    }

    /** The player's clock ("5:00"), lit while it runs, red under 20 seconds; null hides it. */
    public void setClock(String text, boolean running, boolean low) {
        clock.setVisible(text != null);
        if (text == null) {
            return;
        }
        String shown = ClockFace.format(text);
        if (!shown.equals(clock.getText())) {
            clock.setText(shown);
        }
        clock.getStyleClass().removeAll("active", "low");
        if (running) {
            clock.getStyleClass().add(low ? "low" : "active");
        }
    }

    public boolean isWhite() {
        return white;
    }

    public void setName(String text) {
        name.setText(text);
    }

    public void setMeta(String text) {
        meta.setText(text == null ? "" : text);
    }

    public void setPosition(String fen) {
        material.setPosition(fen);
    }

    /** The game's starting position (captures are counted against it). */
    public void setStartPosition(String fen) {
        material.setStart(fen);
    }

    /** Glyph in the colour tile (e.g. an engine icon for the computer). */
    public void setIcon(String iconLiteral) {
        avatarBox.getChildren().setAll(avatar);
        if (iconLiteral != null) {
            var icon = Icons.of(iconLiteral, 30);
            icon.setIconColor(javafx.scene.paint.Color.web(white ? "#141416" : "#F4F4F5"));
            icon.getStyleClass().remove("icon");
            avatarBox.getChildren().add(icon);
        }
    }
}
