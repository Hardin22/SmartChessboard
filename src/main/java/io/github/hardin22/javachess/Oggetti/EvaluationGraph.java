package io.github.hardin22.javachess.Oggetti;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineJoin;
import io.github.hardin22.javachess.Components.ReviewLabels;
import io.github.hardin22.javachess.Components.ThemeManager;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Evaluation over the game: White's advantage as a light area over a dark one, the zero line, coloured dots on the
 * notable moves (Geniale, Grande, Occasione persa, Errore, Errore grave) and a cursor on the current move.
 * Theme-aware; repainted only when data, cursor, size or theme change. Tapping (or dragging along) the graph
 * reports the move index.
 */
public class EvaluationGraph extends Region {

    private static final double MAX_EVAL = 600.0; // centipawns shown at the edges
    private static final double RADIUS = 18;

    private final Canvas canvas;
    private List<MoveAnalysis> analysisData;
    private int totalMoves;
    private int currentMoveIndex = -1;
    private IntConsumer onMoveSelected;

    public EvaluationGraph(double width, double height) {
        getStyleClass().add("eval-graph");
        setPrefSize(width, height);
        setMinHeight(80);
        canvas = new Canvas(width, height);
        getChildren().add(canvas);
        widthProperty().addListener((obs, o, n) -> draw());
        heightProperty().addListener((obs, o, n) -> draw());
        ThemeManager.get().darkProperty().addListener((obs, o, n) -> draw());
        setOnMouseClicked(e -> select(e.getX()));
        setOnMouseDragged(e -> select(e.getX()));
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(RADIUS * 2);
        clip.setArcHeight(RADIUS * 2);
        setClip(clip);
    }

    private void select(double x) {
        int n = slots();
        if (onMoveSelected != null && analysisData != null && !analysisData.isEmpty() && n > 1) {
            double step = getWidth() / (n - 1);
            int index = (int) Math.round(x / step);
            onMoveSelected.accept(Math.max(0, Math.min(analysisData.size() - 1, index)));
        }
    }

    private int slots() {
        return analysisData == null ? 0 : Math.max(analysisData.size(), totalMoves);
    }

    public void setOnMoveSelected(IntConsumer listener) {
        this.onMoveSelected = listener;
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(Math.floor(getWidth()));
        canvas.setHeight(Math.floor(getHeight()));
    }

    public void setData(List<MoveAnalysis> data) {
        setData(data, 0);
    }

    /** Data of the first moves of a game of {@code totalMoves} moves (a review in progress keeps the final scale). */
    public void setData(List<MoveAnalysis> data, int totalMoves) {
        this.analysisData = data;
        this.totalMoves = totalMoves;
        draw();
    }

    public void setHighlightMove(int moveIndex) {
        if (moveIndex != currentMoveIndex) {
            this.currentMoveIndex = moveIndex;
            draw();
        }
    }

    private static double clampScore(MoveAnalysis move) {
        double score = move.getScore();
        if (move.isMate()) {
            return score > 0 ? MAX_EVAL : -MAX_EVAL;
        }
        return Math.max(-MAX_EVAL, Math.min(MAX_EVAL, score));
    }

    private void draw() {
        double width = Math.floor(getWidth());
        double height = Math.floor(getHeight());
        if (width <= 0 || height <= 0) {
            return;
        }
        canvas.setWidth(width);
        canvas.setHeight(height);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        ThemeManager.Palette p = ThemeManager.get().palette();
        boolean dark = ThemeManager.get().isDark();

        Color blackArea = dark ? Color.web("#232328") : Color.web("#3A3A40");
        Color whiteArea = dark ? Color.web("#DCDCE0") : Color.web("#FFFFFF");
        gc.setFill(blackArea);
        gc.fillRect(0, 0, width, height);

        int n = analysisData == null ? 0 : analysisData.size();
        if (n < 2) {
            gc.setFill(whiteArea);
            gc.fillRect(0, height / 2, width, height / 2);
            return;
        }

        double step = width / (slots() - 1);
        double pad = 10;
        double usable = height - 2 * pad;
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];
        for (int i = 0; i < n; i++) {
            double normalized = (clampScore(analysisData.get(i)) + MAX_EVAL) / (2 * MAX_EVAL);
            xs[i] = i * step;
            ys[i] = pad + usable - normalized * usable;
        }
        xs[n] = xs[n - 1];
        ys[n] = height;
        xs[n + 1] = 0;
        ys[n + 1] = height;
        gc.setFill(whiteArea);
        gc.fillPolygon(xs, ys, n + 2);

        gc.setStroke(Color.web("#8A8A92", 0.55));
        gc.setLineWidth(1);
        gc.strokeLine(0, Math.round(height / 2) + 0.5, width, Math.round(height / 2) + 0.5);

        if (currentMoveIndex >= 0 && currentMoveIndex < n) {
            double x = Math.round(currentMoveIndex * step) + 0.5;
            gc.setStroke(p.accent());
            gc.setLineWidth(3);
            gc.strokeLine(x, 0, x, height);
        }

        // Notable moves: a dot in the label colour with a ring in the area colour, so it reads on both areas.
        gc.setLineJoin(StrokeLineJoin.ROUND);
        for (int i = 0; i < n; i++) {
            MoveAnalysis move = analysisData.get(i);
            if (!ReviewLabels.notable(move.getClassification())) {
                continue;
            }
            double r = i == currentMoveIndex ? 9 : 7;
            gc.setFill(i == currentMoveIndex ? p.fg() : blackArea);
            gc.fillOval(xs[i] - r - 2.5, ys[i] - r - 2.5, (r + 2.5) * 2, (r + 2.5) * 2);
            gc.setFill(ReviewLabels.color(move.getClassification()));
            gc.fillOval(xs[i] - r, ys[i] - r, r * 2, r * 2);
        }
        if (currentMoveIndex >= 0 && currentMoveIndex < n
                && !ReviewLabels.notable(analysisData.get(currentMoveIndex).getClassification())) {
            gc.setFill(p.accent());
            gc.fillOval(xs[currentMoveIndex] - 7, ys[currentMoveIndex] - 7, 14, 14);
        }
    }
}
