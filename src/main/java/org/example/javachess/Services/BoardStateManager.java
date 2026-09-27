package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import org.example.javachess.Controllers.ArduinoController;

public class BoardStateManager {

    public interface BoardMoveListener {
        void onPhysicalMoveDetected(String fromSquare, String toSquare);

        void onBoardSetupComplete();

        void onSetupProgress(String message);

        void onBoardStateUpdated(String fen, String errorSquare); // errorSquare is null if no error

        void onBotMoveReplicated();
    }

    private BoardMoveListener listener;
    private org.example.javachess.Services.MoveEvaluatorService moveEvaluator;
    private EvaluationProvider evaluationProvider;
    private String currentBestMove;

    public interface EvaluationProvider {
        double getCurrentEvaluation();
    }

    public void setEvaluationProvider(EvaluationProvider provider) {
        this.evaluationProvider = provider;
    }

    public void setBestMove(String move) {
        this.currentBestMove = move;
    }

    private boolean isEvaluationEnabled = true;

    public void setEvaluationEnabled(boolean enabled) {
        this.isEvaluationEnabled = enabled;
    }

    private boolean isGameActive = false;

    public void startGameMode() {
        this.isGameActive = true;
        this.isSetupMode = false; // Ensure setup mode is cleared
        System.out.println("[BoardState] --- MODALITÀ GIOCO ATTIVATA ---");
    }

    public void stopGameMode() {
        this.isGameActive = false;
        this.isSetupMode = false;
        this.isReplicatingBotMove = false;
        moveEvaluator.stopEvaluation();
        ArduinoController.getInstance().clearLeds();
        liftedSquaresSequence.clear();
        this.listener = null;
        this.logicalBoard = new Board(); // Reset to standard start
        this.setupTargetBoard = new Board(); // Reset to standard start
        System.out.println("[BoardState] --- MODALITÀ GIOCO DISATTIVATA ---");
    }

    public BoardStateManager() {
        this.logicalBoard = new Board();
        this.moveEvaluator = new org.example.javachess.Services.MoveEvaluatorService();
    }

    private boolean[] physicalBoard = new boolean[64]; // true = piece present

    // Setup Mode
    private boolean isSetupMode = false;
    private Board setupTargetBoard = new Board(); // Default to standard start

    // Game Mode State
    private Board logicalBoard = new Board(); // The "Truth" of the game
    private List<String> liftedSquaresSequence = new ArrayList<>();

    // Bot Move Replication State
    private boolean isReplicatingBotMove = false;
    private String botMoveFrom = null;
    private String botMoveTo = null;

    public void setListener(BoardMoveListener listener) {
        this.listener = listener;
    }

    public void setLogicalBoard(Board board) {
        this.logicalBoard = board.clone(); // Keep a copy of the valid state
    }

    public void setSetupTargetFen(String fen) {
        this.setupTargetBoard.loadFromFen(fen);
    }

    public void startSetupMode() {
        this.isSetupMode = true;
        System.out.println("[BoardState] --- MODALITÀ SETUP ATTIVATA ---");
        System.out.println("[BoardState] Posiziona i pezzi come richiesto.");

        // Initial Scan for LEDs (Batched)
        new Thread(() -> {
            ArduinoController arduino = ArduinoController.getInstance();
            arduino.clearLeds();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }

            java.util.Map<String, int[]> batch = new java.util.HashMap<>();
            int missingCount = 0;
            int wrongCount = 0;

            for (int i = 0; i < 64; i++) {
                if (!isSetupMode)
                    break;

                boolean isPresent = physicalBoard[i];
                Square sq = Square.squareAt(i);
                boolean shouldBe = setupTargetBoard.getPiece(sq) != Piece.NONE;

                if (!isPresent && shouldBe) {
                    // Missing - Warm White
                    batch.put(sq.name(), new int[] { 255, 240, 200 });
                    missingCount++;
                } else if (isPresent && !shouldBe) {
                    // Wrong - Crimson
                    batch.put(sq.name(), new int[] { 220, 20, 60 });
                    wrongCount++;
                }
            }

            System.out.println("[BoardState] Setup Scan: Missing=" + missingCount + ", Wrong=" + wrongCount);

            if (!batch.isEmpty() && isSetupMode) {
                System.out.println("[BoardState] Sending Setup Batch of size: " + batch.size());
                arduino.sendLedBatch(batch);
            } else {
                System.out.println("[BoardState] Setup Batch Empty (Board Perfect?)");
            }
        }).start();

