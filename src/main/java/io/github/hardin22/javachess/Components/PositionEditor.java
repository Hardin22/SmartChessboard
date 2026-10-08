package io.github.hardin22.javachess.Components;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Play.PositionSetup;
import io.github.hardin22.javachess.Utils.ImageCache;

import java.util.List;
import java.util.function.Consumer;

/**
 * Starting position for a game: pieces placed with the finger (palette + tap on a square), side to move, or a FEN
 * typed on the on-screen keyboard. {@link PositionSetup} (package Play) checks it and explains what is wrong.
 */
public class PositionEditor extends VBox {

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static final Piece[] PALETTE = {
            Piece.WHITE_KING, Piece.WHITE_QUEEN, Piece.WHITE_ROOK, Piece.WHITE_BISHOP, Piece.WHITE_KNIGHT,
            Piece.WHITE_PAWN, Piece.BLACK_KING, Piece.BLACK_QUEEN, Piece.BLACK_ROOK, Piece.BLACK_BISHOP,
            Piece.BLACK_KNIGHT, Piece.BLACK_PAWN };

    private final Piece[] pieces = new Piece[64];
    private Side toMove = Side.WHITE;
    private Piece brush = Piece.WHITE_PAWN;
    private final ChessBoardUI board;
    private final ToggleGroup paletteGroup = new ToggleGroup();
    private final ToggleGroup sideGroup = new ToggleGroup();
    private final ToggleButton whiteToMove = new ToggleButton(I18n.t("setup.position.tomove.white"));
    private final ToggleButton blackToMove = new ToggleButton(I18n.t("setup.position.tomove.black"));
    private final VBox messages = new VBox(6);
    private final Button use;
    private final VBox editor;
    private final TouchKeyboard keyboard = new TouchKeyboard(I18n.t("setup.position.fen.prompt"));
    private final VBox fenBox;
    private Button pasteButton;
    private PositionSetup.Result result;

    /** @param onUse receives the checked FEN when the player taps "Usa questa posizione" */
    public PositionEditor(String fen, Consumer<String> onUse) {
        this(fen, onUse, I18n.t("setup.position.use"));
    }

    /** Same, with another text on the confirm button ("Analizza questa posizione"). */
    public PositionEditor(String fen, Consumer<String> onUse, String useText) {
        super(16);
        board = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 70);
        board.setShowCoordinates(true);
        board.setOverlaysEnabled(false);
        board.setOnSquareTapped(this::tap);
        StackPane boardBox = new StackPane(board);
        boardBox.setAlignment(Pos.CENTER);

