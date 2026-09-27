package org.example.javachess.Oggetti;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

public class EvaluationPanel extends VBox {

    public EvaluationPanel() {
        this.setSpacing(5);
        this.setStyle("-fx-background-color: transparent;");
    }

    public void clearLines() {
        this.getChildren().clear();
        this.setVisible(false);
        this.setManaged(false);
    }

    public void addStatusMessage(String message) {
        if (message != null && !message.isEmpty()) {
            Label statusLabel = new Label(message);
            statusLabel.getStyleClass().add("body-label");
            statusLabel.setStyle("-fx-text-fill: #E0E0E0; -fx-padding: 0 0 5 0;");
            this.getChildren().add(statusLabel);
            this.setVisible(true);
            this.setManaged(true);
        }
    }

    public void addAnalysisLine(String fullLine, String score) {
        HBox lineBox = new HBox(10);
        lineBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        lineBox.setPadding(new javafx.geometry.Insets(5));
        lineBox.setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); -fx-background-radius: 5;");
        lineBox.setMaxWidth(Double.MAX_VALUE);

        // Evaluation Badge
        String evalText = (score != null) ? score : "0.0";
        Label evalBadge = new Label(evalText);
        evalBadge.getStyleClass().add("body-label");
        evalBadge.setStyle("-fx-font-weight: bold; -fx-padding: 2 6 2 6; -fx-background-radius: 4;");
        evalBadge.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Color coding
        if (evalText.contains("#")) {
            evalBadge.setStyle(evalBadge.getStyle() + "-fx-background-color: #E91E63; -fx-text-fill: white;"); // Mate:
                                                                                                               // Pink
        } else {
            try {
                double val = Double.parseDouble(evalText);
                if (val > 0.5) {
                    // White Advantage: Lighter White
                    evalBadge.setStyle(evalBadge.getStyle() + "-fx-background-color: #F5F5F5; -fx-text-fill: #121212;");
                } else if (val < -0.5) {
                    // Black Advantage: Black Gradient
                    evalBadge.setStyle(evalBadge.getStyle()
                            + "-fx-background-color: linear-gradient(to bottom, #2c2c2c, #1a1a1a); -fx-text-fill: white;");
                } else {
                    // Drawish: Grey
                    evalBadge.setStyle(evalBadge.getStyle() + "-fx-background-color: #9E9E9E; -fx-text-fill: white;");
                }
            } catch (Exception e) {
                evalBadge.setStyle(evalBadge.getStyle() + "-fx-background-color: #9E9E9E; -fx-text-fill: white;");
            }
        }

        // Move Text Processing
        String cleanLine = fullLine;
        // Regex to remove [score] or (score) at the start
        cleanLine = cleanLine.replaceAll("^\\s*[\\[\\(][+-]?\\d+\\.\\d+[\\]\\)]\\s*", "");
        cleanLine = cleanLine.replaceAll("^\\s*[\\[\\(]M\\d+[\\]\\)]\\s*", "");
        cleanLine = cleanLine.replaceAll("^\\s*[\\[\\(]-M\\d+[\\]\\)]\\s*", "");

        TextFlow moveTextFlow = new TextFlow();
        moveTextFlow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(moveTextFlow, javafx.scene.layout.Priority.ALWAYS);

        // Parse line for piece symbols and apply styling
        String[] parts = cleanLine.split("(?=[♔♕♖♗♘♙♚♛♜♝♞♟])|(?<=[♔♕♖♗♘♙♚♛♜♝♞♟])");

        for (String part : parts) {
            Text textNode = new Text(part);
            textNode.getStyleClass().add("body-label");

            if (part.matches("[♔♕♖♗♘♙♚♛♜♝♞♟]")) {
                textNode.setStyle("-fx-fill: #E0E0E0; -fx-font-size: 18px;");
            } else {
                textNode.setStyle("-fx-fill: #E0E0E0;");
            }
            moveTextFlow.getChildren().add(textNode);
        }

        lineBox.getChildren().addAll(evalBadge, moveTextFlow);
        this.getChildren().add(lineBox);
        this.setVisible(true);
        this.setManaged(true);
    }
}
