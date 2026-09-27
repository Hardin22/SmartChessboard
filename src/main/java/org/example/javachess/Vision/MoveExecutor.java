package org.example.javachess.Vision;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;

public class MoveExecutor {

    private Robot robot;

    public MoveExecutor() {
        try {
            this.robot = new Robot();
            this.robot.setAutoDelay(10); // Small delay for realism
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void makeMove(String from, String to, Rectangle boardRect) {
        if (boardRect == null) {
            System.err.println("[MoveExecutor] Cannot move: Board not detected.");
            return;
        }
        
        Point p1 = getSquareCenter(from, boardRect);
        Point p2 = getSquareCenter(to, boardRect);
        
        System.out.println("[MoveExecutor] Moving " + from + " -> " + to);
        
        // 1. Move to Source
        robot.mouseMove(p1.x, p1.y);
        robot.delay(50);
        
        // 2. Click and Hold
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(50);
        
        // 3. Drag to Dest
        // Optional: Smooth drag? For now, direct jump is faster/simpler
        robot.mouseMove(p2.x, p2.y);
        robot.delay(50);
        
        // 4. Release
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(50);
        
        // 5. Move away (to avoid hover effects)
        robot.mouseMove(0, 0);
    }
    
    private Point getSquareCenter(String square, Rectangle boardRect) {
        // Square: "e2" -> file 'e', rank '2'
        char fileChar = square.charAt(0); // 'a'-'h'
        char rankChar = square.charAt(1); // '1'-'8'
        
        int file = fileChar - 'a'; // 0-7
        int rank = 8 - (rankChar - '0'); // 0-7 (Rank 8 is index 0 in visual grid)
        
        double cellW = boardRect.width / 8.0;
        double cellH = boardRect.height / 8.0;
        
        int x = (int) (boardRect.x + (file * cellW) + (cellW / 2));
        int y = (int) (boardRect.y + (rank * cellH) + (cellH / 2));
        
        return new Point(x, y);
    }
    
    static class Point {
        int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
    }
}
