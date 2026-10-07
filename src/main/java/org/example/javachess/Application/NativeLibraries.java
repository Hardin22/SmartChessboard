package org.example.javachess.Application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native libraries loaded on first use instead of at start-up (OpenCV alone costs ~0.3 s on a Mac and several
 * seconds on a Raspberry Pi, and only the Vision module needs it).
 */
public final class NativeLibraries {

    private static final Logger log = LoggerFactory.getLogger(NativeLibraries.class);
    private static volatile boolean openCvLoaded;

    private NativeLibraries() {
    }

    /** Loads OpenCV once; safe to call from any thread and more than once. */
    public static synchronized void loadOpenCv() {
        if (openCvLoaded) {
            return;
        }
        long start = System.nanoTime();
        try {
            nu.pattern.OpenCV.loadLocally();
            openCvLoaded = true;
            log.info("OpenCV loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            log.error("Failed to load OpenCV", t);
        }
    }
}
