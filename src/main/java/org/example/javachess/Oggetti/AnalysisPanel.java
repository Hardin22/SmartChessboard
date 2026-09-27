package org.example.javachess.Oggetti;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.control.OverrunStyle;

public class AnalysisPanel extends VBox {

    private String currentStatusMessage = "";
    private boolean showEvaluation = true;
    private boolean showBestMoves = true;

    public AnalysisPanel() {
        setSpacing(5);
        setAlignment(Pos.CENTER);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().add("glass-card");
        setVisible(false);
        setManaged(false);
    }

    public void setShowEvaluation(boolean show) {
        this.showEvaluation = show;
        updateVisibility();
    }

    public void setShowBestMoves(boolean show) {
        this.showBestMoves = show;
        updateVisibility();
    }

    public void setStatusMessage(String message) {
        this.currentStatusMessage = message;
        refresh();
    }

    private void updateVisibility() {
        boolean shouldBeVisible = (showEvaluation || showBestMoves)
                && (!getChildren().isEmpty() || !currentStatusMessage.isEmpty());
        setVisible(shouldBeVisible);
        setManaged(shouldBeVisible);
    }

    public void clear() {
        getChildren().clear();
        updateVisibility();
    }

    public void showResult(String result) {
        getChildren().clear();
        HBox lineBox = new HBox(10);
        lineBox.setAlignment(Pos.CENTER);
        lineBox.setPadding(new Insets(10));
        lineBox.setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); -fx-background-radius: 5;");
        lineBox.setMaxWidth(Double.MAX_VALUE);

        Label resultBadge = createEvalBadge(result);
        resultBadge.setStyle(resultBadge.getStyle() + "-fx-font-size: 24px; -fx-padding: 5 15 5 15;");

        lineBox.getChildren().add(resultBadge);
        getChildren().add(lineBox);
        updateVisibility();
    }

    public void updateAnalysis(int pv, String fullLine, String scoreText) {
        if (pv == 0) {
            getChildren().clear();
            if (currentStatusMessage != null && !currentStatusMessage.isEmpty()) {
                Label statusLabel = new Label(currentStatusMessage);
                statusLabel.getStyleClass().add("body-label");
                statusLabel.setStyle("-fx-text-fill: #E0E0E0; -fx-padding: 0 0 5 0;");
                getChildren().add(statusLabel);
            }
        }

        HBox lineBox = new HBox(10);
        lineBox.setAlignment(Pos.CENTER_LEFT);
        lineBox.setPadding(new Insets(5));
        lineBox.setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); -fx-background-radius: 5;");
        lineBox.setMaxWidth(Double.MAX_VALUE);

        // Evaluation Badge
        Label evalBadge = createEvalBadge(scoreText);

        // Move Label (No wrap, ellipsis)
        Label moveLabel = createMoveLabel(fullLine);

        if (showEvaluation)
            lineBox.getChildren().add(evalBadge);
        if (showBestMoves)
            lineBox.getChildren().add(moveLabel);

        if (showEvaluation || showBestMoves) {
            getChildren().add(lineBox);
        }

        updateVisibility();
    }

    private Label createEvalBadge(String evalText) {
        Label evalBadge = new Label(evalText);
        evalBadge.getStyleClass().add("body-label");
        evalBadge.setStyle("-fx-font-weight: bold; -fx-padding: 2 6 2 6; -fx-background-radius: 4;");
        evalBadge.setMinWidth(Region.USE_PREF_SIZE);

        boolean isWhiteAdvantage = true;
        if (evalText.startsWith("-") || evalText.equals("0-1")) {
            isWhiteAdvantage = false;
        }

        if (isWhiteAdvantage) {
            // White Advantage or Neutral: White Background, Dark Text
            evalBadge.setStyle(evalBadge.getStyle() + "-fx-background-color: #F5F5F5; -fx-text-fill: #121212;");
        } else {
            // Black Advantage: Black Gradient, White Text
            evalBadge.setStyle(evalBadge.getStyle()
                    + "-fx-background-color: linear-gradient(to bottom, #2c2c2c, #1a1a1a); -fx-text-fill: white; -fx-border-color: #444444; -fx-border-radius: 4;");
        }
        return evalBadge;
    }

    private Label createMoveLabel(String fullLine) {
        String cleanLine = fullLine.replaceAll("^\\s*[\\[\\(][+-]?\\d+\\.\\d+[\\]\\)]\\s*", "");
        cleanLine = cleanLine.replaceAll("^\\s*[\\[\\(]M\\d+[\\]\\)]\\s*", "");
        cleanLine = cleanLine.replaceAll("^\\s*[\\[\\(]-M\\d+[\\]\\)]\\s*", "");
        cleanLine = cleanLine.replaceAll("^\\s*[\\[\\(]1-0[\\]\\)]\\s*", "");
        cleanLine = cleanLine.replaceAll("^\\s*[\\[\\(]0-1[\\]\\)]\\s*", "");

        Label label = new Label(cleanLine);
        label.getStyleClass().add("body-label");
        label.setStyle("-fx-text-fill: #E0E0E0; -fx-font-size: 16px;");
        label.setWrapText(false);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);

        return label;
    }

    private void refresh() {
        // Simple refresh: clear and wait for next update, or just update visibility if
        // only status changed
        if (getChildren().isEmpty() && !currentStatusMessage.isEmpty()) {
            Label statusLabel = new Label(currentStatusMessage);
            statusLabel.getStyleClass().add("body-label");
            statusLabel.setStyle("-fx-text-fill: #E0E0E0;");
            getChildren().add(statusLabel);
        }
        updateVisibility();
    }
}
