package org.example.javachess.Services;

import org.example.javachess.Controllers.ArduinoController;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.Side;
import javafx.application.Platform;

public class GameSyncManager implements BoardStateManager.BoardMoveListener {
    
    private VisionService visionService;
    private ArduinoController arduinoController;
    private BoardStateManager boardStateManager;
    private Board internalBoard;
    private boolean isMyTurn = true; // Assumption: Start as White or detect from FEN
    
    public GameSyncManager(VisionService visionService, ArduinoController arduinoController) {
        this.visionService = visionService;
        this.arduinoController = arduinoController;
        this.boardStateManager = arduinoController.getBoardStateManager();
        this.internalBoard = new Board();
        
        // Listen to Vision
        this.visionService.setOnFenChanged(this::handleVisionFenChange);
        
        // Listen to Physical Board
        this.boardStateManager.setListener(this);
    }
    
    // --- Vision (Screen) -> Physical ---
    private void handleVisionFenChange(String newFen) {
        System.out.println("[Sync] Vision FEN: " + newFen);
        
        // Check if FEN matches internal board
        // We need to be careful about FEN formatting (en passant, counters)
        // Let's compare piece placement mainly.
        
        if (isFenEqual(internalBoard.getFen(), newFen)) {
            return;
        }
        
        // Try to find the move that led to this FEN
        Move detectedMove = findMove(internalBoard, newFen);
        
        if (detectedMove != null) {
            System.out.println("[Sync] Detected Move on Screen: " + detectedMove);
            
            // Update Internal Board
            internalBoard.doMove(detectedMove);
            
            // If it was NOT my turn (so it was Opponent's turn), trigger replication
            if (!isMyTurn) {
                System.out.println("[Sync] Opponent Moved! Triggering Replication.");
                String from = detectedMove.getFrom().name();
                String to = detectedMove.getTo().name();
                boardStateManager.startBotMoveReplication(from, to);
                isMyTurn = true; // Now it's my turn
            } else {
                // It was my turn. This means *I* moved (physically -> bot -> screen).
                // Vision just confirmed it.
                System.out.println("[Sync] My move confirmed by Vision.");
                isMyTurn = false; // Now it's opponent's turn
            }
        } else {
            // FEN mismatch but no legal move found? 
            // Maybe game started/reset or vision error.
            // For now, just sync internal board to new FEN to recover.
            System.out.println("[Sync] FEN mismatch (No move found). Resyncing internal board.");
            internalBoard.loadFromFen(newFen);
            
            // Update turn based on FEN
            isMyTurn = internalBoard.getSideToMove() == Side.WHITE; // Assuming we play White for now
            // TODO: Detect our color properly
        }
    }
    
    // --- Physical -> Vision (Screen) ---
    @Override
    public void onPhysicalMoveDetected(String fromSquare, String toSquare) {
        if (!isMyTurn) {
            System.out.println("[Sync] Ignored physical move (Not my turn).");
            return;
        }
        
        String uciMove = fromSquare.toLowerCase() + toSquare.toLowerCase();
        System.out.println("[Sync] Physical Move Detected: " + uciMove);
        
        // Execute on Screen
        // visionService.executeMove(uciMove); // DISABLED: VisionService no longer handles moves. Use BrowserController.
        System.err.println("[GameSyncManager] executeMove is disabled. Use BrowserController for online play.");
        
        // Note: We don't update internalBoard here immediately. 
        // We wait for Vision to confirm the move on screen to keep sync robust.
        // OR we can update it optimistically. 
        // Let's wait for Vision (handleVisionFenChange will handle the turn switch).
    }

    @Override
    public void onBoardSetupComplete() {}

    @Override
    public void onSetupProgress(String message) {}

    @Override
    public void onBoardStateUpdated(String fen, String errorSquare) {}

    @Override
    public void onBotMoveReplicated() {
        System.out.println("[Sync] Bot Move Replicated Physically.");
        // Opponent move is fully done. We are ready for user move.
    }
    
    // --- Helpers ---
    
    private boolean isFenEqual(String fen1, String fen2) {
        // Simple comparison of the first part (piece placement)
        String p1 = fen1.split(" ")[0];
        String p2 = fen2.split(" ")[0];
        return p1.equals(p2);
    }
    
    private Move findMove(Board board, String targetFen) {
        String targetPlacement = targetFen.split(" ")[0];
        
        for (Move move : board.legalMoves()) {
            Board clone = board.clone();
            clone.doMove(move);
            if (clone.getFen().split(" ")[0].equals(targetPlacement)) {
                return move;
            }
        }
        return null;
    }
}
