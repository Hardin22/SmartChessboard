package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.example.javachess.Utils.ImageCache;

import java.util.ArrayList;
import java.util.List;

public class ChessBoardUI extends StackPane {

    private int TILE_SIZE;
    private static final int BOARD_SIZE = 8;
    private final String chessboardStyle;
    private final String pieceStyle;
    private Board chessBoard;
    private List<String> moveList;
    private int currentMoveIndex;
    
    private Canvas boardCanvas;
    private Canvas pieceCanvas;
    private Canvas highlightCanvas; // For square highlights (moves, legal)
    private Canvas arrowCanvas;     // For arrows (analysis)
    private Canvas iconCanvas;      // For analysis icons (Best, Blunder, etc.)
    private javafx.scene.layout.Pane animationPane; // For piece animations
    private javafx.animation.Animation currentAnimation;

    public ChessBoardUI(String chessboardStyle, String piecesStyle, int TileSize) {
        this.chessBoard = new Board();
        this.chessboardStyle = chessboardStyle;
        this.pieceStyle = piecesStyle;
        this.TILE_SIZE = TileSize;
        
        int width = TILE_SIZE * BOARD_SIZE;
        int height = TILE_SIZE * BOARD_SIZE;
        
        this.setPrefSize(width, height);
        this.setMaxSize(width, height);
        this.setMinSize(width, height);

        boardCanvas = new Canvas(width, height);
        highlightCanvas = new Canvas(width, height);
        pieceCanvas = new Canvas(width, height);
        arrowCanvas = new Canvas(width, height);
        iconCanvas = new Canvas(width, height);
        animationPane = new javafx.scene.layout.Pane();
        animationPane.setPrefSize(width, height);
        animationPane.setMouseTransparent(true); // Let clicks pass through
        
        // Layer order: Board -> Highlights -> Pieces -> Arrows -> Icons -> Animation
        this.getChildren().addAll(boardCanvas, highlightCanvas, pieceCanvas, arrowCanvas, iconCanvas, animationPane);

        // Optimization: Cache the static board background
        boardCanvas.setCache(true);
        boardCanvas.setCacheHint(javafx.scene.CacheHint.QUALITY); // Background needs to look good
        
        // Cache other layers for performance
        pieceCanvas.setCache(true);
        pieceCanvas.setCacheHint(javafx.scene.CacheHint.SPEED);
        
        highlightCanvas.setCache(true);
        highlightCanvas.setCacheHint(javafx.scene.CacheHint.SPEED);
        
        arrowCanvas.setCache(true);
        arrowCanvas.setCacheHint(javafx.scene.CacheHint.SPEED);
        
        iconCanvas.setCache(true);
        iconCanvas.setCacheHint(javafx.scene.CacheHint.SPEED);
        
        // Animation pane benefits from caching during transitions
        animationPane.setCache(true);
        animationPane.setCacheHint(javafx.scene.CacheHint.SPEED);

        drawBoardBackground(width, height);
        updateBoard(chessBoard, null, pieceStyle);
        currentMoveIndex = 0;
    }
    
    // ... (drawBoardBackground, updateBoard, drawPieces remain same)

    private void stopCurrentAnimation() {
        if (currentAnimation != null) {
            if (currentAnimation.getStatus() == javafx.animation.Animation.Status.RUNNING) {
                currentAnimation.stop();
            }
            animationPane.getChildren().clear();
            currentAnimation = null;
        }
    }

