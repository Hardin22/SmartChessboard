package org.example.javachess.Services;

import org.example.javachess.Oggetti.AnalysisResult;
import org.example.javachess.Utils.ConfigManager;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class StockfishAnalyzer implements AutoCloseable {

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final String stockfishPath;

    public StockfishAnalyzer() {
        this.stockfishPath = ConfigManager.getProperty("stockfish.path", "/opt/homebrew/bin/stockfish");
        startEngine();
    }

    private void startEngine() {
        try {
            ProcessBuilder pb = new ProcessBuilder(stockfishPath);
            process = pb.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            sendCommand("uci");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("uciok"))
                    break;
            }

            int threads = ConfigManager.getIntProperty("stockfish.threads", 1);
            int hash = ConfigManager.getIntProperty("stockfish.hash", 128);
            sendCommand("setoption name Threads value " + threads);
            sendCommand("setoption name Hash value " + hash);
            sendCommand("isready");
            while ((line = reader.readLine()) != null) {
                if (line.equals("readyok"))
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendCommand(String command) {
        try {
            writer.write(command + "\n");
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<AnalysisResult> analyze(String fen, int depth, int multiPV) {
        List<AnalysisResult> results = new ArrayList<>();
        try {
            sendCommand("setoption name MultiPV value " + multiPV);
            sendCommand("position fen " + fen);
            sendCommand("go depth " + depth);

            String line;
            Map<Integer, AnalysisResult> pvMap = new TreeMap<>();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove"))
                    break;
                if (line.startsWith("info") && line.contains("pv") && line.contains("score")) {
                    parseInfoLine(line, fen, pvMap);
                }
            }
            results.addAll(pvMap.values());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return results;
    }

    private void parseInfoLine(String line, String fen, Map<Integer, AnalysisResult> pvMap) {
        String[] parts = line.split(" ");
        int multiPVIndex = -1;
        int scoreIndex = -1;
        int pvIndex = -1;

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("multipv"))
                multiPVIndex = i;
            if (parts[i].equals("score"))
                scoreIndex = i;
            if (parts[i].equals("pv"))
                pvIndex = i;
        }

        if (multiPVIndex != -1 && scoreIndex != -1 && pvIndex != -1) {
            int pv = Integer.parseInt(parts[multiPVIndex + 1]);
            String scoreType = parts[scoreIndex + 1];
            int scoreVal = Integer.parseInt(parts[scoreIndex + 2]);
            String bestMove = parts[pvIndex + 1];

            boolean isMate = scoreType.equals("mate");
            double score;
            int mateIn = 0;

            boolean isWhiteToMove = fen.contains(" w ");
            if (isMate) {
                mateIn = scoreVal;
                score = (mateIn > 0) ? 1000.0 - mateIn : -1000.0 - mateIn;
            } else {
                score = scoreVal / 100.0;
            }

            // Adjust score for side to move (UCI score is always from side to move's
            // perspective)
            // We want it from White's perspective for consistency in GameAnalyzer
            if (!isWhiteToMove) {
                score = -score;
            }

            // Capture full PV
            StringBuilder fullPvBuilder = new StringBuilder();
            for (int i = pvIndex + 1; i < parts.length; i++) {
                fullPvBuilder.append(parts[i]).append(" ");
            }
            String fullPv = fullPvBuilder.toString().trim();

            pvMap.put(pv, new AnalysisResult(score, bestMove, isMate, mateIn, fullPv));
        }
    }

    @Override
    public void close() {
        try {
            sendCommand("quit");
            if (process != null) {
                process.waitFor(2, TimeUnit.SECONDS);
                if (process.isAlive())
                    process.destroy();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
