package io.github.hardin22.javachess.Components;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.util.List;

/**
 * The look of the review labels: an own identity (rounded square tiles, like the logo and the board squares) with
 * the familiar chess.com colour language. Tiles are drawn on canvases (also onto the board), crisp at any size.
 */
public final class ReviewLabels {

    /** Labels in the order they are listed in summaries (best first). */
    public static final List<MoveClassification> ORDER = List.of(MoveClassification.BRILLIANT,
            MoveClassification.GREAT, MoveClassification.BEST, MoveClassification.EXCELLENT, MoveClassification.GOOD,
            MoveClassification.BOOK_MOVE, MoveClassification.FORCED, MoveClassification.INACCURACY,
            MoveClassification.MISTAKE, MoveClassification.MISS, MoveClassification.BLUNDER);

    private ReviewLabels() {
    }

    public static Color color(MoveClassification c) {
        if (c == null) {
            return Color.web("#8A8F98");
        }
        return switch (c) {
            case BRILLIANT -> Color.web("#1FBFB0");
            case GREAT -> Color.web("#4A8FE7");
            case BEST -> Color.web("#55B45E");
            case EXCELLENT -> Color.web("#83B556");
            case GOOD -> Color.web("#93A88A");
            case BOOK_MOVE -> Color.web("#B48A60");
            case FORCED -> Color.web("#8A8F98");
            case INACCURACY -> Color.web("#E9B634");
            case MISTAKE -> Color.web("#EE8536");
            case MISS -> Color.web("#E7708A");
            case BLUNDER -> Color.web("#E5484D");
        };
    }

    /** Italian name ("Geniale", "Grande", ... "Errore grave"). */
    public static String name(MoveClassification c) {
        if (c == null) {
            return "";
        }
        return I18n.t(switch (c) {
            case BRILLIANT -> "review.brilliant";
            case GREAT -> "review.great";
            case BEST -> "review.best";
            case EXCELLENT -> "review.excellent";
            case GOOD -> "review.good";
            case BOOK_MOVE -> "review.book";
            case FORCED -> "review.forced";
            case INACCURACY -> "review.inaccuracy";
            case MISTAKE -> "review.mistake";
            case MISS -> "review.miss";
            case BLUNDER -> "review.blunder";
        });
    }

    /** Sentence for the move card: "Cf3 è la mossa migliore", "Cf3 è un errore grave"... */
    public static String sentence(MoveClassification c, String san) {
        if (c == null) {
            return san;
        }
        return I18n.t(switch (c) {
            case BRILLIANT -> "review.say.brilliant";
            case GREAT -> "review.say.great";
            case BEST -> "review.say.best";
            case EXCELLENT -> "review.say.excellent";
            case GOOD -> "review.say.good";
            case BOOK_MOVE -> "review.say.book";
            case FORCED -> "review.say.forced";
            case INACCURACY -> "review.say.inaccuracy";
            case MISTAKE -> "review.say.mistake";
            case MISS -> "review.say.miss";
            case BLUNDER -> "review.say.blunder";
        }, san);
    }

    /** True for the labels worth a dot on the graph and a highlight in lists. */
    public static boolean notable(MoveClassification c) {
        return c == MoveClassification.BRILLIANT || c == MoveClassification.GREAT || c == MoveClassification.MISS
                || c == MoveClassification.MISTAKE || c == MoveClassification.BLUNDER;
    }

    /** True when the label says the move was worse than the engine's choice (an arrow shows the better move). */
    public static boolean bad(MoveClassification c) {
        return c == MoveClassification.INACCURACY || c == MoveClassification.MISTAKE
                || c == MoveClassification.MISS || c == MoveClassification.BLUNDER;
    }

    /** A tile as a node. */
    public static Canvas tile(MoveClassification c, double size) {
        Canvas canvas = new Canvas(size, size);
        draw(canvas.getGraphicsContext2D(), c, 0, 0, size);
        return canvas;
    }