    public void animateMove(Square from, Square to, Piece piece, String pieceStyle, Runnable onFinished) {
        stopCurrentAnimation(); // Ensure no conflict
        
        int fromCol = from.ordinal() % 8;
        int fromRow = 7 - (from.ordinal() / 8);
        int toCol = to.ordinal() % 8;
        int toRow = 7 - (to.ordinal() / 8);
        
        String pieceFileName = getPieceFileName(piece);
        if (pieceFileName == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        
        Image pieceImage = ImageCache.getInstance().getImage("/images/Pieces/" + pieceStyle + "/" + pieceFileName, TILE_SIZE, TILE_SIZE);
        ImageView animatedPiece = new ImageView(pieceImage);
        animatedPiece.setFitWidth(TILE_SIZE);
        animatedPiece.setFitHeight(TILE_SIZE);
        
        // Use TranslateTransition for GPU acceleration
        // We set the initial position using layoutX/Y (or just place it at 0,0 and translate)
        // Better: Place at 'from' position, then translate to 'to' position relative to 'from'.
        
        animatedPiece.setLayoutX(fromCol * TILE_SIZE);
        animatedPiece.setLayoutY(fromRow * TILE_SIZE);
        
        animationPane.getChildren().add(animatedPiece);
        
        javafx.animation.TranslateTransition transition = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(100), animatedPiece);
        transition.setFromX(0);
        transition.setFromY(0);
        transition.setToX((toCol - fromCol) * TILE_SIZE);
        transition.setToY((toRow - fromRow) * TILE_SIZE);
        
        transition.setOnFinished(e -> {
            animationPane.getChildren().remove(animatedPiece);
            currentAnimation = null;
            if (onFinished != null) onFinished.run();
        });
        
        currentAnimation = transition;
        transition.play();
    }

