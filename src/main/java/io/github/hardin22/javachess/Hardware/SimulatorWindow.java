package io.github.hardin22.javachess.Hardware;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

/**
 * Debug window for the software board ({@code -Djavachess.board=sim -Djavachess.simulator.window=true}).
 * Each cell shows the LED color as the real board would; a dark disc marks a piece. Click a cell to lift or
 * place a piece, or type a move such as "e2e4" (lift e2, place e4) in the text field; "Scollega" unplugs the
 * simulated cable.
 */
public final class SimulatorWindow {

    private static final double CELL = 52;

    private final SimulatedBoard board;
    private final LedMapping mapping;
    private final Rectangle[] leds = new Rectangle[64];
    private final Circle[] pieces = new Circle[64];
    private final Stage stage = new Stage();
    private volatile boolean refreshQueued;
    private volatile int[] latestFrame = new int[64];

    public SimulatorWindow(SimulatedBoard board, LedMapping mapping) {
        this.board = board;
        this.mapping = mapping;
        GridPane grid = new GridPane();
        grid.setHgap(2);
        grid.setVgap(2);
        for (int square = 0; square < 64; square++) {
            int file = square % 8;
            int rank = square / 8;
            Rectangle led = new Rectangle(CELL, CELL, Color.BLACK);
            led.setArcWidth(8);
            led.setArcHeight(8);
            led.setStroke((file + rank) % 2 == 0 ? Color.web("#555") : Color.web("#888"));
            led.setStrokeWidth(3);
            Circle piece = new Circle(CELL * 0.22, Color.web("#222"));
            piece.setStroke(Color.web("#ddd"));
            Label name = new Label(Squares.name(square).toLowerCase());
            name.setStyle("-fx-text-fill: #999; -fx-font-size: 9px;");
            StackPane.setAlignment(name, Pos.BOTTOM_RIGHT);
            StackPane cell = new StackPane(led, piece, name);
            int sq = square;
            cell.setOnMouseClicked(e -> board.toggle(sq));
            grid.add(cell, file, 7 - rank);
            leds[square] = led;
            pieces[square] = piece;
        }
        TextField moveField = new TextField();
        moveField.setPromptText("mossa, es. e2e4 · e7e8q · e1g1 · -d5 +d6");
        moveField.setOnAction(e -> {
            if (playTyped(moveField.getText())) {
                moveField.clear();
            }
        });
        // unplug / plug the simulated cable (tests the reconnection during a game)
        javafx.scene.control.ToggleButton cable = new javafx.scene.control.ToggleButton("Scollega");
        cable.setOnAction(e -> {
            board.setConnected(!cable.isSelected());
            cable.setText(cable.isSelected() ? "Ricollega" : "Scollega");
        });
        javafx.scene.layout.HBox bottom = new javafx.scene.layout.HBox(6, moveField, cable);
        javafx.scene.layout.HBox.setHgrow(moveField, javafx.scene.layout.Priority.ALWAYS);
        BorderPane root = new BorderPane(grid, null, null, bottom, null);
        BorderPane.setMargin(bottom, new Insets(6, 0, 0, 0));
        root.setPadding(new Insets(8));
        root.setStyle("-fx-background-color: #1b1b1b;");
        stage.setScene(new Scene(root));
        stage.setTitle("Scacchiera simulata");
        board.addFrameObserver(frame -> {
            latestFrame = frame;
            queueRefresh();
        });
    }

    public void show() {
        // on the computer's own screen, away from the app (which runs on the board's screen)
        javafx.geometry.Rectangle2D primary = javafx.stage.Screen.getPrimary().getVisualBounds();
        stage.setX(primary.getMinX() + 40);
        stage.setY(primary.getMinY() + 40);
        stage.show();
        refresh();
        Thread.ofPlatform().daemon().name("simulator-window").start(() -> {
            // occupancy has no observer: poll it a few times per second (debug tool only)
            long last = -1;
            while (stage.isShowing() || last == -1) {
                long occupancy = board.occupancy();
                if (occupancy != last) {
                    last = occupancy;
                    queueRefresh();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /**
     * Plays what was typed the way hands do on the board. "e2e4" lifts e2 and places e4; when e4 holds a piece it
     * is taken away first (a capture). A promotion letter ("e7e8q") is ignored: the swap does not change the
     * sensors. A king going two files from e1/e8 also moves the rook (castling). Anything else, such as en
     * passant, is spelled square by square: "-e5 -d5 +d6" (lift e5, lift d5, place d6).
     */
    boolean playTyped(String typed) {
        String text = typed.trim().toLowerCase();
        if (text.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            int from = Squares.parse(text.substring(0, 2));
            int to = Squares.parse(text.substring(2, 4));
            if (board.isOccupied(to)) {
                board.lift(to);
            }
            board.lift(from);
            board.place(to);
            int rank = from / 8;
            boolean kingSquare = from % 8 == 4 && (rank == 0 || rank == 7) && to / 8 == rank;
            if (kingSquare && Math.abs(to % 8 - from % 8) == 2) {
                int rookFrom = rank * 8 + (to % 8 == 6 ? 7 : 0);
                int rookTo = rank * 8 + (to % 8 == 6 ? 5 : 3);
                if (board.isOccupied(rookFrom) && !board.isOccupied(rookTo)) {
                    board.lift(rookFrom);
                    board.place(rookTo);
                }
            }
            return true;
        }
        if (text.matches("([+-][a-h][1-8])(\\s+[+-][a-h][1-8])*")) {
            for (String step : text.split("\\s+")) {
                if (step.charAt(0) == '-') {
                    board.lift(step.substring(1));
                } else {
                    board.place(step.substring(1));
                }
            }
            return true;
        }
        return false;
    }

    private void queueRefresh() {
        if (!refreshQueued) {
            refreshQueued = true;
            Platform.runLater(this::refresh);
        }
    }

    private void refresh() {
        refreshQueued = false;
        int[] frame = latestFrame;
        long occupancy = board.occupancy();
        for (int square = 0; square < 64; square++) {
            int rgb = frame[mapping.ledIndex(square)];
            leds[square].setFill(Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
            pieces[square].setVisible((occupancy & Squares.bit(square)) != 0);
        }
    }
}
