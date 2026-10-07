package io.github.hardin22.javachess.Components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import io.github.hardin22.javachess.Utils.ImageCache;

/**
 * Pieces captured by one side (small images of the opponent's pieces) and the material lead ("+3"), computed from the
 * position. Rebuilt only when the counts change.
 */
public class MaterialView extends HBox {

    private static final char[] ORDER = { 'q', 'r', 'b', 'n', 'p' };
    private static final int[] START = { 1, 2, 2, 2, 8 };
    private static final int[] VALUE = { 9, 5, 3, 3, 1 };

    private final boolean white;
    private final double size;
    private final Label lead = Ui.label("", "material");
    private String shown = "";

    /** @param white true for the pieces captured by White (black pieces shown) */
    public MaterialView(boolean white, double size) {
        this.white = white;
        this.size = size;
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(0);
        setMinWidth(0);
    }

    /** Updates from a FEN (board part is enough). */
    public void setPosition(String fen) {
        int[] whiteCount = new int[5];
        int[] blackCount = new int[5];
        String board = fen == null ? "" : fen.split(" ")[0];
        for (char c : board.toCharArray()) {
            int i = new String(ORDER).indexOf(Character.toLowerCase(c));
            if (i >= 0) {
                if (Character.isUpperCase(c)) {
                    whiteCount[i]++;
                } else {
                    blackCount[i]++;
                }
            }
        }
        int whiteMaterial = 0;
        int blackMaterial = 0;
        for (int i = 0; i < 5; i++) {
            whiteMaterial += whiteCount[i] * VALUE[i];
            blackMaterial += blackCount[i] * VALUE[i];
        }
        int[] opponent = white ? blackCount : whiteCount;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            key.append(Math.max(0, START[i] - opponent[i])).append(',');
        }
        int diff = white ? whiteMaterial - blackMaterial : blackMaterial - whiteMaterial;
        key.append(diff).append(io.github.hardin22.javachess.Components.BoardThemes.currentPieces());
        if (key.toString().equals(shown)) {
            return;
        }
        shown = key.toString();
        getChildren().clear();
        String set = BoardThemes.currentPieces();
        String color = white ? "b" : "w";
        for (int i = 0; i < 5; i++) {
            int captured = Math.max(0, START[i] - opponent[i]);
            if (captured == 0) {
                continue;
            }
            HBox group = new HBox(-size * 0.55);
            for (int k = 0; k < captured; k++) {
                Image img = ImageCache.getInstance().getImage("/images/Pieces/" + set + "/" + color + ORDER[i] + ".png",
                        size, size);
                ImageView view = new ImageView(img);
                view.setFitWidth(size);
                view.setFitHeight(size);
                group.getChildren().add(view);
            }
            group.setPadding(new javafx.geometry.Insets(0, size * 0.18, 0, 0));
            getChildren().add(group);
        }
        lead.setText(diff > 0 ? "+" + diff : "");
        if (diff > 0) {
            getChildren().add(lead);
            HBox.setMargin(lead, new javafx.geometry.Insets(0, 0, 0, 6));
        }
    }
}
