package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.PositionEditor;
import io.github.hardin22.javachess.Components.Ui;

import java.util.function.Supplier;

/** "Posizione iniziale: Standard ›" in the game setup screens; opens the position editor in a sheet. */
final class StartPositionRow {

    private final Supplier<MainController> main;
    private final Label value = Ui.label("", "row-sub");
    private final Button button = new Button();
    private String fen;

    StartPositionRow(Supplier<MainController> main) {
        this.main = main;
        Label title = Ui.label(I18n.t("setup.position"), "row-title");
        VBox texts = new VBox(4, title, value);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(18, Icons.of("fth-grid", 30), texts, Icons.of("fth-chevron-right", 26));
        row.setAlignment(Pos.CENTER_LEFT);
        button.setGraphic(row);
        button.getStyleClass().setAll("option");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(96);
        button.setOnAction(e -> open());
        button.setId("setup-position");
        refresh();
    }

    Button node() {
        return button;
    }

    /** The chosen position, or null for the standard one. */
    String fen() {
        return fen;
    }

    void open() {
        PositionEditor editor = new PositionEditor(fen, chosen -> {
            fen = chosen.split(" ")[0].equals(PositionEditor.START_FEN.split(" ")[0])
                    && chosen.contains(" w ") ? null : chosen;
            refresh();
            main.get().closeSheet();
        });
        main.get().showSheet(I18n.t("setup.position"), editor);
    }

    private void refresh() {
        value.setText(PositionEditor.describe(fen));
    }
}
