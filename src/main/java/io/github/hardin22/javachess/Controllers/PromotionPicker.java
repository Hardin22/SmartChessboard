package io.github.hardin22.javachess.Controllers;

import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Notation;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Utils.ImageCache;

import java.util.function.Consumer;

/** Sheet with the four promotion pieces, big, for a pawn moved to the last rank on the screen. */
final class PromotionPicker {

    private PromotionPicker() {
    }

    /** {@code done} receives "q", "r", "b" or "n"; nothing when the sheet is closed. */
    static void show(MainController main, boolean white, Consumer<String> done) {
        HBox row = new HBox(12);
        for (String piece : new String[] { "q", "r", "b", "n" }) {
            ImageView image = new ImageView(ImageCache.getInstance().getImage(
                    "/images/Pieces/" + BoardThemes.currentPieces() + "/" + (white ? "w" : "b") + piece + ".png", 96, 96));
            image.setFitWidth(96);
            image.setFitHeight(96);
            Button b = new Button(Notation.pieceName(piece.charAt(0)), image);
            b.getStyleClass().setAll("option");
            b.setContentDisplay(ContentDisplay.TOP);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                main.closeSheet();
                done.accept(piece);
            });
            row.getChildren().add(b);
        }
        main.showSheet(I18n.t("game.promotion"), Ui.equalRow(12, row.getChildren().toArray(new javafx.scene.Node[0])));
    }
}