        HBox whites = new HBox(8);
        HBox blacks = new HBox(8);
        for (Piece p : PALETTE) {
            ToggleButton b = new ToggleButton();
            ImageView img = new ImageView(ImageCache.getInstance().getImage("/images/Pieces/"
                    + BoardThemes.currentPieces() + "/" + (p.getPieceSide() == Side.WHITE ? "w" : "b")
                    + Character.toLowerCase(p.getFenSymbol().charAt(0)) + ".png", 56, 56));
            img.setFitWidth(56);
            img.setFitHeight(56);
            b.setGraphic(img);
            b.getStyleClass().setAll("palette-piece");
            b.setUserData(p);
            b.setToggleGroup(paletteGroup);
            b.setSelected(p == brush);
            (p.getPieceSide() == Side.WHITE ? whites : blacks).getChildren().add(b);
        }
        ToggleButton eraser = new ToggleButton();
        eraser.setGraphic(Icons.of("fth-delete", 30));
        eraser.getStyleClass().setAll("palette-piece", "palette-tool");
        eraser.setUserData(Piece.NONE);
        eraser.setToggleGroup(paletteGroup);
        blacks.getChildren().add(eraser);
        Ui.keepOneSelected(paletteGroup);
        paletteGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null) {
                brush = (Piece) n.getUserData();
            }
        });
        Label paletteHint = Ui.wrap(I18n.t("setup.position.palette.hint"), "t-small", "t-muted");

        whiteToMove.setUserData(Side.WHITE);
        blackToMove.setUserData(Side.BLACK);
        HBox sides = Ui.segmented(sideGroup, List.of(whiteToMove, blackToMove));
        Ui.keepOneSelected(sideGroup);
        sideGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null) {
                toMove = (Side) n.getUserData();
                refresh();
            }
        });

        Button clear = Ui.toolButton(I18n.t("setup.position.clear"), "fth-trash-2", () -> {
            java.util.Arrays.fill(pieces, Piece.NONE);
            refresh();
        });
        Button initial = Ui.toolButton(I18n.t("setup.position.initial"), "fth-rotate-ccw", () -> load(START_FEN));
        // set up a position seen from Black's side of the real board
        Button flip = Ui.toolButton(I18n.t("setup.position.flip"), "fth-repeat", () -> {
            board.setFlipped(!board.isFlipped());
            board.setCoordinatesFlipped(false);
        });
        flip.setId("editor-flip");
        Button fenButton = Ui.toolButton(I18n.t("setup.position.fen"), "fth-type", () -> showFen(true));
        HBox tools = Ui.equalRow(12, clear, initial, flip, fenButton);

        use = Ui.wide(useText, "fth-check", "btn-primary", "btn-lg");
        use.setOnAction(e -> {
            if (result != null && result.ok()) {
                onUse.accept(result.fen());
            }
        });

        editor = new VBox(14, boardBox, whites, blacks, paletteHint, sides, tools, messages);
        keyboard.setOnDone(() -> {
            PositionSetup.Result r = PositionSetup.check(keyboard.textProperty().get());
            if (r.fen() != null) {
                load(r.fen());
            }
            showFen(false);
            showMessages(r);
        });
        Button back = Ui.button(I18n.t("common.back"), "fth-arrow-left", "btn-ghost", "btn-md");
        back.setOnAction(e -> showFen(false));
        // desktop: a FEN copied from a site or a book can be pasted instead of typed
        Button paste = Ui.button(I18n.t("setup.position.paste"), "fth-clipboard", "btn-outline", "btn-md");
        paste.setOnAction(e -> {
            String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            if (text != null) {
                keyboard.textProperty().set(text.trim().replaceAll("\\s+", " "));
            }
        });
        pasteButton = paste;
        fenBox = new VBox(14, Ui.wrap(I18n.t("setup.position.fen.hint"), "t-small", "t-muted"), keyboard,
                new HBox(12, back, paste));
        getChildren().addAll(editor, use);
        setPadding(new Insets(0, 0, 8, 0));
        load(fen == null || fen.isBlank() ? START_FEN : fen);
    }

    private void showFen(boolean on) {
        if (on) {
            keyboard.textProperty().set(result != null && result.fen() != null ? result.fen() : "");
            boolean clip = javafx.scene.input.Clipboard.getSystemClipboard().hasString();
            pasteButton.setVisible(clip);
            pasteButton.setManaged(clip);
            getChildren().setAll(fenBox);
        } else {
            getChildren().setAll(editor, use);
        }
    }

    private void tap(Square square) {
        if (square == null || square == Square.NONE) {
            return;
        }
        int i = square.ordinal();
        pieces[i] = pieces[i] == brush ? Piece.NONE : brush; // a second tap with the same piece removes it
        refresh();
    }

    private void load(String fen) {
        Board b = new Board();
        try {
            b.loadFromFen(fen);
        } catch (RuntimeException e) {
            b.loadFromFen(START_FEN);
        }
        for (int i = 0; i < 64; i++) {
            pieces[i] = b.getPiece(Square.squareAt(i));
        }
        toMove = b.getSideToMove();
        (toMove == Side.WHITE ? whiteToMove : blackToMove).setSelected(true);
        refresh();
    }

    private void refresh() {
        board.setPosition(placement() + (toMove == Side.WHITE ? " w" : " b") + " - - 0 1", null);
        result = PositionSetup.fromEditor(pieces, toMove);
        showMessages(result);
    }

    private void showMessages(PositionSetup.Result r) {
        messages.getChildren().clear();
        for (String error : r.errors()) {
            messages.getChildren().add(Ui.wrap(error, "t-body", "t-danger"));
        }
        for (String note : r.notes()) {
            messages.getChildren().add(Ui.wrap(note, "t-small", "t-muted"));
        }
        if (r.ok() && r.errors().isEmpty()) {
            messages.getChildren().add(0, Ui.wrap(I18n.t("setup.position.ok"), "t-body", "t-ok"));
        }
        use.setDisable(result == null || !result.ok());
    }

    /** FEN placement field of the pieces on the editor board. */
    private String placement() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                Piece p = pieces[rank * 8 + file];
                if (p == null || p == Piece.NONE) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    sb.append(empty);
                    empty = 0;
                }
                sb.append(p.getFenSymbol());
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    /** "Standard" or "Da posizione · tocca al Nero" for the setup row. */
    public static String describe(String fen) {
        if (fen == null || fen.isBlank() || fen.split(" ")[0].equals(START_FEN.split(" ")[0])) {
            return I18n.t("setup.position.standard");
        }
        String[] f = fen.split(" ");
        return I18n.t("setup.position.custom") + " · " + I18n.t(f.length > 1 && "b".equals(f[1])
                ? "setup.position.tomove.black" : "setup.position.tomove.white").toLowerCase(java.util.Locale.ITALIAN);
    }
}
