package org.example.javachess.Utils;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared, named executors for work that must stay off the JavaFX thread.
 *
 * <ul>
 *   <li>{@link #io()}: one virtual thread per task, for blocking I/O (files, network, waiting on the engine).</li>
 *   <li>{@link #compute()}: two daemon platform threads for short CPU-bound jobs (puzzle search, parsing).</li>
 *   <li>{@link #scheduler()}: one daemon thread for delays and timers; tasks must be short and hand heavy work on.</li>
 * </ul>
 *
 * All threads are daemons, so they never keep the JVM alive; {@link #shutdown()} is called on application exit.
 */
public final class AppExecutors {

    private static final Logger log = LoggerFactory.getLogger(AppExecutors.class);

    private static final ExecutorService IO =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("io-", 0).factory());
    private static final ExecutorService COMPUTE = Executors.newFixedThreadPool(2, daemonFactory("compute"));
    private static final ScheduledExecutorService SCHEDULER = createScheduler();

    private AppExecutors() {
    }

    public static ExecutorService io() {
        return IO;
    }

    public static ExecutorService compute() {
        return COMPUTE;
    }

    public static ScheduledExecutorService scheduler() {
        return SCHEDULER;
    }

    /** Runs the action on the JavaFX thread: immediately when already on it, otherwise via {@code runLater}. */
    public static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** Thread factory producing daemon threads named {@code prefix-N}. */
    public static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception in {}", t.getName(), e));
            return thread;
        };
    }

    /** Stops accepting tasks and interrupts running ones. Safe to call more than once. */
    public static void shutdown() {
        SCHEDULER.shutdownNow();
        COMPUTE.shutdownNow();
        IO.shutdownNow();
        try {
            COMPUTE.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledExecutorService createScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, daemonFactory("scheduler"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
