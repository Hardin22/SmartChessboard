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

    /** Score in pawns from white's point of view; values beyond +-900 encode mate (1000 - n = mate in n). */
    public void updateEvaluation(double score) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateEvaluation(score));
            return;
        }
        double target;
        String text;
        if (score == Double.POSITIVE_INFINITY || score > 900) {
            int mateIn = score == Double.POSITIVE_INFINITY ? 0 : (int) (1000 - score);
            target = 1;
            text = mateIn == 0 ? "1-0" : "M" + mateIn;
        } else if (score == Double.NEGATIVE_INFINITY || score < -900) {
            int mateIn = score == Double.NEGATIVE_INFINITY ? 0 : (int) (1000 + score);
            target = 0;
            text = mateIn == 0 ? "0-1" : "-M" + mateIn;
        } else {
            // Soft clamp: +-1 pawn already moves the bar noticeably, +-6 is almost full.
            double normalized = Math.tanh(score / 3.5);
            target = (1 + normalized) / 2;
            text = String.format(Locale.US, "%+.1f", score).replace("+0.0", "0.0").replace("-0.0", "0.0");
        }
        scoreText.set(text);
        scoreLabel.setText(text.startsWith("+") ? text.substring(1) : text.replace("-", ""));
        animateTo(target);
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
