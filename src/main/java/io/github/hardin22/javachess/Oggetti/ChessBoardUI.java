package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.ThemeManager;
import io.github.hardin22.javachess.Utils.ImageCache;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chess board drawn on stacked canvases (board, highlights, pieces, arrows, icons) plus an animation layer and
 * an overlay for end-of-game messages.
 *
 * <p>Rendering is incremental: only squares whose piece changed are repainted, the background is painted once per
 * size, and nothing is cached as a bitmap twice (important with the software pipeline on the Raspberry Pi).
 * With {@link #setFitToParent(boolean)} the board follows the size given by its parent (square, multiple of 8 px).</p>
 */
public class ChessBoardUI extends StackPane {

    private static final int BOARD_SIZE = 8;
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    /** Last move: warm yellow, the colour players read as "last move" on any wood or green board (blue turned grey). */
    private static final Color LAST_MOVE = Color.rgb(247, 214, 72, 0.5);
    private static final Color ERROR = Color.rgb(255, 72, 72, 0.6);

    private int TILE_SIZE;
    private final String chessboardStyle;
    private final String pieceStyle;
    private Board chessBoard;
    private List<String> moveList;
    private int currentMoveIndex;

    private final Canvas boardCanvas;
    private final Canvas highlightCanvas;
    private final Canvas pieceCanvas;
    private final Canvas arrowCanvas;
    private final Canvas iconCanvas;
    /** Selected piece and its legal destinations (moves made on the screen). */
    private final Canvas selectCanvas;
    private final Pane animationPane;
    private final StackPane overlayPane;
    private javafx.animation.Animation currentAnimation;

    /** What is currently painted on the piece canvas, used to repaint only the squares that changed. */
    private final Piece[] drawn = new Piece[64];
    private Move lastMoveShown;
    private boolean fitToParent;
    private boolean showCoordinates = true;
    /** Coordinates drawn turned by 180 degrees (the interface is upside down for the viewer). */
    private boolean coordinatesFlipped;
    private boolean overlaysEnabled = true;
    /** Board seen from Black's side (Black's pieces at the bottom). */
    private boolean flipped;
    private final List<Runnable> arrowOps = new ArrayList<>();
    private final List<Runnable> iconOps = new ArrayList<>();
    private final List<Runnable> highlightOps = new ArrayList<>();
    private Consumer<Move> onPositionChanged;

    public ChessBoardUI(String chessboardStyle, String piecesStyle, int TileSize) {
        this.chessBoard = new Board();
        this.chessboardStyle = chessboardStyle;
        this.pieceStyle = piecesStyle;
        this.TILE_SIZE = Math.max(4, TileSize);
        getStyleClass().add("chess-board");

        double side = TILE_SIZE * BOARD_SIZE;
        boardCanvas = new Canvas(side, side);
        highlightCanvas = new Canvas(side, side);
        pieceCanvas = new Canvas(side, side);
        arrowCanvas = new Canvas(side, side);
        iconCanvas = new Canvas(side, side);
        selectCanvas = new Canvas(side, side);
        for (Canvas c : new Canvas[] { highlightCanvas, selectCanvas, pieceCanvas, arrowCanvas, iconCanvas }) {
            c.setMouseTransparent(true);
        }
        animationPane = new Pane();
        animationPane.setMouseTransparent(true);
        animationPane.setMaxSize(side, side);

        overlayPane = new StackPane();
        overlayPane.setPickOnBounds(false);
        overlayPane.setMouseTransparent(true);
        overlayPane.setVisible(false);
        overlayPane.getStyleClass().add("board-overlay");
        overlayPane.setMaxSize(side, side);

        getChildren().addAll(boardCanvas, highlightCanvas, selectCanvas, pieceCanvas, arrowCanvas, iconCanvas,
                animationPane,
                overlayPane);
        applyFixedSize();
        drawBoardBackground(side, side);
        updateBoard(chessBoard, null, pieceStyle);
        currentMoveIndex = 0;
    }

    // ------------------------------------------------------------------ sizing

    /** When true the board grows/shrinks with the space its parent gives it (always square). */
    public void setFitToParent(boolean fit) {
        this.fitToParent = fit;
        if (fit) {
            setMinSize(BOARD_SIZE * 8, BOARD_SIZE * 8);
            setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        } else {
            applyFixedSize();
        }
        requestLayout();
    }

    /** Sets the square size in pixels and repaints everything. */
    public void setTileSize(int tile) {
        tile = Math.max(4, tile);
        if (tile == TILE_SIZE) {
            return;
        }
        TILE_SIZE = tile;
        double side = TILE_SIZE * BOARD_SIZE;
        for (Canvas c : new Canvas[] { boardCanvas, highlightCanvas, selectCanvas, pieceCanvas, arrowCanvas,
                iconCanvas }) {
            c.setWidth(side);
            c.setHeight(side);
        }
        animationPane.setMaxSize(side, side);
        overlayPane.setMaxSize(side, side);
        if (!fitToParent) {
            applyFixedSize();
        }
        stopCurrentAnimation();
        clearSelection();
        drawBoardBackground(side, side);
        java.util.Arrays.fill(drawn, null);
        pieceCanvas.getGraphicsContext2D().clearRect(0, 0, side, side);
        drawPieces(chessBoard, pieceStyle, null);
        repaintHighlights();
        replay(arrowCanvas, arrowOps);
        replay(iconCanvas, iconOps);
    }

    public int getTileSize() {
        return TILE_SIZE;
    }

    public void setShowCoordinates(boolean show) {
        this.showCoordinates = show;
        drawBoardBackground(boardCanvas.getWidth(), boardCanvas.getHeight());
    }

    /** Draws the coordinates upside down, for a viewer at the other end of the screen. */
    public void setCoordinatesFlipped(boolean flipped) {
        if (flipped != coordinatesFlipped) {
            coordinatesFlipped = flipped;
            drawBoardBackground(boardCanvas.getWidth(), boardCanvas.getHeight());
        }
    }

    /**
     * Shows the board from Black's side. The public drawing methods keep taking White-at-the-bottom coordinates
     * (column 0 = file a, row 0 = rank 8): the board converts them.
     */
    public void setFlipped(boolean value) {
        if (value == flipped) {
            return;
        }
        flipped = value;
        double side = TILE_SIZE * BOARD_SIZE;
        stopCurrentAnimation();
        clearSelection();
        drawBoardBackground(side, side);
        java.util.Arrays.fill(drawn, null);
        pieceCanvas.getGraphicsContext2D().clearRect(0, 0, side, side);
        drawPieces(chessBoard, pieceStyle, null);
        repaintHighlights();
        replay(arrowCanvas, arrowOps);
        replay(iconCanvas, iconOps);
    }

    public boolean isFlipped() {
        return flipped;
    }

    /** Display column of a White-at-the-bottom column. */
    private int dc(int column) {
        return flipped ? 7 - column : column;
    }

    /** Display row of a White-at-the-bottom row. */
    private int dr(int row) {
        return flipped ? 7 - row : row;
    }

    /** When false, end-of-game cards are not drawn over the board (the screen shows the result itself). */
    public void setOverlaysEnabled(boolean enabled) {
        this.overlaysEnabled = enabled;
        if (!enabled) {
            clearOverlay();
        }
    }

    /** Called (on the FX thread) whenever a new position is shown; the argument is the last move or null. */
    public void setOnPositionChanged(Consumer<Move> listener) {
        this.onPositionChanged = listener;
    }

    private void applyFixedSize() {
        double side = TILE_SIZE * BOARD_SIZE;
        setMinSize(side, side);
        setPrefSize(side, side);
        setMaxSize(side, side);
    }

    @Override
    protected double computePrefWidth(double height) {
        return TILE_SIZE * BOARD_SIZE;
    }

    @Override
    protected double computePrefHeight(double width) {
        return TILE_SIZE * BOARD_SIZE;
    }

    @Override
    protected void layoutChildren() {
        if (fitToParent && getWidth() > 0 && getHeight() > 0) {
            int tile = (int) Math.floor(Math.min(getWidth(), getHeight()) / BOARD_SIZE);
            if (tile != TILE_SIZE && tile >= 4) {
                setTileSize(tile);
            }
        }
        super.layoutChildren();
    }

    // ------------------------------------------------------------------ painting

    private void drawBoardBackground(double width, double height) {
        GraphicsContext gc = boardCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);
        BoardThemes.Colors colors = BoardThemes.colors(chessboardStyle);
        boolean painted = false;
        if (colors == null) {
            Image boardImage = ImageCache.getInstance().getImage("/images/Scacchiere/" + chessboardStyle, width, height);
            if (boardImage != null && !boardImage.isError()) {
                gc.drawImage(boardImage, 0, 0, width, height);
                colors = sampleColors(boardImage);
                painted = true;
            } else {
                colors = BoardThemes.colors(BoardThemes.DEFAULT);
            }
        }
        if (!painted) {
            for (int row = 0; row < BOARD_SIZE; row++) {
                for (int col = 0; col < BOARD_SIZE; col++) {
                    gc.setFill((row + col) % 2 == 0 ? colors.light() : colors.dark());
                    gc.fillRect(col * TILE_SIZE, row * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                }
            }
        }
        if (showCoordinates && TILE_SIZE >= 32) {
            gc.setFont(Font.font("Geist SemiBold", Math.max(10, TILE_SIZE * 0.19)));
            double pad = Math.max(2, TILE_SIZE * 0.06);
            for (int i = 0; i < BOARD_SIZE; i++) {
                // Ranks on the left edge, files on the bottom edge, in the colour of the opposite square.
                String rank = String.valueOf(flipped ? i + 1 : 8 - i);
                String file = String.valueOf((char) (flipped ? 'h' - i : 'a' + i));
                gc.setFill(i % 2 == 0 ? colors.dark() : colors.light());
                coordinate(gc, rank, pad, i * TILE_SIZE + pad, TextAlignment.LEFT, VPos.TOP);
                // bottom rank: the square in column i is dark for even i (a1 is dark in both orientations)
                gc.setFill(i % 2 == 0 ? colors.light() : colors.dark());
                coordinate(gc, file, (i + 1) * TILE_SIZE - pad, height - pad, TextAlignment.RIGHT, VPos.BOTTOM);
            }
        }
    }

    /** One coordinate label; when flipped it is drawn turned by 180 degrees in the same corner of the square. */
    private void coordinate(GraphicsContext gc, String text, double x, double y, TextAlignment align, VPos baseline) {
        if (!coordinatesFlipped) {
            gc.setTextAlign(align);
            gc.setTextBaseline(baseline);
            gc.fillText(text, x, y);
            return;
        }
        double size = TILE_SIZE * 0.19;
        double cx = align == TextAlignment.LEFT ? x + size * 0.3 : x - size * 0.3;
        double cy = baseline == VPos.TOP ? y + size * 0.5 : y - size * 0.5;
        gc.save();
        gc.translate(cx, cy);
        gc.rotate(180);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(text, 0, 0);
        gc.restore();
    }

    /** Light/dark square colours of an image board, read at the centre of a8 and b8 (for the coordinates). */
    private BoardThemes.Colors sampleColors(Image image) {
        try {
            javafx.scene.image.PixelReader reader = image.getPixelReader();
            double sx = image.getWidth() / BOARD_SIZE;
            double sy = image.getHeight() / BOARD_SIZE;
            Color light = reader.getColor((int) (sx * 0.5), (int) (sy * 0.5));
            Color dark = reader.getColor((int) (sx * 1.5), (int) (sy * 0.5));
            return new BoardThemes.Colors(light, dark);
        } catch (RuntimeException e) {
            return BoardThemes.colors(BoardThemes.DEFAULT);
        }
    }

    public void updateBoard(Board board, Move lastMove, String pieceStyle) {
        updateBoard(board, lastMove, pieceStyle, null);
    }

    public void updateBoard(Board board, Move lastMove, String pieceStyle, Square skipSquare) {
        clearSelection();
        if (board != this.chessBoard) {
            // Never keep (and later mutate through setPosition) a board owned by the caller.
            Board copy = new Board();
            copy.loadFromFen(board.getFen());
            board = copy;
        }
        this.chessBoard = board;
        this.lastMoveShown = lastMove;
        highlightOps.clear();
        drawPieces(board, pieceStyle, skipSquare);
        repaintHighlights();
        if (onPositionChanged != null) {
            onPositionChanged.accept(lastMove);
        }
    }

    private void repaintHighlights() {
        GraphicsContext gc = highlightCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, highlightCanvas.getWidth(), highlightCanvas.getHeight());
        if (lastMoveShown != null) {
            fillSquare(gc, lastMoveShown.getFrom(), LAST_MOVE);
            fillSquare(gc, lastMoveShown.getTo(), LAST_MOVE);
        }
        paintCheck(gc);
        for (Runnable op : highlightOps) {
            op.run();
        }
    }

    /** King in check: a soft red disc under the king (cheap radial gradient on one square). */
    private void paintCheck(GraphicsContext gc) {
        try {
            if (chessBoard == null || !chessBoard.isKingAttacked()) {
                return;
            }
            Square king = chessBoard.getKingSquare(chessBoard.getSideToMove());
            if (king == null || king == Square.NONE) {
                return;
            }
            double x = col(king) * TILE_SIZE;
            double y = row(king) * TILE_SIZE;
            gc.setFill(new javafx.scene.paint.RadialGradient(0, 0, x + TILE_SIZE / 2.0, y + TILE_SIZE / 2.0,
                    TILE_SIZE * 0.7, false, javafx.scene.paint.CycleMethod.NO_CYCLE,
                    new javafx.scene.paint.Stop(0, Color.rgb(255, 40, 40, 0.85)),
                    new javafx.scene.paint.Stop(0.55, Color.rgb(255, 40, 40, 0.35)),
                    new javafx.scene.paint.Stop(1, Color.rgb(255, 40, 40, 0))));
            gc.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        } catch (RuntimeException e) {
            // Positions from the hardware can be incomplete (no king): no check to show.
        }
    }

    private void fillSquare(GraphicsContext gc, Square sq, Color color) {
        if (sq == null || sq == Square.NONE) {
            return;
        }
        gc.setFill(color);
        gc.fillRect(col(sq) * TILE_SIZE, row(sq) * TILE_SIZE, TILE_SIZE, TILE_SIZE);
    }

    private void drawPieces(Board board, String pieceStyle, Square skipSquare) {
        GraphicsContext gc = pieceCanvas.getGraphicsContext2D();
        ImageCache cache = ImageCache.getInstance();
        for (Square square : Square.values()) {
            if (square == Square.NONE) {
                continue;
            }
            Piece piece = square == skipSquare ? Piece.NONE : board.getPiece(square);
            int idx = square.ordinal();
            if (drawn[idx] == piece) {
                continue;
            }
            double x = col(square) * TILE_SIZE;
            double y = row(square) * TILE_SIZE;
            gc.clearRect(x, y, TILE_SIZE, TILE_SIZE);
            drawn[idx] = piece;
            String fileName = getPieceFileName(piece);
            if (fileName != null) {
                Image img = cache.getImage("/images/Pieces/" + pieceStyle + "/" + fileName, TILE_SIZE, TILE_SIZE);
                if (img != null) {
                    gc.drawImage(img, x, y, TILE_SIZE, TILE_SIZE);
                }
            }
        }
    }

    /** Display column of a square. */
    private int col(Square sq) {
        return dc(sq.ordinal() % 8);
    }

    /** Display row of a square. */
    private int row(Square sq) {
        return dr(7 - (sq.ordinal() / 8));
    }

    private void stopCurrentAnimation() {
        if (currentAnimation != null) {
            currentAnimation.stop();
            animationPane.getChildren().clear();
            currentAnimation = null;
        }
    }

    public void animateMove(Square from, Square to, Piece piece, String pieceStyle, Runnable onFinished) {
        stopCurrentAnimation();
        String pieceFileName = getPieceFileName(piece);
        if (pieceFileName == null) {
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }
        Image pieceImage = ImageCache.getInstance().getImage("/images/Pieces/" + pieceStyle + "/" + pieceFileName,
                TILE_SIZE, TILE_SIZE);
        ImageView animatedPiece = new ImageView(pieceImage);
        animatedPiece.setFitWidth(TILE_SIZE);
        animatedPiece.setFitHeight(TILE_SIZE);
        animatedPiece.setLayoutX(col(from) * TILE_SIZE);
        animatedPiece.setLayoutY(row(from) * TILE_SIZE);
        animationPane.getChildren().add(animatedPiece);

        TranslateTransition transition = new TranslateTransition(Duration.millis(140), animatedPiece);
        transition.setToX((col(to) - col(from)) * TILE_SIZE);
        transition.setToY((row(to) - row(from)) * TILE_SIZE);
        transition.setInterpolator(Interpolator.EASE_OUT);
        transition.setOnFinished(e -> {
            animationPane.getChildren().remove(animatedPiece);
            currentAnimation = null;
            if (onFinished != null) {
                onFinished.run();
            }
        });
        currentAnimation = transition;
        transition.play();
    }

    public void highlightSquare(int col, int row, Color color) {
        Runnable op = () -> {
            GraphicsContext gc = highlightCanvas.getGraphicsContext2D();
            gc.setFill(color);
            gc.fillRect(dc(col) * TILE_SIZE, dr(row) * TILE_SIZE, TILE_SIZE, TILE_SIZE);
        };
        highlightOps.add(op);
        op.run();
    }

    public void highlightErrorSquare(String squareName) {
        try {
            Square sq = Square.valueOf(squareName.toUpperCase());
            highlightSquare(sq.ordinal() % 8, 7 - sq.ordinal() / 8, ERROR);
        } catch (IllegalArgumentException e) {
            // Unknown square name from the hardware: nothing to highlight.
        }
    }

    public void clearHighlights() {
        highlightOps.clear();
        lastMoveShown = null;
        GraphicsContext gc = highlightCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, highlightCanvas.getWidth(), highlightCanvas.getHeight());
    }

    /**
     * Arrows are staged and painted once per frame, and only when they differ from what is on screen: the engine
     * redraws the same best-move arrow many times per second, and every canvas change repaints the whole board
     * area with the software pipeline.
     */
    public void clearArrows() {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            javafx.application.Platform.runLater(this::clearArrows);
            return;
        }
        stagedArrows.clear();
        scheduleArrowFlush();
    }

    private final List<String> stagedArrows = new ArrayList<>();
    private final List<String> shownArrows = new ArrayList<>();
    private boolean arrowFlushPending;

    private void scheduleArrowFlush() {
        if (!arrowFlushPending) {
            arrowFlushPending = true;
            javafx.application.Platform.runLater(this::flushArrows);
        }
    }

    private void flushArrows() {
        arrowFlushPending = false;
        if (stagedArrows.equals(shownArrows)) {
            return;
        }
        shownArrows.clear();
        shownArrows.addAll(stagedArrows);
        arrowOps.clear();
        for (String spec : shownArrows) {
            String[] p = spec.split(",");
            int fc = Integer.parseInt(p[0]);
            int fr = Integer.parseInt(p[1]);
            int tc = Integer.parseInt(p[2]);
            int tr = Integer.parseInt(p[3]);
            Color color = Color.web(p[4]);
            arrowOps.add(() -> drawArrow(arrowCanvas.getGraphicsContext2D(), (dc(fc) + 0.5) * TILE_SIZE,
                    (dr(fr) + 0.5) * TILE_SIZE, (dc(tc) + 0.5) * TILE_SIZE, (dr(tr) + 0.5) * TILE_SIZE, color));
        }
        replay(arrowCanvas, arrowOps);
    }

    public void clearIcons() {
        iconOps.clear();
        GraphicsContext gc = iconCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, iconCanvas.getWidth(), iconCanvas.getHeight());
    }

    private static void replay(Canvas canvas, List<Runnable> ops) {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        for (Runnable op : ops) {
            op.run();
        }
    }

    /** Review label tile in the top-right corner of a square (White-at-the-bottom coordinates). */
    public void drawLabelOnSquare(int col, int row, MoveAnalysis.MoveClassification label) {
        Runnable op = () -> {
            double size = Math.round(TILE_SIZE * 0.42);
            double inset = Math.max(1, TILE_SIZE * 0.03);
            double x = (dc(col) + 1) * TILE_SIZE - size * 0.78 - inset;
            double y = dr(row) * TILE_SIZE - size * 0.22 + inset;
            x = Math.min(x, BOARD_SIZE * TILE_SIZE - size);
            y = Math.max(y, 0);
            io.github.hardin22.javachess.Components.ReviewLabels.draw(iconCanvas.getGraphicsContext2D(), label, x, y,
                    size);
        };
        iconOps.add(op);
        op.run();
    }

    public void drawArrowOnBoard(int fromCol, int fromRow, int toCol, int toRow, Color color) {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            javafx.application.Platform.runLater(() -> drawArrowOnBoard(fromCol, fromRow, toCol, toRow, color));
            return;
        }
        String spec = fromCol + "," + fromRow + "," + toCol + "," + toRow + "," + color.toString();
        if (!stagedArrows.contains(spec)) {
            stagedArrows.add(spec);
        }
        scheduleArrowFlush();
    }

    private void drawArrow(GraphicsContext gc, double startX, double startY, double endX, double endY, Color color) {
        Color fill = Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.78);
        double width = TILE_SIZE * 0.17;
        double head = TILE_SIZE * 0.42;
        double angle = Math.atan2(endY - startY, endX - startX);
        double shaftEndX = endX - head * 0.8 * Math.cos(angle);
        double shaftEndY = endY - head * 0.8 * Math.sin(angle);

        gc.setStroke(fill);
        gc.setLineWidth(width);
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.strokeLine(startX, startY, shaftEndX, shaftEndY);

        double spread = Math.PI / 6;
        gc.setFill(fill);
        gc.fillPolygon(
                new double[] { endX, endX - head * Math.cos(angle - spread), endX - head * Math.cos(angle + spread) },
                new double[] { endY, endY - head * Math.sin(angle - spread), endY - head * Math.sin(angle + spread) },
                3);
    }

    // ------------------------------------------------------------------ positions

    public void resetBoard() {
        resetBoard(START_FEN);
    }

    public void resetBoard(String fen) {
        chessBoard = new Board();
        chessBoard.loadFromFen(fen);
        currentMoveIndex = 0;
        clearHighlights();
        clearArrows();
        clearIcons();
        updateBoard(chessBoard, null, pieceStyle);
        clearOverlay();
    }

    public void clearOverlay() {
        overlayPane.getChildren().clear();
        overlayPane.setVisible(false);
        overlayPane.setMouseTransparent(true);
    }

    /** Light-weight end-of-game card over the board (no blur or glow: cheap with software rendering). */
    public void showVictoryAnimation(String title, String subtitle) {
        if (!overlaysEnabled) {
            return;
        }
        overlayPane.getChildren().clear();
        Label titleLabel = new Label(sentenceCase(title));
        titleLabel.getStyleClass().add("board-overlay-title");
        Label subLabel = new Label(sentenceCase(subtitle));
        subLabel.getStyleClass().add("board-overlay-subtitle");
        VBox card = new VBox(6, titleLabel, subLabel);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("board-overlay-card");
        card.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        overlayPane.getChildren().add(card);
        overlayPane.setVisible(true);
        overlayPane.setMouseTransparent(false);
        overlayPane.setOnMouseClicked(e -> clearOverlay());

        FadeTransition fade = new FadeTransition(Duration.millis(180), overlayPane);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    /** Game messages arrive in capitals ("SCACCO MATTO"): show them in sentence case. */
    private static String sentenceCase(String s) {
        if (s == null || s.length() < 2 || !s.equals(s.toUpperCase())) {
            return s;
        }
        String lower = s.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public Board getBoard() {
        return chessBoard;
    }

    public void setPosition(String fen, Move lastMove) {
        chessBoard.loadFromFen(fen);
        updateBoard(chessBoard, lastMove, pieceStyle);
    }

    public String getFen() {
        return chessBoard.getFen();
    }

    /**
     * Shows a position given from outside (analysis view-models). With {@code animate} and a last move, the piece
     * slides from its square (one step forward through the moves).
     */
    public void showPosition(String fen, String lastUci, boolean animate) {
        Move last = null;
        if (lastUci != null && lastUci.length() >= 4) {
            try {
                Square from = Square.valueOf(lastUci.substring(0, 2).toUpperCase());
                Square to = Square.valueOf(lastUci.substring(2, 4).toUpperCase());
                last = new Move(from, to);
            } catch (RuntimeException e) {
                last = null;
            }
        }
        stopCurrentAnimation();
        chessBoard.loadFromFen(fen);
        if (!animate || last == null || !animationsOn()) {
            updateBoard(chessBoard, last, pieceStyle, null);
            return;
        }
        Move move = last;
        updateBoard(chessBoard, move, pieceStyle, move.getTo());
        animateMove(move.getFrom(), move.getTo(), chessBoard.getPiece(move.getTo()), pieceStyle,
                () -> updateBoard(chessBoard, move, pieceStyle, null));
    }

    private static boolean animationsOn() {
        return io.github.hardin22.javachess.Components.Ui.animations();
    }

    public void loadPgn(String pgn) {
        loadPgn(pgn, START_FEN);
    }

    public void loadPgn(String pgn, String initialFen) {
        moveList = parsePgnMoves(pgn);
        resetBoard(initialFen);
    }

    private List<String> parsePgnMoves(String pgn) {
        List<String> moves = new ArrayList<>();
        for (String token : pgn.trim().split("\\s+")) {
            if (!token.isEmpty() && !token.matches("\\d+\\.") && !token.matches("1-0|0-1|1/2-1/2|\\*")) {
                moves.add(token);
            }
        }
        return moves;
    }

    /** Moves of the loaded PGN (UCI strings), for move lists. */
    public List<String> getMoveList() {
        return moveList == null ? List.of() : java.util.Collections.unmodifiableList(moveList);
    }

    public void nextMove() {
        if (moveList != null && currentMoveIndex < moveList.size()) {
            boolean wasAnimating = currentAnimation != null
                    && currentAnimation.getStatus() == javafx.animation.Animation.Status.RUNNING;
            stopCurrentAnimation();
            Move move = parseMoveFromString(moveList.get(currentMoveIndex), chessBoard.getFen());
            if (move != null) {
                chessBoard.doMove(move);
                currentMoveIndex++;
                if (wasAnimating) {
                    updateBoard(chessBoard, move, pieceStyle, null);
                } else {
                    updateBoard(chessBoard, move, pieceStyle, move.getTo());
                    animateMove(move.getFrom(), move.getTo(), chessBoard.getPiece(move.getTo()), pieceStyle,
                            () -> updateBoard(chessBoard, move, pieceStyle, null));
                }
            }
        }
    }

    public void previousMove() {
        if (moveList != null && currentMoveIndex > 0) {
            boolean wasAnimating = currentAnimation != null
                    && currentAnimation.getStatus() == javafx.animation.Animation.Status.RUNNING;
            stopCurrentAnimation();
            String moveStr = moveList.get(currentMoveIndex - 1);
            chessBoard.undoMove();
            currentMoveIndex--;
            Move move = parseMoveFromString(moveStr, chessBoard.getFen());
            Move previous = currentMoveIndex > 0
                    ? parseMoveFromString(moveList.get(currentMoveIndex - 1), null) : null;
            if (move != null && !wasAnimating) {
                // Slide the piece back from TO to FROM; the captured piece (if any) reappears at once.
                updateBoard(chessBoard, previous, pieceStyle, move.getFrom());
                animateMove(move.getTo(), move.getFrom(), chessBoard.getPiece(move.getFrom()), pieceStyle,
                        () -> updateBoard(chessBoard, previous, pieceStyle, null));
            } else {
                updateBoard(chessBoard, previous, pieceStyle, null);
            }
        }
    }

    /** Jumps to the position after {@code index} moves of the loaded PGN (0 = start), without animation. */
    public void goToMove(int index) {
        if (moveList == null) {
            return;
        }
        index = Math.max(0, Math.min(index, moveList.size()));
        stopCurrentAnimation();
        while (currentMoveIndex > index) {
            chessBoard.undoMove();
            currentMoveIndex--;
        }
        Move last = null;
        while (currentMoveIndex < index) {
            Move move = parseMoveFromString(moveList.get(currentMoveIndex), chessBoard.getFen());
            if (move == null) {
                break;
            }
            chessBoard.doMove(move);
            currentMoveIndex++;
            last = move;
        }
        if (last == null && currentMoveIndex > 0) {
            last = parseMoveFromString(moveList.get(currentMoveIndex - 1), null);
        }
        updateBoard(chessBoard, last, pieceStyle, null);
    }

    private Move parseMoveFromString(String moveStr, String fen) {
        try {
            Square from = Square.valueOf(moveStr.substring(0, 2).toUpperCase());
            Square to = Square.valueOf(moveStr.substring(2, 4).toUpperCase());
            if (moveStr.length() == 5 && fen != null) {
                boolean isWhiteToMove = fen.split(" ")[1].equals("w");
                return new Move(from, to, getPromotionPiece(moveStr.charAt(4), isWhiteToMove));
            }
            return new Move(from, to);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Piece getPromotionPiece(char promotionChar, boolean isWhite) {
        switch (Character.toLowerCase(promotionChar)) {
            case 'q':
                return isWhite ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
            case 'r':
                return isWhite ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
            case 'b':
                return isWhite ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
            case 'n':
                return isWhite ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
            default:
                throw new IllegalArgumentException("Invalid promotion piece: " + promotionChar);
        }
    }

    private static final Map<Piece, String> PIECE_FILE_NAMES = new EnumMap<>(Piece.class);
    static {
        PIECE_FILE_NAMES.put(Piece.WHITE_PAWN, "wp.png");
        PIECE_FILE_NAMES.put(Piece.WHITE_ROOK, "wr.png");
        PIECE_FILE_NAMES.put(Piece.WHITE_KNIGHT, "wn.png");
        PIECE_FILE_NAMES.put(Piece.WHITE_BISHOP, "wb.png");
        PIECE_FILE_NAMES.put(Piece.WHITE_QUEEN, "wq.png");
        PIECE_FILE_NAMES.put(Piece.WHITE_KING, "wk.png");
        PIECE_FILE_NAMES.put(Piece.BLACK_PAWN, "bp.png");
        PIECE_FILE_NAMES.put(Piece.BLACK_ROOK, "br.png");
        PIECE_FILE_NAMES.put(Piece.BLACK_KNIGHT, "bn.png");
        PIECE_FILE_NAMES.put(Piece.BLACK_BISHOP, "bb.png");
        PIECE_FILE_NAMES.put(Piece.BLACK_QUEEN, "bq.png");
        PIECE_FILE_NAMES.put(Piece.BLACK_KING, "bk.png");
    }

    private String getPieceFileName(Piece piece) {
        return piece == null ? null : PIECE_FILE_NAMES.get(piece);
    }

    public int getMoveCount() {
        return moveList != null ? moveList.size() : 0;
    }

    public int getCurrentMoveIndex() {
        return currentMoveIndex;
    }

    public boolean hasNextMove() {
        return moveList != null && currentMoveIndex < moveList.size();
    }

    public boolean hasPreviousMove() {
        return currentMoveIndex > 0;
    }

    // ------------------------------------------------------------------ moves made on the screen

    /** Where moves made by tapping the screen go. The board only proposes legal moves of the side to move. */
    public interface MoveInput {
        /** The game's position (side to move, castling rights...). */
        Board position();

        /** False while moves on the screen must not be accepted (physical board connected, not the human's turn). */
        boolean enabled();

        /** A legal move in UCI ("e2e4"; promotions as "e7e8q"). */
        void play(String uci);

        /** A pawn reaches the last rank: ask which piece (q, r, b, n); by default a queen. */
        default void choosePromotion(String from, String to, boolean white, Consumer<String> done) {
            done.accept(from + to + "q");
        }
    }

    private MoveInput moveInput;
    private Square selected;
    private final List<Move> selectedMoves = new ArrayList<>();

    /** Enables tap-to-move: a tap on a piece of the side to move shows its moves, a tap on a destination plays it. */
    public void setMoveInput(MoveInput input) {
        this.moveInput = input;
        clearSelection();
        if (input != null && getOnMouseClicked() == null) {
            setOnMouseClicked(e -> onTap(e.getX(), e.getY()));
        }
    }

    private java.util.function.Consumer<Square> squareTapHandler;

    /** A tap on a square is reported as is (position editor); takes precedence over tap-to-move. */
    public void setOnSquareTapped(java.util.function.Consumer<Square> handler) {
        this.squareTapHandler = handler;
        if (handler != null && getOnMouseClicked() == null) {
            setOnMouseClicked(e -> onTap(e.getX(), e.getY()));
        }
    }

    private void onTap(double x, double y) {
        if (squareTapHandler != null && TILE_SIZE > 0) {
            int dc = (int) (x / TILE_SIZE);
            int dr = (int) (y / TILE_SIZE);
            if (dc >= 0 && dc <= 7 && dr >= 0 && dr <= 7) {
                squareTapHandler.accept(Square.squareAt((flipped ? dr : 7 - dr) * 8 + (flipped ? 7 - dc : dc)));
            }
            return;
        }
        if (moveInput == null || !moveInput.enabled() || TILE_SIZE <= 0) {
            clearSelection();
            return;
        }
        int dcol = (int) (x / TILE_SIZE);
        int drow = (int) (y / TILE_SIZE);
        if (dcol < 0 || dcol > 7 || drow < 0 || drow > 7) {
            return;
        }
        int file = flipped ? 7 - dcol : dcol;
        int rank = flipped ? drow : 7 - drow;
        Square square = Square.squareAt(rank * 8 + file);
        Board position = moveInput.position();
        if (selected != null) {
            for (Move move : selectedMoves) {
                if (move.getTo() == square) {
                    String from = move.getFrom().name().toLowerCase();
                    String to = move.getTo().name().toLowerCase();
                    boolean white = position.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE;
                    MoveInput input = moveInput;
                    clearSelection();
                    if (move.getPromotion() != Piece.NONE) {
                        input.choosePromotion(from, to, white, input::play);
                    } else {
                        input.play(from + to);
                    }
                    return;
                }
            }
        }
        Piece piece = position.getPiece(square);
        if (piece != Piece.NONE && piece.getPieceSide() == position.getSideToMove() && square != selected) {
            select(position, square);
        } else {
            clearSelection();
        }
    }

    private void select(Board position, Square square) {
        selected = square;
        selectedMoves.clear();
        try {
            for (Move move : position.legalMoves()) {
                if (move.getFrom() == square && selectedMoves.stream().noneMatch(m -> m.getTo() == move.getTo())) {
                    selectedMoves.add(move);
                }
            }
        } catch (RuntimeException e) {
            selectedMoves.clear();
        }
        paintSelection(position);
    }

    private void paintSelection(Board position) {
        GraphicsContext gc = selectCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, selectCanvas.getWidth(), selectCanvas.getHeight());
        if (selected == null) {
            return;
        }
        Color accent = ThemeManager.get().palette().accent();
        gc.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.42));
        gc.fillRect(col(selected) * TILE_SIZE, row(selected) * TILE_SIZE, TILE_SIZE, TILE_SIZE);
        Color mark = Color.rgb(20, 20, 24, 0.32);
        for (Move move : selectedMoves) {
            double cx = (col(move.getTo()) + 0.5) * TILE_SIZE;
            double cy = (row(move.getTo()) + 0.5) * TILE_SIZE;
            if (position.getPiece(move.getTo()) != Piece.NONE) {
                gc.setStroke(mark);
                gc.setLineWidth(TILE_SIZE * 0.09);
                double r = TILE_SIZE * 0.44;
                gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            } else {
                gc.setFill(mark);
                double r = TILE_SIZE * 0.16;
                gc.fillOval(cx - r, cy - r, r * 2, r * 2);
            }
        }
    }

    private void clearSelection() {
        if (selected == null && selectedMoves.isEmpty()) {
            return;
        }
        selected = null;
        selectedMoves.clear();
        selectCanvas.getGraphicsContext2D().clearRect(0, 0, selectCanvas.getWidth(), selectCanvas.getHeight());
    }
}
