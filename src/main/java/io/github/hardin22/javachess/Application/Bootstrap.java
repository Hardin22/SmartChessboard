package io.github.hardin22.javachess.Application;

import io.github.hardin22.javachess.Utils.AppPaths;
import io.github.hardin22.javachess.Utils.ConfigManager;
import io.github.hardin22.javachess.Utils.ErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Early start-up steps shared by every entry point: data folder (with migration of the files that old versions
 * kept in the working directory), settings, logging banner and the global handler for uncaught exceptions.
 *
 * <p>Call {@link #init()} first thing in {@code main}. It never throws.
 */
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
    private static volatile boolean initialized;

    private Bootstrap() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ErrorReporter.installGlobalHandler();
        try {
            log.info("javaChess starting (Java {}, {} {}, data folder {})", System.getProperty("java.version"),
                    System.getProperty("os.name"), System.getProperty("os.arch"), AppPaths.dataDir());
            ConfigManager.file(); // loads (and migrates) the settings
            io.github.hardin22.javachess.Services.GameArchiveService.getInstance(); // migrates the old archive if needed
        } catch (RuntimeException e) {
            log.error("Start-up initialisation failed", e);
        }
    }
}
