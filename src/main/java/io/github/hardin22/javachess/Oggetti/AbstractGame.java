package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.application.Platform;
import javafx.concurrent.Task;

import io.github.hardin22.javachess.Engine.AnalysisUpdate;
import io.github.hardin22.javachess.Engine.MoveCoach;
import io.github.hardin22.javachess.Engine.OpeningExplorer;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Services.EngineService;
import io.github.hardin22.javachess.Utils.AppExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class AbstractGame {
    private static final Logger log = LoggerFactory.getLogger(AbstractGame.class);

    protected Board board;
    protected ChessBoardUI chessBoardUI;
    // Removed evaluationLabel
    // Removed move labels
    protected EvalBar evalBar;
    protected boolean gameRunning;
    protected StringBuilder pgn;
    protected boolean saveGame = true;
    protected String initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    protected Task<Void> moveCalculationTask;
    protected PositionAnalyzer.Listener analysisCallback;
    protected java.util.function.Consumer<String> statusCallback;

    public AbstractGame(ChessBoardUI chessBoardUI, EvalBar evalBar) {
        this.board = new Board();
        this.chessBoardUI = chessBoardUI;
        // Removed evaluationLabel assignment
        this.evalBar = evalBar;
        // Removed move labels assignment
        this.pgn = new StringBuilder();
    }

    public void setStatusCallback(java.util.function.Consumer<String> callback) {
        this.statusCallback = callback;
    }

    private volatile String lastStatus = "";

    /** The last status message of the game (instructions, errors, result), as given to the status callback. */
    public String lastStatus() {
        return lastStatus;
    }

    protected void updateStatus(String message) {
        lastStatus = message == null ? "" : message;
        if (statusCallback != null) {
            Platform.runLater(() -> statusCallback.accept(message));
        }
    }

    public void setAnalysisCallback(PositionAnalyzer.Listener callback) {
        this.analysisCallback = callback;
    }

    public abstract void startGame();

    public abstract void handleMoveInput(String moveInput);

    public abstract void endGame(String endMessage, boolean saveGame);

    protected int analysisDepth = io.github.hardin22.javachess.Utils.ConfigManager.getIntProperty("game.depth", 18);
    protected int analysisMultiPV = 1;
    protected boolean analysisEnabled = io.github.hardin22.javachess.Utils.ConfigManager.getBooleanProperty("game.evaluation",
            true);

    /**
     * Starts (or keeps) the live analysis of the current position: eval bar, arrows and analysis panel.
     * Non-blocking: the engine layer runs it on its own threads and replaces any previous analysis.
     */
    protected void evaluatePositionAndMoves() {
        if (!gameRunning || !analysisEnabled) {
            PositionAnalyzer.get().stop();
            return;
        }
        PositionAnalyzer.get().analyze(board.getFen(), analysisDepth, analysisMultiPV, this::onAnalysisUpdate);
    }

    /** Engine event thread: forwards to the controller callback and updates eval bar and arrows. */
    private void onAnalysisUpdate(AnalysisUpdate update) {
        if (analysisCallback != null) {
            analysisCallback.onUpdate(update);
        }
        double score = update.whitePawns();
        String best = update.bestMove();
        boolean arrows = showArrows && shouldShowArrows(update);
        if (evalBar != null) {
            evalBar.updateEvaluation(score); // EvalBar hops to the FX thread itself
        }
        if (showArrows && chessBoardUI != null) {
            Platform.runLater(() -> {
                chessBoardUI.clearArrows();
                if (arrows && best != null && best.length() >= 4) {
                    int fromCol = best.charAt(0) - 'a';
                    int fromRow = '8' - best.charAt(1);
                    int toCol = best.charAt(2) - 'a';
                    int toRow = '8' - best.charAt(3);
                    chessBoardUI.drawArrowOnBoard(fromCol, fromRow, toCol, toRow,
                            javafx.scene.paint.Color.rgb(156, 204, 101, 0.7));
                }
            });
        }
    }

    /** Whether the best-move arrow should be drawn for this update (PvC hides the bot's suggestions). */
    protected boolean shouldShowArrows(AnalysisUpdate update) {
        return true;
    }

    /** Call after a human move was applied: LED verdict + analysis of the new position. */
    protected void onHumanMove(String fenBefore, Move move) {
        if (analysisEnabled && gameRunning) {
            MoveCoach.get().onMovePlayed(fenBefore, move.toString());
        }
    }

    /** Stops the live analysis (game over / left the screen). */
    protected void stopAnalysis() {
        PositionAnalyzer.get().stop();
    }

    /** Looks the opening up asynchronously and shows it in {@code label} when found. */
    protected void updateOpeningLabel(javafx.scene.control.Label label) {
        if (label == null) {
            return;
        }
        String fen = board.getFen();
        // offline book first (the board often has no network), then the online explorer if it answers
        java.util.concurrent.CompletableFuture.supplyAsync(
                () -> io.github.hardin22.javachess.Engine.review.OpeningBook.standard().nameAfter(fen),
                AppExecutors.compute()).thenAccept(name -> name.ifPresent(n -> Platform.runLater(() -> label.setText(n))));
        OpeningExplorer.lookup(fen).thenAccept(name ->
                name.ifPresent(n -> Platform.runLater(() -> label.setText(n))));
    }

    public void setAnalysisParams(int depth, int multiPV) {
        this.analysisDepth = depth;
        this.analysisMultiPV = multiPV;
        evaluatePositionAndMoves(); // Restart with new params
    }

    public void setAnalysisEnabled(boolean enabled) {
        this.analysisEnabled = enabled;
        if (enabled) {
            evaluatePositionAndMoves();
        } else {
            PositionAnalyzer.get().stop();
            chessBoardUI.clearArrows();
        }
    }

    protected boolean showArrows = io.github.hardin22.javachess.Utils.ConfigManager.getBooleanProperty("game.suggestions",
            true);

    public void setShowArrows(boolean show) {
        this.showArrows = show;
        if (!show) {
            chessBoardUI.clearArrows();
        }
        // Restart analysis to update arrows? Or just let next update handle it.
        // Stockfish.startAnalysis needs to know about this flag if we want to stop
        // sending arrows.
        // But simpler: just clear them if false, and in Stockfish callback don't draw
        // if false.
    }

    public void clearArrows() {
        chessBoardUI.clearArrows();
    }

    protected void updatePgn(Move move) {
        if (board.getSideToMove() == Side.BLACK) { // White just moved
            pgn.append(board.getMoveCounter()).append(". ").append(move.toString()).append(" ");
        } else {
            pgn.append(move.toString()).append(" ");
        }
        recordMove(move);
    }

    protected String getDrawReason() {
        if (board.isStaleMate())
            return "Stallo";
        if (board.isRepetition())
            return "Triplice ripetizione";
        if (board.isInsufficientMaterial())
            return "Materiale insufficiente";
        if (board.getHalfMoveCounter() >= 100)
            return "Regola delle 50 mosse";
        return "Patta";
    }

    /**
     * True when a move made by the player is expected now, i.e. the screen may accept a move (tap on the board).
     * Subclasses restrict it (bot's turn, puzzle being set up...).
     */
    public boolean isAwaitingHumanMove() {
        return gameRunning;
    }

    /**
     * Move from the board or the screen: UCI ("e2e4", "e7e8n" with the promotion piece), or the short forms
     * "Nf3"-like "nf3" (piece + square) and "e4" (pawn to square). Null when the text is not a move here.
     */
    protected Move parseMoveInput(String moveInput) {
        try {
            return parseMoveText(moveInput == null ? "" : moveInput.trim());
        } catch (IllegalArgumentException e) {
            return null; // not a square / not a piece
        }
    }

    private Move parseMoveText(String moveInput) {
        if (moveInput.length() == 5) {
            Square from = Square.valueOf(moveInput.substring(0, 2).toUpperCase());
            Square to = Square.valueOf(moveInput.substring(2, 4).toUpperCase());
            char promotion = Character.toUpperCase(moveInput.charAt(4));
            if ("QRBN".indexOf(promotion) < 0) {
                return null;
            }
            Side side = board.getPiece(from).getPieceSide();
            if (side == null) {
                return null;
            }
            return new Move(from, to, Piece.fromFenSymbol(side == Side.WHITE
                    ? String.valueOf(promotion) : String.valueOf(Character.toLowerCase(promotion))));
        } else if (moveInput.length() == 4) {
            Square from = Square.valueOf(moveInput.substring(0, 2).toUpperCase());
            Square to = Square.valueOf(moveInput.substring(2, 4).toUpperCase());
            return new Move(from, to);
        } else if (moveInput.length() == 3) {
            char pieceChar = moveInput.charAt(0);
            Square to = Square.valueOf(moveInput.substring(1, 3).toUpperCase());
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            for (Move move : legalMoves) {
                if (move.getTo().equals(to) && board.getPiece(move.getFrom()).equals(parsePiece(pieceChar))) {
                    return move;
                }
            }
        } else if (moveInput.length() == 2) {
            Square to = Square.valueOf(moveInput.toUpperCase());
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            for (Move move : legalMoves) {
                if (move.getTo().equals(to) && board.getPiece(move.getFrom()).equals(parsePiece('P'))) {
                    return move;
                }
            }
        }
        return null;
    }

    protected Piece parsePiece(char pieceChar) {
        switch (Character.toUpperCase(pieceChar)) {
            case 'R':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
            case 'N':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
            case 'B':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
            case 'Q':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
            case 'K':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_KING : Piece.BLACK_KING;
            case 'P':
                return board.getSideToMove() == Side.WHITE ? Piece.WHITE_PAWN : Piece.BLACK_PAWN;
            default:
                throw new IllegalArgumentException("Pezzo non valido: " + pieceChar);
        }
    }

    /** Appends the result to the PGN and writes the game to the archive on the storage thread. */
    protected void saveGameToJson(String result, String openingName, String type, String timeControl) {
        forgetSnapshotIfFinished(result);
        pgn.append(" ").append(result);
        String pgnText = pgn.toString();
        String startFen = initialFen;
        String finalFen = board.getFen();
        log.info("Saving game: {}", pgnText);
        String white = whitePlayerName();
        String black = blackPlayerName();
        AppExecutors.storage().execute(() -> io.github.hardin22.javachess.Services.GameArchiveService.saveGame(
                type, openingName, pgnText, startFen, finalFen, result, timeControl, white, black));
    }

    /** A game that ended with a result is no longer resumable; an interrupted one stays resumable. */
    protected void forgetSnapshotIfFinished(String result) {
        if (snapshot() != null && !io.github.hardin22.javachess.Play.GameResume.isInterruption(result)) {
            io.github.hardin22.javachess.Play.GameSnapshotStore.get().clear();
        }
    }

    /** Name stored in the archive for White ("?" when unknown). */
    protected String whitePlayerName() {
        return "?";
    }

    /** Name stored in the archive for Black ("?" when unknown). */
    protected String blackPlayerName() {
        return "?";
    }

    // --- delayed actions -----------------------------------------------------------------------------------

    private final Set<ScheduledFuture<?>> pendingActions = ConcurrentHashMap.newKeySet();

    /** Runs {@code action} on the JavaFX thread after {@code delayMs}; cancelled by {@link #cancelPendingActions()}. */
    protected void runLaterOnFx(long delayMs, Runnable action) {
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        holder[0] = AppExecutors.scheduler().schedule(() -> {
            pendingActions.remove(holder[0]);
            Platform.runLater(action);
        }, delayMs, TimeUnit.MILLISECONDS);
        pendingActions.add(holder[0]);
    }

    /** Cancels the actions scheduled with {@link #runLaterOnFx} (end of game, view closed). */
    protected void cancelPendingActions() {
        pendingActions.forEach(f -> f.cancel(false));
        pendingActions.clear();
    }

    /** A pawn move to the last rank without promotion piece becomes a queen promotion. */
    protected Move withAutoQueen(Move move) {
        if (move == null || move.getPromotion() != Piece.NONE) {
            return move;
        }
        Piece piece = board.getPiece(move.getFrom());
        boolean lastRank = move.getTo().getRank() == com.github.bhlangonijr.chesslib.Rank.RANK_8
                || move.getTo().getRank() == com.github.bhlangonijr.chesslib.Rank.RANK_1;
        if (piece.getPieceType() == com.github.bhlangonijr.chesslib.PieceType.PAWN && lastRank) {
            return new Move(move.getFrom(), move.getTo(),
                    board.getSideToMove() == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN);
        }
        return move;
    }

    public Board getBoard() {
        return board;
    }

    // --- LED VISUALIZATION METHODS ---
    // Check, setup and opponent moves are shown by BoardStateManager from the position itself.

    protected void notifyOpponentMove(String from, String to) {
        if (board.isMated()) {
            notifyMate();
        }
    }

    /** Checkmate on the board: the eval bar shows the result (fully the winner's colour), then the animation. */
    protected void notifyMate() {
        if (evalBar != null && analysisEnabled) {
            // the live analysis is not asked for the final position once the game has ended
            evalBar.updateEvaluation(board.getSideToMove() == Side.WHITE ? -1000.0 : 1000.0);
        }
        if (!io.github.hardin22.javachess.Utils.ConfigManager.getBooleanProperty("ui.mate.animation", true)) {
            return;
        }
        Hardware.leds().playVictoryWave();
        Side winner = board.getSideToMove().flip();
        String winnerText = (winner == Side.WHITE ? "IL BIANCO" : "IL NERO") + " VINCE";
        Platform.runLater(() -> chessBoardUI.showVictoryAnimation("SCACCO MATTO", winnerText));
    }

    protected void clearBoardLeds() {
        Hardware.moveLeds().clearCandidates();
    }

    // --- moves, start position, take-back and resuming ----------------------------------------------------

    /** Moves played so far (UCI), in order; kept by {@link #updatePgn}. */
    protected final List<String> movesUci = new java.util.ArrayList<>();
    private boolean replaying;

    /** Moves played so far (UCI). */
    public List<String> getMovesUci() {
        return List.copyOf(movesUci);
    }

    /**
     * Starts the game from {@code fen} instead of the standard position. Call it before {@link #startGame()};
     * check the position first with {@code Play.PositionSetup}.
     */
    public void setStartPosition(String fen) {
        if (gameRunning) {
            throw new IllegalStateException("the game has already started");
        }
        board.loadFromFen(fen);
        initialFen = board.getFen();
        movesUci.clear();
        pgn.setLength(0);
    }

    /** Plays saved moves again (a resumed game), before {@link #startGame()}. Stops at the first illegal move. */
    protected void replayMoves(List<String> uciMoves) {
        replaying = true;
        try {
            for (String uci : uciMoves) {
                Move m = io.github.hardin22.javachess.Analysis.MoveText.legal(board, uci);
                if (m == null) {
                    log.warn("saved move {} is not legal in {}: replay stopped", uci, board.getFen());
                    break;
                }
                board.doMove(m);
                updatePgn(m);
            }
        } finally {
            replaying = false;
        }
    }

    /** Called by {@link #updatePgn}: keeps the move list and saves the game for resuming. */
    private void recordMove(Move move) {
        movesUci.add(move.toString());
        if (!replaying) {
            saveSnapshot();
        }
    }

    /**
     * Takes back the last {@code plies} half-moves: position, move list, PGN, the board on screen, and the physical
     * board (the LEDs show which pieces to put back; the game goes on when the board matches). Returns false when
     * there are not enough moves.
     */
    protected boolean undoPlies(int plies) {
        if (plies <= 0 || plies > movesUci.size()) {
            return false;
        }
        for (int i = 0; i < plies; i++) {
            board.undoMove();
            movesUci.remove(movesUci.size() - 1);
        }
        rebuildPgn();
        io.github.hardin22.javachess.Services.BoardStateManager manager = io.github.hardin22.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();
        manager.setLogicalBoard(board);
        manager.resyncToLogical(); // LEDs: pieces to put back; moves are read again once the board matches
        Move last = movesUci.isEmpty() ? null : new Move(movesUci.get(movesUci.size() - 1),
                board.getSideToMove().flip());
        String fen = board.getFen();
        if (chessBoardUI != null) {
            Platform.runLater(() -> chessBoardUI.setPosition(fen, last));
        }
        return true;
    }

    /** The PGN text of {@link #movesUci} from the initial position (same format as {@link #updatePgn}). */
    private void rebuildPgn() {
        pgn.setLength(0);
        Board replay = new Board();
        replay.loadFromFen(initialFen);
        for (String uci : movesUci) {
            Move m = io.github.hardin22.javachess.Analysis.MoveText.legal(replay, uci);
            if (m == null) {
                break;
            }
            replay.doMove(m);
            if (replay.getSideToMove() == Side.BLACK) {
                pgn.append(replay.getMoveCounter()).append(". ").append(m).append(" ");
            } else {
                pgn.append(m).append(" ");
            }
        }
    }

    /**
     * Invalidates work pending for the old position after a take-back (a bot answer in flight, retries...).
     * Subclasses override it.
     */
    protected void onPositionReset() {
    }

    /** What to save for resuming the game, or null when this kind of game is not resumed. */
    protected io.github.hardin22.javachess.Play.GameSnapshot snapshot() {
        return null;
    }

    /** Saves the game for resuming (after each move). */
    protected void saveSnapshot() {
        if (!gameRunning) {
            return;
        }
        io.github.hardin22.javachess.Play.GameSnapshot s = snapshot();
        if (s != null) {
            io.github.hardin22.javachess.Play.GameSnapshotStore.get().save(s);
        }
    }
}
