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

/**
 * "Posizione iniziale: Standard ›" in the game setup screens. The sheet offers the standard start, the odds games
 * ({@link io.github.hardin22.javachess.Play.OddsPresets}: without a pawn, a knight, a rook, the queen... given by
 * White or by Black) and the position editor.
 */
final class StartPositionRow {

    private final Supplier<MainController> main;
    private final Label value = Ui.label("", "row-sub");
    private final Button button = new Button();
    private String fen;
    /** Name of the odds chosen ("Senza Donna · la dà il Bianco"), null for a position from the editor. */
    private String oddsLabel;
    private boolean blackGives;

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
        VBox content = new VBox(14);
        HBox standard = option(I18n.t("setup.position.standard"), I18n.t("setup.position.standard.note"),
                fen == null, () -> choose(null, null));
        HBox editor = option(I18n.t("setup.position.editor"), I18n.t("setup.position.editor.note"),
                fen != null && oddsLabel == null, this::openEditor);
        editor.getChildren().add(Icons.of("fth-chevron-right", 28));
        editor.setId("setup-position-editor");
        content.getChildren().addAll(standard, editor, Ui.sectionLabel(I18n.t("setup.odds")));
        javafx.scene.control.ToggleGroup givers = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.ToggleButton white = new javafx.scene.control.ToggleButton(I18n.t("setup.odds.white"));
        javafx.scene.control.ToggleButton black = new javafx.scene.control.ToggleButton(I18n.t("setup.odds.black"));
        HBox segments = Ui.segmented(givers, java.util.List.of(white, black));
        Ui.keepOneSelected(givers);
        (blackGives ? black : white).setSelected(true);
        givers.selectedToggleProperty().addListener((obs, o, n) -> blackGives = n == black);
        content.getChildren().add(segments);
        for (io.github.hardin22.javachess.Play.OddsPresets.Preset p : io.github.hardin22.javachess.Play.OddsPresets
                .all()) {
            boolean selected = oddsLabel != null && oddsLabel.startsWith(p.title());
            HBox b = option(p.title(), p.note(), selected, () -> choose(blackGives ? p.blackGives() : p.fen(),
                    p.title() + " · " + I18n.t(blackGives ? "setup.odds.black.short" : "setup.odds.white.short")));
            b.setId("odds-" + p.id());
            content.getChildren().add(b);
        }
        main.get().showSheet(I18n.t("setup.position"), Ui.scroll(content));
    }

    private void choose(String chosen, String label) {
        fen = chosen;
        oddsLabel = label;
        refresh();
        main.get().closeSheet();
    }

    /** A choice of the sheet: a pane rather than a button, so that the note can wrap. */
    private static HBox option(String title, String note, boolean selected, Runnable action) {
        VBox texts = new VBox(4, Ui.label(title, "row-title"), Ui.wrap(note, "row-sub"));
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(16, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        if (selected) {
            row.getChildren().add(Icons.of("fth-check", 30));
            row.getStyleClass().add("selected");
        }
        row.getStyleClass().addAll("option", "option-pane");
        row.setMinHeight(96);
        row.setOnMouseClicked(e -> action.run());
        return row;
    }

    void openEditor() {
        PositionEditor editor = new PositionEditor(fen, chosen -> {
            fen = chosen.split(" ")[0].equals(PositionEditor.START_FEN.split(" ")[0])
                    && chosen.contains(" w ") ? null : chosen;
            oddsLabel = null;
            refresh();
            main.get().closeSheet();
        });
        main.get().showSheet(I18n.t("setup.position"), editor);
    }

    private void refresh() {
        value.setText(oddsLabel != null ? oddsLabel : PositionEditor.describe(fen));
    }
}
