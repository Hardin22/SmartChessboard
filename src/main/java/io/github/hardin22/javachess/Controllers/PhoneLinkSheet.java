package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Pos;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Stats.GameLinks;

import java.util.List;

/**
 * "Apri sul telefono": a QR code that opens the game on lichess.org's analysis board (the moves are in the address:
 * nothing is uploaded, no account). Black on white in both themes, drawn without smoothing so phones read it.
 */
final class PhoneLinkSheet {

    private PhoneLinkSheet() {
    }

    static void show(MainController main, String initialFen, List<String> uciMoves) {
        String url = GameLinks.lichessGame(initialFen, uciMoves);
        WritableImage qr = GameLinks.qrImage(url, 8);
        VBox content = new VBox(18);
        content.setAlignment(Pos.TOP_CENTER);
        if (qr != null) {
            ImageView view = new ImageView(qr);
            view.setSmooth(false);
            double side = Math.max(360, Math.min(440, qr.getWidth()));
            view.setFitWidth(side);
            view.setFitHeight(side);
            StackPane plate = new StackPane(view);
            plate.getStyleClass().add("qr-plate");
            plate.setMaxWidth(Double.MAX_VALUE);
            content.getChildren().addAll(plate, Ui.wrap(I18n.t("phone.scan"), "t-body"));
        } else {
            content.getChildren().add(Ui.wrap(I18n.t("phone.toolong"), "t-body"));
        }
        content.getChildren().add(Ui.wrap(url.length() > 120 ? url.substring(0, 117) + "…" : url, "t-small",
                "t-faint"));
        main.showSheet(I18n.t("phone.title"), content);
    }
}
