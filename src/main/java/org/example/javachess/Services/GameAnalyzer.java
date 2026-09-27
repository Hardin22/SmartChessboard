package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveGeneratorException;
import org.example.javachess.Oggetti.AnalysisResult;
import org.example.javachess.Oggetti.MoveAnalysis;
import org.example.javachess.Oggetti.MoveAnalysis.MoveClassification;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.function.Consumer;

public class GameAnalyzer {

    private final StockfishAnalyzer stockfishAnalyzer;

    public GameAnalyzer() {
        this.stockfishAnalyzer = new StockfishAnalyzer();
    }

    public List<MoveAnalysis> analyzeGame(String pgn, int depth, Consumer<Double> progressCallback) {
        List<MoveAnalysis> analysisList = new ArrayList<>();
        Board board = new Board();

        List<String> moveStrs = parsePgnMoves(pgn);
        int totalMoves = moveStrs.size();

        board.loadFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");

        for (int i = 0; i < totalMoves; i++) {
            String moveStr = moveStrs.get(i);
            String fenBefore = board.getFen();
            Side sideToMove = board.getSideToMove();

            // 1. Check Forced Move
            boolean isForced = isForcedMove(board);

            // 2. Check Book Move
            boolean isBook = isBookMove(fenBefore, moveStr);

            // 3. Run Stockfish Analysis
            List<AnalysisResult> stockfishResults = stockfishAnalyzer.analyze(fenBefore, depth, 3);

            AnalysisResult bestEval = getResultByPv(stockfishResults, 1);
            AnalysisResult secondBestEval = getResultByPv(stockfishResults, 2);

            Move playedMove = parseMove(moveStr, board);
            if (playedMove == null) {
                System.err.println("Failed to parse move: " + moveStr);
                continue;
            }

            AnalysisResult playedEval = findEvalForMove(stockfishResults, playedMove);

            double playedScoreVal;
            if (playedEval != null) {
                playedScoreVal = playedEval.score;
            } else {
                board.doMove(playedMove);
                String fenAfter = board.getFen();
                List<AnalysisResult> afterResults = stockfishAnalyzer.analyze(fenAfter, depth, 1);
                if (!afterResults.isEmpty()) {
                    playedScoreVal = afterResults.get(0).score;
                } else {
                    playedScoreVal = bestEval != null ? bestEval.score - 1.0 : 0.0;
                }
                board.undoMove();
            }

            // --- CALCOLO MATERIALE E IMBALANCE ---
            // Calcoliamo se siamo sotto di materiale PRIMA della mossa (per capire se è una ricattura/difesa)
            int rawImbalance = getMaterialImbalance(board);
            int myImbalance = (sideToMove == Side.WHITE) ? rawImbalance : -rawImbalance;
            // --- CALCOLO CP (MATERIALE) ---
            double bestCp = bestEval != null ? bestEval.score * 100 : 0;
            double playedCp = playedScoreVal * 100;
            
            if (bestEval != null && bestEval.isMate) bestCp = bestEval.mateIn > 0 ? 10000 : -10000;
            if (playedEval != null && playedEval.isMate) playedCp = playedEval.mateIn > 0 ? 10000 : -10000;

            double secondBestCpRaw = secondBestEval != null ? secondBestEval.score * 100 : -10000;
            if (secondBestEval != null && secondBestEval.isMate) secondBestCpRaw = secondBestEval.mateIn > 0 ? 10000 : -10000;

            if (sideToMove == Side.BLACK) {
                bestCp = -bestCp;
                playedCp = -playedCp;
                secondBestCpRaw = -secondBestCpRaw; 
            }
            double secondBestCp = secondBestCpRaw;

            // --- CALCOLO WP (STATISTICA) ---
            double bestWp = calculateWinProbability(bestCp);
            double playedWp = calculateWinProbability(playedCp);
            double secondBestWp = calculateWinProbability(secondBestCp);
            
            double deltaWp = bestWp - playedWp;
            
            // GAPS
            double wpGap = bestWp - secondBestWp;    // Gap Posizionale
            double cpGap = bestCp - secondBestCp;     // Gap Materiale

            // Check Sacrifice / Capture
            boolean isSacrifice = isSacrifice(board, playedMove, playedEval, sideToMove);
            boolean isCapture = isCapture(board, playedMove);

            MoveClassification classification = null;

            if (isBook) {
                classification = MoveClassification.BOOK_MOVE;
            } else if (isForced) {
                classification = MoveClassification.FORCED;
            }

            // 1. BRILLIANT (!!)
            if (classification == null) {
                boolean isCloseToBest = deltaWp < 0.03; 
                boolean isTop3 = isMoveInTopX(stockfishResults, playedMove, 3);
                
                if (isSacrifice && isCloseToBest && isTop3 && playedWp > 0.55) {
                    classification = MoveClassification.BRILLIANT;
                    logMove(moveStr, bestWp, playedWp, deltaWp, classification);
                }
            }

            // 2. GREAT MOVE (!) - LOGICA MATERIALE DEFINITIVA
            if (classification == null) {
                
                // LOGICA RECOVERY:
                // Se eravamo sotto di materiale (<-50, es. meno di un pedone) E stiamo catturando,
                // stiamo probabilmente "Ricatturando" o "Recuperando".
                boolean isRecovery = (myImbalance < -50) && isCapture;

                // Una mossa di recupero è "Great" SOLO SE ci porta in vantaggio netto (> 0.60).
                // Se ci riporta solo in parità (es. 0.50), è una "Boring Trade" -> BEST.
                boolean isWinning = playedWp > 0.60;

                // CRITERIO A: Gap Posizionale
                boolean positionalGreat = (wpGap > 0.08) && (playedWp > 0.50);
                
                // CRITERIO B: Gap Materiale (Pezzo Gratis)
                // Se guadagniamo > 200cp rispetto alla seconda mossa, è un pezzo gratis.
                boolean materialGreat = (cpGap > 200) && isCapture;

                // FILTRO FONDAMENTALE:
                // Se è una Recovery (stavo perdendo materiale) E non sto vincendo la partita,
                // allora è solo uno scambio necessario -> BEST.
                if (isRecovery && !isWinning) {
                    // Force BEST for standard trades/recaptures
                    classification = MoveClassification.BEST;
                } 
                else if (deltaWp < 0.02) {
                    // Se non è bloccato dal filtro sopra, verifichiamo se è Great
                    if (positionalGreat || materialGreat) {
                        // Filtro Severità finale per posizioni già vinte
                        if (bestWp > 0.98 && wpGap < 0.15 && !materialGreat) {
                             classification = MoveClassification.BEST;
                        } else {
                             classification = MoveClassification.GREAT;
                             logMove(moveStr, bestWp, playedWp, deltaWp, classification);
                        }
                    }
                }
            }

            // 3. MISS (X)
            if (classification == null) {
                boolean missedPositional = wpGap > 0.08;
                boolean missedMaterial = cpGap > 200;
                
                if ((missedPositional || missedMaterial) && deltaWp > 0.09 && bestWp > 0.50) {
                    classification = MoveClassification.MISS;
                    logMove(moveStr, bestWp, playedWp, deltaWp, classification);
                }
            }

            // 4. STANDARD LABELS
            if (classification == null) {
                classification = classifyStandard(deltaWp);
                if (classification == MoveClassification.BLUNDER && bestWp < 0.1) {
                    classification = MoveClassification.INACCURACY;
                }
                logMove(moveStr, bestWp, playedWp, deltaWp, classification);
            }

            MoveAnalysis analysis = new MoveAnalysis(
                    i + 1,
                    moveStr,
                    fenBefore,
                    playedScoreVal * 100,
                    bestEval != null ? bestEval.bestMove : "",
                    classification,
                    deltaWp * 100,
                    playedMove.getTo().ordinal(),
                    bestEval != null && bestEval.isMate,
                    100 * (1 - deltaWp),
                    playedWp,
                    bestWp);

            analysisList.add(analysis);

            board.doMove(playedMove);
            
            if (progressCallback != null) {
                progressCallback.accept((double) (i + 1) / totalMoves);
            }
        }

        return analysisList;
    }

