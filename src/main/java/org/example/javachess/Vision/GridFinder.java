package org.example.javachess.Vision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.awt.Rectangle;

/**
 * Locates the 8x8 grid of a 2D board precisely with classic image processing, independently of the neural network.
 *
 * <p>For a candidate grid (origin and square size) every square's background is measured on its four corner
 * patches (pieces stand in the middle of the square and rarely cover the corners), and the score is how well those
 * values separate into the light/dark checker pattern (a t-statistic). The best candidate is searched around an
 * estimate with summed-area tables, so each candidate costs 256 lookups.
 */
final class GridFinder {

    /** An axis-aligned grid: top-left corner and square size, in pixels of the analysed image. */
    record Grid(double x, double y, double cell, double score) {
        Rectangle rect() {
            int size = (int) Math.round(cell * 8);
            return new Rectangle((int) Math.round(x), (int) Math.round(y), size, size);
        }
    }

    /** Below this score the image does not contain a checkerboard at the searched place. */
    static final double MIN_SCORE = 6.0;

    private final double[] sat; // summed-area table, (w+1)*(h+1)
    private final int w;
    private final int h;

    /** @param gray 8-bit single channel image */
    GridFinder(Mat gray) {
        this.w = gray.cols();
        this.h = gray.rows();
        Mat integral = new Mat();
        Imgproc.integral(gray, integral, CvType.CV_64F);
        sat = new double[(w + 1) * (h + 1)];
        integral.get(0, 0, sat);
        integral.release();
    }

    private double boxMean(double x0, double y0, double x1, double y1) {
        int ax = clamp((int) Math.round(x0), 0, w);
        int ay = clamp((int) Math.round(y0), 0, h);
        int bx = clamp((int) Math.round(x1), 0, w);
        int by = clamp((int) Math.round(y1), 0, h);
        if (bx <= ax || by <= ay) {
            return Double.NaN;
        }
        int stride = w + 1;
        double sum = sat[by * stride + bx] - sat[ay * stride + bx] - sat[by * stride + ax] + sat[ay * stride + ax];
        return sum / ((double) (bx - ax) * (by - ay));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Checker score of a grid; 0 when it does not fit in the image. */
    double score(double x, double y, double cell) {
        if (x < -0.5 || y < -0.5 || x + 8 * cell > w + 0.5 || y + 8 * cell > h + 0.5 || cell < 4) {
            return 0;
        }
        double p0 = 0.06 * cell;
        double p1 = 0.20 * cell;
        double sumL = 0;
        double sumD = 0;
        double sqL = 0;
        double sqD = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                double cx = x + c * cell;
                double cy = y + r * cell;
                double v = (boxMean(cx + p0, cy + p0, cx + p1, cy + p1)
                        + boxMean(cx + cell - p1, cy + p0, cx + cell - p0, cy + p1)
                        + boxMean(cx + p0, cy + cell - p1, cx + p1, cy + cell - p0)
                        + boxMean(cx + cell - p1, cy + cell - p1, cx + cell - p0, cy + cell - p0)) / 4.0;
                if (Double.isNaN(v)) {
                    return 0;
                }
                if (((r + c) & 1) == 0) {
                    sumL += v;
                    sqL += v * v;
                } else {
                    sumD += v;
                    sqD += v * v;
                }
            }
        }
        double mL = sumL / 32;
        double mD = sumD / 32;
        double varL = Math.max(0, sqL / 32 - mL * mL);
        double varD = Math.max(0, sqD / 32 - mD * mD);
        return Math.abs(mL - mD) / Math.sqrt((varL + varD) / 2 + 4.0); // +4: noise floor (std 2 grey levels)
    }

    /**
     * Best grid with the top-left corner within {@code [x0, x1] x [y0, y1]} and square size within
     * {@code [cellMin, cellMax]}: coarse search with a 2-pixel step, then a 1/4-pixel refinement.
     */
    Grid search(double x0, double x1, double y0, double y1, double cellMin, double cellMax) {
        Grid best = new Grid(0, 0, 0, 0);
        double step = Math.max(1, (cellMax - cellMin) / 40);
        double posStep = Math.max(1, Math.min(4, cellMin / 12));
        for (double cell = cellMin; cell <= cellMax; cell += step) {
            for (double y = y0; y <= y1; y += posStep) {
                for (double x = x0; x <= x1; x += posStep) {
                    double s = score(x, y, cell);
                    if (s > best.score()) {
                        best = new Grid(x, y, cell, s);
                    }
                }
            }
        }
        if (best.score() <= 0) {
            return best;
        }
        // refinement
        Grid refined = best;
        for (double cell = best.cell() - step; cell <= best.cell() + step; cell += step / 4) {
            for (double y = best.y() - posStep; y <= best.y() + posStep; y += 0.5) {
                for (double x = best.x() - posStep; x <= best.x() + posStep; x += 0.5) {
                    double s = score(x, y, cell);
                    if (s > refined.score()) {
                        refined = new Grid(x, y, cell, s);
                    }
                }
            }
        }
        return refined;
    }

    /** Refines an approximate board rectangle (e.g. a loose crop or the network's box). */
    Grid refine(Rectangle approx, double tolerance) {
        double cell = Math.min(approx.width, approx.height) / 8.0;
        double d = tolerance * approx.width;
        return search(approx.x - d, approx.x + d, approx.y - d, approx.y + d, cell * (1 - tolerance),
                cell * (1 + tolerance));
    }
}
