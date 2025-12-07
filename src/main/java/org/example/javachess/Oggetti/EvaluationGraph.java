package org.example.javachess.Oggetti;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

import java.util.List;

public class EvaluationGraph extends Pane {

    private final Canvas canvas;
    private List<MoveAnalysis> analysisData;

    public EvaluationGraph(double width, double height) {
        this.setPrefSize(width, height);
        this.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #000000; -fx-border-width: 2px;");
        
        canvas = new Canvas(width, height);
        this.getChildren().add(canvas);
        
        // Redraw on resize
        this.widthProperty().addListener((obs, oldVal, newVal) -> {
            canvas.setWidth(newVal.doubleValue());
            draw();
        });
        this.heightProperty().addListener((obs, oldVal, newVal) -> {
            canvas.setHeight(newVal.doubleValue());
            draw();
        });
    }

    public void setData(List<MoveAnalysis> data) {
        this.analysisData = data;
        draw();
    }

    private int currentMoveIndex = -1;

    public void setHighlightMove(int moveIndex) {
        this.currentMoveIndex = moveIndex;
        draw();
    }

    private void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();

        // Clear
        gc.clearRect(0, 0, width, height);
        
        // Background
        gc.setFill(Color.web("#1a1a1a"));
        gc.fillRect(0, 0, width, height);

        if (analysisData == null || analysisData.isEmpty()) {
            return;
        }

        // Draw center line (0.0 evaluation)
        gc.setStroke(Color.web("#555555"));
        gc.setLineWidth(1);
        gc.strokeLine(0, height / 2, width, height / 2);

        // Draw graph
        gc.setStroke(Color.web("#ccff00")); // Neon Yellow
        gc.setLineWidth(2);

        double xStep = width / (analysisData.size() - 1);
        
        // Cap evaluation for display purposes (e.g., +/- 5.0 as requested)
        double maxEval = 5.0; 

        gc.beginPath();
        for (int i = 0; i < analysisData.size(); i++) {
            MoveAnalysis move = analysisData.get(i);
            double score = move.getScore();
            
            if (move.isMate()) {
                // If mate, force to max/min
                score = (score > 0) ? maxEval : -maxEval;
            } else {
                // Clamp score
                if (score > maxEval) score = maxEval;
                if (score < -maxEval) score = -maxEval;
            }

            // Map score to Y (invert because canvas Y is down)
            // +maxEval -> 0 (top)
            // 0 -> height/2
            // -maxEval -> height (bottom)
            
            double normalizedScore = (score + maxEval) / (2 * maxEval); // 0.0 to 1.0
            double y = height - (normalizedScore * height);
            double x = i * xStep;

            if (i == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
        
        // Draw Cursor
        if (currentMoveIndex >= 0 && currentMoveIndex < analysisData.size()) {
            double cursorX = currentMoveIndex * xStep;
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1);
            gc.setLineDashes(5);
            gc.strokeLine(cursorX, 0, cursorX, height);
            gc.setLineDashes(null); // Reset
            
            // Draw dot at intersection
            MoveAnalysis move = analysisData.get(currentMoveIndex);
            double score = move.getScore();
            if (move.isMate()) {
                score = (score > 0) ? maxEval : -maxEval;
            } else {
                if (score > maxEval) score = maxEval;
                if (score < -maxEval) score = -maxEval;
            }
            double normalizedScore = (score + maxEval) / (2 * maxEval);
            double cursorY = height - (normalizedScore * height);
            
            gc.setFill(Color.WHITE);
            gc.fillOval(cursorX - 4, cursorY - 4, 8, 8);
        }
    }
}
