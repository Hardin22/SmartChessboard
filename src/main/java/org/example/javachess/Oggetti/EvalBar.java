package org.example.javachess.Oggetti;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public class EvalBar extends StackPane {
    private final Rectangle blackRect;
    private final Rectangle whiteRect;

    public EvalBar(double width, double height) {
        setPrefSize(width, height);

        blackRect = new Rectangle(width, height / 2, Color.BLACK);
        whiteRect = new Rectangle(width, height / 2, Color.WHITE);

        StackPane.setAlignment(blackRect, javafx.geometry.Pos.TOP_CENTER);
        StackPane.setAlignment(whiteRect, javafx.geometry.Pos.BOTTOM_CENTER);

        getChildren().addAll(blackRect, whiteRect);
    }

    public void updateEvaluation(double score) {
        Platform.runLater(() -> {
            if (score == Double.POSITIVE_INFINITY) {
                // Bianco vince (mate)
                animateBars(getHeight(), 0, 400); // 3 secondi per riempire la barra
            } else if (score == Double.NEGATIVE_INFINITY) {
                // Nero vince (mate)
                animateBars(0, getHeight(), 400); // 3 secondi per riempire la barra
            } else {
                // Scegli il moltiplicatore in base al valore del punteggio

                // Normalizzazione dello score con il divisore scelto
                double normalizedScore = Math.max(-1.0, Math.min(1.0, score / 6));
                double newWhiteHeight = (1.0 + normalizedScore) / 2.0 * getHeight();
                double newBlackHeight = getHeight() - newWhiteHeight;

                animateBars(newWhiteHeight, newBlackHeight, 400); // 3 secondi per animare lo spostamento
            }
        });
    }



    private void animateBars(double newWhiteHeight, double newBlackHeight, int durationMillis) {
        Timeline timeline = new Timeline();

        KeyValue whiteHeightValue = new KeyValue(whiteRect.heightProperty(), newWhiteHeight);
        KeyValue blackHeightValue = new KeyValue(blackRect.heightProperty(), newBlackHeight);

        KeyFrame keyFrame = new KeyFrame(Duration.millis(durationMillis), whiteHeightValue, blackHeightValue);

        timeline.getKeyFrames().add(keyFrame);
        timeline.play();
    }
}
