package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import org.example.javachess.Oggetti.AnalysisResult;
import org.example.javachess.Oggetti.MoveAnalysis;
import org.example.javachess.Oggetti.Stockfish;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GameAnalyzer {

    private final Stockfish stockfish;

    public GameAnalyzer() {
        this.stockfish = StockfishService.getInstance();
    }

    public List<MoveAnalysis> analyzeGame(String pgn, int depth, Consumer<Double> progressCallback) {
        List<MoveAnalysis> analysis = new ArrayList<>();
        Board board = new Board();
        
        // Load PGN to get moves
        // Assuming PGN is just the move text or we parse it. 
        // If PGN is full string, we might need a parser. 
        // For now, let's assume we can replay the game.
        // Actually, simpler: Replay the game move by move.
        
        // If pgn is a raw string of moves, we can try to load it into a MoveList
        MoveList moves = new MoveList();
        try {
            String cleanedPgn = cleanPgn(pgn);
            moves.loadFromSan(cleanedPgn);
        } catch (Exception e) {
            // Fallback: try to load into board and extract moves if possible, 
            // or assume pgn is already formatted.
            // If pgn is full PGN text, we need to parse it.
            // Let's assume the caller passes a valid PGN string that chesslib can handle,
            // or we might need to adjust this to take a list of moves.
            e.printStackTrace();
            return analysis;
        }

        // Initial position analysis
        // We need this to have a baseline 'previousScore' for the first move's CPL calculation.
        AnalysisResult initialEval = stockfish.getEvaluation(board.getFen(), depth);
        double previousScore = initialEval.score;
        boolean previousWasMate = initialEval.isMate;
        String previousBestMove = initialEval.bestMove;

        int totalMoves = moves.size();
        for (int i = 0; i < totalMoves; i++) {
            Move move = moves.get(i);
            String san = move.toString();
            String previousFen = board.getFen();
            
            // Make the move
            board.doMove(move);
            String fen = board.getFen();
            
            // Analyze position AFTER the move
            AnalysisResult result = stockfish.getEvaluation(fen, 18);
            double currentScore = result.score;
            
            // Calculate CPL
            double cpl;
            boolean isWhiteMove = (i % 2 == 0);
            
            if (isWhiteMove) {
                // White moved. Previous score (best achievable) - Current score (actual).
                cpl = previousScore - currentScore;
            } else {
                // Black moved. Previous score (best achievable for Black, i.e. lowest) - Current score.
                // If prev was +0.5, and current is +0.8 (worse for black), CPL = 0.8 - 0.5 = 0.3.
                cpl = currentScore - previousScore;
            }
            
            if (cpl < 0) cpl = 0; 

            // Check for Book Move
            boolean isBookMove = false;
            if (i < 30) {
                isBookMove = stockfish.isBookMove(previousFen, move.toString());
            }

            MoveAnalysis.MoveClassification classification;
            if (isBookMove) {
                classification = MoveAnalysis.MoveClassification.BOOK_MOVE;
            } else {
                classification = classifyMove(cpl, previousScore, currentScore, result.isMate, previousWasMate, isWhiteMove);
            }

            analysis.add(new MoveAnalysis(
                i + 1,
                san,
                fen,
                currentScore,
                previousBestMove, // The best move was from the PREVIOUS position
                classification,
                cpl,
                move.getTo().ordinal(),
                result.isMate
            ));

            previousScore = currentScore;
            previousWasMate = result.isMate;
            previousBestMove = result.bestMove; // For the next move, this is the best move
            
            // Update progress
            if (progressCallback != null) {
                progressCallback.accept((double) (i + 1) / totalMoves);
            }
        }

        return analysis;
    }

    private String cleanPgn(String pgn) {
        if (pgn == null) return "";
        System.out.println("Original PGN: " + pgn);
        
        // Remove headers
        String cleaned = pgn.replaceAll("\\[.*?\\]", "");
        
        // Remove comments
        cleaned = cleaned.replaceAll("\\{.*?\\}", "");
        
        // Remove common non-standard result strings (Case Insensitive)
        cleaned = cleaned.replaceAll("(?i)Partita interrotta", "")
                         .replaceAll("(?i)Vittoria", "")
                         .replaceAll("(?i)Sconfitta", "")
                         .replaceAll("(?i)Pareggio", "")
                         .replaceAll("(?i)Patta", "")
                         .replaceAll("(?i)Stallo", "")
                         .replaceAll("(?i)Abbandono", "")
                         .replaceAll("(?i)Tempo scaduto", "")
                         .replaceAll("(?i)Scaccomatto.*", "") // Remove Scaccomatto and anything after
                         .replaceAll("(?i)Vince.*", "") // Remove Vince and anything after
                         .replaceAll("(?i)Partita.*", "") // Catch-all for "Partita ..."
                         .trim();
                         
        System.out.println("Cleaned PGN: " + cleaned);
        return cleaned;
    }

    private MoveAnalysis.MoveClassification classifyMove(double cpl, double bestScore, double currentScore, boolean isMate, boolean wasMate, boolean isWhiteMove) {
        // Perspective adjustment for scores
        // bestScore, secondBestScore, currentScore are all from White's perspective (if using my Stockfish wrapper)
        // We need to adjust for the side moving to compare differences correctly.
        
        double bestScoreSide = isWhiteMove ? bestScore : -bestScore;
        double currentScoreSide = isWhiteMove ? currentScore : -currentScore;
        
        // 1. Handle Mate Cases
        if (wasMate) {
            if (isMate) return MoveAnalysis.MoveClassification.BEST; 
            else return MoveAnalysis.MoveClassification.BLUNDER; 
        }
        if (isMate) return MoveAnalysis.MoveClassification.BEST; 

        // 2. Check for Missed Win
        // Winning before (> 3.0), dropped significantly (> 1.5 drop), but still winning (> 0.5)
        if (bestScoreSide > 3.0) {
            if (currentScoreSide < bestScoreSide - 1.5 && currentScoreSide > 0.5) {
                return MoveAnalysis.MoveClassification.MISSED_WIN;
            }
        }

        // 3. Handle Winning Positions (Context-Aware) - Lenient
        if (bestScoreSide > 5.0) {
            if (currentScoreSide > 3.5) return MoveAnalysis.MoveClassification.GOOD; 
            if (currentScoreSide > 1.0) return MoveAnalysis.MoveClassification.INACCURACY; 
            return MoveAnalysis.MoveClassification.BLUNDER; 
        }
        // The block above (bestScoreSide > 5.0) handles "Current side was winning".

        // 4. Standard CPL Thresholds
        if (cpl <= 0.05) return MoveAnalysis.MoveClassification.BEST;       
        if (cpl <= 0.20) return MoveAnalysis.MoveClassification.EXCELLENT;  
        if (cpl <= 0.50) return MoveAnalysis.MoveClassification.GOOD;       
        if (cpl <= 0.90) return MoveAnalysis.MoveClassification.INACCURACY; 
        if (cpl <= 2.00) return MoveAnalysis.MoveClassification.MISTAKE;    
        return MoveAnalysis.MoveClassification.BLUNDER;                     
    }
}
