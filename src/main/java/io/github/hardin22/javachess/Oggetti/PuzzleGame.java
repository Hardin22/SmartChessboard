package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import javafx.scene.control.Label;
import io.github.hardin22.javachess.Services.PuzzleService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PuzzleGame extends AbstractGame {

    private static final Logger log = LoggerFactory.getLogger(PuzzleGame.class);
    /** Pause before the opponent's moves, so the player can follow them. */
    private static final long OPPONENT_MOVE_DELAY_MS = 500;

    private Puzzle currentPuzzle;
    private int currentMoveIndex = 0;
    private Label instructionLabel;
    private boolean isSolving = false;
    private boolean progressRecorded;

    public PuzzleGame(ChessBoardUI chessBoardUI, EvalBar evalBar, Label instructionLabel) {
        super(chessBoardUI, evalBar);
        this.instructionLabel = instructionLabel;
    }

    @Override
    public void startGame() {
        // Triggered when user clicks "Start Puzzle" or similar
        // In this new mode, PuzzleController calls startPuzzle(p) explicitly.
    }

    public void startPuzzle(Puzzle puzzle) {
        if (puzzle == null)
            return;

        gameRunning = false;
        cancelPendingActions();
        this.currentPuzzle = puzzle;
        this.currentMoveIndex = 0;
        this.isSolving = false;
        // Reset Hints
        this.hintLevel = 0;
        this.mistakes = 0;
        this.gaveUp = false;
        this.progressRecorded = false;
        this.persistentHintMove = null;

        Platform.runLater(() -> {
            updateStatus("Analisi Puzzle...");

            // Clear previous artifacts
            chessBoardUI.clearArrows();
            chessBoardUI.clearIcons();
            chessBoardUI.clearHighlights();

            // 1. Load Initial FEN (Pre-Opponent Move)
            board.loadFromFen(puzzle.getFen());
            // Show initial state
            chessBoardUI.setPosition(puzzle.getFen(), null);

            // 2. Identify Opponent's Move
            if (!currentPuzzle.getMoves().isEmpty()) {
                String firstMoveUci = currentPuzzle.getMoves().get(0);
                Move move = parseMoveUci(firstMoveUci);

                // Get style for animation (needed for animateMove)
                String pieceStyle = io.github.hardin22.javachess.Utils.ConfigManager.getProperty("theme.piece", "Classico");
                Piece piece = board.getPiece(move.getFrom());

                // 3. Animate the move after a short pause
                runLaterOnFx(OPPONENT_MOVE_DELAY_MS, () -> {
                    if (currentPuzzle != puzzle) {
                        return; // another puzzle was started meanwhile
                    }
                    updateStatus("L'avversario sta muovendo...");
                    chessBoardUI.animateMove(move.getFrom(), move.getTo(), piece, pieceStyle, () -> {
                        // 4. On Animation Finish: Apply Move & Show Highlights
                        board.doMove(move);
                        currentMoveIndex = 1;
                        opponentMove = move; // Store for highlight persistence

                        // Update UI with new FEN and Last Move (triggers highlight)
                        chessBoardUI.setPosition(board.getFen(), move);

                        updateStatus("Mossa avversario: " + firstMoveUci + ". Configura questa posizione.");

                        // Notify Turn Indicator
                        if (onTurnChange != null) {
                            onTurnChange.run();
                        }

                        // 5. Start Physical Setup
                        startPhysicalSetup();
                    });
                });

            } else {
                // Fallback for no-move puzzles (rare/invalid)
                updateStatus("Configura la posizione iniziale.");
                chessBoardUI.setPosition(board.getFen(), null);
                startPhysicalSetup();
            }
        });
    }

    private Move opponentMove; // To persist highlight

    private void startPhysicalSetup() {
        instructionLabel.setText("Configura la scacchiera (Rating: " + currentPuzzle.getRating() + ")");

        io.github.hardin22.javachess.Services.BoardStateManager manager = io.github.hardin22.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();

        // CRITICAL: Use current board FEN (which includes opponent move), NOT
        // puzzle.getFen()
        manager.setSetupTargetFen(board.getFen());
        // No engine hints while solving: coloured destinations would give the solution away.
        manager.setEvaluationEnabled(false);
        // Only the solver's moves are read from the board; the opponent's replies are reproduced.
        manager.setPhysicalMoveSide(board.getSideToMove());

        manager.setListener(new io.github.hardin22.javachess.Services.BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                if (isSolving) {
                    handleMoveInput(from + to);
                }
            }

            @Override
            public void onBoardSetupComplete() {
                Platform.runLater(() -> {
                    manager.startGameMode();
                    manager.setLogicalBoard(board);

                    // Game is ready directly. Move 0 is already applied.
                    isSolving = true;
                    gameRunning = true;

                    updateStatus("Scacchiera Pronta! Tocca a te!");
                    instructionLabel.setText("Tocca a te! Trova la mossa vincente.");
                });
            }

            @Override
            public void onSetupProgress(String message) {
                updateStatus(message);
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
                Platform.runLater(() -> {
                    // During SETUP, do NOT update the board position with the physical state.
                    // Keep showing the TARGET FEN so the user knows what to build.
                    // chessBoardUI.setPosition(fen, null); // CAUSES BLINDNESS

                    if (errorSquare != null) {
                        chessBoardUI.highlightErrorSquare(errorSquare);
                        // Optional: Show status saying "Wrong piece at " + errorSquare
                    } else {
                        chessBoardUI.clearHighlights();
                    }

                    if (opponentMove != null) {
                        Square from = opponentMove.getFrom();
                        Square to = opponentMove.getTo();
                        int fromCol = from.ordinal() % 8;
                        int fromRow = 7 - (from.ordinal() / 8);
                        int toCol = to.ordinal() % 8;
                        int toRow = 7 - (to.ordinal() / 8);
                        // Use correct color (Yellow/Green) same as updateBoard
                        chessBoardUI.highlightSquare(fromCol, fromRow, javafx.scene.paint.Color.rgb(255, 255, 0, 0.5));
                        chessBoardUI.highlightSquare(toCol, toRow, javafx.scene.paint.Color.rgb(255, 255, 0, 0.5));
                    }

                    // RE-APLY USER HINT
                    if (hintLevel > 0) {
                        drawHintHighlights();
                    }
                });
            }

            @Override
            public void onBotMoveReplicated() {
                updateStatus("Tocca a te! Risolvi il puzzle.");
                if (onTurnChange != null) {
                    onTurnChange.run();
                }
            }
        });

        manager.startSetupMode();
    }

    private Runnable onTurnChange;

    public void setOnTurnChange(Runnable onTurnChange) {
        this.onTurnChange = onTurnChange;
    }

    /** The solver's turn: the puzzle is being solved and the opponent's reply is not pending. */
    @Override
    public boolean isAwaitingHumanMove() {
        return gameRunning && isSolving && currentPuzzle != null && currentMoveIndex % 2 == 1
                && currentMoveIndex < currentPuzzle.getMoves().size();
    }

    @Override
    public void handleMoveInput(String moveInput) {
        if (!isAwaitingHumanMove()) {
            return; // e.g. a move while the opponent's reply is still to come
        }

        Move move = parseMoveInput(moveInput);
        if (move == null)
            return;

        // Verify against solution
        if (currentMoveIndex < currentPuzzle.getMoves().size()) {
            String expectedUci = currentPuzzle.getMoves().get(currentMoveIndex);
            if (expectedUci.length() == 5 && move.toString().equals(expectedUci.substring(0, 4))) {
                move = parseMoveUci(expectedUci); // the board reports from/to only: take the promotion piece
            }

            if (move.toString().equals(expectedUci)) {
                // Correct Move
                board.doMove(move);
                chessBoardUI.setPosition(board.getFen(), move);
                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager()
                        .setLogicalBoard(board);

                currentMoveIndex++;

                // Opponent's reply, after a short pause
                if (currentMoveIndex < currentPuzzle.getMoves().size()) {
                    Puzzle puzzle = currentPuzzle;
                    runLaterOnFx(OPPONENT_MOVE_DELAY_MS, () -> {
                        if (!gameRunning || currentPuzzle != puzzle) {
                            return;
                        }
                        Move response = parseMoveUci(puzzle.getMoves().get(currentMoveIndex));
                        board.doMove(response);
                        chessBoardUI.setPosition(board.getFen(), response);
                        io.github.hardin22.javachess.Services.BoardStateManager manager =
                                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager();
                        manager.setLogicalBoard(board);
                        manager.startBotMoveReplication(response.getFrom().name(), response.getTo().name());
                        currentMoveIndex++;

                        if (currentMoveIndex >= puzzle.getMoves().size()) {
                            isSolving = false;
                            updateStatus("PUZZLE COMPLETATO!");
                            recordProgress(puzzle);
                            instructionLabel.setText("COMPLIMENTI!");
                            chessBoardUI.showVictoryAnimation("OTTIMO!", "Puzzle Risolto");
                            io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().playVictoryAnimation();
                        }
                    });
                } else {
                    updateStatus("PUZZLE COMPLETATO!");
                    recordProgress(currentPuzzle);
                    instructionLabel.setText("COMPLIMENTI!");
                    chessBoardUI.showVictoryAnimation("OTTIMO!", "Puzzle Risolto");
                    io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().playVictoryAnimation();
                    isSolving = false;
                }

            } else {
                // Incorrect Move
                log.info("Wrong puzzle move {} (expected {})", move, expectedUci);
                updateStatus("Mossa Errata! Riprova.");
                mistakes++;
                if (resultListener != null) {
                    resultListener.wrongMove(currentPuzzle);
                }
                // the board manager already took the move: tell it the position did not change, and show on
                // the LEDs how to put the piece back
                io.github.hardin22.javachess.Services.BoardStateManager manager =
                        io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager();
                manager.setLogicalBoard(board);
                manager.resyncToLogical();
                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().flashLed(move.getFrom().name(), 255,
                        0, 0, 2);

                // Undo on UI (User must physically undo)
                // We rely on BoardStateManager to report the "Lift" and "Place" back to
                // original?
                // Or we just tell user to undo.
                // Ideally, we force the board state.
                instructionLabel.setText("Mossa Errata. Torna indietro.");
            }
        }
    }

    private Move parseMoveUci(String uci) {
        Square from = Square.valueOf(uci.substring(0, 2).toUpperCase());
        Square to = Square.valueOf(uci.substring(2, 4).toUpperCase());
        if (uci.length() == 5) {
            Side side = board.getPiece(from).getPieceSide();
            Piece promotion = Piece.fromFenSymbol(side == Side.WHITE
                    ? uci.substring(4).toUpperCase() : uci.substring(4).toLowerCase());
            return new Move(from, to, promotion);
        }
        return new Move(from, to);
    }

    public void showHint() {
        if (!isSolving || currentPuzzle == null)
            return;
        hintLevel = Math.max(hintLevel, 1); // a hint means the puzzle is not solved cleanly

        if (currentMoveIndex < currentPuzzle.getMoves().size()) {
            String expectedUci = currentPuzzle.getMoves().get(currentMoveIndex);
            Move move = parseMoveUci(expectedUci);

            Platform.runLater(() -> {
                // Highlight FROM square
                Square from = move.getFrom();
                int col = from.ordinal() % 8;
                int row = 7 - (from.ordinal() / 8);
                chessBoardUI.highlightSquare(col, row, javafx.scene.paint.Color.rgb(0, 255, 0, 0.5));

                // Also flash LED
                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().flashLed(move.getFrom().name(), 0,
                        255, 0, 2);

                updateStatus("Suggerimento: Muovi da " + move.getFrom());
            });
        }
    }

    public void giveUp() {
        if (currentPuzzle == null)
            return;
        gaveUp = true;
        recordProgress(currentPuzzle); // also during set-up: a given-up puzzle is a failed attempt
        if (!isSolving)
            return;

        if (currentMoveIndex < currentPuzzle.getMoves().size()) {
            String expectedUci = currentPuzzle.getMoves().get(currentMoveIndex);
            Move move = parseMoveUci(expectedUci);

            Platform.runLater(() -> {
                Square from = move.getFrom();
                Square to = move.getTo();
                int fromCol = from.ordinal() % 8;
                int fromRow = 7 - (from.ordinal() / 8);
                int toCol = to.ordinal() % 8;
                int toRow = 7 - (to.ordinal() / 8);

                chessBoardUI.drawArrowOnBoard(fromCol, fromRow, toCol, toRow, javafx.scene.paint.Color.RED);
                updateStatus("Soluzione: " + move);
            });
        }

    }

    private int mistakes;
    private boolean gaveUp;

    /** Saves the attempt once per puzzle (solved = no wrong move, no hint, not given up), off the FX thread. */
    private void recordProgress(Puzzle puzzle) {
        if (progressRecorded || puzzle == null || System.getProperty("javachess.snapshot") != null) {
            return; // screenshot/demo runs never touch the user's puzzle progress
        }
        progressRecorded = true;
        boolean clean = mistakes == 0 && hintLevel == 0 && !gaveUp;
        if (resultListener != null) {
            resultListener.finished(puzzle, !gaveUp, clean);
        }
        if (!rated) {
            return; // a timed series does not change the puzzle rating
        }
        io.github.hardin22.javachess.Play.PuzzleReview.get().onAttempt(puzzle, clean);
        io.github.hardin22.javachess.Utils.AppExecutors.storage().execute(() ->
                io.github.hardin22.javachess.Services.PuzzleProgressService.getInstance()
                        .record(puzzle.getId(), puzzle.getRating(), puzzle.getThemes(), clean));
    }

    // --- results for timed series and the review of failed puzzles ----------------------------------------

    /** Told when a puzzle ends or a wrong move is made (timed series). */
    public interface ResultListener {
        /** The puzzle ended: {@code solved} false when given up; {@code clean} = no wrong move and no hint. */
        void finished(Puzzle puzzle, boolean solved, boolean clean);

        /** A wrong move (the puzzle goes on unless the listener moves to another one). */
        void wrongMove(Puzzle puzzle);
    }

    private ResultListener resultListener;
    private boolean rated = true;

    public void setResultListener(ResultListener listener) {
        this.resultListener = listener;
    }

    /** False for puzzles that must not change the rating nor enter the review (timed series). */
    public void setRated(boolean rated) {
        this.rated = rated;
    }

    private Move persistentHintMove;
    private int hintLevel = 0; // 0=None, 1=Highlight From, 2=Arrow

    public int toggleHint() {
        if (currentPuzzle == null)
            return 0;

        Move solutionMove = getCurrentSolutionMove();
        if (solutionMove == null)
            return 0;

        if (hintLevel == 0) {
            hintLevel = 1;
            persistentHintMove = solutionMove;
            drawHintHighlights();
            triggerHintEffects();
        } else if (hintLevel == 1) {
            hintLevel = 2;
            persistentHintMove = solutionMove;
            drawHintHighlights();
            // No new effects for arrow stage usually, or maybe flash destination?
        }
        return hintLevel;
    }

    // Called repeatedly by UI loop - Must be lightweight/Idempotent
    private void drawHintHighlights() {
        Platform.runLater(() -> {
            if (hintLevel == 1 && persistentHintMove != null) {
                Square from = persistentHintMove.getFrom();
                int col = from.ordinal() % 8;
                int row = 7 - (from.ordinal() / 8);
                chessBoardUI.highlightSquare(col, row, javafx.scene.paint.Color.rgb(156, 204, 101, 0.5));

            } else if (hintLevel == 2 && persistentHintMove != null) {
                Square from = persistentHintMove.getFrom();
                Square to = persistentHintMove.getTo();
                int fromCol = from.ordinal() % 8;
                int fromRow = 7 - (from.ordinal() / 8);
                int toCol = to.ordinal() % 8;
                int toRow = 7 - (to.ordinal() / 8);
                chessBoardUI.drawArrowOnBoard(fromCol, fromRow, toCol, toRow,
                        javafx.scene.paint.Color.rgb(156, 204, 101, 0.9));
            }
        });
    }

    // Called ONCE when hint is toggled
    private void triggerHintEffects() {
        Platform.runLater(() -> {
            if (hintLevel == 1 && persistentHintMove != null) {
                updateStatus("Suggerimento: Muovi il pezzo evidenziato.");
                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance()
                        .flashLed(persistentHintMove.getFrom().name(), 0, 255, 0, 2);
            } else if (hintLevel == 2 && persistentHintMove != null) {
                updateStatus("Soluzione: " + persistentHintMove);
            }
        });
    }

    public Move getCurrentSolutionMove() {
        if (currentPuzzle == null)
            return null;
        if (currentMoveIndex < currentPuzzle.getMoves().size()) {
            String uci = currentPuzzle.getMoves().get(currentMoveIndex);
            return parseMoveUci(uci);
        }
        return null; // or empty move?
    }

    @Override
    public void endGame(String msg, boolean save) {
        gameRunning = false;
        isSolving = false;
        cancelPendingActions();
        // Cleanup if needed
        io.github.hardin22.javachess.Services.BoardStateManager manager =
                io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager();
        manager.stopGameMode();
        manager.setEvaluationEnabled(io.github.hardin22.javachess.Utils.ConfigManager.getBooleanProperty(
                "game.suggestions", true));
    }
}