    private MoveClassification classifyStandard(double deltaWp) {
        if (deltaWp < 0.005) return MoveClassification.BEST;
        if (deltaWp < 0.02) return MoveClassification.EXCELLENT;
        if (deltaWp < 0.05) return MoveClassification.GOOD;
        if (deltaWp < 0.10) return MoveClassification.INACCURACY;
        if (deltaWp < 0.20) return MoveClassification.MISTAKE;
        return MoveClassification.BLUNDER;
    }

    private double calculateWinProbability(double cp) {
        if (cp > 9000) return 1.0;
        if (cp < -9000) return 0.0;
        return 1.0 / (1.0 + Math.pow(10, -cp / 400.0));
    }

    private boolean isForcedMove(Board board) {
        try {
            return MoveGenerator.generateLegalMoves(board).size() == 1;
        } catch (MoveGeneratorException e) {
            return false;
        }
    }

    private boolean isBookMove(String fen, String moveUci) {
        try {
            URL url = new URL("https://explorer.lichess.ovh/masters?fen=" + fen.replace(" ", "%20"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);

            if (conn.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();
                String json = content.toString();
                String searchKey = "\"uci\":\"" + moveUci + "\"";
                int moveIndex = json.indexOf(searchKey);
                if (moveIndex == -1) return false;
                String afterMove = json.substring(moveIndex);
                int closeBrace = afterMove.indexOf("}");
                if (closeBrace == -1) return false;
                String moveData = afterMove.substring(0, closeBrace);
                int white = extractCount(moveData, "\"white\":");
                int draws = extractCount(moveData, "\"draws\":");
                int black = extractCount(moveData, "\"black\":");
                int totalGames = white + draws + black;
                return totalGames >= 10;
            }
        } catch (Exception e) { return false; }
        return false;
    }

    private int extractCount(String data, String key) {
        try {
            int start = data.indexOf(key);
            if (start == -1) return 0;
            start += key.length();
            int end = start;
            while (end < data.length() && Character.isDigit(data.charAt(end))) { end++; }
            return Integer.parseInt(data.substring(start, end));
        } catch (Exception e) { return 0; }
    }

    private boolean isCapture(Board board, Move playedMove) {
        return board.getPiece(playedMove.getTo()) != com.github.bhlangonijr.chesslib.Piece.NONE;
    }

    private boolean isMoveInTopX(List<AnalysisResult> results, Move playedMove, int x) {
        for (int i = 0; i < Math.min(results.size(), x); i++) {
            if (results.get(i).bestMove.equals(playedMove.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSacrifice(Board board, Move playedMove, AnalysisResult playedEval, Side side) {
        if (board.isKingAttacked()) return false;
        if (playedEval == null || playedEval.fullPv == null) return false;
        
        int balanceT0 = getMaterialBalance(board, side);
        board.doMove(playedMove);

        boolean pvSacrifice = false;
        if (playedEval != null && playedEval.fullPv != null) {
            String[] pvMoves = playedEval.fullPv.split(" ");
            if (pvMoves.length > 1) {
                String oppResponseStr = pvMoves[1];
                Move oppResponse = parseMove(oppResponseStr, board);
                if (oppResponse != null) {
                    board.doMove(oppResponse);
                    int balanceT1 = getMaterialBalance(board, side);
                    boolean isTrade = false;
                    if (pvMoves.length > 2) {
                        String recaptureStr = pvMoves[2];
                        Move recapture = parseMove(recaptureStr, board);
                        if (recapture != null) {
                            board.doMove(recapture);
                            int balanceT2 = getMaterialBalance(board, side);
                            if (balanceT2 >= balanceT0) isTrade = true;
                            board.undoMove();
                        }
                    }
                    board.undoMove(); 
                    if ((balanceT0 - balanceT1 > 1) && !isTrade) pvSacrifice = true;
                }
            }
        }
        if (pvSacrifice) { board.undoMove(); return true; }

        boolean offeredSacrifice = false;
        try {
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            for (Move move : legalMoves) {
                if (move.getTo() == playedMove.getTo()) {
                    board.doMove(move);
                    int balanceT1 = getMaterialBalance(board, side);
                    boolean canRecapture = false;
                    List<Move> ourReplies = MoveGenerator.generateLegalMoves(board);
                    for (Move reply : ourReplies) {
                        if (reply.getTo() == playedMove.getTo()) { canRecapture = true; break; }
                    }
                    board.undoMove(); 
                    if ((balanceT0 - balanceT1 > 1) && !canRecapture) { offeredSacrifice = true; break; }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        board.undoMove(); 
        return offeredSacrifice;
    }

    // NUOVO METODO: Calcola sbilanciamento materiale (White - Black)
    private int getMaterialImbalance(Board board) {
        int balance = 0;
        for (Square sq : Square.values()) {
            Piece p = board.getPiece(sq);
            if (p != Piece.NONE) {
                int val = 0;
                switch (p.getPieceType()) {
                    case PAWN: val = 100; break;
                    case KNIGHT: val = 300; break;
                    case BISHOP: val = 320; break;
                    case ROOK: val = 500; break;
                    case QUEEN: val = 900; break;
                    default: break;
                }
                if (p.getPieceSide() == Side.WHITE) balance += val;
                else balance -= val;
            }
        }
        return balance;
    }

    private int getMaterialBalance(Board board, Side side) {
        int myMaterial = 0;
        int oppMaterial = 0;
        Side oppSide = side.flip();
        for (Square sq : Square.values()) {
            Piece p = board.getPiece(sq);
            if (p != Piece.NONE) {
                int val = 0;
                switch (p.getPieceType()) {
                    case PAWN: val = 100; break;
                    case KNIGHT: val = 300; break;
                    case BISHOP: val = 320; break;
                    case ROOK: val = 500; break;
                    case QUEEN: val = 900; break;
                    default: break;
                }
                if (p.getPieceSide() == side) myMaterial += val;
                else oppMaterial += val;
            }
        }
        return myMaterial - oppMaterial;
    }

    private List<String> parsePgnMoves(String pgn) {
        List<String> moves = new ArrayList<>();
        String[] tokens = pgn.split("\\s+");
        for (String token : tokens) {
            if (!token.matches("\\d+\\.") && !token.matches("1-0|0-1|1/2-1/2")) {
                moves.add(token);
            }
        }
        return moves;
    }

    private Move parseMove(String moveStr, Board board) {
        try {
            Square from = Square.valueOf(moveStr.substring(0, 2).toUpperCase());
            Square to = Square.valueOf(moveStr.substring(2, 4).toUpperCase());
            if (moveStr.length() == 5) {
                Piece promo = Piece.NONE;
                Side side = board.getSideToMove();
                switch (moveStr.charAt(4)) {
                    case 'q': promo = side == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN; break;
                    case 'r': promo = side == Side.WHITE ? Piece.WHITE_ROOK : Piece.BLACK_ROOK; break;
                    case 'b': promo = side == Side.WHITE ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP; break;
                    case 'n': promo = side == Side.WHITE ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT; break;
                }
                return new Move(from, to, promo);
            }
            return new Move(from, to);
        } catch (Exception e) { return null; }
    }

    private AnalysisResult getResultByPv(List<AnalysisResult> results, int pv) {
        if (results.size() >= pv) return results.get(pv - 1);
        return null;
    }

    private AnalysisResult findEvalForMove(List<AnalysisResult> results, Move move) {
        for (AnalysisResult res : results) {
            if (res.bestMove.equals(move.toString())) return res;
        }
        return null;
    }

    public double calculateAccuracy(List<MoveAnalysis> analysis, boolean isWhite) {
        double sumSquaredAcc = 0;
        int validMoves = 0;

        for (MoveAnalysis move : analysis) {
            boolean isWhiteMove = (move.getMoveNumber() % 2) != 0;
            if (isWhiteMove != isWhite) continue;

            if (move.getClassification() == MoveClassification.BOOK_MOVE || 
                move.getClassification() == MoveClassification.FORCED) {
                continue;
            }

            double wpPrev = move.getBestWp();
            double wpPost = move.getWinProbability();
            double deltaWp = Math.max(0, wpPrev - wpPost);

            // Severity Factor k=9.0
            double accM = 100 * Math.exp(-9.0 * deltaWp);

            // Winning Position Exception
            if (wpPrev > 0.98 && wpPost > 0.95) {
                accM = 100.0;
            }

            sumSquaredAcc += accM * accM;
            validMoves++;
        }

        if (validMoves == 0) return 0;
        double rmsAccuracy = Math.sqrt(sumSquaredAcc / validMoves);
        return Math.round(rmsAccuracy * 10.0) / 10.0;
    }

    private void logMove(String move, double wpPrev, double wpPost, double delta, MoveClassification label) {
        System.out.printf("Mossa %s | WP Prev %.2f | WP Post %.2f | Delta %.3f | Label %s%n", 
            move, wpPrev, wpPost, delta, label);
    }
}