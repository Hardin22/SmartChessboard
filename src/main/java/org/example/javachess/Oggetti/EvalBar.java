package org.example.javachess.Oggetti;

import java.util.Locale;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public class EvalBar extends StackPane {
    private final Region blackRegion;
    private final Region whiteRegion;
    private final VBox barsContainer;
    private final Pane tickContainer;
    private final Region borderRegion;
    private final Rectangle clipRect;
    private final Label scoreLabel;

    public EvalBar(double width, double height) {
        setPrefSize(width, height);
        // Remove CSS border to avoid layout shifts and rendering issues
        setStyle("-fx-background-color: #121212; -fx-background-radius: 10;");

        // Clip for rounded corners
        clipRect = new Rectangle(width, height);
        clipRect.setArcWidth(10);
        clipRect.setArcHeight(10);
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        // Gradient for Black (Top)
        Stop[] blackStops = new Stop[] { new Stop(0, Color.web("#2c2c2c")), new Stop(1, Color.web("#1a1a1a")) };
        LinearGradient blackGradient = new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, blackStops);
        blackRegion = new Region();
        blackRegion.setBackground(new Background(new BackgroundFill(blackGradient, CornerRadii.EMPTY, Insets.EMPTY)));
        // Allow width to fill, height will be animated
        blackRegion.setMaxWidth(Double.MAX_VALUE);

        // Gradient for White (Bottom)
        Stop[] whiteStops = new Stop[] { new Stop(0, Color.web("#f0f0f0")), new Stop(1, Color.web("#e0e0e0")) };
        LinearGradient whiteGradient = new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, whiteStops);
        whiteRegion = new Region();
        whiteRegion.setBackground(new Background(new BackgroundFill(whiteGradient, CornerRadii.EMPTY, Insets.EMPTY)));
        // Allow width to fill, height will be animated
        whiteRegion.setMaxWidth(Double.MAX_VALUE);

        // Container for bars (VBox ensures they stack vertically)
        barsContainer = new VBox(0); // 0 spacing
        barsContainer.getChildren().addAll(blackRegion, whiteRegion);
        barsContainer.setFillWidth(true);
        // Initial heights (50/50)
        blackRegion.setPrefHeight(height / 2);
        whiteRegion.setPrefHeight(height / 2);

        // Container for tick marks
        tickContainer = new Pane();
        tickContainer.setMouseTransparent(true);
        tickContainer.setPrefSize(0, 0); // Prevent layout loop

        // Manual Border on top
        borderRegion = new Region();
        borderRegion.setBackground(Background.EMPTY);
        borderRegion.setStyle("-fx-border-color: #333333; -fx-border-width: 2; -fx-border-radius: 5;"); // Radius
                                                                                                        // matches clip
                                                                                                        // roughly
        borderRegion.setMouseTransparent(true);
        borderRegion.setPrefSize(0, 0); // Prevent layout loop

        // Score Label
        scoreLabel = new Label("");
        scoreLabel.setFont(Font.font("System", FontWeight.SEMI_BOLD, 10));
        scoreLabel.setMouseTransparent(true);

        getChildren().addAll(barsContainer, tickContainer, borderRegion, scoreLabel);
        StackPane.setAlignment(scoreLabel, Pos.CENTER);

        // Initial draw
        drawTicks(width, height);

        // Redraw ticks on resize
        widthProperty().addListener((obs, oldVal, newVal) -> drawTicks(newVal.doubleValue(), getHeight()));
        heightProperty().addListener((obs, oldVal, newVal) -> drawTicks(getWidth(), newVal.doubleValue()));
    }

    private void drawTicks(double width, double height) {
        tickContainer.getChildren().clear();
        double centerY = height / 2;

        // Center Line (0.0)
        Line centerLine = new Line(0, centerY, width, centerY);
        centerLine.setStroke(Color.web("#D4AF37")); // Gold center
        centerLine.setStrokeWidth(2);
        tickContainer.getChildren().add(centerLine);

        // Ticks
        double[] tickValues = { 1.0, 3.0, 5.0 };
        for (double val : tickValues) {
            double yPosWhite = centerY + (val / 6.0) * (height / 2);
            if (yPosWhite < height) {
                Line tick = new Line(width * 0.25, yPosWhite, width * 0.75, yPosWhite);
                tick.setStroke(Color.web("#888888", 0.5));
                tick.setStrokeWidth(1);
                tickContainer.getChildren().add(tick);
            }

            double yPosBlack = centerY - (val / 6.0) * (height / 2);
            if (yPosBlack > 0) {
                Line tick = new Line(width * 0.25, yPosBlack, width * 0.75, yPosBlack);
                tick.setStroke(Color.web("#888888", 0.5));
                tick.setStrokeWidth(1);
                tickContainer.getChildren().add(tick);
            }
        }
    }

    public void updateEvaluation(double score) {
        Platform.runLater(() -> {
            String scoreText;
            boolean isBlackWinning;

            if (score == Double.POSITIVE_INFINITY || score > 900) {
                animateBars(getHeight(), 0, 400);
                int mateIn = (score == Double.POSITIVE_INFINITY) ? 0 : (int) (1000 - score);
                scoreText = mateIn == 0 ? "1-0" : "M" + mateIn;
                isBlackWinning = false;
            } else if (score == Double.NEGATIVE_INFINITY || score < -900) {
                animateBars(0, getHeight(), 400);
                int mateIn = (score == Double.NEGATIVE_INFINITY) ? 0 : (int) (1000 + score);
                scoreText = mateIn == 0 ? "0-1" : "M" + mateIn;
                isBlackWinning = true;
            } else {
                double normalizedScore = Math.max(-1.0, Math.min(1.0, score / 6.0));
                double newWhiteHeight = (1.0 + normalizedScore) / 2.0 * getHeight();
                double newBlackHeight = getHeight() - newWhiteHeight;
                animateBars(newWhiteHeight, newBlackHeight, 400);
                scoreText = String.format(Locale.US, "%.1f", Math.abs(score));
                isBlackWinning = score < 0;
            }

            scoreLabel.setText(scoreText);
            if (isBlackWinning) {
                scoreLabel.setTextFill(Color.WHITE);
                StackPane.setAlignment(scoreLabel, Pos.TOP_CENTER);
                StackPane.setMargin(scoreLabel, new Insets(5, 0, 0, 0));
            } else {
                scoreLabel.setTextFill(Color.BLACK);
                StackPane.setAlignment(scoreLabel, Pos.BOTTOM_CENTER);
                StackPane.setMargin(scoreLabel, new Insets(0, 0, 5, 0));
            }
        });
    }

    private void animateBars(double newWhiteHeight, double newBlackHeight, int durationMillis) {
        Timeline timeline = new Timeline();
        KeyValue whiteHeightValue = new KeyValue(whiteRegion.prefHeightProperty(), newWhiteHeight);
        KeyValue blackHeightValue = new KeyValue(blackRegion.prefHeightProperty(), newBlackHeight);
        KeyFrame keyFrame = new KeyFrame(Duration.millis(durationMillis), whiteHeightValue, blackHeightValue);
        timeline.getKeyFrames().add(keyFrame);
        timeline.play();
    }
}
