package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.MoveBackup;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;
import kong.unirest.Unirest;
import org.example.javachess.Utils.ConfigManager;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class LichessGameManager {

    private static final String LICHESS_STREAM_URL = "https://lichess.org/api/board/game/stream/";
    private static final String LICHESS_MOVE_URL = "https://lichess.org/api/board/game/";
    private String gameId;
    private String token;
    private Board board;
    private boolean isWhite; // Am I white?
    private Thread streamThread;
    private boolean isRunning = false;
    
    private BoardStateManager boardStateManager;

    public LichessGameManager(String gameId, BoardStateManager boardStateManager) {
        this.gameId = gameId;
        this.boardStateManager = boardStateManager;
        this.token = ConfigManager.getProperty("lichess.token");
        this.board = new Board(); // Start with standard board, will sync from stream
    }

    public void startGame() {
        isRunning = true;
        streamThread = new Thread(this::streamGameEvents);
        streamThread.start();
        
        // Listen for physical moves to send to Lichess
        boardStateManager.setListener(new BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                if (isMyTurn()) {
                    String uciMove = from.toLowerCase() + to.toLowerCase();
                    System.out.println("[Lichess] Sending move: " + uciMove);
                    sendMove(uciMove);
                } else {
                    System.out.println("[Lichess] Ignored physical move (not my turn): " + from + to);
                }
            }

            @Override
            public void onBoardSetupComplete() {}

            @Override
            public void onSetupProgress(String message) {
                if (uiCallback != null) {
                    Platform.runLater(() -> uiCallback.onStatusMessage(message));
                }
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
                if (uiCallback != null) {
                    Platform.runLater(() -> uiCallback.onBoardUpdated(fen, null, errorSquare));
                }
            }

            @Override
            public void onBotMoveReplicated() {
                System.out.println("[Lichess] Opponent move replicated on board.");
                if (uiCallback != null) {
                    Platform.runLater(uiCallback::onBotMoveReplicated);
                }
            }
        });
        
        // Sync logical board
        boardStateManager.setLogicalBoard(board);
    }

    private void streamGameEvents() {
        try {
            System.out.println("[Lichess] Connecting to stream for game: " + gameId);
            
            java.net.URL url = new java.net.URL(LICHESS_STREAM_URL + gameId);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoInput(true);
            // connection.setReadTimeout(0); // Infinite timeout

            int responseCode = connection.getResponseCode();
            System.out.println("[Lichess] Stream Response Code: " + responseCode);

            if (responseCode == 200) {
                if (uiCallback != null) Platform.runLater(() -> uiCallback.onConnected());
                
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while (isRunning && (line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        System.out.println("[Lichess Stream] " + line);
                        processStreamEvent(new JSONObject(line));
                    }
                }
            } else {
                System.err.println("[Lichess] Stream failed: " + responseCode);
                if (uiCallback != null) Platform.runLater(() -> uiCallback.onError("Errore Stream: " + responseCode));
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (uiCallback != null) Platform.runLater(() -> uiCallback.onError("Errore Stream: " + e.getMessage()));
        }
    }

    private void processStreamEvent(JSONObject event) {
        String type = event.getString("type");
        System.out.println("[Lichess Event] Type: " + type);
        
        if (type.equals("gameFull")) {
            JSONObject white = event.getJSONObject("white");
            JSONObject black = event.getJSONObject("black");
            String myId = getMyUserId();
            
            System.out.println("[Lichess] White ID: " + (white.has("id") ? white.getString("id") : "AI/Anon"));
            System.out.println("[Lichess] Black ID: " + (black.has("id") ? black.getString("id") : "AI/Anon"));
            System.out.println("[Lichess] My ID: " + myId);
            
            if (white.has("id") && white.getString("id").equalsIgnoreCase(myId)) {
                this.isWhite = true;
            } else {
                this.isWhite = false;
            }
            System.out.println("[Lichess] I am playing as: " + (isWhite ? "WHITE" : "BLACK"));
            
            String state = event.getJSONObject("state").getString("moves");
            syncBoard(state);
            
            if (uiCallback != null) Platform.runLater(() -> uiCallback.onConnected());
            
        } else if (type.equals("gameState")) {
            String moves = event.getString("moves");
            System.out.println("[Lichess] Game State Moves: " + moves);
            syncBoard(moves);
        }
    }

    private void syncBoard(String moves) {
        board = new Board(); // Reset
        Move lastMove = null;
        
        if (!moves.isEmpty()) {
            String[] moveList = moves.split(" ");
            for (String moveStr : moveList) {
                board.doMove(moveStr);
            }
             String lastMoveUci = moveList[moveList.length - 1];
             lastMove = new Move(Square.fromValue(lastMoveUci.substring(0, 2).toUpperCase()), Square.fromValue(lastMoveUci.substring(2, 4).toUpperCase()));
        }
        
        System.out.println("[Lichess] Synced Board FEN: " + board.getFen());
        System.out.println("[Lichess] Side To Move: " + board.getSideToMove());
        System.out.println("[Lichess] Is My Turn? " + isMyTurn());
        
        boardStateManager.setLogicalBoard(board);
        
        if (uiCallback != null) {
            String lastMoveStr = (lastMove != null) ? lastMove.toString() : null;
            Platform.runLater(() -> uiCallback.onBoardUpdated(board.getFen(), lastMoveStr, null));
        }
        
        if (isMyTurn()) {
            if (lastMove != null) {
                String from = lastMove.getFrom().name();
                String to = lastMove.getTo().name();
                
                System.out.println("[Lichess] Opponent moved: " + from + to + ". Replicating...");
                Platform.runLater(() -> boardStateManager.startBotMoveReplication(from, to));
            }
        }
    }
    
    private boolean isMyTurn() {
        return board.getSideToMove() == (isWhite ? com.github.bhlangonijr.chesslib.Side.WHITE : com.github.bhlangonijr.chesslib.Side.BLACK);
    }
    
    private String getMyUserId() {
        String username = ConfigManager.getProperty("lichess.username");
        if (username == null || username.isEmpty()) {
            // Fallback: Fetch from API
            try {
                JSONObject account = new JSONObject(Unirest.get("https://lichess.org/api/account")
                        .header("Authorization", "Bearer " + token)
                        .asString().getBody());
                username = account.getString("id");
                // Cache it?
            } catch (Exception e) {
                e.printStackTrace();
                username = "anonymous";
            }
        }
        return username.toLowerCase();
    }

    public interface UiCallback {
        void onConnected();
        void onStatusMessage(String message);
        void onMoveMade(String lastMove);
        void onBoardUpdated(String fen, String lastMove, String errorSquare);
        void onBotMoveReplicated();
        void onGameEnd(String result);
        void onError(String message);
    }
    
    private UiCallback uiCallback;
    
    public void setUiCallback(UiCallback callback) {
        this.uiCallback = callback;
    }

    public void sendMove(String uciMove) {
        Unirest.post(LICHESS_MOVE_URL + gameId + "/move/" + uciMove)
                .header("Authorization", "Bearer " + token)
                .asStringAsync(response -> {
                    if (response.getStatus() == 200) {
                        System.out.println("[Lichess] Move success: " + uciMove);
                        if (uiCallback != null) {
                            Platform.runLater(() -> uiCallback.onMoveMade(uciMove));
                        }
                    } else {
                        System.err.println("[Lichess] Move failed: " + response.getBody());
                    }
                });
    }

    public boolean isWhite() {
        return isWhite;
    }

    public void stop() {
        isRunning = false;
        if (streamThread != null) streamThread.interrupt();
    }
    
    public void setPlayerColor(boolean isWhite) {
        this.isWhite = isWhite;
    }
}
