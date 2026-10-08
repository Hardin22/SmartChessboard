package io.github.hardin22.javachess.Controllers;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import io.github.hardin22.javachess.Components.BoardFrame;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.StatusCard;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Oggetti.ChessBoardUI;
import io.github.hardin22.javachess.Oggetti.EvalBar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Layout shared by the training screens with a board (openings, endgames, coordinates, board test), the same as
 * "Rigioca i tuoi errori": header · board at full width · status card · a few lines of extras · tool buttons at the
 * bottom. Landscape: board on the left, the rest in a column on the right. Subclasses fill the parts and keep their
 * view-model's listeners in {@link #unbind} (removed by {@link #unbindAll()}).
 */
abstract class BoardScreen implements Screen {

    static final Color GOOD = Color.web("#5FBF6A");
    static final Color BAD = Color.web("#E5534B");
    static final Color HINT = Color.web("#4F9DFF");

    protected MainController mainController;
    protected final VBox root = new VBox();
    protected final ScreenHeader header;
    protected final EvalBar evalBar = new EvalBar(22, 600);
    protected final BoardFrame boardFrame = new BoardFrame(evalBar);
    protected final StatusCard status = new StatusCard();
    /** Between the status card and the tools: counters, theory bars, choices... */
    protected final VBox extras = new VBox(14);
    protected final HBox tools = new HBox(12);
    protected final VBox lower;
    protected ChessBoardUI board;
    private String shownFen;
    protected final List<Runnable> unbind = new ArrayList<>();

    BoardScreen(String title) {
        header = new ScreenHeader(title, this::leave);
        evalBar.setVisible(false);
        lower = new VBox(16, status, extras);
        lower.setPadding(new Insets(16, 24, 0, 24));
        tools.setPadding(new Insets(12, 24, 28, 24));
        tools.setAlignment(Pos.CENTER);
        // tool buttons share the width equally, whatever their labels
        tools.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
            for (Node n : tools.getChildren()) {
                if (n instanceof javafx.scene.layout.Region r) {
                    r.setPrefWidth(1);
                    r.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(r, Priority.ALWAYS);
                }
            }
        });
        root.getStyleClass().add("screen");
        setWide(false);
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void setWide(boolean wide) {
        root.getChildren().clear();
        if (!wide) {
            VBox.setVgrow(boardFrame, Priority.NEVER);
            root.getChildren().addAll(header, new VBox(boardFrame), lower, Ui.vgrow(), tools);
            return;
        }
        VBox.setVgrow(boardFrame, Priority.ALWAYS);
        VBox boardBox = new VBox(boardFrame);
        VBox panel = new VBox(header, lower, Ui.vgrow(), tools);
        panel.setPrefWidth(Ui.COLUMN);
        panel.setMinWidth(560);
        HBox.setHgrow(boardBox, Priority.ALWAYS);
        HBox columns = new HBox(8, boardBox, panel);
        VBox.setVgrow(columns, Priority.ALWAYS);
        root.getChildren().add(columns);
    }

    @Override
    public boolean onBack() {
        leave();
        return true;
    }

    @Override
    public void onNavigatedFrom() {
        close();
    }

    /** Back button: closes the session and goes to the parent screen. */
    protected abstract void leave();

    /** Ends the view-model (LEDs off, sensors given back). Safe to call twice. */
    protected void close() {
        unbindAll();
    }

    protected void unbindAll() {
        unbind.forEach(Runnable::run);
        unbind.clear();
    }

    /** Listens to the properties until {@link #unbindAll()}; each change runs {@code refresh}. */
    protected void watch(Runnable refresh, javafx.beans.Observable... properties) {
        javafx.beans.InvalidationListener l = o -> refresh.run();
        for (javafx.beans.Observable p : properties) {
            p.addListener(l);
            unbind.add(() -> p.removeListener(l));
        }
    }

    /** A new board on screen, drawn from {@code side}; moves by touch go to {@code play} when {@code enabled}. */
    protected ChessBoardUI newBoard(Side side, Supplier<String> fen, BooleanSupplier enabled, Consumer<String> play) {
        board = new ChessBoardUI(BoardThemes.currentBoard(), BoardThemes.currentPieces(), 80);
        board.setFitToParent(true);
        board.setOverlaysEnabled(false);
        board.setFlipped(side == Side.BLACK);
        boardFrame.setBoard(board);
        shownFen = null;
        if (play != null) {
            board.setMoveInput(new ChessBoardUI.MoveInput() {
                @Override
                public Board position() {
                    Board b = new Board();
                    String f = fen.get();
                    if (f != null) {
                        b.loadFromFen(f);
                    }
                    return b;
                }

                @Override
                public boolean enabled() {
                    return enabled.getAsBoolean();
                }

                @Override
                public void play(String uci) {
                    play.accept(uci);
                }

                @Override
                public void choosePromotion(String from, String to, boolean white, Consumer<String> done) {
                    PromotionPicker.show(mainController, white, piece -> done.accept(from + to + piece));
                }
            });
        }
        return board;
    }

    /**
     * Shows a position from the view-model; redrawn (with the move sliding) only when it changed, since refreshes
     * also come from messages and counters.
     */
    protected void show(String fen, String lastMove) {
        if (board == null || fen == null || fen.equals(shownFen)) {
            return;
        }
        boolean animate = shownFen != null;
        shownFen = fen;
        board.showPosition(fen, lastMove, animate);
    }

    /** Turns the screen towards the side that plays (as against the computer). */
    protected void face(boolean white) {
        if (mainController != null) {
            mainController.face(white ? Side.WHITE : Side.BLACK);
        }
    }

    /** Draws an arrow for a UCI move ("e2e4"); nothing for null or short text. */
    protected void arrow(String uci, Color color) {
        if (board == null || uci == null || uci.length() < 4) {
            return;
        }
        board.drawArrowOnBoard(uci.charAt(0) - 'a', '8' - uci.charAt(1), uci.charAt(2) - 'a', '8' - uci.charAt(3),
                color);
    }

    /** Highlights a square ("e4") with a colour. */
    protected void mark(String square, Color color) {
        if (board == null || square == null || square.length() != 2) {
            return;
        }
        board.highlightSquare(square.charAt(0) - 'a', '8' - square.charAt(1), color);
    }

    /**
     * A large tappable card (icon, title, text that wraps, an extra line): a pane rather than a button, because a
     * button's graphic does not wrap its text.
     */
    static VBox tileCard(String icon, String title, String text, javafx.scene.control.Label extra, Runnable action) {
        VBox card = new VBox(10, io.github.hardin22.javachess.Components.Icons.of(icon, 40),
                Ui.label(title, "tile-title"), Ui.wrap(text, "tile-sub"));
        if (extra != null) {
            card.getChildren().add(extra);
        }
        card.getStyleClass().add("tile");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setOnMouseClicked(e -> action.run());
        return card;
    }

    protected static Node action(String text, String icon, String style, Runnable run) {
        Button b = Ui.button(text, icon, style, "btn-md");
        b.setOnAction(ev -> run.run());
        return b;
    }

    protected static StatusCard.Content card(StatusCard.Tone tone, String kicker, String title, String detail,
            Node... actions) {
        return new StatusCard.Content(tone, kicker, title, null, detail, List.of(actions));
    }
}