    /** Draws the tile of {@code c} with its top-left corner at (x, y). */
    public static void draw(GraphicsContext gc, MoveClassification c, double x, double y, double size) {
        double r = size * 0.30;
        gc.setFill(color(c));
        gc.fillRoundRect(x, y, size, size, r * 2, r * 2);
        gc.setFill(Color.WHITE);
        gc.setStroke(Color.WHITE);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        double cx = x + size / 2;
        double cy = y + size / 2;
        if (c == null) {
            return;
        }
        switch (c) {
            case BRILLIANT -> text(gc, "!!", cx, cy, size * 0.58);
            case GREAT -> text(gc, "!", cx, cy, size * 0.62);
            case INACCURACY -> text(gc, "?!", cx, cy, size * 0.52);
            case MISTAKE -> text(gc, "?", cx, cy, size * 0.62);
            case BLUNDER -> text(gc, "??", cx, cy, size * 0.52);
            case BEST -> star(gc, cx, cy + size * 0.02, size * 0.30);
            case EXCELLENT -> check(gc, cx, cy, size, size * 0.11);
            case GOOD -> check(gc, cx, cy, size, size * 0.075);
            case BOOK_MOVE -> book(gc, cx, cy, size);
            case FORCED -> arrow(gc, cx, cy, size);
            case MISS -> cross(gc, cx, cy, size);
        }
    }

    private static void text(GraphicsContext gc, String glyph, double cx, double cy, double fontSize) {
        gc.setFont(Font.font("Geist", javafx.scene.text.FontWeight.BOLD, fontSize));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(glyph, cx, cy + fontSize * 0.02);
    }

    private static void star(GraphicsContext gc, double cx, double cy, double outer) {
        double inner = outer * 0.45;
        double[] xs = new double[10];
        double[] ys = new double[10];
        for (int i = 0; i < 10; i++) {
            double radius = i % 2 == 0 ? outer : inner;
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            xs[i] = cx + radius * Math.cos(angle);
            ys[i] = cy + radius * Math.sin(angle);
        }
        gc.fillPolygon(xs, ys, 10);
    }

    private static void check(GraphicsContext gc, double cx, double cy, double size, double width) {
        gc.setLineWidth(width);
        double s = size * 0.20;
        gc.strokePolyline(new double[] { cx - s * 1.15, cx - s * 0.25, cx + s * 1.25 },
                new double[] { cy + s * 0.05, cy + s * 0.95, cy - s * 0.85 }, 3);
    }

    private static void cross(GraphicsContext gc, double cx, double cy, double size) {
        gc.setLineWidth(size * 0.11);
        double s = size * 0.19;
        gc.strokeLine(cx - s, cy - s, cx + s, cy + s);
        gc.strokeLine(cx - s, cy + s, cx + s, cy - s);
    }

    private static void arrow(GraphicsContext gc, double cx, double cy, double size) {
        gc.setLineWidth(size * 0.10);
        double s = size * 0.22;
        gc.strokeLine(cx - s, cy, cx + s, cy);
        gc.strokePolyline(new double[] { cx + s * 0.25, cx + s, cx + s * 0.25 },
                new double[] { cy - s * 0.75, cy, cy + s * 0.75 }, 3);
    }

    /** An open book: two pages with a spine. */
    private static void book(GraphicsContext gc, double cx, double cy, double size) {
        double w = size * 0.25;
        double h = size * 0.34;
        double top = cy - h / 2;
        gc.fillPolygon(new double[] { cx - size * 0.02, cx - w - size * 0.02, cx - w - size * 0.02, cx - size * 0.02 },
                new double[] { top + h * 0.12, top, top + h, top + h * 1.0 + h * 0.08 }, 4);
        gc.fillPolygon(new double[] { cx + size * 0.02, cx + w + size * 0.02, cx + w + size * 0.02, cx + size * 0.02 },
                new double[] { top + h * 0.12, top, top + h, top + h * 1.0 + h * 0.08 }, 4);
    }
}
