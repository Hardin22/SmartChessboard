package org.example.javachess.Utils;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.stage.Stage;

import java.util.List;

public class SmoothGraph extends Application {

    @Override
    public void start(Stage stage) {
        Pane root = new Pane();

        // Carica i dati dal JSON
        List<Double> dataPoints = List.of(
                0.0, 0.2, 0.3, -1.5, 0.5, 0.7, -0.2, 1.0, -0.5, 0.3, 0.8, -0.1, 0.4, 0.6, -0.3,
                0.0, 0.2, 0.3, -1.5, 0.5, 0.7, -0.2, 1.0, -0.5, 0.3, 0.8, -0.1, 0.4, 0.6, -0.3,
                0.0, 0.2, 0.3, -1.5, 0.5, 0.7, -0.2, 1.0, -0.5, 0.3, 0.8, -0.1, 0.4, 0.6, -0.3,
                0.1, 0.5, -0.4, 0.2, 0.9, -0.6
        );

        // Inizializza il Path per il grafico
        Path path = new Path();
        path.setStroke(Color.BLACK);
        path.setStrokeWidth(2);
        path.setFill(Color.WHITE); // Colore di riempimento sotto la curva

        // Imposta la posizione iniziale (assumiamo che x parta da 0)
        double xOffset = 50; // Margine sinistro
        double yOffset = 200; // Margine per altezza
        double scaleFactor = 20; // Fattore di scala ridotto per il grafico
        double maxWidth = 500; // Larghezza massima del grafico
        double pointDistance = maxWidth / (dataPoints.size() - 1); // Distanza tra i punti
        double maxYValue = 5.0; // Valore massimo per l'asse y
        double minYValue = -5.0; // Valore minimo per l'asse y

        // Mossa iniziale
        path.getElements().add(new MoveTo(xOffset, yOffset - Math.max(Math.min(dataPoints.get(0), maxYValue), minYValue) * scaleFactor));

        // Aggiungi punti con curve smussate
        for (int i = 1; i < dataPoints.size(); i++) {
            double prevX = xOffset + (i - 1) * pointDistance;
            double currX = xOffset + i * pointDistance;

            double prevY = yOffset - Math.max(Math.min(dataPoints.get(i - 1), maxYValue), minYValue) * scaleFactor;
            double currY = yOffset - Math.max(Math.min(dataPoints.get(i), maxYValue), minYValue) * scaleFactor;

            // Aggiungi una curva smussata tra i punti
            path.getElements().add(new CubicCurveTo(
                    prevX + pointDistance / 2, prevY,   // Primo punto di controllo
                    currX - pointDistance / 2, currY,   // Secondo punto di controllo
                    currX, currY                        // Punto finale
            ));

            // Calcola la differenza in valore assoluto
            double diff = Math.abs(dataPoints.get(i) - dataPoints.get(i - 1));

            // Aggiungi un pallino rosso o arancione in base alla differenza
            if (diff > 1.7) {
                Circle redCircle = new Circle(currX, currY, 5, Color.RED);
                root.getChildren().add(redCircle);
            } else if (diff > 1.2) {
                Circle orangeCircle = new Circle(currX, currY, 5, Color.ORANGE);
                root.getChildren().add(orangeCircle);
            }
        }

        // Aggiungi un riempimento sotto il grafico
        path.getElements().add(new LineTo(xOffset + (dataPoints.size() - 1) * pointDistance, yOffset)); // Linea verticale alla base
        path.getElements().add(new LineTo(xOffset, yOffset)); // Linea orizzontale verso sinistra
        path.getElements().add(new LineTo(xOffset, yOffset - Math.max(Math.min(dataPoints.get(0), maxYValue), minYValue) * scaleFactor)); // Chiudi il riempimento

        // Aggiungi il path alla scena
        root.getChildren().add(path);

        // Configura e visualizza la scena
        Scene scene = new Scene(root, 600, 400);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}