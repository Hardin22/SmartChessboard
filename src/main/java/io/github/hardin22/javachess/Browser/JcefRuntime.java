package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Utils.AppPaths;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import me.friwi.jcefmaven.impl.step.check.CefInstallationChecker;
import org.cef.CefApp;
import org.cef.CefSettings;
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
        // stability on macOS and on the Pi (software rendering), see docs/browser.md
        builder.addJcefArgs("--disable-gpu", "--disable-gpu-compositing", "--disable-gpu-rasterization",
                "--no-sandbox", "--no-zygote", "--disable-dev-shm-usage", "--disable-gpu-shader-disk-cache",
                "--disable-site-isolation-trials", "--disable-features=VizDisplayCompositor");
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
        });
        CefApp built = builder.build();
        log.info("Integrated browser ready (Chromium {})", cefVersion());
        return built;
    }

    /** Disposes the engine if this process started it (never initialises JCEF just to dispose it). */
    public static synchronized void disposeIfStarted() {
        CefApp a = app;
        if (a == null) {
            return;
        }
        app = null; // idempotent: App.stop() and the shutdown hook both call this
        try {
            a.dispose();
            log.info("JCEF disposed");
        } catch (Throwable t) {
            log.warn("JCEF dispose failed: {}", t.toString());
        }
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
