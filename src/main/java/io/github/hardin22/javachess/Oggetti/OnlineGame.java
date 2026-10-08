package io.github.hardin22.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;

import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Services.LichessGameManager;
import io.github.hardin22.javachess.Utils.PgnCodec;
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
        io.github.hardin22.javachess.Services.BoardStateManager manager = io.github.hardin22.javachess.Controllers.ArduinoController
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

                // Game mode is started once in startGame(): calling it here (every gameFull, i.e. at every
                // reconnection) would cancel the replication of the opponent's move queued just before.
                initialFen = lichessGameManager.getInitialFen();
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
                io.github.hardin22.javachess.Utils.AppExecutors.runOnFx(() -> { // already on the FX thread: no extra hop
                    // Update Board UI
                    Move move = lastMove != null ? uciToMove(lastMove) : null;
                    chessBoardUI.setPosition(fen, move);

                    if (errorSquare != null) {
                        updateStatus("⚠️ ERRORE: Controlla " + errorSquare);
                        chessBoardUI.highlightErrorSquare(errorSquare);
                        return; // Don't overwrite with turn info
                    }

                    {
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
                io.github.hardin22.javachess.Utils.AppExecutors.runOnFx(() -> {
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

        io.github.hardin22.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager().startGameMode();
        // Start the Lichess Stream
        lichessGameManager.startGame();
    }

    /** "e7e8q" -> promotion included (the side comes from the piece on the from-square). */
    private Move uciToMove(String uci) {
        com.github.bhlangonijr.chesslib.Square from = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(0, 2).toUpperCase());
        com.github.bhlangonijr.chesslib.Square to = com.github.bhlangonijr.chesslib.Square.valueOf(uci.substring(2, 4).toUpperCase());
        if (uci.length() >= 5) {
            boolean white = board.getPiece(from).getPieceSide() == com.github.bhlangonijr.chesslib.Side.WHITE;
            String symbol = uci.substring(4, 5);
            return new Move(from, to, com.github.bhlangonijr.chesslib.Piece.fromFenSymbol(
                    white ? symbol.toUpperCase() : symbol.toLowerCase()));
        }
        return new Move(from, to);
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

    /**
     * Resignation asked for on the screen: sent to Lichess (an abort before both sides have moved); the game ends when
     * Lichess confirms it through the stream, and is archived then.
     */
    public void resign() {
        if (!gameRunning || lichessGameManager.isGameOver()) {
            return;
        }
        boolean aborted = lichessGameManager.resignOrAbort();
        updateStatus(aborted ? "Annullamento inviato a Lichess…" : "Abbandono inviato a Lichess…");
    }

    /** The side of the account playing this game (known once Lichess has sent the game). */
    public boolean isPlayingWhite() {
        return lichessGameManager.isWhite();
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
                ArchivedGame game = new ArchivedGame(0, ArchivedGame.GameMode.LICHESS,
                        "Lichess " + gameId, lichessGameManager.getWhiteName(), lichessGameManager.getBlackName(),
                        lichessGameManager.getResult(), lichessGameManager.getTermination(), "", "",
                        LocalDateTime.now(), lichessGameManager.getInitialFen(), "", moves);
                io.github.hardin22.javachess.Utils.AppExecutors.storage().execute(
                        () -> GameArchiveService.getInstance().add(game));
                isGameSaved = true;
            } else {
                log.info("Lichess game {} too short, not archived ({} moves)", gameId, moves.size());
            }
        }
    }
}
