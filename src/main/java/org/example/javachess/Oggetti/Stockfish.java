package org.example.javachess.Oggetti;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.application.Platform;

import javafx.scene.paint.Color;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.example.javachess.Utils.ChessMoveConverter;
import org.example.javachess.Utils.ConfigManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Stockfish implements AutoCloseable {

    private Process process;
    private BufferedReader reader;
    private OutputStreamWriter writer;
    private final String stockfishPath;

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private Thread readerThread;

    private volatile boolean isAnalyzing = false;
    private final Object analysisLock = new Object();

    // For synchronous requests
    private volatile CompletableFuture<AnalysisResult> syncEvaluationFuture;
    private volatile CompletableFuture<String> syncBestMoveFuture;
    private volatile List<AnalysisResult> currentMultiPVResults; // To accumulate multiPV for sync

    // Callbacks for UI updates
    private volatile AnalysisUpdateCallback currentCallback;

    private ChessMoveConverter chessMoveConverter = new ChessMoveConverter();

    public interface AnalysisUpdateCallback {
        void onUpdate(int pv, String bestMove, String fullLine, double score, String[] moveEvaluations);
    }

    public Stockfish() {
        this.stockfishPath = ConfigManager.getProperty("stockfish.path", "/opt/homebrew/bin/stockfish");
        initialize();
    }

    public Stockfish(String path) {
        this.stockfishPath = path;
        initialize();
    }

    private void initialize() {
        startEngine();
    }

    private void startEngine() {
        try {
            if (process != null && process.isAlive()) {
                process.destroy();
            }

            ProcessBuilder pb = new ProcessBuilder(stockfishPath);
            process = pb.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new OutputStreamWriter(process.getOutputStream());

            // Start Reader Thread
            if (readerThread != null && readerThread.isAlive()) {
                readerThread.interrupt();
            }
            readerThread = new Thread(this::readOutputLoop);
            readerThread.setDaemon(true);
            readerThread.start();

            // Initialize Engine Options
            sendCommand("uci");

            int threads = ConfigManager.getIntProperty("stockfish.threads", 2);
            int hash = ConfigManager.getIntProperty("stockfish.hash", 32);

            sendCommand("setoption name Threads value " + threads);
            sendCommand("setoption name Hash value " + hash);
            sendCommand("isready");

        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to start Stockfish: " + e.getMessage());
        }
    }

    private void ensureEngineRunning() {
        if (process == null || !process.isAlive()) {
            System.out.println("Stockfish died. Restarting...");
            startEngine();
        }
    }

    private void sendCommand(String command) {
        try {
            ensureEngineRunning();
            writer.write(command + "\n");
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void readOutputLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                processLine(line);
            }
        } catch (IOException e) {
            // Process likely died
        }
    }

    private volatile boolean isSyncRunning = false;

    private void processLine(String line) {
        if (line.startsWith("bestmove")) {
            synchronized (analysisLock) {
                isAnalyzing = false;
                analysisLock.notifyAll();
            }

            // Handle Sync Best Move
            if (syncBestMoveFuture != null && !syncBestMoveFuture.isDone()) {
                String[] parts = line.split(" ");
                if (parts.length > 1) {
                    syncBestMoveFuture.complete(parts[1]);
                } else {
                    syncBestMoveFuture.complete(null);
                }
            }

            // Handle Sync Evaluation (Completion)
            if (syncEvaluationFuture != null && !syncEvaluationFuture.isDone()) {
                if (currentMultiPVResults != null && !currentMultiPVResults.isEmpty()) {
                    syncEvaluationFuture.complete(currentMultiPVResults.get(0));
                } else {
                    // If no info lines were received, it might be checkmate or draw
                    syncEvaluationFuture.complete(new AnalysisResult(0.0, "", false, 0));
                }
            }

        } else if (line.startsWith("info")) {
            // Handle Sync Evaluation (Accumulation)
            if (syncEvaluationFuture != null && !syncEvaluationFuture.isDone()) {
                parseSyncInfoLine(line);
            }

            // Handle UI Callback - ONLY if not in sync mode
            if (isAnalyzing && currentCallback != null && !isSyncRunning) {
                parseInfoLine(line);
            }
        }
    }

    // --- Analysis Logic (Async UI) ---

    private String currentFenForAnalysis = "";

    public void startAnalysis(String fen, int depth, int multiPV,
            AnalysisUpdateCallback callback,
            ChessBoardUI chessBoard, EvalBar evalBar,
            boolean showArrows) {

        this.currentFenForAnalysis = fen;

        // Define callback for UI updates
        currentCallback = (pv, bestMove, fullLine, score, moveEvaluations) -> {
            // Pass data to the provided callback
            if (callback != null) {
                callback.onUpdate(pv, bestMove, fullLine, score, moveEvaluations);
            }

            // Handle internal logic (EvalBar, Arrows)
            Platform.runLater(() -> {
                if (pv == 0) {
                    // Update EvalBar
                    evalBar.updateEvaluation(score);

                    if (showArrows) {
                        chessBoard.clearArrows();
                        drawArrowFromMove(bestMove, chessBoard, Color.rgb(156, 204, 101, 0.7));
                    }
                }
            });
        };

        // Submit to executor
        commandExecutor.submit(() -> {
            try {
                ensureEngineRunning();

                // 1. Stop current analysis if running
                stopAndSync();

                // 2. Start new analysis
                sendCommand("setoption name MultiPV value " + multiPV);
                sendCommand("position fen " + fen);
                sendCommand("go depth " + depth);

                synchronized (analysisLock) {
                    isAnalyzing = true;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void stopCalculating() {
        commandExecutor.submit(this::stopAndSync);
    }

    private void stopAndSync() {
        synchronized (analysisLock) {
            if (isAnalyzing) {
                sendCommand("stop");
                try {
                    analysisLock.wait(2000); // 2s timeout
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // --- Synchronous Methods (GameAnalyzer / PvcGame) ---

    public void setSkillLevel(int skillLevel) {
        commandExecutor.submit(() -> {
            sendCommand("setoption name Skill Level value " + skillLevel);
        });
    }

    public String getBestMove(String fen) {
        return getBestMove(fen, 10); // Default to depth 10 (old behavior)
    }

    public String getBestMove(String fen, int depth) {
        try {
            return commandExecutor.submit(() -> {
                ensureEngineRunning();
                stopAndSync();

                isSyncRunning = true;
                try {
                    syncBestMoveFuture = new CompletableFuture<>();
                    sendCommand("position fen " + fen);
                    sendCommand("go depth " + depth);

                    synchronized (analysisLock) {
                        isAnalyzing = true;
                    }

                    try {
                        String move = syncBestMoveFuture.get(10, TimeUnit.SECONDS);
                        syncBestMoveFuture = null;
                        return move;
                    } catch (Exception e) {
                        return null;
                    }
                } finally {
                    isSyncRunning = false;
                }
            }).get();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getBestMoveByTime(String fen, int movetime) {
        try {
            return commandExecutor.submit(() -> {
                ensureEngineRunning();
                stopAndSync();

                isSyncRunning = true;
                try {
                    syncBestMoveFuture = new CompletableFuture<>();
                    sendCommand("position fen " + fen);
                    sendCommand("go movetime " + movetime);

                    synchronized (analysisLock) {
                        isAnalyzing = true;
                    }

                    try {
                        String move = syncBestMoveFuture.get(movetime + 2000, TimeUnit.MILLISECONDS); // Add buffer for
                                                                                                      // safety
                        syncBestMoveFuture = null;
                        return move;
                    } catch (Exception e) {
                        return null;
                    }
                } finally {
                    isSyncRunning = false;
                }
            }).get();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public AnalysisResult getEvaluation(String fen, int depth) {
        try {
            return commandExecutor.submit(() -> {
                ensureEngineRunning();
                stopAndSync();

                isSyncRunning = true;
                try {
                    syncEvaluationFuture = new CompletableFuture<>();
                    currentMultiPVResults = new ArrayList<>();
                    this.currentFenForAnalysis = fen; // Needed for parsing

                    sendCommand("setoption name MultiPV value 1");
                    sendCommand("position fen " + fen);
                    sendCommand("go depth " + depth);

                    synchronized (analysisLock) {
                        isAnalyzing = true;
                    }

                    try {
                        AnalysisResult result = syncEvaluationFuture.get(10, TimeUnit.SECONDS);
                        syncEvaluationFuture = null;
                        return result;
                    } catch (Exception e) {
                        return new AnalysisResult(0.0, "", false);
                    }
                } finally {
                    isSyncRunning = false;
                }
            }).get();
        } catch (Exception e) {
            return new AnalysisResult(0.0, "", false);
        }
    }

    // --- Highlighting Logic (Heavy) ---

    public void highlightLegalMovesWithEvaluation(Board board, Square pieceSquare, ChessBoardUI chessBoard,
            double currentEvaluation) {
        // This is a heavy operation. We should run it on the executor but it updates
        // UI.
        // It calls 'go' many times.
        // We will run the loop in the executor.

        commandExecutor.submit(() -> {
            Map<Square, Double> evaluations = evaluateMovesForPiece(board, pieceSquare, currentEvaluation);

            Platform.runLater(() -> {
                for (Map.Entry<Square, Double> entry : evaluations.entrySet()) {
                    Square toSquare = entry.getKey();
                    double evalDifference = entry.getValue();

                    Color color;
                    if (evalDifference >= -0.5)
                        color = Color.GREEN;
                    else if (evalDifference < -0.5 && evalDifference > -1)
                        color = Color.YELLOW;
                    else if (evalDifference <= -1.0 && evalDifference > -1.5)
                        color = Color.ORANGE;
                    else
                        color = Color.RED;

                    int col = toSquare.ordinal() % 8;
                    int row = 7 - (toSquare.ordinal() / 8);
                    chessBoard.highlightSquare(col, row, color);
                }
            });
        });
    }

    public Map<Square, Double> evaluateMovesForPiece(Board board, Square pieceSquare, double currentEvaluation) {
        Map<Square, Double> moveEvaluations = new HashMap<>();
        org.example.javachess.Oggetti.ChessHelper chessHelper = new org.example.javachess.Oggetti.ChessHelper(); // Full
                                                                                                                 // path
                                                                                                                 // to
                                                                                                                 // avoid
                                                                                                                 // import
                                                                                                                 // issues
                                                                                                                 // if
                                                                                                                 // any

        try {
            List<Square> legalMoves = chessHelper.getLegalMovesForPiece(board, pieceSquare);
            boolean isWhiteToMove = board.getSideToMove() == Side.WHITE;

            for (Square toSquare : legalMoves) {
                Board tempBoard = new Board();
                tempBoard.loadFromFen(board.getFen());
                Move move = new Move(pieceSquare, toSquare);
                tempBoard.doMove(move);

                double newEvaluation;
                if (tempBoard.isMated()) {
                    newEvaluation = isWhiteToMove ? 1000.0 : -1000.0;
                } else if (tempBoard.isDraw()) {
                    newEvaluation = 0.0;
                } else {
                    ensureEngineRunning();
                    isSyncRunning = true;
                    try {
                        syncEvaluationFuture = new CompletableFuture<>();
                        currentMultiPVResults = new ArrayList<>();
                        this.currentFenForAnalysis = tempBoard.getFen();

                        sendCommand("setoption name MultiPV value 1");
                        sendCommand("position fen " + tempBoard.getFen());
                        sendCommand("go depth 8"); // Low depth for speed

                        try {
                            AnalysisResult result = syncEvaluationFuture.get(2, TimeUnit.SECONDS);
                            newEvaluation = result.score;
                        } catch (Exception e) {
                            newEvaluation = currentEvaluation; // Fallback
                        }
                        syncEvaluationFuture = null;
                    } finally {
                        isSyncRunning = false;
                    }
                }

                double diff = calculateEvaluationDifference(currentEvaluation, newEvaluation, isWhiteToMove);
                moveEvaluations.put(toSquare, diff);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return moveEvaluations;
    }

    // --- Parsing Helpers ---

    private void parseInfoLine(String line) {
        String fen = currentFenForAnalysis;
        String[] parts = line.split(" ");
        int multiPVIndex = Arrays.asList(parts).indexOf("multipv");

        if (multiPVIndex != -1) {
            int pv = Integer.parseInt(parts[multiPVIndex + 1]) - 1;
            int pvIndex = Arrays.asList(parts).indexOf("pv");
            int scoreIndex = Arrays.asList(parts).indexOf("score");

            if (pvIndex != -1 && scoreIndex != -1) {
                double adjustedScore = calculateAdjustedScore(parts, scoreIndex, fen);
                String moveEvaluation = formatMoveEvaluation(parts, scoreIndex, adjustedScore);
                int moveIndex = pvIndex + 1;
                String bestMove = parts[moveIndex];
                String fullLine = buildFullLine(moveIndex, parts, fen, moveEvaluation);

                String[] evals = new String[3];
                evals[0] = moveEvaluation; // Simplified

                if (currentCallback != null) {
                    currentCallback.onUpdate(pv, bestMove, fullLine, adjustedScore, evals);
                }
            }
        }
    }

    private void parseSyncInfoLine(String line) {
        String fen = currentFenForAnalysis;
        String[] parts = line.split(" ");
        int scoreIndex = Arrays.asList(parts).indexOf("score");
        int pvIndex = Arrays.asList(parts).indexOf("pv");

        if (scoreIndex != -1 && pvIndex != -1) {
            double adjustedScore = calculateAdjustedScore(parts, scoreIndex, fen);
            String bestMove = parts[pvIndex + 1];
            boolean isMate = parts[scoreIndex + 1].equals("mate");
            int mateIn = isMate ? Integer.parseInt(parts[scoreIndex + 2]) : 0;

            // Update current results (always index 0 for sync)
            if (currentMultiPVResults.isEmpty()) {
                currentMultiPVResults.add(new AnalysisResult(adjustedScore, bestMove, isMate, mateIn));
            } else {
                currentMultiPVResults.set(0, new AnalysisResult(adjustedScore, bestMove, isMate, mateIn));
            }
        }
    }

    private double calculateAdjustedScore(String[] parts, int scoreIndex, String fen) {
        String scoreType = parts[scoreIndex + 1];
        String[] fenParts = fen.split(" ");
        String sideToMove = fenParts.length > 1 ? fenParts[1] : "w";

        if (scoreType.equals("cp")) {
            double score = Double.parseDouble(parts[scoreIndex + 2].replace(",", ".")) / 100.0;
            return sideToMove.equals("b") ? -score : score;
        } else if (scoreType.equals("mate")) {
            int mateIn = Integer.parseInt(parts[scoreIndex + 2]);
            double mateScore;
            if (mateIn > 0) {
                // Side to move mates in X. Better if X is smaller.
                mateScore = 1000.0 - mateIn;
            } else if (mateIn < 0) {
                // Side to move is mated in X. Worse if X is smaller.
                mateScore = -1000.0 - mateIn; // e.g. mateIn = -1 -> -999.0
            } else {
                // Checkmate (mate 0)
                mateScore = -1000.0;
            }
            return sideToMove.equals("w") ? mateScore : -mateScore;
        }
        return 0.0;
    }

    private double calculateEvaluationDifference(double currentEvaluation, double newEvaluation,
            boolean isWhiteToMove) {
        if (isWhiteToMove) {
            return newEvaluation - currentEvaluation;
        } else {
            return currentEvaluation - newEvaluation;
        }
    }

    private String formatMoveEvaluation(String[] parts, int scoreIndex, double adjustedScore) {
        String scoreType = parts[scoreIndex + 1];
        if (scoreType.equals("cp")) {
            return String.format("%.2f", adjustedScore);
        } else if (scoreType.equals("mate")) {
            if (adjustedScore > 900) {
                int mateIn = (int) (1000 - adjustedScore);
                return "M" + mateIn;
            } else if (adjustedScore < -900) {
                int mateIn = (int) (1000 + adjustedScore);
                return "-M" + mateIn;
            }
        }
        return "N/A";
    }

    private String buildFullLine(int moveIndex, String[] parts, String fen, String moveEvaluation) {
        StringBuilder fullLine = new StringBuilder();
        fullLine.append("[").append(moveEvaluation).append("] ");
        int moveNumber = 1;
        boolean isWhiteMove = fen.contains(" w ");

        Board board = new Board();
        board.loadFromFen(fen);
        try {
            moveNumber = Integer.parseInt(fen.split(" ")[5]);
        } catch (Exception e) {
        }

        for (int i = moveIndex; i < parts.length; i++) {
            if (isWhiteMove) {
                fullLine.append(moveNumber).append(")");
                moveNumber++;
            } else {
                if (i == moveIndex)
                    fullLine.append(moveNumber).append(")...");
            }

            String uciMove = parts[i];
            try {
                String algebraicMove = chessMoveConverter.convertToAlgebraicNotation(uciMove, board.getFen());
                fullLine.append(algebraicMove).append(" ");
                Square fromSquare = Square.valueOf(uciMove.substring(0, 2).toUpperCase());
                Square toSquare = Square.valueOf(uciMove.substring(2, 4).toUpperCase());
                Move move = new Move(fromSquare, toSquare);
                board.doMove(move);
            } catch (Exception e) {
                break;
            }

            if (!isWhiteMove)
                moveNumber++;
            isWhiteMove = !isWhiteMove;
        }
        return fullLine.toString().trim();
    }

    // --- API Methods ---
    public String getOpeningName(String fen) {
        try {
            String apiKey = ConfigManager.getProperty("lichess.token", "");
            var request = Unirest.get("https://explorer.lichess.ovh/masters?variant=standard&fen=" + fen);
            if (!apiKey.isEmpty()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = request.asString();
            if (response.getStatus() != 200)
                return "Unknown Opening";
            String body = response.getBody();
            if (body == null || !body.trim().startsWith("{"))
                return "Unknown Opening";
            JSONObject jsonResponse = new JSONObject(body);
            if (jsonResponse.has("opening") && !jsonResponse.isNull("opening")) {
                return jsonResponse.getJSONObject("opening").getString("eco") + " "
                        + jsonResponse.getJSONObject("opening").getString("name");
            }
            return "Unknown Opening";
        } catch (Exception e) {
            return "Unknown Opening";
        }
    }

    public boolean isBookMove(String fen, String moveUci) {
        try {
            String apiKey = ConfigManager.getProperty("lichess.token", "");
            var request = Unirest.get("https://explorer.lichess.ovh/masters?variant=standard&fen=" + fen);
            if (!apiKey.isEmpty()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = request.asString();
            if (response.getStatus() != 200)
                return false;
            String body = response.getBody();
            if (body == null || !body.trim().startsWith("{"))
                return false;
            JSONObject jsonResponse = new JSONObject(body);
            if (jsonResponse.has("moves")) {
                JSONArray moves = jsonResponse.getJSONArray("moves");
                for (int i = 0; i < moves.length(); i++) {
                    if (moves.getJSONObject(i).getString("uci").equals(moveUci))
                        return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Helper Methods ---
    private void drawArrowFromMove(String moveInUci, ChessBoardUI chessBoard, Color color) {
        if (moveInUci == null || moveInUci.length() < 4)
            return;
        int fromCol = moveInUci.charAt(0) - 'a';
        int fromRow = '8' - moveInUci.charAt(1);
        int toCol = moveInUci.charAt(2) - 'a';
        int toRow = '8' - moveInUci.charAt(3);
        chessBoard.drawArrowOnBoard(fromCol, fromRow, toCol, toRow, color);
    }

    @Override
    public void close() {
        commandExecutor.shutdownNow();
        if (process != null)
            process.destroy();
    }
}
