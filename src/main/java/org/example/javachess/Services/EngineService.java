package org.example.javachess.Services;

import org.example.javachess.Oggetti.UCIEngine;
import org.example.javachess.Utils.ConfigManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EngineService {
    private static EngineService instance;
    private UCIEngine currentEngine;
    private EngineType currentType = EngineType.STOCKFISH;

    public enum EngineType {
        STOCKFISH,
        MAIA_1100,
        MAIA_1500,
        MAIA_1900
    }

    private EngineService() {
        // Load default engine
        reloadEngine();
    }

    public static synchronized EngineService getInstance() {
        if (instance == null) {
            instance = new EngineService();
        }
        return instance;
    }

    public synchronized UCIEngine getEngine() {
        return currentEngine;
    }

    public synchronized EngineType getEngineType() {
        return currentType;
    }

    public synchronized void setEngineType(EngineType type) {
        if (this.currentType != type) {
            this.currentType = type;
            reloadEngine();
        }
    }

    public UCIEngine createEngineInstance(EngineType type) {
        String stockfishPath = ConfigManager.getProperty("stockfish.path", "/opt/homebrew/bin/stockfish");
        String lc0Path = ConfigManager.getProperty("lc0.path", "/opt/homebrew/bin/lc0");
        String weightsDir = "engines/maia/";

        List<String> commands = new ArrayList<>();
        String enginePath = stockfishPath;

        if (type == EngineType.STOCKFISH) {
            enginePath = stockfishPath;
            int threads = ConfigManager.getIntProperty("stockfish.threads", 2);
            int hash = ConfigManager.getIntProperty("stockfish.hash", 32);
            commands.add("setoption name Threads value " + threads);
            commands.add("setoption name Hash value " + hash);
        } else {
            // Maia (Lc0)
            enginePath = lc0Path;
            commands.add("setoption name Threads value 2");

            String weightFile = "";
            switch (type) {
                case MAIA_1100:
                    weightFile = "maia-1100.pb.gz";
                    break;
                case MAIA_1500:
                    weightFile = "maia-1500.pb.gz";
                    break;
                case MAIA_1900:
                    weightFile = "maia-1900.pb.gz";
                    break;
                default:
                    break;
            }

            File wFile = new File(weightsDir + weightFile);
            commands.add("setoption name WeightsFile value " + wFile.getAbsolutePath());
        }

        return new UCIEngine(enginePath, commands);
    }

    private void reloadEngine() {
        if (currentEngine != null) {
            currentEngine.close();
            currentEngine = null;
        }
        System.out.println("[EngineService] Starting Logic Engine: " + currentType);
        currentEngine = createEngineInstance(currentType);
    }

    public static synchronized void close() {
        if (instance != null && instance.currentEngine != null) {
            instance.currentEngine.close();
            instance.currentEngine = null;
        }
    }
}
