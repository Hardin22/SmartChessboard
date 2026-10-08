package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Utils.AppPaths;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import me.friwi.jcefmaven.impl.step.check.CefInstallationChecker;
import me.friwi.jcefmaven.impl.step.init.CefInitializer;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.network.CefCookieManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the Chromium engine (JCEF) once per process, lazily, off the JavaFX thread.
 *
 * <ul>
 *   <li>The native bundle (~100 MB download, ~300-450 MB on disk) is installed on first use in
 *       {@code ~/.jcef-bundle-<CEF version>} (one folder per version, so different app versions never overwrite
 *       each other's bundle); {@code -Djavachess.jcef.dir} overrides it.</li>
 *   <li>On arm64 Linux (Raspberry Pi) {@code libcef.so} must be preloaded before the JVM starts ("cannot allocate
 *       memory in static TLS block"): {@code run_pi.sh} does it when the bundle exists. Without the right preload the
 *       bundle is only installed and {@link RestartRequiredException} asks for a restart.</li>
 *   <li>Failures are classified ({@link #classify}) into messages the user can act on.</li>
 * </ul>
 */
public final class JcefRuntime {

    private static final Logger log = LoggerFactory.getLogger(JcefRuntime.class);

    /** Progress of the start-up, for the user. */
    public interface Progress {
        void downloading(double fraction);

        void installing();
    }

    /** The bundle is installed but the app must be restarted to load it (arm64 Linux). */
    public static final class RestartRequiredException extends Exception {
        public RestartRequiredException(String message) {
            super(message);
        }
    }

    private static CefApp app;
    private static CompletableFuture<CefApp> starting;

    private JcefRuntime() {
    }

    private static volatile boolean frameworkLoaded;

    /**
     * JCEF's documented start-up step, to be called <b>at the beginning of {@code main()}</b>: "This method must be
     * called at the beginning of the main() method to perform platform-specific startup initialization. On Linux this
     * initializes Xlib multithreading and on macOS this dynamically loads the CEF framework." (javadoc of
     * {@code org.cef.CefApp.startup}; JCEF's own sample, {@code tests/detailed/MainFrame}, calls it first thing).
     * jcefmaven calls it only when the engine is built, i.e. at the first opening of the browser, with the app's
     * threads running; on macOS loading the framework there let a {@code free()} on another thread abort the
     * process (loading it makes PartitionAlloc the default malloc zone: crashes of 8 October 2026, reproduced by
     * {@code CefLoadRace}). Does nothing when the engine is not installed yet (the first download then asks for a
     * restart) or with {@code -Djavachess.browser.preload=false}. Returns true when done.
     */
    public static synchronized boolean startup() {
        if (frameworkLoaded) {
            return true;
        }
        if ("false".equals(System.getProperty("javachess.browser.preload"))) {
            return false;
        }
        boolean mac = isMac();
        boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        if (!mac && !linux) {
            return false; // nothing to do there (startup returns at once on Windows)
        }
        Path dir = installDir();
        long t0 = System.nanoTime();
        try {
            if (!CefInstallationChecker.checkInstallation(dir.toFile())) {
                return false;
            }
            if (linux && needsPreload() && preloadedLibcef() == null) {
                return false; // arm64 without libcef.so preloaded by run_pi.sh: the engine asks for a restart
            }
            // as jcefmaven's CefInitializer: the bundle on java.library.path (JCEF finds its helper there), the
            // JCEF library from the bundle, then CefApp.startup
            String libraryPath = System.getProperty("java.library.path", "");
            if (!libraryPath.contains(dir.toString())) {
                System.setProperty("java.library.path", libraryPath
                        + (libraryPath.isEmpty() || libraryPath.endsWith(java.io.File.pathSeparator)
                        ? "" : java.io.File.pathSeparator) + dir.toAbsolutePath());
            }
            System.load(dir.resolve(mac ? "libjcef.dylib" : "libjcef.so").toString());
            org.cef.SystemBootstrap.setLoader(name -> { });
            String[] args = mac ? new String[]{"--framework-dir-path=" + dir.resolve(
                    "Chromium Embedded Framework.framework")} : new String[0];
            boolean ok = CefApp.startup(args);
            frameworkLoaded = ok;
            log.info("JCEF start-up step done at the start of the app in {} ms ({})",
                    (System.nanoTime() - t0) / 1_000_000, ok ? "ok" : "failed");
            return ok;
        } catch (Throwable t) {
            log.warn("JCEF start-up step not done at the start of the app: {}", t.toString());
            return false;
        }
    }

    /**
     * What jcefmaven's {@code CefInitializer} does, minus {@code CefApp.startup} (already done by
     * {@link #startup()} at the start of the app; a second call fails).
     */
    private static CefApp initializePreloaded(Path dir, java.util.List<String> jcefArgs, CefSettings settings) {
        java.util.List<String> args = new java.util.ArrayList<>(jcefArgs);
        if (!isMac()) { // Linux: after startup jcefmaven loads libcef.so (preloaded by run_pi.sh on the Pi)
            System.load(dir.resolve("libcef.so").toString());
            return CefApp.getInstance(args.toArray(new String[0]), settings);
        }
        String helper = dir.resolve("jcef Helper.app/Contents/MacOS/jcef Helper").toString();
        args.add(0, "--framework-dir-path=" + dir.resolve("Chromium Embedded Framework.framework"));
        args.add(0, "--main-bundle-path=" + dir.resolve("jcef Helper.app"));
        args.add(0, "--browser-subprocess-path=" + helper);
        settings.browser_subprocess_path = helper;
        return CefApp.getInstance(args.toArray(new String[0]), settings);
    }

    /**
     * Downloads and installs the engine without starting it ({@code run_pi.sh --install-browser}, part of the
     * Raspberry Pi set-up): the app then finds it at its first start and never has to restart for it. Prints the
     * progress; true when the engine is installed.
     */
    public static boolean installOnly() {
        Path dir = installDir();
        try {
            if (CefInstallationChecker.checkInstallation(dir.toFile())) {
                System.out.println("Browser engine already installed in " + dir);
                return true;
            }
            System.out.println("Downloading the browser engine into " + dir + " (about 150 MB)...");
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(dir.toFile());
            int[] last = {-1};
            builder.setProgressHandler((state, percent) -> {
                int p = Math.round(percent);
                if (state == EnumProgress.DOWNLOADING && p / 10 != last[0]) {
                    last[0] = p / 10;
                    System.out.println("  " + p + "%");
                } else if (state == EnumProgress.EXTRACTING || state == EnumProgress.INSTALL) {
                    System.out.println("  " + state.name().toLowerCase(Locale.ROOT) + "...");
                }
            });
            builder.install();
            boolean ok = CefInstallationChecker.checkInstallation(dir.toFile());
            System.out.println(ok ? "Browser engine installed in " + dir : "The browser engine is not complete");
            return ok;
        } catch (Exception e) {
            System.out.println("Could not install the browser engine: " + e);
            return false;
        }
    }

    /** True when JCEF's start-up step was done at the start of the app (see {@link #startup()}). */
    public static boolean startedUp() {
        return frameworkLoaded;
    }

    /** The running engine, or null. */
    public static synchronized CefApp app() {
        return app;
    }

    /**
     * Starts the engine (or returns the one already started / starting). The future fails with
     * {@link RestartRequiredException} or with the start-up error; a later call tries again.
     */
    public static synchronized CompletableFuture<CefApp> start(Progress progress) {
        if (app != null) {
            return CompletableFuture.completedFuture(app);
        }
        if (starting != null && !starting.isDone()) {
            return starting;
        }
        CompletableFuture<CefApp> future = new CompletableFuture<>();
        starting = future;
        Thread t = new Thread(() -> {
            try {
                CefApp started = build(progress);
                synchronized (JcefRuntime.class) {
                    app = started;
                }
                future.complete(started);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }, "jcef-start");
        t.setDaemon(true);
        t.start();
        return future;
    }

    private static CefApp build(Progress progress) throws Exception {
        Path dir = installDir();
        log.info("Starting the integrated browser (bundle {})", dir);
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(dir.toFile());
        builder.setProgressHandler((state, percent) -> {
            log.debug("JCEF {} {}", state, percent);
            if (state == EnumProgress.DOWNLOADING) {
                progress.downloading(percent == EnumProgress.NO_ESTIMATION ? -1 : percent / 100.0);
            } else if (state == EnumProgress.EXTRACTING || state == EnumProgress.INSTALL) {
                progress.installing();
            }
        });
        if (needsPreload()) {
            String preloaded = preloadedLibcef();
            Path expected = dir.resolve("libcef.so");
            if (preloaded == null || !Paths.get(preloaded).normalize().equals(expected.normalize())
                    || !CefInstallationChecker.checkInstallation(dir.toFile())) {
                if (!CefInstallationChecker.checkInstallation(dir.toFile())) {
                    log.info("Installing the browser bundle (it is loaded at the next start)");
                    builder.install();
                }
                throw new RestartRequiredException("libcef.so must be preloaded: " + expected
                        + (preloaded == null ? " (nothing preloaded)" : " (preloaded: " + preloaded + ")"));
            }
        }
        if (isMac() && !frameworkLoaded && !"false".equals(System.getProperty("javachess.browser.preload"))) {
            if (!CefInstallationChecker.checkInstallation(dir.toFile())) {
                // first use: install, then load the framework at the next start of the app, while it is quiet
                log.info("Installing the browser bundle (it is loaded at the next start)");
                builder.install();
                throw new RestartRequiredException("the Chromium framework is loaded at the start of the app");
            }
            // tools and tests that start the engine without the app's start-up: do the step now
            startup();
        }
        boolean gpu = useGpu(System.getProperty("javachess.browser.gpu"), isMac());
        builder.addJcefArgs(chromiumArgs(gpu).toArray(new String[0]));
        String extra = System.getProperty("javachess.browser.chromiumArgs"); // experiments only
        if (extra != null && !extra.isBlank()) {
            builder.addJcefArgs(extra.trim().split("\\s+"));
            log.info("Extra Chromium arguments: {}", extra);
        }
        log.info("Chromium graphics: {}", gpu ? "GPU" : softwareWebGl ? "software, WebGL by SwiftShader"
                : "software (no WebGL)");
        CefSettings settings = builder.getCefSettings();
        Path cache = AppPaths.resolve("jcef-cache"); // login sessions survive restarts
        Files.createDirectories(cache);
        settings.cache_path = cache.toString();
        settings.root_cache_path = cache.toString();
        settings.persist_session_cookies = true;
        settings.locale = "it-IT";
        settings.log_file = AppPaths.logsDir().resolve("chromium.log").toString();
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                log.info("JCEF state: {}", state);
            }

            @Override
            public boolean onBeforeTerminate() {
                // JCEF would dispose itself here (on the Swing thread: an abort on macOS); the app quits its own
                // way instead, and shutdown() decides what is safe
                log.info("The system asked to quit");
                Runnable handler = quitHandler;
                if (handler != null) {
                    handler.run();
                }
                return true;
            }
        });
        // CefAppBuilder.build() is install() + CefInitializer.initialize() + a JVM shutdown hook that disposes
        // the engine; on macOS that dispose aborts the process now and then (see shutdown()), so the engine is
        // started here without that hook and ours decides what is safe
        builder.install();
        CefApp built = frameworkLoaded ? initializePreloaded(dir, builder.getJcefArgs(), settings)
                : CefInitializer.initialize(dir.toFile(), builder.getJcefArgs(), settings);
        Runtime.getRuntime().addShutdownHook(new Thread(JcefRuntime::shutdown, "jcef-shutdown"));
        log.info("Integrated browser ready (Chromium {})", cefVersion());
        return built;
    }

    /**
     * Ends the engine if this process started it (never initialises JCEF just to end it). Idempotent: App.stop()
     * and the shutdown hook both call this.
     *
     * <p>The cookies are written to disk first (logins survive the restart). Then, where it is safe, Chromium is
     * shut down ({@link CefApp#dispose()}); on macOS it is not: JCEF runs the shutdown on the Swing thread, which
     * closes Cocoa windows outside the main thread, and macOS 27 aborts the process for that ("Must only be used
     * from the main thread", seen in tests on 2026-10-08). There the engine simply ends with the process, and its
     * helper processes with it.</p>
     */
    public static void shutdown() {
        CefApp a;
        synchronized (JcefRuntime.class) {
            a = app;
            app = null;
        }
        if (a == null) {
            return;
        }
        flushCookies(1500);
        if (!disposeIsSafe()) {
            log.info("JCEF left to end with the process (disposing it aborts on macOS)");
            return;
        }
        try {
            a.dispose();
            log.info("JCEF disposed");
        } catch (Throwable t) {
            log.warn("JCEF dispose failed: {}", t.toString());
        }
    }

    /**
     * Chromium's command line. The GPU stays off by default everywhere: on macOS Chromium's GPU in the app's
     * process crashed JavaFX's OpenGL renderer (8 October 2026, at the first opening of chess.com: JavaFX's
     * QuantumRenderer died inside Apple's Metal OpenGL layer with Chromium's frames on the stack; probably why the
     * GPU was disabled since JCEF 141). Without the GPU Chromium 146 has no WebGL. {@code -Djavachess.browser.gpu}
     * turns it on for experiments only.
     */
    static java.util.List<String> chromiumArgs(boolean gpu) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--no-sandbox", "--no-zygote",
                "--disable-dev-shm-usage", "--disable-site-isolation-trials"));
        if (!gpu) {
            args.addAll(java.util.List.of("--disable-gpu", "--disable-gpu-compositing", "--disable-gpu-rasterization",
                    "--disable-gpu-shader-disk-cache", "--disable-features=VizDisplayCompositor"));
            if (softwareWebGl) {
                // WebGL without the GPU: SwiftShader, in Chromium's GPU process (not in the app's): sites' bot
                // checks (Cloudflare Turnstile) distrust a browser without WebGL. "Unsafe" because shaders of any
                // page run on a software rasteriser: acceptable for the chess sites this browser is for
                args.add("--enable-unsafe-swiftshader");
            }
        }
        return args;
    }

    /** {@code -Djavachess.browser.webgl=false} turns software WebGL off. */
    static boolean softwareWebGl = !"false".equals(System.getProperty("javachess.browser.webgl"));

    /** {@code -Djavachess.browser.gpu=true|false}; off by default (see {@link #chromiumArgs}). */
    static boolean useGpu(String setting, boolean mac) {
        if (setting != null && !setting.isBlank()) {
            return Boolean.parseBoolean(setting.trim());
        }
        return false;
    }

    /** False on macOS, see {@link #shutdown()}. */
    static boolean disposeIsSafe() {
        return !isMac();
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Writes Chromium's cookies (login sessions) to disk; Chromium does it by itself only every ~30 s. Called when
     * the user leaves the browser and after each page load (so a login is on disk before the app can be closed),
     * and at shutdown.
     */
    public static void flushCookies() {
        flushCookies(0);
    }

    /**
     * Waits at most {@code timeoutMs} for the flush, but never on the thread that has to deliver its completion:
     * Chromium's UI thread is the macOS main thread, which is also the JavaFX thread there, and the Swing thread
     * runs Chromium's message loop on Linux. Those threads only start the flush.
     */
    private static void flushCookies(long timeoutMs) {
        try {
            if (app() == null && timeoutMs == 0) {
                return;
            }
            CefCookieManager cookies = CefCookieManager.getGlobalManager();
            if (cookies == null) {
                return;
            }
            CountDownLatch done = new CountDownLatch(1);
            boolean started = cookies.flushStore(done::countDown);
            if (started && timeoutMs > 0 && canWaitForChromium()
                    && !done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.info("Cookie flush still running after {} ms", timeoutMs);
            }
        } catch (Throwable t) {
            log.debug("Cookie flush failed: {}", t.toString());
        }
    }

    private static boolean canWaitForChromium() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            return false;
        }
        try {
            return !(isMac() && javafx.application.Platform.isFxApplicationThread());
        } catch (Throwable t) {
            return true; // no JavaFX (tools, tests)
        }
    }

    /** Called instead of JCEF's own shutdown when the system asks the app to quit (macOS: Cmd+Q, log out). */
    private static volatile Runnable quitHandler;

    /** What to do when the system asks to quit while Chromium runs (the app: its orderly exit). */
    public static void setQuitHandler(Runnable handler) {
        quitHandler = handler;
    }

    // ------------------------------------------------------------------ install location

    /** The folder of the native bundle for this JCEF version. */
    public static Path installDir() {
        String custom = System.getProperty("javachess.jcef.dir");
        if (custom != null && !custom.isBlank()) {
            return Paths.get(custom);
        }
        return Paths.get(System.getProperty("user.home"), ".jcef-bundle-" + cefVersion());
    }

    /** CEF version of the JCEF jar on the class path, e.g. "152.0.6" ("unknown" when not found). */
    public static String cefVersion() {
        try {
            return cefVersionOf(CefBuildInfo.fromClasspath().getReleaseTag());
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    static String cefVersionOf(String releaseTag) {
        Matcher m = Pattern.compile("cef-([0-9]+(?:\\.[0-9]+)*)").matcher(releaseTag == null ? "" : releaseTag);
        return m.find() ? m.group(1) : "unknown";
    }

    /** arm64 Linux needs libcef.so preloaded. */
    static boolean needsPreload() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") && (arch.equals("aarch64") || arch.equals("arm64"));
    }

    /** Path of the libcef.so mapped in this process, or null. */
    static String preloadedLibcef() {
        Path maps = Paths.get("/proc/self/maps");
        if (!Files.isReadable(maps)) {
            return null;
        }
        try {
            return libcefIn(Files.readAllLines(maps));
        } catch (IOException e) {
            return null;
        }
    }

    static String libcefIn(Iterable<String> mapsLines) {
        for (String line : mapsLines) {
            int slash = line.indexOf('/');
            if (slash >= 0 && line.endsWith("/libcef.so")) {
                return line.substring(slash).trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ failures

    /** True when the failure means: installed, restart to load it. */
    public static boolean isRestartRequired(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestartRequiredException
                    || String.valueOf(t.getMessage()).contains("static TLS")) {
                return true;
            }
        }
        return false;
    }

    /** What the user can do about a start-up failure. */
    public static BrowserSession.Failure classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (t instanceof UnsupportedPlatformException) {
                return BrowserSession.Failure.UNSUPPORTED;
            }
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SocketTimeoutException || t instanceof NoRouteToHostException
                    || message.contains("download")) {
                return BrowserSession.Failure.NO_NETWORK;
            }
            if (message.contains("no space left")) {
                return BrowserSession.Failure.NO_SPACE;
            }
            if (t instanceof IllegalAccessError || t.getClass().getName().endsWith("InaccessibleObjectException")
                    || message.contains("sun.awt") || message.contains("sun.lwawt")) {
                return BrowserSession.Failure.MISSING_OPTIONS;
            }
        }
        return BrowserSession.Failure.OTHER;
    }

    /** Size in MB of the installed bundle, for the logs (-1 when not installed). */
    static long installedSizeMb(File dir) {
        if (!dir.isDirectory()) {
            return -1;
        }
        try (var files = Files.walk(dir.toPath())) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum() / (1024 * 1024);
        } catch (IOException e) {
            return -1;
        }
    }
}