    private void drawBoardBackground(double width, double height) {
        GraphicsContext gc = boardCanvas.getGraphicsContext2D();
        try {
            Image boardImage = ImageCache.getInstance().getImage("/images/Scacchiere/" + chessboardStyle, width, height);
            if (boardImage != null) {
                gc.drawImage(boardImage, 0, 0, width, height);
            } else {
                for (int row = 0; row < 8; row++) {
                    for (int col = 0; col < 8; col++) {
                        if ((row + col) % 2 == 0) gc.setFill(Color.BEIGE);
                        else gc.setFill(Color.BROWN);
                        gc.fillRect(col * TILE_SIZE, row * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateBoard(Board board, Move lastMove, String pieceStyle) {
        updateBoard(board, lastMove, pieceStyle, null);
    }

    public void updateBoard(Board board, Move lastMove, String pieceStyle, Square skipSquare) {
        this.chessBoard = board;
        clearHighlights();
        drawPieces(board, pieceStyle, skipSquare);
        
        if (lastMove != null) {
            Square from = lastMove.getFrom();
            Square to = lastMove.getTo();
            highlightSquare(from.ordinal() % 8, 7 - (from.ordinal() / 8), Color.rgb(255, 255, 0, 0.5));
            highlightSquare(to.ordinal() % 8, 7 - (to.ordinal() / 8), Color.rgb(255, 255, 0, 0.5));
        }
    }

    private void drawPieces(Board board, String pieceStyle, Square skipSquare) {
        GraphicsContext gc = pieceCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, pieceCanvas.getWidth(), pieceCanvas.getHeight());
        
        ImageCache cache = ImageCache.getInstance();
        
        for (Square square : Square.values()) {
            if (square == skipSquare) continue;
            
            Piece piece = board.getPiece(square);
            if (piece != Piece.NONE) {
                String pieceFileName = getPieceFileName(piece);
                if (pieceFileName != null) {
                    Image pieceImage = cache.getImage("/images/Pieces/" + pieceStyle + "/" + pieceFileName, TILE_SIZE, TILE_SIZE);
                    if (pieceImage != null) {
                        int col = square.ordinal() % 8;
                        int row = 7 - (square.ordinal() / 8);
                        gc.drawImage(pieceImage, col * TILE_SIZE, row * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                    }
                }
            }
        }
    }



    public void highlightSquare(int col, int row, Color color) {
        GraphicsContext gc = highlightCanvas.getGraphicsContext2D();
        gc.setFill(color);
        gc.fillRect(col * TILE_SIZE, row * TILE_SIZE, TILE_SIZE, TILE_SIZE);
    }

    public void clearHighlights() {
        GraphicsContext gc = highlightCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, highlightCanvas.getWidth(), highlightCanvas.getHeight());
    }
    
    public void clearArrows() {
        GraphicsContext gc = arrowCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, arrowCanvas.getWidth(), arrowCanvas.getHeight());
    }
    
    public void clearIcons() {
        GraphicsContext gc = iconCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, iconCanvas.getWidth(), iconCanvas.getHeight());
    }

    public void drawIconOnSquare(int col, int row, String iconName) {
        GraphicsContext gc = iconCanvas.getGraphicsContext2D();
        double x = col * TILE_SIZE;
        double y = row * TILE_SIZE;
        double iconSize = TILE_SIZE / 2.5; // Small icon at top-left
        
        try {
            // Load icon (assuming standard path /images/analysis/...)
            // If user provides them elsewhere, we might need to adjust.
            // For now, let's assume they are in /images/analysis/
            Image icon = ImageCache.getInstance().getImage("/images/analysis/" + iconName, iconSize, iconSize);
            if (icon != null) {
                // Draw at top-left corner of the square
                gc.drawImage(icon, x, y, iconSize, iconSize);
            }
        } catch (Exception e) {
            // Ignore missing icons to prevent crash
        }
    }
    
    public void drawArrowOnBoard(int fromCol, int fromRow, int toCol, int toRow, Color color) {
        GraphicsContext gc = arrowCanvas.getGraphicsContext2D();
        double startX = (fromCol + 0.5) * TILE_SIZE;
        double startY = (fromRow + 0.5) * TILE_SIZE;
        double endX = (toCol + 0.5) * TILE_SIZE;
        double endY = (toRow + 0.5) * TILE_SIZE;
        
        drawArrow(gc, startX, startY, endX, endY, color);
    }

    private void drawArrow(GraphicsContext gc, double startX, double startY, double endX, double endY, Color color) {
        // Use a transparent color for the arrow
        Color transparentColor = Color.rgb((int)(color.getRed()*255), (int)(color.getGreen()*255), (int)(color.getBlue()*255), 0.7);
        
        gc.setStroke(transparentColor);
        gc.setLineWidth(15); // Thicker line
        
        double angle = Math.atan2(endY - startY, endX - startX);
        double arrowLength = 25;
        
        double newEndX = endX - arrowLength * Math.cos(angle);
        double newEndY = endY - arrowLength * Math.sin(angle);
        
        gc.strokeLine(startX, startY, newEndX, newEndY);
        
        double x1 = endX;
        double y1 = endY;
        double x2 = endX - arrowLength * Math.cos(angle - Math.PI / 5); // Wider angle
        double y2 = endY - arrowLength * Math.sin(angle - Math.PI / 5);
        double x3 = endX - arrowLength * Math.cos(angle + Math.PI / 5);
        double y3 = endY - arrowLength * Math.sin(angle + Math.PI / 5);
        
        gc.setFill(transparentColor);
        gc.fillPolygon(new double[]{x1, x2, x3}, new double[]{y1, y2, y3}, 3);
    }

    public void resetBoard() {
        chessBoard = new Board();
        updateBoard(chessBoard, null, pieceStyle);
        currentMoveIndex = 0;
        clearHighlights();
    }

    public void setPosition(String fen, Move lastMove) {
        chessBoard.loadFromFen(fen);
        updateBoard(chessBoard, lastMove, pieceStyle);
    }

    public String getFen() {
        return chessBoard.getFen();
    }

    public void loadPgn(String pgn) {
        moveList = parsePgnMoves(pgn);
        resetBoard();
    }

    private List<String> parsePgnMoves(String pgn) {
        List<String> moves = new ArrayList<>();
        String[] tokens = pgn.split("\\s+");
        for (String token : tokens) {
            if (!token.matches("\\d+\\.") && !token.matches("1-0|0-1|1/2-1/2")) {
                moves.add(token);
            }
        }
        return moves;
    }

    public void nextMove() {
        if (moveList != null && currentMoveIndex < moveList.size()) {
            boolean wasAnimating = (currentAnimation != null && currentAnimation.getStatus() == javafx.animation.Animation.Status.RUNNING);
            stopCurrentAnimation();

            String moveStr = moveList.get(currentMoveIndex);
            Move move = parseMoveFromString(moveStr, chessBoard.getFen());
            
            if (move != null) {
                chessBoard.doMove(move);
                currentMoveIndex++;
                
                if (wasAnimating) {
                    // Skip animation if we were already animating (fast scroll)
                    updateBoard(chessBoard, move, pieceStyle, null);
                } else {
                    // Normal animation
                    updateBoard(chessBoard, move, pieceStyle, move.getTo()); // Hide dest
                    animateMove(move.getFrom(), move.getTo(), chessBoard.getPiece(move.getTo()), pieceStyle, () -> {
                        updateBoard(chessBoard, move, pieceStyle, null);
                    });
                }
            }
        }
    }

    public void previousMove() {
        if (moveList != null && currentMoveIndex > 0) {
            boolean wasAnimating = (currentAnimation != null && currentAnimation.getStatus() == javafx.animation.Animation.Status.RUNNING);
            stopCurrentAnimation();

            // Get the move we are undoing
            String moveStr = moveList.get(currentMoveIndex - 1);
            // We need to parse it BEFORE undoing to know from/to
            // But wait, parseMoveFromString needs FEN *before* the move was made?
            // No, it needs FEN to determine color/promotion.
            // The current FEN is AFTER the move.
            // Undo first to get back to state BEFORE move.
            
            chessBoard.undoMove();
            currentMoveIndex--;
            
            // Re-parse move to get coordinates (now we are at state BEFORE move)
            Move move = parseMoveFromString(moveStr, chessBoard.getFen());
            
            if (move != null) {
                if (wasAnimating) {
                    updateBoard(chessBoard, null, pieceStyle, null);
                } else {
                    // Backward animation: Piece moves from TO back to FROM
                    // We hide the piece at FROM (where it ends up)
                    // Captured pieces (at TO) will just appear (handled by updateBoard drawing them, except we don't hide TO)
                    // Wait, if we captured a piece, it is now back at TO.
                    // The moving piece is back at FROM.
                    // We want to animate moving piece from TO -> FROM.
                    // So we hide FROM.
                    // What about the captured piece at TO?
                    // updateBoard draws everything.
                    // If we hide FROM, the moving piece is hidden.
                    // The captured piece at TO is drawn.
                    // The animated piece moves TO -> FROM.
                    // This looks correct: captured piece reappears, moving piece slides back.
                    
                    updateBoard(chessBoard, null, pieceStyle, move.getFrom());
                    animateMove(move.getTo(), move.getFrom(), chessBoard.getPiece(move.getFrom()), pieceStyle, () -> {
                        updateBoard(chessBoard, null, pieceStyle, null);
                    });
                }
            } else {
                updateBoard(chessBoard, null, pieceStyle);
            }
        }
    }

    private Move parseMoveFromString(String moveStr, String fen) {
        try {
            Square from = Square.valueOf(moveStr.substring(0, 2).toUpperCase());
            Square to = Square.valueOf(moveStr.substring(2, 4).toUpperCase());
            boolean isWhiteToMove = fen.split(" ")[1].equals("w");
            
            if (moveStr.length() == 5) {
                char promotionChar = moveStr.charAt(4);
                Piece promotionPiece = getPromotionPiece(promotionChar, isWhiteToMove);
                return new Move(from, to, promotionPiece);
            }
            return new Move(from, to);
        } catch (Exception e) {
            return null;
        }
    }

    public Piece getPromotionPiece(char promotionChar, boolean isWhite) {
        switch (Character.toLowerCase(promotionChar)) {
            case 'q': return isWhite ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
            case 'r': return isWhite ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
            case 'b': return isWhite ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
            case 'n': return isWhite ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
            default: throw new IllegalArgumentException("Invalid promotion piece: " + promotionChar);
        }
    }

    private String getPieceFileName(Piece piece) {
        switch (piece) {
            case WHITE_PAWN: return "wp.png";
            case WHITE_ROOK: return "wr.png";
            case WHITE_KNIGHT: return "wn.png";
            case WHITE_BISHOP: return "wb.png";
            case WHITE_QUEEN: return "wq.png";
            case WHITE_KING: return "wk.png";
            case BLACK_PAWN: return "bp.png";
            case BLACK_ROOK: return "br.png";
            case BLACK_KNIGHT: return "bn.png";
            case BLACK_BISHOP: return "bb.png";
            case BLACK_QUEEN: return "bq.png";
            case BLACK_KING: return "bk.png";
            default: return null;
        }
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
}