package io.github.hardin22.javachess.Oggetti;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.Icons;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine output under the board: an optional status line (setup instructions, errors) and up to five
 * principal variations, each with an evaluation badge and the moves. Line nodes are created once and reused.
 */
public class AnalysisPanel extends VBox {

    private static final int MAX_LINES = 5;

    private final Label statusLabel = new Label();
    private final List<HBox> lines = new ArrayList<>();
    private final List<Label> badges = new ArrayList<>();
    private final List<Label> moves = new ArrayList<>();
    private final HBox resultBox = new HBox();
    private final Label resultLabel = new Label();
    private String currentStatusMessage = "";
    private boolean showEvaluation = true;
    private boolean showBestMoves = true;
    private int visibleLines;

    public AnalysisPanel() {
        getStyleClass().add("analysis-panel");
        setMaxWidth(Double.MAX_VALUE);

        statusLabel.getStyleClass().add("analysis-status");
        statusLabel.setGraphic(Icons.of("fth-info", 16));
        statusLabel.getGraphic().getStyleClass().add("icon-muted");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        statusLabel.setVisible(false);
        getChildren().add(statusLabel);

        resultLabel.getStyleClass().add("result-badge");
        resultBox.getStyleClass().add("analysis-line");
        resultBox.setAlignment(Pos.CENTER);
        resultBox.getChildren().add(resultLabel);
        resultBox.managedProperty().bind(resultBox.visibleProperty());
        resultBox.setVisible(false);
        getChildren().add(resultBox);

        for (int i = 0; i < MAX_LINES; i++) {
            Label badge = new Label();
            badge.getStyleClass().add("eval-badge");
            badge.setMinWidth(USE_PREF_SIZE);
            badge.managedProperty().bind(badge.visibleProperty());
            Label moveLabel = new Label();
            moveLabel.getStyleClass().add("analysis-moves");
            moveLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            moveLabel.setMaxWidth(Double.MAX_VALUE);
            moveLabel.managedProperty().bind(moveLabel.visibleProperty());
            HBox.setHgrow(moveLabel, Priority.ALWAYS);
            HBox line = new HBox(badge, moveLabel);
            line.getStyleClass().add("analysis-line");
            line.managedProperty().bind(line.visibleProperty());
            line.setVisible(false);
            lines.add(line);
            badges.add(badge);
            moves.add(moveLabel);
            getChildren().add(line);
        }
    }

    public void setShowEvaluation(boolean show) {
        this.showEvaluation = show;
        for (Label badge : badges) {
            badge.setVisible(show);
        }
        updateVisibility();
    }

    public void setShowBestMoves(boolean show) {
        this.showBestMoves = show;
        for (Label m : moves) {
            m.setVisible(show);
        }
        updateVisibility();
    }

    public void setStatusMessage(String message) {
        this.currentStatusMessage = message == null ? "" : message.trim();
        statusLabel.setText(prettify(currentStatusMessage));
        statusLabel.setVisible(!currentStatusMessage.isEmpty());
    }

    /**
     * Game messages often arrive (partly) in capitals ("SCACCHIERA PRONTA! Partita Iniziata"): show them in
     * sentence case, capitalising after . ! ? as well. Square names (e2, F8) are kept upper-case.
     */
    static String prettify(String s) {
        long letters = s.chars().filter(Character::isLetter).count();
        long upper = s.chars().filter(Character::isUpperCase).count();
        if (letters < 4 || upper < letters * 0.5) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean capitalizeNext = true;
        for (String word : s.toLowerCase().split("(?<=\\s)")) {
            String w = word;
            if (w.trim().matches("[a-h][1-8][,.!?:]?")) {
                w = w.toUpperCase();
            } else if (capitalizeNext && !w.isBlank()) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            out.append(w);
            String t = w.trim();
            if (!t.isEmpty()) {
                capitalizeNext = t.endsWith(".") || t.endsWith("!") || t.endsWith("?");
            }
        }
        return out.toString();
    }

    private void updateVisibility() {
        for (int i = 0; i < MAX_LINES; i++) {
            lines.get(i).setVisible(i < visibleLines && (showEvaluation || showBestMoves));
        }
    }

    public void clear() {
        visibleLines = 0;
        resultBox.setVisible(false);
        updateVisibility();
    }

    public void showResult(String result) {
        visibleLines = 0;
        updateVisibility();
        resultLabel.setText(result);
        resultBox.setVisible(true);
    }

    /** {@code pv} is 0-based; a new pv 0 starts a fresh set of lines. */
    public void updateAnalysis(int pv, String fullLine, String scoreText) {
        if (pv < 0 || pv >= MAX_LINES) {
            return;
        }
        resultBox.setVisible(false);
        if (pv == 0) {
            visibleLines = 1;
        } else {
            visibleLines = Math.max(visibleLines, pv + 1);
        }
        Label badge = badges.get(pv);
        String score = formatScore(scoreText);
        badge.setText(score);
        boolean black = score.startsWith("-") || score.startsWith("−") || score.equals("0-1");
        badge.getStyleClass().removeAll("white-adv", "black-adv");
        badge.getStyleClass().add(black ? "black-adv" : "white-adv");
        moves.get(pv).setText(cleanLine(fullLine));
        updateVisibility();
    }

    /** "0.35" -> "+0.35", "-1.20" -> "−1.20", "M3"/"-M2" kept; anything unparsable -> an en dash. */
    static String formatScore(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.matches("-?M\\d+") || s.equals("1-0") || s.equals("0-1")) {
            return s.startsWith("-M") ? "−" + s.substring(1) : s;
        }
        try {
            double v = Double.parseDouble(s);
            if (Math.abs(v) < 0.005) {
                return "0.00";
            }
            return (v > 0 ? "+" : "−") + String.format(java.util.Locale.ROOT, "%.2f", Math.abs(v));
        } catch (NumberFormatException e) {
            return "–";
        }
    }

    private static String cleanLine(String fullLine) {
        if (fullLine == null) {
            return "";
        }
        return io.github.hardin22.javachess.Components.Notation.castling(fullLine)
                .replaceAll("^\\s*[\\[(][+-]?\\d+\\.\\d+[\\])]\\s*", "")
                .replaceAll("^\\s*[\\[(]-?M\\d+[\\])]\\s*", "")
                .replaceAll("^\\s*[\\[(](1-0|0-1)[\\])]\\s*", "");
    }
}