        checkSetupStatus();

        // Trigger initial update
        if (listener != null) {
            Platform.runLater(() -> listener.onBoardStateUpdated(generateSetupFen(), null));
        }
    }

    public void updateSquare(String squareName, boolean isPiecePresent) {
        try {
            Square sq = Square.valueOf(squareName.toUpperCase());
            int index = sq.ordinal();

            if (physicalBoard[index] != isPiecePresent) {
                physicalBoard[index] = isPiecePresent;

                System.out.println("[BoardState] Update " + squareName + "=" + isPiecePresent +
                        " | SetupMode=" + isSetupMode +
                        " | Replicating=" + isReplicatingBotMove);

                if (isSetupMode) {
                    handleSetupChange(squareName, isPiecePresent);
                } else if (isReplicatingBotMove) {
                    handleReplicationChange(squareName, isPiecePresent);
                } else if (isGameActive) {
                    handleGameChange(squareName, isPiecePresent);
                } else {
                    System.out.println("[BoardState] Ignoring change: No active game or setup mode.");
                }
            }
        } catch (Exception e) {
            System.err.println("Invalid square name from Arduino: " + squareName);
        }
    }

    private void handleSetupChange(String square, boolean isPiecePresent) {
        String status = isPiecePresent ? "POSIZIONATO" : "RIMOSSO";
        System.out.println("[SETUP] Pezzo su " + square + " " + status);

        Square sq = Square.valueOf(square);
        boolean shouldBeOccupied = setupTargetBoard.getPiece(sq) != Piece.NONE;

        ArduinoController arduino = ArduinoController.getInstance();

        if (isPiecePresent && shouldBeOccupied) {
            logToListener("✅ Corretto: Pezzo su " + square);
            arduino.sendLedCommand(square, 0, 0, 0); // Off (Correct)
        } else if (isPiecePresent && !shouldBeOccupied) {
            logToListener("❌ Errore: " + square + " dovrebbe essere VUOTA!");
            arduino.sendLedCommand(square, 220, 20, 60); // Crimson (Error)
        } else if (!isPiecePresent && shouldBeOccupied) {
            logToListener("⚠️ Mancante: Posiziona pezzo su " + square);
            arduino.sendLedCommand(square, 255, 240, 200); // Warm White (Missing)
        } else {
            // Empty and should be empty -> Off
            arduino.sendLedCommand(square, 0, 0, 0);
        }

        checkSetupStatus();

        if (listener != null) {
            Platform.runLater(() -> listener.onBoardStateUpdated(generateSetupFen(), null));
        }
    }

    private void checkSetupStatus() {
        int correctPieces = 0;
        int wrongPieces = 0;
        int missingPieces = 0;
        int totalExpected = 0;

        for (Square sq : Square.values()) {
            if (sq == Square.NONE)
                continue;
            int i = sq.ordinal();
            boolean shouldBe = setupTargetBoard.getPiece(sq) != Piece.NONE;
            if (shouldBe)
                totalExpected++;

            if (physicalBoard[i] == shouldBe) {
                if (physicalBoard[i])
                    correctPieces++;
            } else {
                if (physicalBoard[i])
                    wrongPieces++;
                else
                    missingPieces++;
            }
        }

        System.out.println(String.format("[SETUP STATUS] Corretti: %d/%d | Errati: %d | Mancanti: %d", correctPieces,
                totalExpected, wrongPieces, missingPieces));

        if (correctPieces == totalExpected && wrongPieces == 0 && missingPieces == 0) {
            System.out.println("[SETUP] 🎉 SCACCHIERA PRONTA! LA PARTITA PUÒ INIZIARE.");
            isSetupMode = false;
            if (listener != null) {
                Platform.runLater(listener::onBoardSetupComplete);
            }
        }
    }

    private void handleGameChange(String square, boolean isPiecePresent) {
        ArduinoController arduino = ArduinoController.getInstance();

        // 1. Update Sequence Logic (for Move Detection)
        if (!isPiecePresent) {
            // PIECE LIFTED
            liftedSquaresSequence.add(square);
            System.out.println("Piece lifted from: " + square);

            // LED: Delegate to MoveEvaluatorService for standardized behavior
            if (isEvaluationEnabled) {
                if (evaluationProvider != null) {
                    double currentEval = evaluationProvider.getCurrentEvaluation();
                    moveEvaluator.startEvaluation(logicalBoard, Square.valueOf(square), currentEval, currentBestMove);
                } else {
                    moveEvaluator.startEvaluation(logicalBoard, Square.valueOf(square), 0.0, currentBestMove);
                }
            } else {
                // Evaluation Disabled (Online Mode or Suggestions Off) - Show Legal Moves Only
                // (Blue)
                // Use the standardized method that includes batching and source square
                // highlighting
                moveEvaluator.highlightLegalMoves(logicalBoard, Square.valueOf(square), new int[] { 0, 100, 255 });
            }

        } else {
            // PIECE PLACED
            // Clear LEDs immediately
            arduino.clearLeds();

            if (!liftedSquaresSequence.isEmpty()) {
                // ... (Rest of move detection logic) ...
                String detectedFrom = null;
                String detectedTo = square;

                // ... (Existing logic to determine detectedFrom) ...
                if (liftedSquaresSequence.size() == 1) {
                    detectedFrom = liftedSquaresSequence.get(0);
                } else if (liftedSquaresSequence.size() == 2) {
                    String firstLift = liftedSquaresSequence.get(0);
                    String secondLift = liftedSquaresSequence.get(1);

                    if (square.equals(firstLift))
                        detectedFrom = secondLift;
                    else if (square.equals(secondLift))
                        detectedFrom = firstLift;
                    else
                        detectedFrom = secondLift;
                }

                if (detectedFrom != null && !detectedFrom.equals(detectedTo)) {
                    final String finalFrom = detectedFrom;
                    final String finalTo = detectedTo;

                    boolean isCleanMove = true;
                    if (liftedSquaresSequence.size() > 2) {
                        isCleanMove = false;
                        System.out
                                .println("Move ignored: Too many pieces lifted (" + liftedSquaresSequence.size() + ")");
                        arduino.flashLed(square, 220, 20, 60, 3); // Error Flash
                    }

                    if (isCleanMove) {
                        System.out.println("Move Detected: " + finalFrom + " -> " + finalTo);
                        System.out.println("[BoardState] Physical Mapping Identity: " + finalFrom + " -> " + finalFrom
                                + " (No Rotation Applied)");

                        // LED: Turn off Source (it was Gold)
                        arduino.sendLedCommand(finalFrom, 0, 0, 0);
                        // LED: Turn off Dest (just in case)
                        arduino.sendLedCommand(finalTo, 0, 0, 0);

                        if (listener != null) {
                            listener.onPhysicalMoveDetected(finalFrom, finalTo);
                        }
                    }
                } else {
                    // Put back on same square? Turn off LED
                    arduino.sendLedCommand(square, 0, 0, 0);
                }
                liftedSquaresSequence.clear();
            } else {
                // Placed without lift? (Maybe new piece or error)
                // Just turn off to be safe
                arduino.sendLedCommand(square, 0, 0, 0);
            }
        }

        // 2. Visual Feedback & Error Detection
        updateVisualState();
    }

    private void updateVisualState() {
        // Generate a FEN that represents the PHYSICAL state, but using LOGICAL pieces
        // Also detect errors (Ghost pieces)

        StringBuilder fen = new StringBuilder();
        String errorSquare = null;

        for (int rank = 7; rank >= 0; rank--) {
            int emptyCount = 0;
            for (int file = 0; file < 8; file++) {
                int index = rank * 8 + file;
                Square sq = Square.squareAt(index);
                Square currentSq = Square.encode(com.github.bhlangonijr.chesslib.Rank.values()[rank],
                        com.github.bhlangonijr.chesslib.File.values()[file]);
                int sqIndex = currentSq.ordinal();

                boolean isPhysicalPresent = physicalBoard[sqIndex];
                Piece logicalPiece = logicalBoard.getPiece(currentSq);
                boolean isLogicalPresent = logicalPiece != Piece.NONE;

                // Handle Bot Replication Exception
                boolean isBotSource = isReplicatingBotMove && currentSq.name().equals(botMoveFrom);
                boolean isBotDest = isReplicatingBotMove && currentSq.name().equals(botMoveTo);

                // LOGIC:
                // If we are replicating a bot move, we TRUST the Logical Board for the involved
                // squares.
                // We do NOT show errors for them.

                if (isBotSource || isBotDest) {
                    // Force display of Logical State
                    if (isLogicalPresent) {
                        if (emptyCount > 0) {
                            fen.append(emptyCount);
                            emptyCount = 0;
                        }
                        fen.append(getPieceChar(logicalPiece));
                    } else {
                        emptyCount++;
                    }
                    // Skip physical check for these squares
                    continue;
                }

                if (isPhysicalPresent) {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }

                    if (isLogicalPresent) {
                        // Normal case
                        fen.append(getPieceChar(logicalPiece));
                    } else {
                        // Physical Present, Logical Empty -> Ghost Piece (Error)
                        // DO NOT INJECT 'P' into FEN. Just report error.
                        // We treat it as empty in FEN to avoid confusing UI.
                        errorSquare = currentSq.name();
                        emptyCount++;
                    }
                } else {
                    // Physical Empty
                    if (isLogicalPresent) {
                        // Logical Present, Physical Empty -> Lifted Piece?
                        // If it's just lifted, we usually show empty or the piece?
                        // Standard behavior: Show empty (so user sees they lifted it).
                        emptyCount++;
                    } else {
                        // Both Empty
                        emptyCount++;
                    }
                }
            }
            if (emptyCount > 0) {
                fen.append(emptyCount);
            }
            if (rank > 0) {
                fen.append("/");
            }
        }

        fen.append(" ").append(logicalBoard.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE ? "w" : "b");
        fen.append(" KQkq - 0 1");

        final String finalFen = fen.toString();
        final String finalError = errorSquare;

        if (listener != null) {
            Platform.runLater(() -> listener.onBoardStateUpdated(finalFen, finalError));
        }
    }

    private char getPieceChar(Piece piece) {
        String s = piece.getFanSymbol(); // This returns unicode, we need FEN char
        // Manual mapping
        switch (piece) {
            case WHITE_PAWN:
                return 'P';
            case WHITE_ROOK:
                return 'R';
            case WHITE_KNIGHT:
                return 'N';
            case WHITE_BISHOP:
                return 'B';
            case WHITE_QUEEN:
                return 'Q';
            case WHITE_KING:
                return 'K';
            case BLACK_PAWN:
                return 'p';
            case BLACK_ROOK:
                return 'r';
            case BLACK_KNIGHT:
                return 'n';
            case BLACK_BISHOP:
                return 'b';
            case BLACK_QUEEN:
                return 'q';
            case BLACK_KING:
                return 'k';
            default:
                return 'P';
        }
    }

    private void handleReplicationChange(String square, boolean isPiecePresent) {
        System.out.println("[REPLICATION] Change on " + square + ": " + isPiecePresent);

        // Strict Check: Only allow changes on FROM or TO squares
        if (!square.equals(botMoveFrom) && !square.equals(botMoveTo)) {
            System.out.println("[REPLICATION] ERROR: Change on unexpected square " + square);
            ArduinoController.getInstance().flashLed(square, 255, 0, 0, 3); // Red Flash
        }

        checkReplicationStatus();
        updateVisualState();
    }

    private void checkReplicationStatus() {
        if (!isReplicatingBotMove)
            return;

        Square fromSq = Square.valueOf(botMoveFrom);
        Square toSq = Square.valueOf(botMoveTo);

        boolean isFromEmpty = !physicalBoard[fromSq.ordinal()];
        boolean isToOccupied = physicalBoard[toSq.ordinal()];

        if (isFromEmpty && isToOccupied) {
            System.out.println("[REPLICATION] Mossa replicata correttamente!");
            isReplicatingBotMove = false;

            // Clear LEDs (Bot Move Highlight)
            ArduinoController.getInstance().clearLeds();

            if (listener != null) {
                Platform.runLater(listener::onBotMoveReplicated);
            }

            botMoveFrom = null;
            botMoveTo = null;
        } else {
            // Re-enforce LEDs if they were cleared or just to be safe
            // Actually, we should just let them stay on.
            // But if user made a mistake, we might want to remind them.
            // For now, assume they stay on.

            StringBuilder msg = new StringBuilder("Muovi il Bot: ");
            if (!isFromEmpty)
                msg.append("Solleva da ").append(botMoveFrom).append(" ");
            if (!isToOccupied)
                msg.append("Posiziona su ").append(botMoveTo);

            if (listener != null) {
                String feedback = msg.toString();
                Platform.runLater(() -> listener.onSetupProgress(feedback));
            }
        }
    }

    public void reset() {
        liftedSquaresSequence.clear();
        isReplicatingBotMove = false;
    }

    private String generateSetupFen() {
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int emptyCount = 0;
            for (int file = 0; file < 8; file++) {
                int index = rank * 8 + file;
                Square currentSq = Square.encode(com.github.bhlangonijr.chesslib.Rank.values()[rank],
                        com.github.bhlangonijr.chesslib.File.values()[file]);
                int sqIndex = currentSq.ordinal();

                boolean isPresent = physicalBoard[sqIndex];

                if (isPresent) {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }
                    // Infer piece from target board if possible
                    Piece targetPiece = setupTargetBoard.getPiece(currentSq);
                    char pieceChar = (targetPiece != Piece.NONE) ? getPieceChar(targetPiece) : '?'; // ? for
                                                                                                    // unknown/wrong

                    // If ? (wrong piece), maybe default to Pawn for display safety or just P
                    if (pieceChar == '?')
                        pieceChar = 'P';

                    fen.append(pieceChar);
                } else {
                    emptyCount++;
                }
            }
            if (emptyCount > 0) {
                fen.append(emptyCount);
            }
            if (rank > 0) {
                fen.append("/");
            }
        }
        fen.append(" w KQkq - 0 1");
        return fen.toString();
    }

    private char getStandardPieceChar(int index) {
        // Deprecated/Unused now
        return 'P';
    }

    private void logToListener(String msg) {
        if (listener != null) {
            Platform.runLater(() -> listener.onSetupProgress(msg));
        }
    }

    public void startBotMoveReplication(String from, String to) {
        this.isReplicatingBotMove = true;
        this.botMoveFrom = from;
        this.botMoveTo = to;
        System.out.println("[BoardState] In attesa di replica mossa bot: " + from + " -> " + to);

        // LED Feedback: Cyan for From and To
        // Use batch to ensure both are sent reliably
        java.util.Map<String, int[]> leds = new java.util.HashMap<>();
        leds.put(from, new int[] { 0, 255, 255 });
        leds.put(to, new int[] { 0, 255, 255 });

        ArduinoController.getInstance().sendLedBatch(leds);

        checkReplicationStatus();
        updateVisualState(); // Update visual state to show current physical state
    }
}
