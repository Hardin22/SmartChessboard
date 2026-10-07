package io.github.hardin22.javachess.Oggetti;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import io.github.hardin22.javachess.Components.ThemeManager;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Evaluation over the game: white's advantage as a light area above a dark baseline area (Lichess style),
 * a 1.5px line, the zero axis and a cursor on the current move. Theme-aware, repainted only on data,
 * cursor, size or theme changes. Tapping the graph reports the move index.
 */
public class EvaluationGraph extends Region {

    private static final double MAX_EVAL = 500.0; // centipawns shown at the edges

    private final Canvas canvas;
    private List<MoveAnalysis> analysisData;
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
        setOnMouseClicked(e -> {
            if (onMoveSelected != null && analysisData != null && analysisData.size() > 1) {
                double step = getWidth() / (analysisData.size() - 1);
                int index = (int) Math.round(e.getX() / step);
                onMoveSelected.accept(Math.max(0, Math.min(analysisData.size() - 1, index)));
            }
        });
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
        this.analysisData = data;
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

        // Lower part (black's side) and upper part (white's side) of the area chart.
        Color blackArea = dark ? Color.web("#1C1C1C") : Color.web("#3A3A3A");
        Color whiteArea = dark ? Color.web("#D4D4D4") : Color.web("#FFFFFF");
        gc.setFill(blackArea);
        gc.fillRect(0, 0, width, height);

        if (analysisData == null || analysisData.size() < 2) {
            gc.setStroke(p.borderStrong());
            gc.setLineWidth(1);
            gc.strokeLine(0, height / 2, width, height / 2);
            return;
        }

        int n = analysisData.size();
        double step = width / (n - 1);
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];
        for (int i = 0; i < n; i++) {
            double normalized = (clampScore(analysisData.get(i)) + MAX_EVAL) / (2 * MAX_EVAL);
            xs[i] = i * step;
            ys[i] = height - normalized * height;
        }
        xs[n] = width;
        ys[n] = height;
        xs[n + 1] = 0;
        ys[n + 1] = height;
        gc.setFill(whiteArea);
        gc.fillPolygon(xs, ys, n + 2);

        gc.setStroke(Color.web("#808080", 0.6));
        gc.setLineWidth(1);
        gc.strokeLine(0, Math.round(height / 2) + 0.5, width, Math.round(height / 2) + 0.5);

        if (currentMoveIndex >= 0 && currentMoveIndex < n) {
            double x = Math.round(currentMoveIndex * step) + 0.5;
            gc.setStroke(p.accent());
            gc.setLineWidth(2);
            gc.strokeLine(x, 0, x, height);
            double y = ys[currentMoveIndex];
            gc.setFill(p.accent());
            gc.fillOval(x - 5, y - 5, 10, 10);
        }
    }
}
