package io.github.hardin22.javachess.Oggetti;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.Locale;

/**
 * Thin evaluation bar: white share grows from the bottom (vertical) or from the left (horizontal).
 * Only two rectangles are moved by layout, the score label is shown when the bar is at least 20 px thick.
 * Colours come from CSS ({@code .eval-bar}, {@code .eval-white}, {@code .eval-black}).
 */
public class EvalBar extends Region {

    private final Region whiteRegion = new Region();
    private final Region blackRegion = new Region();
    private final Label scoreLabel = new Label();
    private final Rectangle clip = new Rectangle();
    /** Share of the bar owned by white, 0..1. */
    private final DoubleProperty whiteShare = new SimpleDoubleProperty(0.5);
    private final ReadOnlyStringWrapper scoreText = new ReadOnlyStringWrapper("0.0");
    private Orientation orientation = Orientation.VERTICAL;
    private Timeline timeline;

    public EvalBar(double width, double height) {
        getStyleClass().add("eval-bar");
        whiteRegion.getStyleClass().add("eval-white");
        blackRegion.getStyleClass().add("eval-black");
        scoreLabel.getStyleClass().add("eval-score");
        scoreLabel.setManaged(false);
        scoreLabel.setMouseTransparent(true);
        getChildren().addAll(blackRegion, whiteRegion, scoreLabel);
        setPrefSize(width, height);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(4);
        clip.setArcHeight(4);
        setClip(clip);
        whiteShare.addListener((obs, o, n) -> requestLayout());
    }

    public void setOrientation(Orientation value) {
        if (value != orientation) {
            orientation = value;
            requestLayout();
        }
    }

    public Orientation getOrientation() {
        return orientation;
    }

    /** Last score as shown to the user ("+0.4", "M3", "1-0"...). */
    public ReadOnlyStringProperty scoreTextProperty() {
        return scoreText.getReadOnlyProperty();
    }

    @Override
    protected void layoutChildren() {
        // The 1px padding lets the background act as an outline (visible on both themes).
        double x0 = snappedLeftInset();
        double y0 = snappedTopInset();
        double w = getWidth() - x0 - snappedRightInset();
        double h = getHeight() - y0 - snappedBottomInset();
        double share = whiteShare.get();
        if (orientation == Orientation.VERTICAL) {
            double whiteH = Math.round(h * share);
            blackRegion.resizeRelocate(x0, y0, w, h - whiteH);
            whiteRegion.resizeRelocate(x0, y0 + h - whiteH, w, whiteH);
        } else {
            double whiteW = Math.round(w * share);
            whiteRegion.resizeRelocate(x0, y0, whiteW, h);
            blackRegion.resizeRelocate(x0 + whiteW, y0, w - whiteW, h);
        }
        boolean showLabel = Math.min(w, h) >= 20;
        scoreLabel.setVisible(showLabel);
        if (showLabel) {
            scoreLabel.autosize();
            double lw = scoreLabel.getWidth();
            double lh = scoreLabel.getHeight();
            boolean whiteAhead = share >= 0.5;
            scoreLabel.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("on-white"), whiteAhead);
            if (orientation == Orientation.VERTICAL) {
                scoreLabel.relocate((w - lw) / 2, whiteAhead ? h - lh - 4 : 4);
            } else {
                scoreLabel.relocate(whiteAhead ? 6 : w - lw - 6, (h - lh) / 2);
            }
        }
    }

    /** What the bar shows for a score: white share (0..1) and text ("+0.4", "M3", "1-0"...). */
    record Display(double whiteShare, String text) {
    }

    /**
     * Maps a score in pawns from White's point of view to the bar. Values beyond +-900 encode mate
     * (1000 - n = White mates in n, exactly 1000 = White has mated; negative for Black), infinities too.
     */
    static Display display(double score) {
        if (Double.isNaN(score)) {
            return new Display(0.5, "0.0");
        }
        if (score == Double.POSITIVE_INFINITY || score > 900) {
            int mateIn = score == Double.POSITIVE_INFINITY ? 0 : (int) Math.round(1000 - score);
            return new Display(1, mateIn <= 0 ? "1-0" : "M" + mateIn);
        }
        if (score == Double.NEGATIVE_INFINITY || score < -900) {
            int mateIn = score == Double.NEGATIVE_INFINITY ? 0 : (int) Math.round(1000 + score);
            return new Display(0, mateIn <= 0 ? "0-1" : "-M" + mateIn);
        }
        // Soft clamp: +-1 pawn already moves the bar noticeably, +-6 is almost full.
        double normalized = Math.tanh(score / 3.5);
        String text = String.format(Locale.US, "%+.1f", score).replace("+0.0", "0.0").replace("-0.0", "0.0");
        return new Display((1 + normalized) / 2, text);
    }

    /** Label inside the bar: the magnitude only (the side is shown by where it sits), results as they are. */
    static String label(String text) {
        if (text.equals("1-0") || text.equals("0-1")) {
            return text;
        }
        return text.startsWith("+") ? text.substring(1) : text.replace("-", "");
    }

    /** Score in pawns from white's point of view; see {@link #display(double)} for the mate encoding. */
    public void updateEvaluation(double score) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateEvaluation(score));
            return;
        }
        Display d = display(score);
        String text = d.text();
        scoreText.set(text);
        scoreLabel.setText(label(text));
        animateTo(d.whiteShare());
    }

    private void animateTo(double target) {
        if (timeline != null) {
            timeline.stop();
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(200),
                new KeyValue(whiteShare, target, Interpolator.EASE_BOTH)));
        timeline.play();
    }
}
