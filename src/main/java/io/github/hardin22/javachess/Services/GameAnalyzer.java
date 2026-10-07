package io.github.hardin22.javachess.Services;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveGeneratorException;
import io.github.hardin22.javachess.Engine.EngineManager;
import io.github.hardin22.javachess.Engine.InfoLine;
import io.github.hardin22.javachess.Engine.MoveClassifier;
import io.github.hardin22.javachess.Engine.OpeningExplorer;
import io.github.hardin22.javachess.Engine.Score;
import io.github.hardin22.javachess.Engine.SearchLimits;
import io.github.hardin22.javachess.Engine.SearchResult;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis;
import io.github.hardin22.javachess.Oggetti.MoveAnalysis.MoveClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Full game review: move labels (book, forced, brilliant, great, miss, best ... blunder) and accuracy.
 *
 * <p>Engine use: every position of the game is searched once (MultiPV 3) on the shared review engine of
 * {@link EngineManager}; the score after a move outside the top 3 comes from the search of the next position
 * (same depth), so a game of N moves costs N+1 searches instead of up to 2N, and no process is spawned per
 * review. Scores are kept from the mover's point of view (fixes the old sign error on Black's mates).</p>
 */
public class GameAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GameAnalyzer.class);
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final int MULTI_PV = 3;
    private static final int BOOK_MAX_PLY = 24;

    /** One searched line, mover's point of view. */
    private record Eval(Score score, String move, List<String> pv) {
        double cp() {
            return score.centipawns();
        }
    }

    public GameAnalyzer() {
        // engines are owned by EngineManager: nothing to start here
    }

    public List<MoveAnalysis> analyzeGame(String pgn, int depth, Consumer<Double> progressCallback) {
        return analyzeGame(pgn, START_FEN, depth, progressCallback);
    }

    /** Analyses a game from {@code initialFen} given as a list of UCI moves. Blocking. */
    public List<MoveAnalysis> analyzeGame(String initialFen, List<String> uciMoves, int depth,
                                          Consumer<Double> progressCallback) {
        return analyzeGame(String.join(" ", uciMoves), initialFen, depth, progressCallback);
    }

    /**
     * Analyses a game given as UCI moves (tokens like "1." and results are ignored).
     * Blocking: call it from a background thread.
     */
    public List<MoveAnalysis> analyzeGame(String pgn, String initialFen, int depth, Consumer<Double> progressCallback) {
        List<MoveAnalysis> analysisList = new ArrayList<>();
        Board board = new Board();
        board.loadFromFen(initialFen == null || initialFen.isBlank() ? START_FEN : initialFen);

        // 1. Replay the game to collect positions (stops at the first unreadable/illegal token)
        List<String> moveStrs = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        List<String> fens = new ArrayList<>();
        fens.add(board.getFen());
        for (String token : parsePgnMoves(pgn)) {
            Move m = parseMove(token, board);
            if (m == null || !board.legalMoves().contains(m)) {
                log.warn("review: stopping at unreadable move '{}'", token);
                break;
            }
            board.doMove(m);
            moveStrs.add(token);
            moves.add(m);
            fens.add(board.getFen());
        }
        int totalMoves = moves.size();
        if (totalMoves == 0) {
            return analysisList;
        }

        // 2. Search every position once (N+1 searches)
        List<List<Eval>> evals = new ArrayList<>();
        EngineManager manager = EngineManager.get();
        PositionAnalyzer engine = manager.analyzer();
        long t0 = System.nanoTime();
        // The review shares the analysis process: the live analysis pauses until it is done (no extra process).
        try (AutoCloseable pause = engine.holdLive()) {
            int cap = manager.budget().reviewMovetimeCapMs();
            for (int i = 0; i <= totalMoves; i++) {
                evals.add(search(engine, fens.get(i), depth, cap));
                if (progressCallback != null) {
                    progressCallback.accept(0.98 * (i + 1) / (totalMoves + 1));
                }
            }
        } catch (Exception e) {
            log.error("review engine failed: {}", e.toString());
            return analysisList;
        }
        log.info("review: {} positions searched at depth {} in {} ms", totalMoves + 1, depth,
                (System.nanoTime() - t0) / 1_000_000);

        // 3. Classify
        board.loadFromFen(fens.get(0));
        boolean inBook = true;
        for (int i = 0; i < totalMoves; i++) {
            String moveStr = moveStrs.get(i);
            String fenBefore = fens.get(i);
            Side sideToMove = board.getSideToMove();
            Move playedMove = moves.get(i);
            List<Eval> results = evals.get(i);

            // 1. Check Forced Move
            boolean isForced = isForcedMove(board);

            // 2. Check Book Move (only while the game is still in book, with a short network timeout)
            boolean isBook = inBook && i < BOOK_MAX_PLY && isBookMove(fenBefore, moveStr);
            inBook = isBook;

            Eval bestEval = results.isEmpty() ? null : results.get(0);
            Eval secondBestEval = results.size() > 1 ? results.get(1) : null;
            Eval playedEval = findEvalForMove(results, playedMove);
            if (playedEval == null) {
                playedEval = evalAfter(playedMove, evals.get(i + 1), fens.get(i + 1));
            }

            // --- CALCOLO MATERIALE E IMBALANCE ---
            // Calcoliamo se siamo sotto di materiale PRIMA della mossa (per capire se è una ricattura/difesa)
            int rawImbalance = getMaterialImbalance(board);
            int myImbalance = (sideToMove == Side.WHITE) ? rawImbalance : -rawImbalance;
            // --- CALCOLO CP (MATERIALE), punto di vista di chi muove, matti = +/-10000 ---
            double bestCp = bestEval != null ? bestEval.cp() : playedEval.cp();
            double playedCp = playedEval.cp();
            double secondBestCp = secondBestEval != null ? secondBestEval.cp() : -10000;

            // --- CALCOLO WP (STATISTICA) ---
            double bestWp = calculateWinProbability(bestCp);
            double playedWp = calculateWinProbability(playedCp);
            double secondBestWp = calculateWinProbability(secondBestCp);

            double deltaWp = Math.max(0, bestWp - playedWp);

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
                boolean isTop3 = isMoveInTopX(results, playedMove, 3);

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

            boolean whiteMoved = sideToMove == Side.WHITE;
            MoveAnalysis analysis = new MoveAnalysis(
                    i + 1,
                    moveStr,
                    fenBefore,
                    playedEval.score().forWhite(whiteMoved).legacyPawns() * 100, // White POV, as the graph expects
                    bestEval != null ? bestEval.move() : "",
                    classification,
                    deltaWp * 100,
                    playedMove.getTo().ordinal(),
                    playedEval.score().mate(),
                    100 * (1 - deltaWp),
                    playedWp,
                    bestWp);

            analysisList.add(analysis);

            board.doMove(playedMove);
        }
        if (progressCallback != null) {
            progressCallback.accept(1.0);
        }

        return analysisList;
    }

    private static List<Eval> search(PositionAnalyzer engine, String fen, int depth, int capMs) throws Exception {
        Board b = new Board();
        b.loadFromFen(fen);
        if (b.legalMoves().isEmpty()) {
            return List.of();
        }
        SearchLimits limits = SearchLimits.depth(Math.max(1, depth)).withMultiPv(MULTI_PV);
        if (capMs > 0) {
            limits = limits.withMovetime(capMs);
        }
        SearchResult r = engine.submit(PositionAnalyzer.Priority.REVIEW, fen,
                limits.withTimeout(Math.max(capMs, 30_000) + 5_000L), Map.of()).get(120, TimeUnit.SECONDS);
        List<Eval> out = new ArrayList<>();
        for (InfoLine l : r.lines()) {
            out.add(new Eval(l.score(), l.move(), l.pv()));
        }
        return out;
    }

    /** Score of a move outside the top lines, from the search of the resulting position (same depth). */
    private static Eval evalAfter(Move played, List<Eval> next, String fenAfter) {
        List<String> pv = new ArrayList<>();
        pv.add(played.toString());
        if (next.isEmpty()) {
            Board b = new Board();
            b.loadFromFen(fenAfter);
            return new Eval(b.isKingAttacked() ? Score.mate(1) : Score.cp(0), played.toString(), pv);
        }
        Eval reply = next.get(0);
        pv.addAll(reply.pv());
        return new Eval(reply.score().negate(), played.toString(), pv);
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
        return MoveClassifier.winProbability(cp);
    }

    private boolean isForcedMove(Board board) {
        try {
            return MoveGenerator.generateLegalMoves(board).size() == 1;
        } catch (MoveGeneratorException e) {
            return false;
        }
    }

    /** Book move = played at least 10 times in the Lichess masters database (network, 2 s cap). */
    private boolean isBookMove(String fen, String moveUci) {
        try {
            return OpeningExplorer.masterGames(fen, moveUci).get(2500, TimeUnit.MILLISECONDS) >= 10;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCapture(Board board, Move playedMove) {
        return board.getPiece(playedMove.getTo()) != com.github.bhlangonijr.chesslib.Piece.NONE;
    }

    private boolean isMoveInTopX(List<Eval> results, Move playedMove, int x) {
        for (int i = 0; i < Math.min(results.size(), x); i++) {
            if (results.get(i).move().equals(playedMove.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSacrifice(Board board, Move playedMove, Eval playedEval, Side side) {
        if (board.isKingAttacked()) return false;
        if (playedEval == null || playedEval.pv().isEmpty()) return false;
        
        int balanceT0 = getMaterialBalance(board, side);
        board.doMove(playedMove);

        boolean pvSacrifice = false;
        if (playedEval != null && !playedEval.pv().isEmpty()) {
            String[] pvMoves = playedEval.pv().toArray(new String[0]);
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
        } catch (Exception e) { log.debug("sacrifice check failed: {}", e.toString()); }
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
        String[] tokens = pgn == null ? new String[0] : pgn.trim().split("\\s+");
        for (String token : tokens) {
            if (!token.isEmpty() && !token.matches("\\d+\\.+") && !token.matches("1-0|0-1|1/2-1/2|\\*")) {
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

    private Eval findEvalForMove(List<Eval> results, Move move) {
        for (Eval res : results) {
            if (res.move().equals(move.toString())) return res;
        }
        return null;
    }

    public double calculateAccuracy(List<MoveAnalysis> analysis, boolean isWhite) {
        double sumSquaredAcc = 0;
        int validMoves = 0;

        for (MoveAnalysis move : analysis) {
            boolean isWhiteMove = move.isWhiteMove();
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
        if (log.isDebugEnabled()) {
            log.debug(String.format(Locale.ROOT, "Mossa %s | WP Prev %.2f | WP Post %.2f | Delta %.3f | Label %s",
                    move, wpPrev, wpPost, delta, label));
        }
    }
}