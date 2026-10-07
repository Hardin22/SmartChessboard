package org.example.javachess.Components;

import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Move list in SAN, one row per move pair ("12.  Nf3  Nc6"), virtualised by ListView. Rows are updated in place
 * when moves are appended; the current ply is highlighted and tapping a move reports its ply (1-based).
 */
public class MoveListView extends ListView<MoveListView.Row> {

    private static final Logger LOG = LoggerFactory.getLogger(MoveListView.class);
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");

    /** One numbered row: SAN of white's and black's move (black may be empty). */
    public record Row(int number, String white, String black, int whitePly) {
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private int currentPly;
    private boolean blackStarts;
    private IntConsumer onPlySelected;

    public MoveListView() {
        super();
        setItems(rows);
        getStyleClass().add("move-list");
        setFocusTraversable(false);
        setPlaceholder(new Label(I18n.t("moves.empty")));
        getPlaceholder().getStyleClass().add("muted");
        setCellFactory(list -> new RowCell());
    }

    public void setOnPlySelected(IntConsumer listener) {
        this.onPlySelected = listener;
    }

    public void clear() {
        rows.clear();
        currentPly = 0;
    }

    /** Recomputes SAN for the given moves played from {@code startFen}; keeps unchanged rows. */
    public void setMoves(String startFen, List<Move> moves) {
        String[] san;
        try {
            MoveList list = new MoveList(startFen);
            list.addAll(moves);
            san = list.toSanArray();
        } catch (Exception e) {
            LOG.debug("SAN conversion failed, falling back to UCI", e);
            san = moves.stream().map(Move::toString).toArray(String[]::new);
        }
        blackStarts = startFen != null && startFen.split(" ").length > 1 && startFen.split(" ")[1].equals("b");
        int fullMove = 1;
        try {
            fullMove = Integer.parseInt(startFen.split(" ")[5]);
        } catch (RuntimeException ignored) {
            // malformed FEN: number from 1
        }
        List<Row> built = new ArrayList<>();
        int i = 0;
        if (blackStarts && san.length > 0) {
            built.add(new Row(fullMove++, "…", san[0], 0));
            i = 1;
        }
        for (; i < san.length; i += 2) {
            built.add(new Row(fullMove++, san[i], i + 1 < san.length ? san[i + 1] : "", i + 1));
        }
        for (int r = 0; r < built.size(); r++) {
            if (r < rows.size()) {
                if (!rows.get(r).equals(built.get(r))) {
                    rows.set(r, built.get(r));
                }
            } else {
                rows.add(built.get(r));
            }
        }
        if (rows.size() > built.size()) {
            rows.remove(built.size(), rows.size());
        }
        setCurrentPly(san.length);
    }

    /** Highlights the move that leads to the position after {@code ply} half-moves (0 = start position). */
    public void setCurrentPly(int ply) {
        this.currentPly = ply;
        refresh();
        if (!rows.isEmpty()) {
            int rowIndex = Math.max(0, Math.min(rows.size() - 1, rowOfPly(ply)));
            scrollTo(Math.max(0, rowIndex - 3));
        }
    }

    private int rowOfPly(int ply) {
        int p = blackStarts ? ply : ply - 1;
        return blackStarts ? p / 2 : Math.max(0, p) / 2;
    }

    private final class RowCell extends ListCell<Row> {
        private final Label number = new Label();
        private final Label white = new Label();
        private final Label black = new Label();
        private final HBox box = new HBox(number, white, black);

        RowCell() {
            getStyleClass().add("move-row");
            number.getStyleClass().add("move-number");
            white.getStyleClass().add("move-san");
            black.getStyleClass().add("move-san");
            box.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(white, Priority.ALWAYS);
            HBox.setHgrow(black, Priority.ALWAYS);
            white.setMaxWidth(Double.MAX_VALUE);
            black.setMaxWidth(Double.MAX_VALUE);
            white.setOnMouseClicked(e -> select(true));
            black.setOnMouseClicked(e -> select(false));
        }

        private void select(boolean whiteMove) {
            Row row = getItem();
            if (row == null || onPlySelected == null) {
                return;
            }
            int ply = whiteMove ? row.whitePly() : row.whitePly() + 1;
            if (blackStarts && getIndex() == 0) {
                ply = 1;
            }
            if (!whiteMove || !"…".equals(row.white())) {
                onPlySelected.accept(ply);
            }
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            number.setText(row.number() + ".");
            white.setText(row.white());
            black.setText(row.black());
            int whitePly = (blackStarts && getIndex() == 0) ? -1 : row.whitePly();
            int blackPly = (blackStarts && getIndex() == 0) ? 1 : row.whitePly() + 1;
            white.pseudoClassStateChanged(CURRENT, whitePly > 0 && whitePly == currentPly);
            black.pseudoClassStateChanged(CURRENT, !row.black().isEmpty() && blackPly == currentPly);
            setGraphic(box);
        }
    }
}
