package org.example.javachess.Application;

import javafx.animation.AnimationTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;

/**
 * Lightweight start-up and frame-time instrumentation.
 *
 * <p>Always logs one line with the time from JVM start to the first rendered frame and the heap in use.
 * With {@code -Djavachess.metrics=true} it also logs, every 5 seconds, the average and worst frame interval
 * seen by the JavaFX pulse (a long interval means the FX thread was blocked) and the heap in use.</p>
 */
public final class StartupMetrics {

    private static final Logger log = LoggerFactory.getLogger(StartupMetrics.class);
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

    private StartupMetrics() {
    }

    /** Milliseconds since the JVM started. */
    public static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    public static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /** Call right after {@code stage.show()}: logs once the first frame has been rendered. */
    public static void onStageShown(long startCalledAtMs) {
        boolean continuous = Boolean.getBoolean("javachess.metrics");
        new AnimationTimer() {
            private long frames;
            private long windowStart;
            private long last;
            private long worst;

            @Override
            public void handle(long now) {
                if (frames++ == 0) {
                    log.info("Startup: first frame at {} ms after JVM start (Application.start at {} ms), heap {} MB",
                            uptimeMs(), startCalledAtMs, usedHeapMb());
                    if (!continuous) {
                        stop();
                        return;
                    }
                    windowStart = now;
                    last = now;
                    return;
                }
                worst = Math.max(worst, now - last);
                last = now;
                if (now - windowStart >= REPORT_INTERVAL_NS) {
                    long count = frames - 1;
                    log.info("Frames: {} in 5 s, avg {} ms, worst {} ms, heap {} MB",
                            count, (now - windowStart) / Math.max(1, count) / 1_000_000, worst / 1_000_000,
                            usedHeapMb());
                    frames = 1;
                    worst = 0;
                    windowStart = now;
                }
            }
        }.start();
    }
}
