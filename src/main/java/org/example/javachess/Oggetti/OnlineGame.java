package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;

import org.example.javachess.Services.GameArchiveService;
import org.example.javachess.Services.LichessGameManager;
import org.example.javachess.Utils.PgnCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

public class OnlineGame extends AbstractGame {

    private static final Logger log = LoggerFactory.getLogger(OnlineGame.class);

    private LichessGameManager lichessGameManager;
    private String gameId;
    private boolean isGameSaved = false;

    public OnlineGame(ChessBoardUI chessBoardUI, EvalBar evalBar, String gameId) {
        super(chessBoardUI, evalBar);
        this.gameId = gameId;

        // Initialize Lichess Manager
        org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();
        this.lichessGameManager = new LichessGameManager(gameId, manager);

        // We can set analysis enabled by default or disable it for online games
        // Usually online games don't allow assistance, so maybe disable stockfish?
        // But user might want it for post-game or just to see.
        // Let's keep it but maybe warn? Or just disable it to be safe/fair.
        this.analysisEnabled = false;
    }

    @Override
    public void startGame() {
        gameRunning = true;
        isGameSaved = false;
        updateStatus("Connessione a Lichess...\nGame ID: " + gameId);

        lichessGameManager.setUiCallback(new LichessGameManager.UiCallback() {
            @Override
            public void onConnected() {
                String color = lichessGameManager.isWhite() ? "BIANCO" : "NERO";
                updateStatus("Connesso! Giochi come: " + color + "\nGame ID: " + gameId);

                // ACTIVATE GAME MODE in BoardStateManager
                org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                        .getInstance().getBoardStateManager();
                manager.startGameMode();

                // Capture Initial FEN
                initialFen = board.getFen();
            }

            @Override
            public void onStatusMessage(String message) {
                updateStatus(message);
            }

            @Override
            public void onMoveMade(String lastMove) {
                // This is called when WE send a move.
                // But onBoardUpdated is better for general state sync.
            }

            @Override
            public void onBoardUpdated(String fen, String lastMove, String errorSquare) {
                Platform.runLater(() -> {
                    // Update Board UI
                    Move move = null;
                    if (lastMove != null) {
                        move = new Move(
                                com.github.bhlangonijr.chesslib.Square.valueOf(lastMove.substring(0, 2).toUpperCase()),
                                com.github.bhlangonijr.chesslib.Square.valueOf(lastMove.substring(2, 4).toUpperCase()));
                    }
                    chessBoardUI.setPosition(fen, move);

                    if (errorSquare != null) {
                        updateStatus("⚠️ ERRORE: Controlla " + errorSquare);
                        chessBoardUI.highlightErrorSquare(errorSquare);
                        return; // Don't overwrite with turn info
                    }

                    if (lastMove != null) {
                        // SYNC LOCAL BOARD: rebuild from the full move list sent by Lichess, so a missed event or
                        // a reconnection can never desynchronise it (promotions included).
                        PgnCodec.Replay replay = PgnCodec.replay(lichessGameManager.getInitialFen(),
                                lichessGameManager.getMovesUci());
                        board.loadFromFen(lichessGameManager.getInitialFen());
                        pgn.setLength(0);
                        for (String uci : replay.uciMoves()) {
                            Move m = PgnCodec.fromUci(board, uci);
                            if (m == null) {
                                break;
                            }
                            board.doMove(m);
                            pgn.append(uci).append(' ');
                        }
                    }

                    // Update Labels
                    if (lastMove != null) {
                        String side = board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE ? "Nero"
                                : "Bianco";
                        String status = "Mossa: " + lastMove + " (" + side + ")";

                        // LED Logic: If it's NOW my turn, it means Opponent just moved.
                        boolean isMyTurn = (lichessGameManager.isWhite()
                                && board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE) ||
                                (!lichessGameManager.isWhite()
                                        && board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.BLACK);

                        if (isMyTurn) {
                            // Opponent moved. LichessGameManager has already triggered
                            // startBotMoveReplication.
                            // We just update the UI to tell user what to do.
                            status += "\n⚠️ REPLICA MOSSA AVVERSARIO: " + lastMove;
                        } else {
                            // My move (or just happened). Check if I mated them.
                            if (board.isMated()) {
                                notifyMate();
                            }

                            String turn = board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE
                                    ? "Tocca al BIANCO"
                                    : "Tocca al NERO";
                            if (lichessGameManager.isWhite()
                                    && board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE)
                                turn += " (TU)";
                            else if (!lichessGameManager.isWhite()
                                    && board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.BLACK)
                                turn += " (TU)";
                            status += "\n" + turn;
                        }
                        updateStatus(status);
                    }
                });
            }

            @Override
            public void onBotMoveReplicated() {
                Platform.runLater(() -> {
                    updateStatus("✅ Mossa replicata! TOCCA A TE.");
                    // Optional: Flash green or something on UI?
                });
            }

            @Override
            public void onGameEnd(String result) {
                String how = lichessGameManager.getTermination();
                endGame("Partita terminata: " + result + (how.isEmpty() ? "" : " (" + how + ")"), true);
            }

            @Override
            public void onError(String message) {
                updateStatus("Errore: " + message);
            }
        });

        // Start the Lichess Stream
        lichessGameManager.startGame();
    }

    @Override
    public void handleMoveInput(String moveInput) {
        // In OnlineGame, moves come from Physical Board (handled by LichessGameManager
        // -> sendMove)
        // OR from UI input (if we allow it).
        // If UI input:
        if (!gameRunning)
            return;

        // We can try to parse and send.
        Move move = parseMoveInput(moveInput);
        if (move != null) {
            // Send to Lichess
            lichessGameManager.sendMove(PgnCodec.toUci(move));
        }
    }

    @Override
    public void endGame(String endMessage, boolean saveGame) {
        boolean wasRunning = gameRunning;
        gameRunning = false;
        lichessGameManager.stop();
        if (wasRunning) {
            updateStatus(endMessage);
        }

        if (saveGame && !isGameSaved) {
            List<String> moves = lichessGameManager.getMovesUci();
            if (moves.size() >= 3) {
                GameArchiveService.getInstance().add(new ArchivedGame(0, ArchivedGame.GameMode.LICHESS,
                        "Lichess " + gameId, lichessGameManager.getWhiteName(), lichessGameManager.getBlackName(),
                        lichessGameManager.getResult(), lichessGameManager.getTermination(), "", "",
                        LocalDateTime.now(), lichessGameManager.getInitialFen(), "", moves));
                isGameSaved = true;
            } else {
                log.info("Lichess game {} too short, not archived ({} moves)", gameId, moves.size());
            }
        }
    }
}
