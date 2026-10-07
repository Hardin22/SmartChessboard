package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefMessageRouter;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.File;

public class BrowserController implements NavigationAware {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrowserController.class);

    @FXML
    private StackPane browserContainer;
    @FXML
    private Button btnBack;
    @FXML
    private Button btnToggleVision;
    @FXML
    private Label lblStatus;
    // @FXML private VBox platformSelection; // REMOVED

    // Minimal JCEF Components
    private static CefApp cefApp;
    private CefClient cefClient;
    private CefBrowser cefBrowser;

    private MainController mainController;

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    private javax.swing.JFrame frame;
    private boolean isInitializing = false;

    public void initialize() {
        log.info("[BrowserController] Controller initialized. Waiting for loadPage().");
    }

    public void loadPage(String url) {
        log.info("[BrowserController] Request to load: " + url);

        if (cefApp == null && !isInitializing) {
            initializeJCEF(url);
        } else if (cefBrowser != null) {
            // Browser already exists, just load URL and show frame
            SwingUtilities.invokeLater(() -> {
                cefBrowser.loadURL(url);

                // FIX: Recreate frame if it was disposed
                if (frame == null) {
                    createHeaderBarFrame();
                }

                if (frame != null) {
                    frame.setVisible(true);
                    frame.toFront();
                    frame.requestFocus();
                }
            });
        }
    }

    private void initializeJCEF(String initialUrl) {
        isInitializing = true;
        log.info("[BrowserController] Initializing JCEF...");

        new Thread(() -> {
            try {
                CefAppBuilder builder = new CefAppBuilder();
                // Move JCEF bundle outside of iCloud synced folders to avoid sync
                // conflicts/crashes
                String userHome = System.getProperty("user.home");
                // Use a versioned folder to force a fresh download and avoid corruption
                builder.setInstallDir(new File(userHome, ".jcef-bundle-v141"));

                // MACOS JCEF STABILITY FIXES
                builder.addJcefArgs("--disable-gpu");
                builder.addJcefArgs("--disable-gpu-compositing");
                builder.addJcefArgs("--disable-gpu-rasterization");
                builder.addJcefArgs("--no-sandbox");
                builder.addJcefArgs("--no-zygote");
                builder.addJcefArgs("--disable-dev-shm-usage");
                builder.addJcefArgs("--disable-gpu-shader-disk-cache");
                builder.addJcefArgs("--disable-site-isolation-trials");
                builder.addJcefArgs("--disable-features=VizDisplayCompositor");

                // SILENCE LOGS (Temporarily re-enabled for debugging)
                // builder.addJcefArgs("--log-severity=disable");

                // ENABLE PERSISTENCE (Cookies/Login)
                String cachePath = new File(userHome, ".javachess/jcef-cache").getAbsolutePath();
                log.info("[BrowserController] Setting Cache Path: " + cachePath);
                File cacheDir = new File(cachePath);
                if (!cacheDir.exists()) {
                    boolean created = cacheDir.mkdirs();
                    log.info("[BrowserController] Cache directory created: " + created);
                }

                // CORRECT WAY: Set settings directly via Builder
                org.cef.CefSettings settings = builder.getCefSettings();
                settings.cache_path = cachePath;
                settings.persist_session_cookies = true;
                // settings.log_severity = org.cef.CefSettings.LogSeverity.DISABLE; // Fixed:
                // Commented out to avoid symbol error and allow debug logs
                // builder.addJcefArgs("--user-data-dir=" + cachePath); // REMOVED: Might be
                // causing conflicts with Lichess?
                // setup

                log.info("[BrowserController] CefSettings Configured -> CachePath: " + settings.cache_path);

                builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                    @Override
                    public void stateHasChanged(org.cef.CefApp.CefAppState state) {
                        log.info("[JCEF] State: " + state);
                    }
                });

                cefApp = builder.build();

                SwingUtilities.invokeLater(() -> {
                    cefClient = cefApp.createClient();
                    CefMessageRouter msgRouter = CefMessageRouter.create();
                    cefClient.addMessageRouter(msgRouter);

                    // Network errors (no connection, DNS, site down) are shown in the header bar.
                    cefClient.addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                        @Override
                        public void onLoadError(CefBrowser browser, org.cef.browser.CefFrame frame,
                                                org.cef.handler.CefLoadHandler.ErrorCode errorCode, String errorText,
                                                String failedUrl) {
                            if (frame != null && frame.isMain()
                                    && errorCode != org.cef.handler.CefLoadHandler.ErrorCode.ERR_ABORTED) {
                                log.warn("Page load failed ({}): {}", errorCode, failedUrl);
                                updateStatus("PAGINA NON RAGGIUNGIBILE: controlla la connessione (" + errorText + ")",
                                        java.awt.Color.RED);
                            }
                        }
                    });

                    // No credential auto-fill: passwords are not stored (see ConfigManager). The user logs in once
                    // in this browser and the session is kept in the persistent JCEF cache (~/.javachess/jcef-cache).

                    // ENABLE OFF-SCREEN RENDERING (OSR) ONLY FOR LINUX (Raspberry Pi)
                    // Windowed mode causes X11 focus stealing on Linux/ARM, so we need OSR there.
                    // On macOS/Windows, OSR requires JOGL (GLCanvas) which is not in our deps,
                    // so we must use Windowed mode (which works fine there).
                    boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");
                    boolean useOSR = isLinux;

                    log.info(
                            "[BrowserController] OS: " + System.getProperty("os.name") + " -> Using OSR: " + useOSR);

                    cefBrowser = cefClient.createBrowser(initialUrl, useOSR, false);

                    // Link BotMover to Browser
                    botMover.setBrowser(cefBrowser);
                    botMover.registerDisplayHandler(); // Register Title Listener for Native Moves & Orientation

                    botMover.setOnOrientationChanged(isFlipped -> {
                        this.isFlipped = isFlipped;
                        if (visionService != null) {
                            visionService.setFlipped(isFlipped);
                        }
                    });

                    createHeaderBarFrame();

                    isInitializing = false;
                });

            } catch (Throwable e) {
                // Missing native bundle, no network for the first download, unsupported platform...
                log.error("Cannot start the integrated browser", e);
                isInitializing = false;
                org.example.javachess.Utils.ErrorReporter.showError("Browser",
                        "Impossibile avviare il browser integrato: "
                                + org.example.javachess.Utils.ErrorReporter.userMessage(e)
                                + (String.valueOf(e.getMessage()).contains("static TLS")
                                        ? "\nBrowser scaricato: riavvia l'app (run_pi.sh lo carica all'avvio)."
                                        : "\nAl primo avvio serve Internet per scaricare il browser (~150 MB)."));
                Platform.runLater(() -> {
                    if (mainController != null) {
                        mainController.navigateTo("HOME");
                    }
                });
            }
        }).start();
    }

    // --- ONLINE GAME INTEGRATION ---
    /** Created on first use: loading the ONNX model costs ~12 MB and a few hundred ms. */
    private org.example.javachess.Services.VisionService visionService;
    private org.example.javachess.Vision.BotMover botMover = new org.example.javachess.Vision.BotMover();
    private com.github.bhlangonijr.chesslib.Board internalBoard = new com.github.bhlangonijr.chesslib.Board();

    private boolean isVisionRunning = false;
    private volatile boolean isFlipped = false; // Track orientation locally
    private boolean isSetupPhase = false;
    private String lastFen = "";
    private String initialFen = "";
    private StringBuilder pgn = new StringBuilder();
    private boolean isGameSaved = false;

    private javax.swing.JLabel lblStatusSwing;

    private void createHeaderBarFrame() {
        frame = new javax.swing.JFrame("JavaChess Browser");
        frame.setUndecorated(true);

        java.awt.GraphicsDevice gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        if (gd.isFullScreenSupported()) {
            gd.setFullScreenWindow(frame);
        } else {
            frame.setExtendedState(javax.swing.JFrame.MAXIMIZED_BOTH);
        }

        frame.setLayout(new BorderLayout());

        JPanel headerPanel = new JPanel();
        headerPanel.setBackground(java.awt.Color.BLACK);
        headerPanel.setPreferredSize(new java.awt.Dimension(1920, 60)); // Increased height for easier touch
        headerPanel.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 20, 10));

        javax.swing.JButton btnBackSwing = new javax.swing.JButton("BACK TO HOME");
        styleButton(btnBackSwing);
        btnBackSwing.setPreferredSize(new java.awt.Dimension(200, 40));
        btnBackSwing.addActionListener(e -> closeBrowser());

        javax.swing.JButton btnVisionSwing = new javax.swing.JButton("ENABLE VISION");
        styleButton(btnVisionSwing);
        btnVisionSwing.setPreferredSize(new java.awt.Dimension(200, 40));
        btnVisionSwing.addActionListener(e -> toggleVision(btnVisionSwing));

        // STATUS LABEL
        lblStatusSwing = new javax.swing.JLabel("READY");
        lblStatusSwing.setFont(new java.awt.Font("Consolas", java.awt.Font.BOLD, 14));
        lblStatusSwing.setForeground(java.awt.Color.GREEN);

        headerPanel.add(btnBackSwing);
        headerPanel.add(btnVisionSwing);
        headerPanel.add(javax.swing.Box.createHorizontalStrut(20));
        headerPanel.add(lblStatusSwing);

        frame.add(headerPanel, BorderLayout.NORTH);
        // OSR Component is returned by getUIComponent() just like Windowed, but handles
        // painting internally
        frame.add(cefBrowser.getUIComponent(), BorderLayout.CENTER);

        java.awt.event.KeyAdapter escListener = new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    closeBrowser();
                }
            }
        };
        frame.addKeyListener(escListener);
        headerPanel.addKeyListener(escListener);
        btnBackSwing.addKeyListener(escListener);
        btnVisionSwing.addKeyListener(escListener);

        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    private void updateStatus(String msg, java.awt.Color color) {
        if (lblStatusSwing != null) {
            SwingUtilities.invokeLater(() -> {
                lblStatusSwing.setText(msg);
                lblStatusSwing.setForeground(color);
            });
        }
    }

    private void toggleVision(javax.swing.JButton btn) {
        if (isVisionRunning) {
            stopOnlineGame();
            btn.setText("ENABLE VISION");
            btn.setBackground(java.awt.Color.DARK_GRAY);
        } else {
            startOnlineGame();
            btn.setText("DISABLE VISION");
            btn.setBackground(java.awt.Color.RED);
        }
    }

    private void startOnlineGame() {
        log.info("[OnlineGame] Starting...");
        updateStatus("VISION STARTING...", java.awt.Color.YELLOW);

        isVisionRunning = true;
        isSetupPhase = true;

        // RESET GAME STATE
        internalBoard = new com.github.bhlangonijr.chesslib.Board();
        lastFen = "";
        initialFen = "";
        pgn = new StringBuilder();
        isGameSaved = false;

        // Initialize Vision Callback
        if (visionService == null) {
            visionService = new org.example.javachess.Services.VisionService();
        }
        visionService.setFlipped(isFlipped);
        visionService.setOnReading(this::handleReading);
        visionService.setOnError(msg -> updateStatus(msg, java.awt.Color.RED));

        // HARD RESET VISION STATE: Ensure no "ghost" boards from previous sessions
        // persist
        visionService.resetState();
        visionService.startScanning();

        // CHECK ORIENTATION
        botMover.checkOrientation();

        org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();

        // CRITICAL: Reset Manager and Disable Stockfish Evaluation
        manager.reset();
        manager.setLogicalBoard(internalBoard);
        manager.setEvaluationEnabled(false);

        manager.setListener(new org.example.javachess.Services.BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                if (!isSetupPhase) {
                    updateStatus("PHYSICAL MOVE: " + from + " -> " + to, java.awt.Color.CYAN);
                    handlePhysicalMove(from, to);
                }
            }

            @Override
            public void onBoardSetupComplete() {
                log.info("[OnlineGame] Board Setup Complete. Game Started.");
                updateStatus("SETUP COMPLETE! GAME STARTED.", java.awt.Color.GREEN);
                isSetupPhase = false;

                if (!lastFen.isEmpty()) {
                    try {
                        internalBoard.loadFromFen(lastFen);
                        initialFen = lastFen; // Capture initial position
                        // SYNC BOARD STATE MANAGER
                        org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                                .getInstance().getBoardStateManager();
                        manager.setLogicalBoard(internalBoard);
                        manager.startGameMode(); // ACTIVATE GAME MODE
                    } catch (Exception e) {
                        log.error("Unexpected error", e);
                    }
                }
            }

            @Override
            public void onSetupProgress(String message) {
                updateStatus(message, java.awt.Color.ORANGE);
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
            }

            @Override
            public void onBotMoveReplicated() {
                updateStatus("OPPONENT MOVE REPLICATED", java.awt.Color.GREEN);
                // Resume Vision after user has moved
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        visionService.startScanning();
                    } catch (Exception e) {
                    }
                }).start();
            }
        });
    }

    private void stopOnlineGame() {
        isVisionRunning = false;
        if (visionService != null) {
            visionService.stopScanning();
        }
        org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();
        manager.stopGameMode(); // DEACTIVATE GAME MODE
        manager.setEvaluationEnabled(true); // Re-enable for offline
        updateStatus("VISION STOPPED", java.awt.Color.GRAY);

        // SAVE GAME
        if (!isGameSaved && !initialFen.isEmpty()) {
            java.util.List<String> moves = java.util.Arrays.stream(pgn.toString().trim().split("\\s+"))
                    .filter(org.example.javachess.Utils.PgnCodec::looksLikeUci).toList();
            if (moves.size() >= 3) {
                String decided = org.example.javachess.Utils.PgnCodec.resultOf(internalBoard);
                org.example.javachess.Services.GameArchiveService.getInstance().add(
                        new org.example.javachess.Oggetti.ArchivedGame(0,
                                org.example.javachess.Oggetti.ArchivedGame.GameMode.BROWSER,
                                currentSiteName(), "", "", decided != null ? decided : "*",
                                decided != null ? "" : "Interrotta", "", "", java.time.LocalDateTime.now(),
                                initialFen, internalBoard.getFen(), moves));
                isGameSaved = true;
            } else {
                log.info("Online game too short, not archived ({} moves)", moves.size());
            }
        }
    }

    private String currentSiteName() {
        String url = cefBrowser != null ? cefBrowser.getURL() : "";
        if (url != null && url.contains("chess.com")) {
            return "Chess.com";
        }
        if (url != null && url.contains("lichess.org")) {
            return "Lichess (browser)";
        }
        return "Online (browser)";
    }

    /**
     * Disposes JCEF only if this session actually started it. Never call {@code CefApp.getInstance()} for this:
     * it would initialise the native library just to shut it down ({@code UnsatisfiedLinkError N_PreInitialize}).
     */
    public static synchronized void disposeIfStarted() {
        CefApp app = cefApp;
        if (app == null) {
            return;
        }
        cefApp = null; // idempotent: App.stop() and the shutdown hook both call this
        try {
            app.dispose();
            log.info("JCEF disposed");
        } catch (Throwable t) {
            log.warn("JCEF dispose failed: {}", t.toString());
        }
    }

    
    private final org.example.javachess.Vision.PositionResolver resolver =
            new org.example.javachess.Vision.PositionResolver();

    /**
     * New stable position seen on screen. The reading is matched against the positions that can legally follow
     * the known one (PositionResolver), so one or two misread squares do not break the synchronisation.
     */
    private void handleReading(org.example.javachess.Vision.BoardReading reading) {
        if (!isVisionRunning) {
            return;
        }
        String partialFen = reading.placement();

        if (isSetupPhase) {
            // FAST-FORWARD: the screen shows our known position plus one move -> go straight to game mode
            // (move replication LEDs) instead of the setup flow.
            org.example.javachess.Vision.PositionResolver.Resolution fast = resolver.resolve(internalBoard, reading,
                    false);
            boolean isImmediateMove = fast.confident() && fast.moves().size() == 1;

            if (isImmediateMove) {
                log.info("Immediate move detected during setup, switching to game mode");
                updateStatus("GAME SYNCED (MOVE DETECTED)", java.awt.Color.GREEN);
                isSetupPhase = false;
                if (initialFen.isEmpty()) {
                    initialFen = internalBoard.getFen(); // the game is archived from this position
                }
                org.example.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager()
                        .startGameMode();
                // fall through to the game logic below
            } else {
                if (lastFen.isEmpty() || !simplifyFen(lastFen).equals(partialFen)) {
                    updateStatus("SETUP TARGET FOUND", java.awt.Color.MAGENTA);

                    // If the board is flipped (we play black) and it is not the start position, assume black to move.
                    String turn = "w";
                    boolean isStandardStart = partialFen.equals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR");
                    if (this.isFlipped && !isStandardStart) {
                        turn = "b";
                    }

                    // Normalise through PgnCodec: drops impossible castling rights and rejects positions the
                    // classifier got wrong (which would crash chesslib).
                    com.github.bhlangonijr.chesslib.Board setupBoard = org.example.javachess.Utils.PgnCodec
                            .boardFromFen(partialFen + " " + turn + " KQkq - 0 1");
                    if (setupBoard == null) {
                        updateStatus("POSIZIONE NON VALIDA, RIPROVO...", java.awt.Color.ORANGE);
                        log.warn("Vision produced an impossible position: {}", partialFen);
                        return;
                    }
                    if (reading.minConfidence() < 0.6f) {
                        log.info("Uncertain squares in setup target: {}", reading.uncertainSquares(0.6f));
                    }
                    String fullSetupFen = setupBoard.getFen();
                    log.info("Setup target {} (turn {}, flipped {})", fullSetupFen, turn, isFlipped);

                    lastFen = fullSetupFen;
                    org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                            .getInstance().getBoardStateManager();
                    manager.setSetupTargetFen(fullSetupFen);
                    manager.startSetupMode();
                }
                return;
            }
        }

        // GAME LOGIC
        org.example.javachess.Vision.PositionResolver.Resolution res = resolver.resolve(internalBoard, reading, false);
        if (res.unchanged()) {
            return;
        }
        if (!res.confident() || res.moves().size() != 1) {
            java.util.List<String> diff = org.example.javachess.Vision.PositionResolver.differences(internalBoard,
                    reading);
            log.info("Screen position not matched to a legal move (margin {}, mismatches {}, differences {})",
                    String.format("%.1f", res.margin()), res.mismatches(), diff);
            updateStatus("LETTURA INCERTA: " + String.join(" ", diff.subList(0, Math.min(6, diff.size()))),
                    java.awt.Color.ORANGE);
            return;
        }
        com.github.bhlangonijr.chesslib.move.Move legalMove = res.moves().get(0);
        log.info("Opponent moved: {} (mismatched squares: {})", legalMove, res.mismatches());
        updateStatus("OPPONENT MOVED: " + legalMove, java.awt.Color.MAGENTA);

        updatePgn(legalMove);
        internalBoard.doMove(legalMove);
        lastFen = internalBoard.getFen();

        org.example.javachess.Services.BoardStateManager manager = org.example.javachess.Controllers.ArduinoController
                .getInstance().getBoardStateManager();
        manager.setLogicalBoard(internalBoard);
        manager.startBotMoveReplication(legalMove.getFrom().name(), legalMove.getTo().name());

        visionService.stopScanning(); // pause while the user replicates the move on the board
    }

    private void handlePhysicalMove(String from, String to) {
        try {
            // Legal move from the physical squares (a pawn reaching the last rank promotes to a queen).
            com.github.bhlangonijr.chesslib.move.Move move = org.example.javachess.Utils.PgnCodec
                    .fromUci(internalBoard, (from + to).toLowerCase());

            if (move != null) {
                boolean isBlackMove = internalBoard.getSideToMove() == com.github.bhlangonijr.chesslib.Side.BLACK;

                updateStatus("EXECUTING MOVE: " + move, java.awt.Color.CYAN);

                updatePgn(move);
                internalBoard.doMove(move);
                lastFen = internalBoard.getFen();

                // If it was Black's turn, we assume the board is flipped (Black Player View)
                botMover.makeMove(org.example.javachess.Utils.PgnCodec.toUci(move), isBlackMove);

                // SYNC BOARD STATE MANAGER (For LED Legal Moves)
                org.example.javachess.Controllers.ArduinoController.getInstance().getBoardStateManager()
                        .setLogicalBoard(internalBoard);

                // Pause vision briefly to skip animation
                if (visionService != null) {
                    visionService.stopScanning();
                }
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                        if (isVisionRunning)
                            visionService.startScanning();
                    } catch (Exception e) {
                    }
                }).start();

            } else {
                updateStatus("ILLEGAL MOVE IGNORED", java.awt.Color.RED);
            }
        } catch (Exception e) {
            log.error("Unexpected error", e);
        }
    }

    private void updatePgn(com.github.bhlangonijr.chesslib.move.Move move) {
        pgn.append(org.example.javachess.Utils.PgnCodec.toUci(move)).append(' ');
    }

    private String simplifyFen(String fen) {
        String[] parts = fen.split(" ");
        return parts[0]; // Only compare piece placement, ignore turn/castling/etc.
    }

    private void closeBrowser() {
        stopOnlineGame();

        // 1. CLEANUP SWING/JCEF
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                // Exit Fullscreen Mode properly for GraphicsDevice
                java.awt.GraphicsDevice gd = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice();
                if (frame != null) {
                    if (gd.getFullScreenWindow() == frame) {
                        gd.setFullScreenWindow(null);
                    }

                    // STANDARD PROCEDURE: Hide then Dispose
                    frame.setVisible(false);
                    frame.dispose();
                    frame = null;
                }
            }
        });

        // 2. RESTORE JAVAFX FOCUS
        // Use standard JavaFX thread to request focus back
        Platform.runLater(() -> {
            if (mainController != null) {
                mainController.navigateTo("HOME");

                // Get Window and Request Focus
                if (mainController.getMainContainer().getScene() != null) {
                    javafx.stage.Window window = mainController.getMainContainer().getScene().getWindow();
                    if (window instanceof javafx.stage.Stage) {
                        javafx.stage.Stage stage = (javafx.stage.Stage) window;

                        // Standard Focus Request (Simple & Clean)
                        stage.setFullScreen(true);
                        stage.toFront();
                        stage.requestFocus();
                        log.info("[BrowserController] JavaFX Focus Requested (Simple).");
                    }
                }
            }
        });
    }

    private void styleButton(javax.swing.JButton btn) {
        btn.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 14));
        btn.setBackground(java.awt.Color.DARK_GRAY);
        btn.setForeground(java.awt.Color.WHITE);
        btn.setFocusPainted(false);
    }

    // @FXML private void onLichess() {} // REMOVED
    // @FXML private void onChessCom() {} // REMOVED
    @FXML
    private void onToggleVision() {
    }

    @FXML
    private void onBack() {
    }
}
