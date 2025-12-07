package org.example.javachess.Services;

import org.example.javachess.Oggetti.Stockfish;

public class StockfishService {
    private static Stockfish instance;

    private StockfishService() {
        // Private constructor to enforce singleton
    }

    public static synchronized Stockfish getInstance() {
        if (instance == null) {
            instance = new Stockfish();
        }
        return instance;
    }

    public static synchronized void close() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }
}
