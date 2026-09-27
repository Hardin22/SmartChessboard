package org.example.javachess.Vision;

import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;

public class BoardDetector {

    static {
        // Load OpenCV native library
        try {
            OpenCV.loadLocally();
            System.out.println("[Vision] OpenCV loaded successfully.");
        } catch (Throwable e) {
            System.err.println("[Vision] Failed to load OpenCV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Rectangle detectBoard() {
        try {
            System.out.println("[Vision] Starting Board Detection...");
            
            // 1. Capture Screen
            Robot robot = new Robot();
            java.awt.Rectangle screenRect = new java.awt.Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screenCapture = robot.createScreenCapture(screenRect);
            
            // 2. Convert to Mat
            Mat src = bufferedImageToMat(screenCapture);
            Mat gray = new Mat();
            Mat edges = new Mat();
            
            // 3. Pre-processing
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);
            Imgproc.Canny(gray, edges, 50, 150);
            
            // 4. Find Contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
            
            // 5. Filter for Chessboard
            Rect bestRect = null;
            double maxArea = 0;
            
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                
                // Minimum size filter (e.g. 400x400 = 160000)
                if (area < 100000) continue; 
                
                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                double peri = Imgproc.arcLength(contour2f, true);
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true);
                
                if (approx.total() == 4) {
                    Rect rect = Imgproc.boundingRect(contour);
                    double aspectRatio = (double) rect.width / rect.height;
                    
                    // Square-ish filter
                    if (aspectRatio >= 0.90 && aspectRatio <= 1.10) {
                        if (area > maxArea) {
                            maxArea = area;
                            bestRect = rect;
                        }
                    }
                }
            }
            
            if (bestRect != null) {
                System.out.println("[Vision] Board Found: " + bestRect.toString());
                
                // Draw debug
                Imgproc.rectangle(src, bestRect, new Scalar(0, 255, 0), 3);
                Imgcodecs.imwrite("board_debug.png", src);
                
                return new Rectangle(bestRect.x, bestRect.y, bestRect.width, bestRect.height);
            } else {
                System.err.println("[Vision] No board found!");
                Imgcodecs.imwrite("board_debug_failed.png", src);
                return null;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    private Mat bufferedImageToMat(BufferedImage bi) {
        Mat mat;
        if (bi.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage converted = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            converted.getGraphics().drawImage(bi, 0, 0, null);
            bi = converted;
        }
        mat = new Mat(bi.getHeight(), bi.getWidth(), CvType.CV_8UC3);
        byte[] data = ((DataBufferByte) bi.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, data);
        return mat;
    }
    
    // Main for testing
    public static void main(String[] args) {
        BoardDetector detector = new BoardDetector();
        Rectangle rect = detector.detectBoard();
        if (rect != null) {
            System.out.println("Success! Board at: " + rect);
        }
    }
}
